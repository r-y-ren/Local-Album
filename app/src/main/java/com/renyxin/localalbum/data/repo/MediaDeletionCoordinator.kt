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
class MediaDeletionCoordinator(private val database: AppDatabase) {
    companion object {
        const val CHUNK_SIZE = 300
    }

    suspend fun purge(paths: Collection<String>) {
        paths.distinct().chunked(CHUNK_SIZE).forEach { chunk ->
            // 派生缩略图文件必须先成功删除/已缺失，才允许在同一关联事务中移除 metadata。
            val cacheEntries = database.thumbnailCacheDao().getByPaths(chunk)
            val removableCachePaths = cacheEntries.filter { entry ->
                val cacheFile = File(entry.path)
                !cacheFile.exists() || (cacheFile.isFile && runCatching { cacheFile.delete() }.getOrDefault(false))
            }.map { it.filePath }.toSet()

            database.withTransaction {
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
                if (removableCachePaths.isNotEmpty()) {
                    database.thumbnailCacheDao().deleteByPaths(removableCachePaths.toList())
                }
                database.analysisTaskDao().deleteByPaths(chunk)
                // 必须最后删除主记录，便于未来引入 FK 时维持相同语义。
                database.mediaDao().deleteByPaths(chunk)
            }
        }
    }
}
