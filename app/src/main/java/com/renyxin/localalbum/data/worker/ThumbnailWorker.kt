package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.room.withTransaction
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.repo.ThumbnailCacheTrimmer
import com.renyxin.localalbum.data.source.MediaSource
import java.io.File
import java.util.UUID

/**
 * 基于持久化任务、领取租约和失败退避的缩略图 Worker。
 *
 * 每次执行只处理有限任务；损坏文件超过重试阈值后进入 FAILED，Worker 必然收敛。
 */
class ThumbnailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ThumbnailWorker"
        private const val WORK_NAME = "thumbnail_generation_queue"
        private const val BATCH_SIZE = 50
        private const val TASK_SEED_PAGE_SIZE = 500
        private const val MAX_BATCHES_PER_RUN = 10
        private const val MAX_ATTEMPTS = 4
        private const val LEASE_DURATION_MS = 10 * 60 * 1000L
        private const val BASE_RETRY_DELAY_MS = 60 * 1000L
        private const val MAX_ERROR_LENGTH = 240

        /** KEEP 保证任意时刻只有一个队列 Worker 被调度。 */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "缩略图持久任务已调度")
        }

        internal fun retryDelayMs(attemptCount: Int): Long {
            val exponent = (attemptCount - 1).coerceIn(0, 10)
            return BASE_RETRY_DELAY_MS * (1L shl exponent)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val database = app.container.database
        val mediaDao = database.mediaDao()
        val taskDao = database.thumbnailTaskDao()
        val cacheDao = database.thumbnailCacheDao()
        val mediaSource = MediaSource(applicationContext)

        return try {
            seedMissingTasks(mediaDao, taskDao)
            var processed = 0
            var failed = 0
            var batches = 0

            while (batches < MAX_BATCHES_PER_RUN) {
                val now = System.currentTimeMillis()
                val leaseToken = UUID.randomUUID().toString()
                val tasks = taskDao.claimBatch(
                    now = now,
                    limit = BATCH_SIZE,
                    leaseToken = leaseToken,
                    leaseDurationMs = LEASE_DURATION_MS,
                )
                if (tasks.isEmpty()) break

                tasks.forEach { task ->
                    val outcome = processTask(task, database, mediaDao, mediaSource)
                    val completedAt = System.currentTimeMillis()
                    if (outcome == null) {
                        taskDao.markDone(task.taskId, leaseToken, completedAt)
                        processed++
                    } else {
                        val nextRetryAt = completedAt + retryDelayMs(task.attemptCount)
                        taskDao.markFailed(
                            taskId = task.taskId,
                            leaseToken = leaseToken,
                            error = outcome.take(MAX_ERROR_LENGTH),
                            nextRetryAt = nextRetryAt,
                            maxAttempts = MAX_ATTEMPTS,
                            now = completedAt,
                        )
                        failed++
                    }
                }
                batches++
            }

            val evicted = ThumbnailCacheTrimmer(cacheDao).trim()
            val active = taskDao.countActive()
            Log.i(TAG, "缩略图任务完成: 成功 $processed，失败 $failed，待处理 $active，清理 $evicted")
            if (active > 0) {
                // unique work 在当前 Worker 结束前仍处于占用状态，不能靠再次 enqueue 续跑；
                // Result.retry 由 WorkManager 在退避后可靠恢复队列。
                Result.retry()
            } else {
                Result.success(workDataOf("processed" to processed, "failed" to failed, "active" to active, "evicted" to evicted))
            }
        } catch (e: Exception) {
            Log.e(TAG, "缩略图任务执行失败", e)
            Result.retry()
        }
    }

    private suspend fun seedMissingTasks(mediaDao: MediaDao, taskDao: ThumbnailTaskDao) {
        var afterPath = ""
        while (true) {
            val media = mediaDao.getMissingThumbnailsAfter(afterPath, TASK_SEED_PAGE_SIZE)
            if (media.isEmpty()) break
            val now = System.currentTimeMillis()
            taskDao.enqueueAll(media.map { entity ->
                ThumbnailTaskEntity(
                    filePath = entity.filePath,
                    sourceVersion = "${entity.modifiedAtMs}:${entity.fileSize}",
                    mediaType = entity.mediaType.name,
                    createdAt = now,
                    updatedAt = now,
                )
            })
            afterPath = media.last().filePath
            if (media.size < TASK_SEED_PAGE_SIZE) break
        }
    }

    /** 返回 null 表示成功，否则返回不含完整隐私路径的错误摘要。 */
    private suspend fun processTask(
        task: ThumbnailTaskEntity,
        database: com.renyxin.localalbum.data.db.AppDatabase,
        mediaDao: MediaDao,
        mediaSource: MediaSource,
    ): String? {
        val file = File(task.filePath)
        if (!file.exists() || !file.isFile) return "source_missing"
        val media = mediaDao.getByFilePathLight(task.filePath) ?: return null
        val currentVersion = ThumbnailSpec.sourceVersion(media.modifiedAtMs, media.fileSize)
        if (currentVersion != task.sourceVersion || media.isTrashed) return null
        val cacheDao = database.thumbnailCacheDao()
        val existing = cacheDao.getReady(task.filePath, task.sizeClass, task.sourceVersion)
        if (existing != null && File(existing.path).isFile) {
            cacheDao.touchThrottled(
                task.filePath,
                task.sizeClass,
                task.sourceVersion,
                System.currentTimeMillis(),
                System.currentTimeMillis() - ThumbnailSpec.TOUCH_THROTTLE_MS,
            )
            return null
        }
        return runCatching {
            val thumbnail = mediaSource.generateThumbnailSync(file, media.mediaType, task.sizeClass)
                ?: error("decode_failed")
            val generated = File(thumbnail)
            val now = System.currentTimeMillis()
            database.withTransaction {
                cacheDao.upsert(
                    ThumbnailCacheEntryEntity(
                        filePath = task.filePath,
                        sizeClass = task.sizeClass,
                        sourceVersion = task.sourceVersion,
                        path = thumbnail,
                        byteSize = generated.length(),
                        lastAccessAt = now,
                        createdAt = now,
                    ),
                )
                // 旧字段固定代表 grid；preview 绝不覆盖该语义。
                if (task.sizeClass == ThumbnailSpec.SIZE_GRID) {
                    mediaDao.updateThumbnail(task.filePath, thumbnail)
                }
            }
        }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
    }
}
