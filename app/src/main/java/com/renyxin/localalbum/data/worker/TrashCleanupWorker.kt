package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.*
import com.renyxin.localalbum.AppContainer
import java.io.File
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
        val container = (applicationContext as? com.renyxin.localalbum.LocalAlbumApplication)
            ?.container ?: return Result.failure()
        val dao = container.albumRepository.getMediaDao()
        val trashFiles = dao.getTrashed()

        val now = System.currentTimeMillis()
        val threshold = now - CLEANUP_DAYS_MS
        // P0-3: 使用 deletedAtMs 判定回收站过期时间，而非 modifiedAtMs
        // modifiedAtMs 是文件系统修改时间，deletedAtMs 才是移入回收站的时间
        val toDelete = trashFiles.filter { entity ->
            entity.deletedAtMs in 1L..threshold
        }

        if (toDelete.isNotEmpty()) {
            val paths = toDelete.map { it.filePath }
            // 删除物理文件
            paths.forEach { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                    // 忽略删除失败
                }
            }
            // 从数据库移出
            dao.deleteByPaths(paths)
            dao.deleteFtsEntries(paths)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "trash_cleanup_worker"
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