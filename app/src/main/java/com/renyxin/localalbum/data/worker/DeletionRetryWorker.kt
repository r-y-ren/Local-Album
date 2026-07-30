package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 有限批次领取到期 tombstone；Room 租约提供跨进程/多 Worker 防并发边界。 */
class DeletionRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? LocalAlbumApplication)?.container ?: return Result.failure()
        val dao = container.database.deletionTombstoneDao()
        val token = UUID.randomUUID().toString()
        val claimed = dao.claimDue(System.currentTimeMillis(), BATCH_SIZE, LEASE_MS, token)
        if (claimed.isEmpty()) return Result.success()
        container.deletionService.retryClaimed(claimed)
        if (claimed.size == BATCH_SIZE) enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "deletion_retry_v26"
        internal const val BATCH_SIZE = 50
        internal val LEASE_MS = TimeUnit.MINUTES.toMillis(10)

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeletionRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
