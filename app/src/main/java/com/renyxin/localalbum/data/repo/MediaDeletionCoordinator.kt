package com.renyxin.localalbum.data.repo

import androidx.room.withTransaction
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import java.io.File

/**
 * Room 级媒体关联一致性唯一入口。
 *
 * Repository、扫描孤儿与删除重试都复用本服务，禁止直接删除 media_items。
 * 全局 generation/meta 表不会因单路径删除；仅清理成员并将受影响结果标为失效。
 */
class MediaDeletionCoordinator(
    private val database: AppDatabase,
    private val profileId: String,
) {
    companion object {
        const val CHUNK_SIZE = 300
    }

    private data class PreparedChunk(
        val paths: List<String>,
        val removableCacheEntries: List<ThumbnailCacheEntryEntity>,
    )

    /**
     * Canonical scan cleanup preserves the currently published home read model by default.
     * Explicit user deletion opts into removing the same paths from that read model atomically.
     */
    suspend fun purge(
        paths: Collection<String>,
        removeFromHomeSnapshot: Boolean = false,
    ) {
        prepare(paths, preservePublishedGrid = !removeFromHomeSnapshot).forEach { prepared ->
            database.withTransaction {
                purgePrepared(
                    prepared = prepared,
                    deleteSourceReferences = true,
                    removeFromHomeSnapshot = removeFromHomeSnapshot,
                )
            }
        }
    }

    /**
     * Removes old path-keyed rows and commits their replacement in one Room transaction.
     * Used by MediaStore rename/move handling after user state has been copied in memory.
     */
    suspend fun <T> replacePaths(
        oldPaths: Collection<String>,
        deleteSourceReferences: Boolean = true,
        writeReplacement: suspend () -> T,
    ): T {
        val prepared = prepare(oldPaths, preservePublishedGrid = true)
        return database.withTransaction {
            prepared.forEach {
                purgePrepared(
                    prepared = it,
                    deleteSourceReferences = deleteSourceReferences,
                    removeFromHomeSnapshot = false,
                )
            }
            writeReplacement()
        }
    }

    private suspend fun prepare(
        paths: Collection<String>,
        preservePublishedGrid: Boolean,
    ): List<PreparedChunk> = paths.distinct().chunked(CHUNK_SIZE).map { chunk ->
        // A scan may remove canonical rows before the next publication. Keep the old published Grid
        // file and cache metadata alive so the visible snapshot remains complete during that window.
        val protectedPaths = if (preservePublishedGrid) {
            database.homeMediaSnapshotDao().readyThumbnailPaths(chunk).toHashSet()
        } else {
            emptySet()
        }
        // Other derived files are handled before the DB transaction; failed deletion keeps metadata.
        // Delete metadata by the full cache key: one removable Preview must never erase a protected Grid row.
        val now = System.currentTimeMillis()
        val cacheEntries = database.thumbnailCacheDao().getByPaths(chunk)
        val removableCacheEntries = cacheEntries.filter { entry ->
            entry.path !in protectedPaths &&
                entry.leaseUntil <= now &&
                (entry.state == ThumbnailCacheEntryEntity.STATE_READY ||
                    entry.state == ThumbnailCacheEntryEntity.STATE_DELETE_RETRY) && run {
                    val cacheFile = File(entry.path)
                    !cacheFile.exists() ||
                        (cacheFile.isFile && runCatching { cacheFile.delete() }.getOrDefault(false))
                }
        }
        PreparedChunk(chunk, removableCacheEntries)
    }

    private suspend fun purgePrepared(
        prepared: PreparedChunk,
        deleteSourceReferences: Boolean,
        removeFromHomeSnapshot: Boolean,
    ) {
        val chunk = prepared.paths
        database.duplicateMaintenanceDao().invalidateGroupsContaining(chunk)
        database.duplicateMaintenanceDao().deleteMembersByPaths(chunk)
        database.duplicateMaintenanceDao().deleteStagingByPaths(chunk)
        database.semanticDao().invalidateClustersContaining(chunk)
        database.semanticDao().deleteClusterMembersByPaths(chunk)

        database.mediaDao().deleteFtsEntries(chunk)
        database.faceDao().deleteByFilePaths(chunk)
        database.embeddingDao().deleteByFilePaths(chunk)
        database.analysisStateDao().deleteByPaths(chunk)
        database.featureStoreDao().deleteByFilePaths(chunk)
        database.thumbnailTaskDao().deleteByPaths(chunk)
        database.enhancementOutboxDao().deleteByPaths(chunk)
        // Delete only metadata whose private file was actually removed. Snapshot-protected Grid rows
        // remain addressable until the next home publication releases them.
        prepared.removableCacheEntries.forEach { entry ->
            database.thumbnailCacheDao().deleteExact(
                entry.filePath,entry.mediaType,entry.sourceVersion,entry.sizeClass,
                entry.formatVersion,entry.path,
            )
        }
        database.analysisTaskDao().deleteByPaths(chunk)
        if (deleteSourceReferences) {
            database.mediaChangeDao().deleteReferencesByPaths(profileId, chunk)
        }
        if (removeFromHomeSnapshot) {
            database.homeMediaSnapshotDao().deleteByPaths(chunk)
        }
        // The primary rows are last so this order remains valid if foreign keys are introduced.
        database.mediaDao().deleteByPaths(chunk)
    }
}
