package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * 托盘自动清理 Worker：每日扫描回收站中超过 30 天的文件，永久删除。
 * 由 Application 在启动时通过 PeriodicWorkRequest 调度。
 */
class TrashCleanupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as? com.renyxin.localalbum.LocalAlbumApplication)
            ?.container?.albumRepository ?: return Result.failure()
        val threshold = System.currentTimeMillis() - CLEANUP_DAYS_MS

        // 使用 keyset 游标继续扫描，即使某批全部因权限失败，也不会阻塞后续过期项。
        // 删除失败文件仍留在回收站，并由持久 tombstone 重试机制继续处理。
        var batches = 0
        var afterPath = ""
        while (batches < MAX_BATCHES_PER_RUN) {
            val result = repository.purgeExpiredTrashBatch(threshold, afterPath, BATCH_SIZE)
            if (result.scanned == 0) break
            afterPath = result.lastPath ?: break
            batches++
            if (result.scanned < BATCH_SIZE) break
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "trash_cleanup_worker"
        private const val BATCH_SIZE = 200
        private const val MAX_BATCHES_PER_RUN = 10
        private val CLEANUP_DAYS_MS = TimeUnit.DAYS.toMillis(30)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(
                1, TimeUnit.DAYS,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}