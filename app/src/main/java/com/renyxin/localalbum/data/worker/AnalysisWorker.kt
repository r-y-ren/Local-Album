package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 唯一、可恢复的持久分析任务消费者。 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        val dao = container.database.analysisTaskDao()
        val pipeline = container.pluginAnalysisPipeline
        val scope = pipeline.pipelineScope
        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN && !isStopped) {
            val now = System.currentTimeMillis()
            val token = UUID.randomUUID().toString()
            val currentTasks = dao.claimBatch(now, BATCH_SIZE, token, LEASE_MS, scope)
            // v19→v20 无法在 SQL 迁移期获知运行时 Provider；仅把迁移默认 scope 作为一次性兼容队列。
            val tasks = if (currentTasks.isNotEmpty() || scope == AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE) {
                currentTasks
            } else {
                dao.claimBatch(now, BATCH_SIZE, token, LEASE_MS, AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE)
            }
            if (tasks.isEmpty()) break
            val taskIds = tasks.map { it.taskId }
            try {
                coroutineScope {
                    val heartbeat = launch(start = CoroutineStart.UNDISPATCHED) {
                        while (isActive) {
                            val renewedAt = System.currentTimeMillis()
                            dao.renewLease(token, renewedAt + LEASE_MS, renewedAt)
                            delay(LEASE_RENEW_INTERVAL_MS)
                        }
                    }
                    try {
                        val results = pipeline.runIncremental(tasks.map { it.filePath }, emptyList())
                        val failure = failureSummary(results, pipeline.requiredStageIds)
                        if (failure == null) {
                            dao.markDone(taskIds, token, System.currentTimeMillis())
                        } else {
                            markFailed(dao, tasks, token, failure)
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                markFailed(dao, tasks, token, error.javaClass.simpleName)
            }
            batches++
        }
        val active = dao.countActive(scope) + if (scope == AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE) {
            0
        } else {
            dao.countActive(AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE)
        }
        return if (active > 0) Result.retry() else Result.success()
    }

    private suspend fun markFailed(
        dao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao,
        tasks: List<AnalysisTaskEntity>,
        token: String,
        error: String,
    ) {
        val now = System.currentTimeMillis()
        val attempt = tasks.maxOfOrNull { it.attemptCount } ?: 1
        dao.markFailed(
            tasks.map { it.taskId },
            token,
            error.take(240),
            now + retryDelay(attempt),
            MAX_ATTEMPTS,
            now,
        )
    }

    companion object {
        private const val WORK_NAME = "analysis_task_queue"
        private const val BATCH_SIZE = 250
        private const val MAX_BATCHES_PER_RUN = 4
        private const val MAX_ATTEMPTS = 3
        private const val LEASE_MS = 30 * 60 * 1000L
        private const val LEASE_RENEW_INTERVAL_MS = 5 * 60 * 1000L

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AnalysisWorker>().build(),
            )
        }

        internal fun failureSummary(
            results: Map<String, StageResult>,
            requiredStageIds: Set<String> = results.keys,
        ): String? {
            if (requiredStageIds.isEmpty()) return "pipeline_no_stages"
            val missing = requiredStageIds - results.keys
            if (missing.isNotEmpty()) return "missing_stages:${missing.sorted().joinToString("+")}"
            val failures = results.filterKeys { it in requiredStageIds }.filterValues { it.failedCount > 0 }
            return failures.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString(",") { (stage, result) -> "$stage:${result.failedCount}" }
        }

        internal fun retryDelay(attempt: Int): Long = 60_000L * (1L shl (attempt - 1).coerceIn(0, 8))
    }
}
