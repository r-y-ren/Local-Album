package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.data.repo.PersistedScanDrainResult

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
 * Durable changed-set recovery worker.
 *
 * The observer persists notifications before scheduling this unique work. The normal in-process
 * path still drains immediately; this worker handles process death, expired leases, and retry delay.
 * It never creates a new reconciliation request and only consumes requests already in Room.
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        var admittedScanId: String? = null
        return try {
            admittedScanId = container.libraryPipelineCoordinator.admittedScanIdOrNull()
            setForeground(getForegroundInfo())
            val drainResult = container.albumRepository.drainPersistedScanRequests()
            when (scanWorkDecision(drainResult, runAttemptCount)) {
                ScanWorkDecision.SUCCESS -> Result.success()
                // 有界失败重试（受 MAX_RETRIES 约束）仍用 WorkManager 退避。
                ScanWorkDecision.RETRY -> Result.retry()
                // Deferred（journal 全部处于重试延迟窗）改为自调度固定延迟的新请求：
                // 新 WorkSpec 的退避计数从零开始。若走 Result.retry()，本唯一链的
                // 指数退避会累积到小时级，把后续所有增量扫描压在毒化链后面。
                ScanWorkDecision.DEFERRED -> {
                    scheduleDeferred(applicationContext)
                    Result.success()
                }
                ScanWorkDecision.FAILURE -> {
                    val failed = drainResult as PersistedScanDrainResult.Failed
                    container.libraryPipelineCoordinator.markActiveScanFailed(
                        scanId = failed.scanId,
                        error = failed.error,
                    )
                    Result.failure()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ScanWorker 执行失败 (attempt=${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                container.libraryPipelineCoordinator.markActiveScanFailed(
                    scanId = admittedScanId,
                    error = e.javaClass.simpleName.ifBlank { "scan_worker_failed" },
                )
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ScanServiceController.ensureChannel(applicationContext)
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            ScanServiceController.CORE_CHANNEL_ID,
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

    internal enum class ScanWorkDecision {
        SUCCESS,
        RETRY,
        FAILURE,
        /** journal 存在但全部处于重试延迟窗：不消耗失败预算，也不烧 WorkManager 退避。 */
        DEFERRED,
    }

    companion object {
        private const val TAG = "ScanWorker"
        private const val MAX_RETRIES = 3

        internal fun scanWorkDecision(
            result: PersistedScanDrainResult,
            runAttemptCount: Int,
        ): ScanWorkDecision = when (result) {
            PersistedScanDrainResult.Completed -> ScanWorkDecision.SUCCESS
            // Durable work that is not claimable yet must never consume the finite failure budget.
            PersistedScanDrainResult.Deferred -> ScanWorkDecision.DEFERRED
            is PersistedScanDrainResult.Failed ->
                if (runAttemptCount < MAX_RETRIES) {
                    ScanWorkDecision.RETRY
                } else {
                    ScanWorkDecision.FAILURE
                }
        }
        private const val UNIQUE_WORK_NAME = "localalbum_scan_worker"
        /** Worker 自身前台通知 ID，与 ScanServiceController.NOTIFICATION_ID 区分。 */
        private const val WORKER_NOTIFICATION_ID = 1002
        /** Deferred 自调度延迟，对齐 journal 的重试延迟窗。 */
        private const val DEFERRED_RESCHEDULE_SECONDS = 5L

        /**
         * Called only by the durable pipeline pump after scan-stage admission. Appending preserves a
         * newly admitted scan when the preceding ScanWorker is still completing its bounded cleanup;
         * external event bursts are deduplicated earlier by LibraryPipelineWorker's KEEP policy.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(initialDelaySeconds = 0L),
            )
        }

        /** Deferred 重排：与 journal 重试窗对齐的固定延迟；每次都是全新 WorkSpec。 */
        internal fun scheduleDeferred(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(initialDelaySeconds = DEFERRED_RESCHEDULE_SECONDS),
            )
        }

        /**
         * 启动自愈：历史重试风暴会把本唯一链的指数退避毒化到小时级，且
         * APPEND_OR_REPLACE 不会替换"仍在退避重试中"的链。启动时点（本进程尚无
         * 运行中 worker）整链取消是安全的；随后 wake() 依持久状态重建所需队列。
         */
        fun resetPoisonedChain(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private fun buildRequest(initialDelaySeconds: Long): androidx.work.OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<ScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            if (initialDelaySeconds > 0L) builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            return builder.build()
        }
    }
}
