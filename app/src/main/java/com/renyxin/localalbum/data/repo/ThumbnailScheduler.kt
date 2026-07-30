package com.renyxin.localalbum.data.repo

import android.content.Context
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.worker.ThumbnailWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** UI 到持久化缩略图队列的轻量边界；不持有媒体集合。 */
class ThumbnailScheduler(
    private val context: Context,
    private val taskDao: ThumbnailTaskDao,
    private val cacheDao: ThumbnailCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val recentRequests = ConcurrentHashMap<String, Long>()

    suspend fun request(item: MediaItem, sizeClass: String, priority: Int): String? {
        val sourceVersion = ThumbnailSpec.sourceVersion(item.modifiedAt.toEpochMilli(), item.fileSize)
        val key = "${item.filePath}|$sizeClass|$sourceVersion"
        val timestamp = now()
        val ready = cacheDao.getReady(item.filePath, sizeClass, sourceVersion)
        if (ready != null && File(ready.path).isFile) {
            cacheDao.touchThrottled(
                item.filePath,
                sizeClass,
                sourceVersion,
                timestamp,
                timestamp - ThumbnailSpec.TOUCH_THROTTLE_MS,
            )
            recentRequests[key] = timestamp
            return ready.path
        }
        val last = recentRequests[key]
        if (last == null || timestamp - last >= REQUEST_COOLDOWN_MS) {
            recentRequests[key] = timestamp
            taskDao.enqueueOrPromote(
                ThumbnailTaskEntity(
                    filePath = item.filePath,
                    sizeClass = sizeClass,
                    sourceVersion = sourceVersion,
                    mediaType = item.type.name,
                    priority = priority,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            ThumbnailWorker.enqueue(context)
            if (recentRequests.size > MAX_RECENT_KEYS) {
                recentRequests.entries.removeIf { timestamp - it.value >= REQUEST_COOLDOWN_MS }
            }
        }
        return null
    }

    companion object {
        private const val REQUEST_COOLDOWN_MS = 15_000L
        private const val MAX_RECENT_KEYS = 2_000
    }
}
