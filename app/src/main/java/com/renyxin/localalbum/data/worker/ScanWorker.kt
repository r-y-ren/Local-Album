package com.renyxin.localalbum.data.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.R
import java.util.concurrent.TimeUnit

/**
 * 媒体扫描 Worker（Phase 2 激活）。
 *
 * 将原死代码改造为 long-running worker：
 * - [getForegroundInfo] 提供前台通知，使 WorkManager 在后台执行时不被系统回收；
 * - [doWork] 委托 [com.renyxin.localalbum.data.repo.AlbumRepository.rescan]，
 *   失败时指数退避重试（最多 [MAX_RETRIES] 次）；
 * - [schedule] 以 `enqueueUniqueWork` + `KEEP` 策略入队，防重复。
 *
 * ## 与 Phase 0 自建 FGS 的关系
 *
 * Phase 0 已通过 [ScanServiceController] 为进程内扫描提供前台服务保活。
 * 本 Worker 主要面向「系统调度触发的后台扫描」场景（如未来定期扫描）。
 * 注意：[com.renyxin.localalbum.data.repo.AlbumRepository.rescan] 内部会启动
 * [ScanForegroundService]（通知 ID = [ScanServiceController.NOTIFICATION_ID]），
 * 本 Worker 自身的前台通知使用独立 ID（[WORKER_NOTIFICATION_ID]），避免通知冲突。
 * 当前未在任何入口自动 enqueue，避免与进程内扫描路径重复触发；可通过 [schedule] 按需启用。
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val repository = app.container.albumRepository
        return try {
            // 提升为前台 Worker，避免后台执行被系统回收
            setForeground(getForegroundInfo())
            repository.rescan()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ScanWorker 执行失败 (attempt=${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ScanServiceController.ensureChannel(applicationContext)
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            ScanServiceController.CHANNEL_ID,
        )
            .setContentTitle(applicationContext.getString(R.string.scan_notif_title))
            .setContentText(applicationContext.getString(R.string.scan_notif_scanning))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WORKER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ScanWorker"
        private const val MAX_RETRIES = 3
        private const val UNIQUE_WORK_NAME = "localalbum_scan_worker"
        /** Worker 自身前台通知 ID，与 ScanServiceController.NOTIFICATION_ID 区分。 */
        private const val WORKER_NOTIFICATION_ID = 1002

        /**
         * 入队一次扫描任务（幂等，KEEP 策略防重复）。
         * 约束：电量不低时执行；失败指数退避。
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
