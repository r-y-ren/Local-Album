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
                ScanWorkDecision.RETRY -> Result.retry()
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
            PersistedScanDrainResult.Deferred -> ScanWorkDecision.RETRY
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

        /**
         * Called only by the durable pipeline pump after scan-stage admission. Appending preserves a
         * newly admitted scan when the preceding ScanWorker is still completing its bounded cleanup;
         * external event bursts are deduplicated earlier by LibraryPipelineWorker's KEEP policy.
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
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
