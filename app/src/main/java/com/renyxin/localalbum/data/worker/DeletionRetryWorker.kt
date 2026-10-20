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
        // 自续排必须 APPEND：当前 WorkSpec 尚处 RUNNING，KEEP 会把新请求当作
        // "存在未完成同名工作"丢弃，剩余到期 tombstone 被无谓延迟（审计 F-P2-1）。
        if (claimed.size == BATCH_SIZE) enqueueMore(applicationContext)
        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "deletion_retry_v26"
        internal const val BATCH_SIZE = 50
        internal val LEASE_MS = TimeUnit.MINUTES.toMillis(10)

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest(),
            )
        }

        /** 批次满时的窗口推进续排，追加在当前运行之后。 */
        private fun enqueueMore(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(),
            )
        }

        private fun buildRequest() = OneTimeWorkRequestBuilder<DeletionRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
    }
}
