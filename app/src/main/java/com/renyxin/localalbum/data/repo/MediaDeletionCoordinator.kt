package com.renyxin.localalbum.data.repo

import androidx.room.withTransaction
import com.renyxin.localalbum.data.db.AppDatabase
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
        val removableCachePaths: List<String>,
    )

    suspend fun purge(paths: Collection<String>) {
        prepare(paths).forEach { prepared ->
            database.withTransaction { purgePrepared(prepared, deleteSourceReferences = true) }
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
        val prepared = prepare(oldPaths)
        return database.withTransaction {
            prepared.forEach { purgePrepared(it, deleteSourceReferences) }
            writeReplacement()
        }
    }

    private suspend fun prepare(paths: Collection<String>): List<PreparedChunk> =
        paths.distinct().chunked(CHUNK_SIZE).map { chunk ->
            // Derived files are handled before the DB transaction; failed file deletion keeps metadata.
            val cacheEntries = database.thumbnailCacheDao().getByPaths(chunk)
            val removableCachePaths = cacheEntries.filter { entry ->
                val cacheFile = File(entry.path)
                !cacheFile.exists() ||
                    (cacheFile.isFile && runCatching { cacheFile.delete() }.getOrDefault(false))
            }.map { it.filePath }.distinct()
            PreparedChunk(chunk, removableCachePaths)
        }

    private suspend fun purgePrepared(
        prepared: PreparedChunk,
        deleteSourceReferences: Boolean,
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
        if (prepared.removableCachePaths.isNotEmpty()) {
            database.thumbnailCacheDao().deleteByPaths(prepared.removableCachePaths)
        }
        database.analysisTaskDao().deleteByPaths(chunk)
        if (deleteSourceReferences) {
            database.mediaChangeDao().deleteReferencesByPaths(profileId, chunk)
        }
        // The primary rows are last so this order remains valid if foreign keys are introduced.
        database.mediaDao().deleteByPaths(chunk)
    }
}
