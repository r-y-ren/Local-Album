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
        val libraryState = database.libraryPipelineDao().get()
        val libraryStage = com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
            .fromPersisted(libraryState?.stage.orEmpty())
        val admittedScanId = libraryState?.activeRunId?.takeIf { libraryStage.isAnalysis }
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
        admittedScanId?.let(touchedScanIds::add)
        // Explicit user work is independent, but it must not delay or preempt a persisted automatic
        // scan/thumbnail/analysis phase. It resumes when the library pipeline returns to an idle
        // terminal state.
        val activeUserTasksAtStart = dao.countActiveUserTasks()
        val userStageAllowed = libraryStage ==
            com.renyxin.localalbum.data.db.entity.LibraryPipelineStage.READY ||
            libraryStage == com.renyxin.localalbum.data.db.entity.LibraryPipelineStage.NEEDS_REBUILD ||
            libraryStage == com.renyxin.localalbum.data.db.entity.LibraryPipelineStage.FAILED
        val includeUserTasks = userStageAllowed && userTaskAdmission(
            userPaused = AnalysisResumePrefs.isUserPaused(applicationContext),
            resumePending = AnalysisResumePrefs.isPending(applicationContext),
            activeUserTasks = activeUserTasksAtStart,
        )
        if (includeUserTasks && activeUserTasksAtStart > 0) {
            AnalysisResumePrefs.setPending(applicationContext, true)
        }
        if (admittedScanId == null && !includeUserTasks) {
            // 流水线忙碌（增量扫描/缩略图/发布等阶段）期间用户任务让路——但绝不能
            // 裸结束：这是链式续排的断头路，会让重跑队列在流水线回到空闲后无人唤醒，
            // 表现为"跑一段时间就停、重启才继续"。排队等流水线空闲后自动恢复。
            if (activeUserTasksAtStart > 0 && !AnalysisResumePrefs.isUserPaused(applicationContext)) {
                scheduleDeferred(applicationContext, PIPELINE_BUSY_RESCHEDULE_SECONDS)
            }
            return Result.success()
        }
        try {
            if (container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) {
                // 核心扫描优先时让路，但绝不能走 Result.retry()：WM 指数退避会翻倍累积
                // （上限 5h），与重试延迟窗叠加后表现为"几小时不动、重启才跑一批"。
                // 自调度固定延迟的新请求（全新 WorkSpec，退避从零起算），核心完成时的
                // 级联唤醒同样会命中 KEEP 复用本请求。
                scheduleDeferred(applicationContext, CORE_PREEMPTION_RESCHEDULE_SECONDS)
                return Result.success()
            }
            val diagnosticNow = System.currentTimeMillis()
            val activeAtStart = activeTaskCount(
                dao,
                claimableScopes,
                includeUserTasks,
                admittedScanId,
            )
            val claimableAtStart = claimableTaskCount(
                dao,
                claimableScopes,
                diagnosticNow,
                includeUserTasks,
                admittedScanId,
            )
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
                    admittedScanId?.let { scanRunDao.recoverRunningEnhancement(it) }
                    val recoveryNow = System.currentTimeMillis()
                    dao.recoverInterruptedLeasesForAdmission(
                        admittedScanId = admittedScanId,
                        includeUserTasks = includeUserTasks,
                        now = recoveryNow,
                    )
                    pipeline.retiredAutomaticScopeSelectors.forEach { selector ->
                        dao.supersedeRetiredPolicyScopes(
                            pipelinePrefix = selector.pipelinePrefix,
                            planName = selector.planName,
                            policyIdentity = selector.policyIdentity,
                            reason = RETIRED_POLICY_REASON,
                            now = recoveryNow,
                        )
                    }
                    // Pick exactly one immediately claimable durable identity per Worker run.
                    // A prior scope whose failures are waiting for nextRetryAt must not starve a later
                    // Stage that can run now. Aggregate compatibility scopes still retain precedence.
                    val activeScope = claimableScopes.firstOrNull { scope ->
                        dao.countClaimable(
                            now = recoveryNow,
                            scope = scope,
                            includeUserTasks = includeUserTasks,
                            admittedScanId = admittedScanId,
                            admitAnyScan = false,
                        ) > 0
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
                                admittedScanId = admittedScanId,
                                admitAnyScan = false,
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
                    val active = activeTaskCount(
                        dao,
                        claimableScopes,
                        includeUserTasks,
                        admittedScanId,
                    )
                    val claimable = claimableTaskCount(
                        dao,
                        claimableScopes,
                        now,
                        includeUserTasks,
                        admittedScanId,
                    )
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
                            appendSuccessor(applicationContext)
                            Result.success()
                        }
                        // 有任务但都在重试延迟窗：自调度固定延迟而非 Result.retry()，
                        // 否则 WM 指数退避逐次翻倍（上限 5h）毒化整条链——重试延迟的
                        // 任务没有定时唤醒者，表现为"几小时不动、重启才跑一批"。
                        ContinuationDecision.RETRY_BACKOFF -> {
                            scheduleDeferred(applicationContext, RETRY_WAIT_RESCHEDULE_SECONDS)
                            Result.success()
                        }
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
                    val active = activeTaskCount(
                        dao,
                        claimableScopes,
                        includeUserTasks,
                        admittedScanId,
                    )
                    if (active > 0) {
                        scheduleDeferred(applicationContext, RETRY_WAIT_RESCHEDULE_SECONDS)
                        Result.success()
                    } else {
                        Result.success()
                    }
                }
            }
            val result = laneResult ?: run {
                scheduleDeferred(applicationContext, RETRY_WAIT_RESCHEDULE_SECONDS)
                Result.success()
            }
            if (admittedScanId != null) {
                container.libraryPipelineCoordinator.finishAnalysisIfIdle(admittedScanId)
            }
            return result
        } catch (cancelled: CancellationException) {
            ScanServiceController.clearEnhancementProgress(applicationContext)
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "增强任务执行失败", error)
            // 真实异常保留有限次 WM 重试（默认 30s 起步）；连续异常的兜底由失败
            // 任务的 attemptCount 上限终态化，不构成退避毒化的主路径。
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
        admittedScanId: String?,
    ): Int = scopes.sumOf { scope ->
        dao.countRunnable(
            scope = scope,
            includeUserTasks = includeUserTasks,
            admittedScanId = admittedScanId,
            admitAnyScan = false,
        )
    }

    private suspend fun claimableTaskCount(
        dao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao,
        scopes: List<String>,
        now: Long,
        includeUserTasks: Boolean,
        admittedScanId: String?,
    ): Int = scopes.sumOf { scope ->
        dao.countClaimable(
            now = now,
            scope = scope,
            includeUserTasks = includeUserTasks,
            admittedScanId = admittedScanId,
            admitAnyScan = false,
        )
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
        /** 重试延迟窗的轮询间隔；任务退避下限即 60s，60s 轮询不漏批。 */
        private const val RETRY_WAIT_RESCHEDULE_SECONDS = 60L
        /** 核心扫描让路后的回来间隔；核心完成时有级联唤醒，此值仅兜底。 */
        private const val CORE_PREEMPTION_RESCHEDULE_SECONDS = 60L
        /** 流水线忙碌期用户任务的等待轮询间隔。 */
        private const val PIPELINE_BUSY_RESCHEDULE_SECONDS = 60L
        private const val MAX_ATTEMPTS = 3
        private const val RETIRED_POLICY_REASON = "retired_automatic_policy"
        private const val LEASE_MS = 30 * 60 * 1000L
        private const val LEASE_RENEW_INTERVAL_MS = 5 * 60 * 1000L

        /** External/coordinator admission is level-triggered; one active or pending consumer suffices. */
        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        /** Ordinary bounded continuation appends behind the currently running consumer. */
        internal fun appendSuccessor(context: Context) =
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

        /**
         * 等待型续跑（重试延迟窗/核心让路）：固定延迟的全新 WorkSpec，退避计数从零
         * 起算。任务的重试延迟下限即 60s（retryDelay），固定 60s 轮询代价可忽略。
         */
        internal fun scheduleDeferred(context: Context, delaySeconds: Long) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<AnalysisWorker>()
                    .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * 启动自愈：历史毒化（Result.retry 累积到小时级退避）的分析链与扫描/泵链同样
         * 需要整链重置；启动点无在途消费者，随后 wake() 依持久任务重建。
         */
        fun resetPoisonedChain(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                policy,
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
