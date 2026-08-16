package com.renyxin.localalbum.data.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.R
import com.renyxin.localalbum.core.analysis.AiAnalysisPreferences
import com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime
import com.renyxin.localalbum.core.analysis.FaceGroupingStrictness
import com.renyxin.localalbum.core.analysis.OcrAnalysisScope
import com.renyxin.localalbum.core.analysis.RecommendationPreference
import com.renyxin.localalbum.core.analysis.SemanticSearchStrictness
import com.renyxin.localalbum.core.concurrent.AnalysisDeviceCapabilityDetector
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingMode
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingResolver
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingRuntime
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.prefs.SettingsStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 唯一、可恢复的持久分析任务消费者。 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        val database = container.database
        val dao = database.analysisTaskDao()
        val scanRunDao = database.scanRunDao()
        val pipeline = container.pluginAnalysisPipeline
        val claimableScopes = pipeline.claimableTaskScopes
        val requestedMode = AnalysisSchedulingMode.fromPersistedValue(
            SettingsStore(applicationContext).analysisSchedulingMode.first(),
        )
        val schedulingProfile = AnalysisSchedulingResolver.resolve(
            requestedMode,
            AnalysisDeviceCapabilityDetector.detect(applicationContext),
        )
        AnalysisSchedulingRuntime.update(schedulingProfile)
        val settings = SettingsStore(applicationContext)
        AiAnalysisPreferencesRuntime.update(
            AiAnalysisPreferences(
                faceGroupingStrictness = FaceGroupingStrictness.fromPersistedValue(
                    settings.faceGroupingStrictness.first(),
                ),
                faceMinimumGroupSize = settings.faceMinimumGroupSize.first(),
                ocrAnalysisScope = OcrAnalysisScope.fromPersistedValue(settings.ocrAnalysisScope.first()),
                semanticSearchStrictness = SemanticSearchStrictness.fromPersistedValue(
                    settings.semanticSearchStrictness.first(),
                ),
                semanticSearchResultCount = settings.semanticSearchResultCount.first(),
                recommendationPreference = RecommendationPreference.fromPersistedValue(
                    settings.recommendationPreference.first(),
                ),
            ),
        )

        val leasedGroups = mutableListOf<LeasedTaskGroup>()
        val touchedScanIds = linkedSetOf<String>()
        // Snapshot explicit-user admission before a scan-owned Stage can publish generic pipeline
        // status. A dedicated pause bit distinguishes an old/import queue from an explicit cancel.
        val activeUserTasksAtStart = dao.countActiveUserTasks()
        val includeUserTasks = userTaskAdmission(
            userPaused = AnalysisResumePrefs.isUserPaused(applicationContext),
            resumePending = AnalysisResumePrefs.isPending(applicationContext),
            activeUserTasks = activeUserTasksAtStart,
        )
        if (includeUserTasks && activeUserTasksAtStart > 0) {
            AnalysisResumePrefs.setPending(applicationContext, true)
        }
        try {
            if (container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) return Result.retry()
            val diagnosticNow = System.currentTimeMillis()
            val activeAtStart = activeTaskCount(dao, claimableScopes, includeUserTasks)
            val claimableAtStart = claimableTaskCount(dao, claimableScopes, diagnosticNow, includeUserTasks)
            Log.i(
                TAG,
                "analysis diagnostic: attempt=$runAttemptCount claimableScopes=${claimableScopes.size} " +
                    "includeUser=$includeUserTasks activeAtStart=$activeAtStart " +
                    "claimableAtStart=$claimableAtStart",
            )
            setForeground(getForegroundInfo())

            val laneResult = EnhancementResourceGate.tryWithAutomaticEnhancement {
                try {
                    // Recovery, leasing, inference and lease cleanup all occur while this lane is
                    // held. Backup maintenance cannot replace tables between those operations.
                    scanRunDao.recoverRunningEnhancements()
                    val recoveryNow = System.currentTimeMillis()
                    dao.recoverInterruptedLeases(recoveryNow)
                    pipeline.retiredAutomaticScopeSelectors.forEach { selector ->
                        dao.supersedeRetiredPolicyScopes(
                            pipelinePrefix = selector.pipelinePrefix,
                            planName = selector.planName,
                            policyIdentity = selector.policyIdentity,
                            reason = RETIRED_POLICY_REASON,
                            now = recoveryNow,
                        )
                    }
                    // Include runs queued before this process started, even when their tasks were
                    // completed or superseded during recovery and no new lease is claimed below.
                    touchedScanIds += scanRunDao.getUnsettledEnhancementScanIds()

                    // Pick exactly one immediately claimable durable identity per Worker run.
                    // A prior scope whose failures are waiting for nextRetryAt must not starve a later
                    // Stage that can run now. Aggregate compatibility scopes still retain precedence.
                    val activeScope = claimableScopes.firstOrNull { scope ->
                        dao.countClaimable(recoveryNow, scope, includeUserTasks) > 0
                    }
                    if (activeScope != null) {
                        repeat(schedulingProfile.workerLeaseGroups.coerceIn(1, MAX_BATCHES_PER_RUN)) {
                            if (
                                isStopped ||
                                container.albumRepository.isCoreScanActive() ||
                                EnhancementResourceGate.isAutomaticWorkBlocked
                            ) {
                                return@repeat
                            }
                            val now = System.currentTimeMillis()
                            val token = UUID.randomUUID().toString()
                            val tasks = dao.claimBatch(
                                now = now,
                                limit = BATCH_SIZE,
                                leaseToken = token,
                                leaseDurationMs = LEASE_MS,
                                scope = activeScope,
                                includeUserTasks = includeUserTasks,
                            )
                            if (tasks.isEmpty()) return@repeat
                            leasedGroups += LeasedTaskGroup(token, tasks)
                            touchedScanIds += tasks.mapNotNull { it.scanId }
                        }
                    }

                    if (touchedScanIds.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        touchedScanIds.forEach { scanId ->
                            scanRunDao.markEnhancementRunning(scanId, now = now)
                        }
                    }

                    if (leasedGroups.isNotEmpty()) {
                        coroutineScope {
                            val heartbeat = launch(start = CoroutineStart.UNDISPATCHED) {
                                while (isActive) {
                                    val renewedAt = System.currentTimeMillis()
                                    leasedGroups.forEach { group ->
                                        dao.renewLease(group.token, renewedAt + LEASE_MS, renewedAt)
                                    }
                                    delay(LEASE_RENEW_INTERVAL_MS)
                                }
                            }
                            val notification = launch(start = CoroutineStart.UNDISPATCHED) {
                                pipeline.progressManager.progress.collect { progress ->
                                    if (!progress.isCompleted && progress.processedFiles > 0) {
                                        val text = applicationContext.getString(
                                            R.string.scan_notif_stage,
                                            progress.currentStageName.ifEmpty { "正在分析" },
                                            progress.processedFiles,
                                            progress.totalFiles,
                                        )
                                        ScanServiceController.updateEnhancementProgress(applicationContext, text)
                                    }
                                }
                            }
                            try {
                                leasedGroups.forEach { group ->
                                    val taskScope = group.tasks.first().pipelineScope
                                    check(group.tasks.all { it.pipelineScope == taskScope }) {
                                        "Analysis lease mixed task scopes"
                                    }
                                    val requiredStageIds = pipeline.requiredStageIdsForTaskScope(taskScope)
                                    val attemptedPaths = group.tasks.map { it.filePath }
                                    val results = pipeline.runIncrementalForTaskScope(
                                        taskScope = taskScope,
                                        incrementalPaths = attemptedPaths,
                                        allPaths = emptyList(),
                                    )
                                    val failure = failureSummary(results, requiredStageIds)
                                    val failedPaths = failedPaths(results, requiredStageIds, attemptedPaths)
                                    val failedTasks = group.tasks.filter { it.filePath in failedPaths }
                                    val succeededTasks = group.tasks.filterNot { it.filePath in failedPaths }
                                    val completedAt = System.currentTimeMillis()
                                    if (succeededTasks.isNotEmpty()) {
                                        dao.markDone(succeededTasks.map { it.taskId }, group.token, completedAt)
                                    }
                                    if (failedTasks.isNotEmpty()) {
                                        markFailed(
                                            dao,
                                            failedTasks,
                                            group.token,
                                            failure ?: "stage_file_failure",
                                        )
                                    }
                                }
                            } finally {
                                heartbeat.cancel()
                                notification.cancel()
                            }
                        }
                    }

                    finalizeEnhancementStates(scanRunDao, dao, touchedScanIds)
                    val now = System.currentTimeMillis()
                    val active = activeTaskCount(dao, claimableScopes, includeUserTasks)
                    val claimable = claimableTaskCount(dao, claimableScopes, now, includeUserTasks)
                    if (includeUserTasks) {
                        AnalysisResumePrefs.setPending(
                            applicationContext,
                            AnalysisResumePrefs.isPending(applicationContext) &&
                                dao.countActiveUserTasks() > 0,
                        )
                    }
                    val decision = continuationDecision(activeTasks = active, claimableTasks = claimable)
                    Log.i(
                        TAG,
                        "analysis diagnostic: leasedGroups=${leasedGroups.size} " +
                            "touchedScans=${touchedScanIds.size} remainingActive=$active " +
                            "claimable=$claimable decision=$decision",
                    )
                    when (decision) {
                        ContinuationDecision.ENQUEUE_SUCCESSOR -> {
                            enqueue(applicationContext)
                            Result.success()
                        }
                        ContinuationDecision.RETRY_BACKOFF -> Result.retry()
                        ContinuationDecision.COMPLETE -> Result.success()
                    }
                } catch (cancelled: CancellationException) {
                    // The caller persists PREEMPTED (core/maintenance) or PAUSED (user) before
                    // cancellation. Release leases before this automatic lane becomes available.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        if (leasedGroups.isNotEmpty()) {
                            dao.releaseLeases(
                                leasedGroups.map { it.token },
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    leasedGroups.forEach { group ->
                        try {
                            markFailed(dao, group.tasks, group.token, error.javaClass.simpleName)
                        } catch (markError: Throwable) {
                            Log.e(TAG, "保存增强失败任务状态失败", markError)
                        }
                    }
                    try {
                        finalizeEnhancementStates(scanRunDao, dao, touchedScanIds)
                    } catch (stateError: Throwable) {
                        Log.e(TAG, "保存增强终态失败", stateError)
                    }
                    val active = activeTaskCount(dao, claimableScopes, includeUserTasks)
                    if (active > 0) Result.retry() else Result.success()
                }
            }
            return laneResult ?: Result.retry()
        } catch (cancelled: CancellationException) {
            ScanServiceController.clearEnhancementProgress(applicationContext)
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "增强任务执行失败", error)
            return Result.retry()
        } finally {
            ScanServiceController.clearEnhancementProgress(applicationContext)
        }
    }

    private suspend fun finalizeEnhancementStates(
        scanRunDao: com.renyxin.localalbum.data.db.dao.ScanRunDao,
        taskDao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao,
        scanIds: Set<String>,
    ) {
        scanIds.forEach { scanId ->
            val terminalState = terminalEnhancementState(
                activeTasks = taskDao.countActiveForScan(scanId) +
                    (applicationContext as LocalAlbumApplication).container.database
                        .thumbnailTaskDao().countActiveForScan(scanId) +
                    (applicationContext as LocalAlbumApplication).container.database
                        .enhancementOutboxDao().countActiveForScan(scanId),
                failedTasks = taskDao.countFailedForScan(scanId) +
                    (applicationContext as LocalAlbumApplication).container.database
                        .thumbnailTaskDao().countFailedForScan(scanId) +
                    (applicationContext as LocalAlbumApplication).container.database
                        .enhancementOutboxDao().countFailedForScan(scanId),
            )
            if (terminalState == null) {
                scanRunDao.markEnhancementQueued(scanId)
            } else {
                scanRunDao.markEnhancementTerminal(
                    scanId = scanId,
                    state = terminalState.name,
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun activeTaskCount(
        dao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao,
        scopes: List<String>,
        includeUserTasks: Boolean,
    ): Int = scopes.sumOf { scope -> dao.countRunnable(scope, includeUserTasks) }

    private suspend fun claimableTaskCount(
        dao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao,
        scopes: List<String>,
        now: Long,
        includeUserTasks: Boolean,
    ): Int = scopes.sumOf { scope -> dao.countClaimable(now, scope, includeUserTasks) }

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

    private data class LeasedTaskGroup(
        val token: String,
        val tasks: List<AnalysisTaskEntity>,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ScanServiceController.ensureEnhancementChannel(applicationContext)
        val notification: Notification = ScanServiceController.buildEnhancementNotification(
            applicationContext,
            applicationContext.getString(R.string.scan_notif_analyzing),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ScanServiceController.ENHANCEMENT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ScanServiceController.ENHANCEMENT_NOTIFICATION_ID, notification)
        }
    }

    internal enum class ContinuationDecision {
        ENQUEUE_SUCCESSOR,
        RETRY_BACKOFF,
        COMPLETE,
    }

    companion object {
        private const val TAG = "AnalysisWorker"
        private const val WORK_NAME = "analysis_task_queue"
        private const val BATCH_SIZE = 250
        private const val MAX_BATCHES_PER_RUN = 4
        private const val MAX_ATTEMPTS = 3
        private const val RETIRED_POLICY_REASON = "retired_automatic_policy"
        private const val LEASE_MS = 30 * 60 * 1000L
        private const val LEASE_RENEW_INTERVAL_MS = 5 * 60 * 1000L

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<AnalysisWorker>().build(),
            )
        }

        /**
         * Ordinary bounded continuation must not consume WorkManager's failure backoff. A successor
         * is appended while this unique request is still RUNNING, then this request completes.
         */
        internal fun continuationDecision(
            activeTasks: Int,
            claimableTasks: Int,
        ): ContinuationDecision = when {
            claimableTasks > 0 -> ContinuationDecision.ENQUEUE_SUCCESSOR
            activeTasks > 0 -> ContinuationDecision.RETRY_BACKOFF
            else -> ContinuationDecision.COMPLETE
        }

        /** User cancellation is persistent and must not be auto-resumed. */
        fun cancel(context: Context) {
            AnalysisResumePrefs.setUserPaused(context, true)
            AnalysisResumePrefs.setPending(context, false)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        /** Core/maintenance preemption preserves, but never creates, the user resume marker. */
        fun cancelForCorePreemption(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        /** User reanalysis replaces the chain but must remain resumable until new tasks are durable. */
        fun cancelForQueueReplacement(context: Context) {
            AnalysisResumePrefs.setUserPaused(context, false)
            AnalysisResumePrefs.setPending(context, true)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Publishes the post-transaction user queue as an independent unique chain. Normal bounded
         * continuation must keep using [enqueue]; REPLACE is reserved for this explicit reset path.
         */
        fun publishQueueReplacement(context: Context, hasRunnableTasks: Boolean) {
            AnalysisResumePrefs.setUserPaused(context, false)
            AnalysisResumePrefs.setPending(context, hasRunnableTasks)
            val workManager = WorkManager.getInstance(context.applicationContext)
            if (hasRunnableTasks) {
                workManager.enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AnalysisWorker>().build(),
                )
            } else {
                workManager.cancelUniqueWork(WORK_NAME)
            }
        }

        internal fun userTaskAdmission(
            userPaused: Boolean,
            resumePending: Boolean,
            activeUserTasks: Int,
        ): Boolean = !userPaused && (resumePending || activeUserTasks > 0)

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

        /** 仅返回无法通过全部必需阶段的文件；健康文件不再被同批坏文件拖入重试。 */
        internal fun failedPaths(
            results: Map<String, StageResult>,
            requiredStageIds: Set<String>,
            attemptedPaths: List<String>,
        ): Set<String> {
            val attempted = attemptedPaths.toSet()
            if (requiredStageIds.isEmpty() || requiredStageIds.any { it !in results }) return attempted
            return requiredStageIds
                .asSequence()
                .flatMap { stageId -> results.getValue(stageId).failedPaths.asSequence() }
                .filter { it in attempted }
                .toSet()
        }

        internal fun retryDelay(attempt: Int): Long = 60_000L * (1L shl (attempt - 1).coerceIn(0, 8))

        internal fun terminalEnhancementState(
            activeTasks: Int,
            failedTasks: Int,
        ): EnhancementState? = when {
            activeTasks > 0 -> null
            failedTasks > 0 -> EnhancementState.COMPLETED_WITH_FAILURES
            else -> EnhancementState.COMPLETED
        }
    }
}
