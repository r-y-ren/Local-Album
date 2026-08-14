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
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.MediaDao
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
        private const val INTERACTIVE_WORK_NAME = "thumbnail_interactive_queue"
        private const val BACKGROUND_WORK_NAME = "thumbnail_background_queue"
        private const val BATCH_SIZE = 4
        private const val MAX_BATCHES_PER_RUN = 8
        private const val MAX_ATTEMPTS = 4
        private const val LEASE_DURATION_MS = 10 * 60 * 1000L
        private const val BASE_RETRY_DELAY_MS = 60 * 1000L
        private const val MAX_ERROR_LENGTH = 240

        /** Interactive requests bypass the post-core delay but remain in one bounded queue. */
        fun enqueue(context: Context) = enqueueInternal(context, background = false)

        /** Automatic pre-generation is released only by the post-core handoff Worker. */
        fun enqueueBackground(context: Context) = enqueueInternal(context, background = true)

        private fun enqueueInternal(context: Context, background: Boolean) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>()
                .setInputData(workDataOf(KEY_BACKGROUND to background))
                .apply {
                    if (background) setInitialDelay(BACKGROUND_INITIAL_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (background) BACKGROUND_WORK_NAME else INTERACTIVE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            Log.i(TAG, "缩略图持久任务已调度: background=$background")
        }

        internal fun retryDelayMs(attemptCount: Int): Long {
            val exponent = (attemptCount - 1).coerceIn(0, 10)
            return BASE_RETRY_DELAY_MS * (1L shl exponent)
        }

        private const val KEY_BACKGROUND = "background"
        private const val BACKGROUND_INITIAL_DELAY_MS = 2_000L
        private const val INTERACTIVE_BATCH_SIZE = 2

        fun cancelBackground(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(BACKGROUND_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val database = app.container.database
        val mediaDao = database.mediaDao()
        val taskDao = database.thumbnailTaskDao()
        val cacheDao = database.thumbnailCacheDao()
        val mediaSource = MediaSource(applicationContext)
        val background = inputData.getBoolean(KEY_BACKGROUND, false)

        return try {
            val coreActive = app.container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested
            // Visible tasks are allowed through a separate one-permit lane. Automatic scan-owned
            // tasks must wait for the strict core/automatic barrier.
            if (background && coreActive) return Result.retry()
            val touchedScanIds = linkedSetOf<String>()
            var processed = 0
            var failed = 0
            var batches = 0
            var interruptedLaneLeasesRecovered = false

            while (batches < MAX_BATCHES_PER_RUN) {
                if (isStopped) break
                val runBatch: suspend () -> Boolean = runBatch@{
                    val now = System.currentTimeMillis()
                    // This closure runs only after automatic-lane admission. A RUNNING scan-owned
                    // lease therefore belongs to an interrupted process, while independent
                    // interactive leases remain untouched.
                    if (!interruptedLaneLeasesRecovered) {
                        if (background) {
                            taskDao.recoverInterruptedAutomaticLeases(now)
                        } else {
                            taskDao.recoverInterruptedInteractiveLeases(now)
                        }
                        interruptedLaneLeasesRecovered = true
                    }
                    val leaseToken = UUID.randomUUID().toString()
                    val tasks = if (background) {
                        taskDao.claimAutomaticBatch(
                            now = now,
                            limit = BATCH_SIZE,
                            leaseToken = leaseToken,
                            leaseDurationMs = LEASE_DURATION_MS,
                        )
                    } else {
                        taskDao.claimInteractiveBatch(
                            now = now,
                            limit = INTERACTIVE_BATCH_SIZE,
                            leaseToken = leaseToken,
                            leaseDurationMs = LEASE_DURATION_MS,
                        )
                    }
                    if (tasks.isEmpty()) return@runBatch false
                    if (background) touchedScanIds += tasks.mapNotNull { it.scanId }
                    try {
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
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            taskDao.releaseLease(leaseToken, System.currentTimeMillis())
                        }
                        throw cancelled
                    }
                    true
                }
                val completedBatch = if (background) {
                    EnhancementResourceGate.tryWithAutomaticEnhancement { runBatch() }
                } else {
                    EnhancementResourceGate.withInteractiveThumbnail { runBatch() }
                }
                if (completedBatch == null || completedBatch == false) break
                batches++
            }

            val finalizeWork: suspend () -> Result = {
                // Terminal state, cache trimming and active-count reads touch tables replaced by an
                // import. Keep them in the same kind of lane as task processing.
                finalizeEnhancementStates(database, touchedScanIds)
                val evicted = ThumbnailCacheTrimmer(cacheDao).trim()
                val active = if (background) {
                    taskDao.countAutomaticRunnable()
                } else {
                    taskDao.countInteractiveActive()
                }
                Log.i(TAG, "缩略图任务完成: 成功 $processed，失败 $failed，可运行 $active，清理 $evicted")
                if (active > 0) {
                    // unique work 在当前 Worker 结束前仍处于占用状态，不能靠再次 enqueue 续跑；
                    // Result.retry 由 WorkManager 在退避后可靠恢复队列。
                    Result.retry()
                } else {
                    Result.success(
                        workDataOf(
                            "processed" to processed,
                            "failed" to failed,
                            "active" to active,
                            "evicted" to evicted,
                        ),
                    )
                }
            }
            if (background) {
                EnhancementResourceGate.tryWithAutomaticEnhancement { finalizeWork() }
                    ?: Result.retry()
            } else {
                EnhancementResourceGate.withInteractiveThumbnail { finalizeWork() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "缩略图任务执行失败", e)
            Result.retry()
        }
    }

    private suspend fun finalizeEnhancementStates(
        database: com.renyxin.localalbum.data.db.AppDatabase,
        scanIds: Set<String>,
    ) {
        val now = System.currentTimeMillis()
        scanIds.forEach { scanId ->
            val active = database.thumbnailTaskDao().countActiveForScan(scanId) +
                database.analysisTaskDao().countActiveForScan(scanId) +
                database.enhancementOutboxDao().countActiveForScan(scanId)
            if (active == 0) {
                val failed = database.thumbnailTaskDao().countFailedForScan(scanId) +
                    database.analysisTaskDao().countFailedForScan(scanId) +
                    database.enhancementOutboxDao().countFailedForScan(scanId)
                database.scanRunDao().markEnhancementTerminal(
                    scanId = scanId,
                    state = if (failed > 0) {
                        com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED_WITH_FAILURES.name
                    } else {
                        com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED.name
                    },
                    now = now,
                )
            }
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
        if (existing != null &&
            File(existing.path).isFile &&
            ThumbnailSpec.isCurrentCachePath(task.sizeClass, existing.path)
        ) {
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
