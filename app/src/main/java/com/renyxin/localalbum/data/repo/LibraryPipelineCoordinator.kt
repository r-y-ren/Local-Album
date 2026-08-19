package com.renyxin.localalbum.data.repo

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.LibraryPipelineEntity
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.worker.AnalysisResumePrefs
import com.renyxin.localalbum.data.worker.AnalysisWorker
import com.renyxin.localalbum.data.worker.EnhancementHandoffWorker
import com.renyxin.localalbum.data.worker.LibraryPipelineWorker
import com.renyxin.localalbum.data.worker.ThumbnailWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class FailedTaskRetryResult(
    val retried: Int = 0,
    val admitted: Boolean = true,
)

private data class UserTaskAdmission(
    val analysis: Boolean = false,
    val thumbnails: Boolean = false,
) {
    val hasWork: Boolean get() = analysis || thumbnails
}

data class EnhancementOutboxRetryResult(
    val outboxesRetried: Int = 0,
    val analysisTasks: Int = 0,
    val thumbnailTasks: Int = 0,
    val unsupportedOutboxes: Int = 0,
    val remainingFailed: Int = 0,
    val admitted: Boolean = true,
    internal val thumbnailWakeRequired: Boolean = false,
)

/** Durable state-machine facade shared by repository and workers. */
class LibraryPipelineCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val indexer: HybridIndexer,
    private val analysisPipeline: () -> PluginAnalysisPipeline,
) {
    private val mutex = Mutex()
    private val dao get() = database.libraryPipelineDao()
    private var processRecoveryCompleted = false

    /** UI admission derives from the same persisted rule used by every explicit retry mutation. */
    val userTaskRetryAllowed: Flow<Boolean> = dao.observe()
        .map { state -> state?.let(::canAdmitUserTasks) ?: false }
        .distinctUntilChanged()

    suspend fun ensureState(): LibraryPipelineEntity = mutex.withLock {
        ensureStateLocked()
    }

    private suspend fun ensureStateLocked(): LibraryPipelineEntity {
        val canonicalCount = database.mediaDao().getCount()
        val homeDao = database.homeMediaSnapshotDao()
        val homeCount = homeDao.count()
        val homeGeneration = homeDao.publishedGeneration()
        var state = dao.ensureInitialized(
            hasCanonicalMedia = canonicalCount > 0,
            hasPublishedSnapshot = homeCount > 0,
            publishedGeneration = homeGeneration,
        )
        val generationIsValid = state.publishedGeneration > 0L && if (homeCount == 0) {
            canonicalCount == 0
        } else {
            homeDao.distinctPublishedGenerationCount() == 1 &&
                homeGeneration == state.publishedGeneration
        }
        val validBaseline = state.hasPublishedBaseline &&
            canonicalCount == homeCount && generationIsValid
        if (!validBaseline && state.hasPublishedBaseline) {
            dao.invalidatePublishedBaselineIfIdle()
            state = requireNotNull(dao.get())
        }
        val firstProcessRecovery = !processRecoveryCompleted
        recoverActiveStageLocked(
            state = state,
            recoverInterruptedEnhancement = firstProcessRecovery,
        )
        // AppContainer 已在构造 dispatcher/coordinator/任何 Worker 入口前同步完成双 lane 的
        // processEpoch 恢复。这里仅保留其它增强域的一次性恢复，避免第二套缩略图恢复语义。
        processRecoveryCompleted = true
        return requireNotNull(dao.get())
    }

    /**
     * A committed core run can be advanced idempotently on every wake. Enhancement RUNNING recovery
     * is process-death-specific and must happen only once, otherwise a live worker would be reset.
     */
    private suspend fun recoverActiveStageLocked(
        state: LibraryPipelineEntity,
        recoverInterruptedEnhancement: Boolean,
    ) {
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val scanId = state.activeRunId ?: return
        val run = requireNotNull(database.scanRunDao().getById(scanId)) {
            "Persisted pipeline run is missing: $scanId"
        }
        check(run.generation == state.candidateGeneration) {
            "Persisted pipeline/run generation mismatch for stage=$stage"
        }
        if (stage.isScan) {
            check(run.scanType == scanTypeFor(stage)) {
                "Persisted pipeline/run type mismatch for stage=$stage"
            }
            if (
                run.coreScanState == CoreScanState.PUBLISHING.name ||
                run.coreScanState == CoreScanState.COMPLETED.name
            ) {
                advanceCoreRunToThumbnailsLocked(stage, scanId, run.generation)
            }
        } else if (recoverInterruptedEnhancement && (stage.isAnalysis || stage.isThumbnail)) {
            database.scanRunDao().recoverPipelineEnhancement(scanId)
        }
    }

    /** Startup/user wake-up resumes the one Worker admitted by durable state. */
    suspend fun wake() {
        val (advanced, userTasks) = mutex.withLock {
            ensureStateLocked()
            val pipelineAdvanced = startQueuedWorkIfIdleLocked()
            pipelineAdvanced to if (pipelineAdvanced) {
                UserTaskAdmission()
            } else {
                admittedUserTasksLocked()
            }
        }
        when {
            advanced -> LibraryPipelineWorker.appendSuccessor(context)
            userTasks.hasWork -> enqueueAdmittedUserTasks(userTasks)
            else -> LibraryPipelineWorker.enqueue(context)
        }
    }

    /**
     * MediaStore events are already durable. While another stage is busy they deliberately do not
     * enqueue any Worker; the current analysis/thumbnail stage completes and consumes the journal next.
     */
    suspend fun onMediaChangesRecorded() {
        val advanced = mutex.withLock {
            val state = ensureStateLocked()
            val stage = LibraryPipelineStage.fromPersisted(state.stage)
            if (stage in IDLE_ADMISSION_STAGES) startQueuedWorkIfIdleLocked() else false
        }
        if (advanced) LibraryPipelineWorker.appendSuccessor(context)
    }

    suspend fun requestExplicitRebuild(reason: String = "user_requested") {
        val advanced = mutex.withLock {
            ensureStateLocked()
            dao.requestRebuild(reason.take(80))
            startQueuedWorkIfIdleLocked()
        }
        if (advanced) {
            LibraryPipelineWorker.appendSuccessor(context)
        } else {
            LibraryPipelineWorker.enqueue(context)
        }
    }

    suspend fun markRebuildRequired(reason: String) {
        ensureState()
        dao.requireRebuild(reason.take(80))
    }

    /**
     * Persists a scan-domain change without authorizing traversal. A not-yet-reserved initial scan
     * will already read the new settings and therefore consumes the change naturally. Once a run has
     * been reserved (or a baseline exists), the change must wait as a later explicit rebuild advisory.
     */
    suspend fun markScanScopeChanged(reason: String) = mutex.withLock {
        val state = ensureStateLocked()
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val coveredByPendingInitialScan = !state.hasPublishedBaseline &&
            state.activeRunId == null &&
            stage in setOf(
                LibraryPipelineStage.UNINITIALIZED,
                LibraryPipelineStage.INITIAL_SCAN,
            )
        if (!coveredByPendingInitialScan) {
            dao.requireRebuild(reason.take(80))
        }
    }

    /**
     * Starts at most one stage. Ambiguous hints only preserve a rebuild advisory; independently
     * journaled item identities remain safe to consume through the incremental pipeline.
     */
    suspend fun startQueuedWorkIfIdle(): Boolean {
        val (advanced, userTasks) = mutex.withLock {
            val pipelineAdvanced = startQueuedWorkIfIdleLocked()
            pipelineAdvanced to if (pipelineAdvanced) {
                UserTaskAdmission()
            } else {
                admittedUserTasksLocked()
            }
        }
        if (userTasks.hasWork) enqueueAdmittedUserTasks(userTasks)
        return advanced
    }

    private suspend fun startQueuedWorkIfIdleLocked(): Boolean {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (stage !in IDLE_ADMISSION_STAGES) return false

        if (stage == LibraryPipelineStage.UNINITIALIZED || !state.hasPublishedBaseline) {
            return dao.transition(
                from = setOf(LibraryPipelineStage.UNINITIALIZED),
                to = LibraryPipelineStage.INITIAL_SCAN,
            )
        }

        if (state.rebuildRequested) {
            return dao.startRequestedRebuild() == 1
        }

        if (indexer.consumeReconciliationHintAsAdvisory()) {
            dao.requireRebuild("ambiguous_media_store_change")
        }
        return indexer.hasOutstandingMediaChanges() && dao.transition(
            from = setOf(
                LibraryPipelineStage.READY,
                LibraryPipelineStage.NEEDS_REBUILD,
            ),
            to = LibraryPipelineStage.INCREMENTAL_SCAN,
        )
    }

    private suspend fun admittedUserTasksLocked(): UserTaskAdmission {
        val state = requireNotNull(dao.get())
        if (!canAdmitUserTasks(state)) return UserTaskAdmission()
        val thumbnailLane = database.thumbnailTaskDao().laneState(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
        )
        return UserTaskAdmission(
            analysis = !AnalysisResumePrefs.isUserPaused(context) &&
                database.analysisTaskDao().countActiveUserTasks() > 0,
            thumbnails = thumbnailLane?.deliveryState in setOf(
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_DELAYED,
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_DISPATCHING,
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_ENQUEUED,
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_RUNNING,
            ),
        )
    }

    private suspend fun enqueueAdmittedUserTasks(
        admission: UserTaskAdmission,
        appendAnalysis: Boolean = false,
    ) {
        if (admission.thumbnails) {
            ThumbnailWorker.replayInteractiveWake(context, database.thumbnailTaskDao())
        }
        if (admission.analysis) {
            AnalysisResumePrefs.setPending(context, true)
            if (appendAnalysis) {
                AnalysisWorker.appendSuccessor(context)
            } else {
                AnalysisWorker.enqueue(context)
            }
        }
    }

    suspend fun admittedScanStage(): LibraryPipelineStage {
        ensureState()
        return LibraryPipelineStage.fromPersisted(requireNotNull(dao.get()).stage)
    }

    /**
     * Returns the already-reserved scan identity for safe attribution of a Worker-level failure.
     * This method is read-only: it never reserves a run or advances the durable stage.
     */
    suspend fun admittedScanIdOrNull(): String? = mutex.withLock {
        val state = dao.get() ?: return@withLock null
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val scanId = state.activeRunId ?: return@withLock null
        if (!stage.isScan) return@withLock null
        val run = database.scanRunDao().getById(scanId) ?: return@withLock null
        if (run.generation != state.candidateGeneration || run.scanType != scanTypeFor(stage)) {
            return@withLock null
        }
        scanId
    }

    /**
     * Reserves the identity of the currently admitted core run before source enumeration begins.
     * A process restart reads the same row and generation instead of inventing another full scan.
     */
    suspend fun getOrCreateAdmittedScanRun(): ScanRunEntity = mutex.withLock {
        ensureStateLocked()
        val pipeline = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(pipeline.stage)
        require(stage.isScan) { "Pipeline is not admitting a core scan: $stage" }
        val expectedType = scanTypeFor(stage)
        pipeline.activeRunId?.let { activeRunId ->
            val existing = requireNotNull(database.scanRunDao().getById(activeRunId)) {
                "Persisted pipeline run is missing: $activeRunId"
            }
            check(existing.generation == pipeline.candidateGeneration && existing.scanType == expectedType) {
                "Persisted pipeline/run mismatch for stage=$stage"
            }
            return@withLock existing
        }

        val runDao = database.scanRunDao()
        val now = System.currentTimeMillis()
        val run = ScanRunEntity(
            scanId = UUID.randomUUID().toString(),
            generation = maxOf(now, (runDao.getMaxGeneration() ?: 0L) + 1L),
            scanType = expectedType,
            startedAt = now,
        )
        database.withTransaction {
            runDao.insert(run)
            check(dao.bindScanRun(stage.name, run.scanId, run.generation, now) == 1) {
                "Pipeline scan reservation lost for stage=$stage"
            }
        }
        run
    }

    suspend fun markActiveScanFailed(scanId: String?, error: String) = mutex.withLock {
        if (scanId == null) return@withLock
        val state = requireNotNull(dao.get())
        if (LibraryPipelineStage.fromPersisted(state.stage).isScan && state.activeRunId == scanId) {
            dao.markActiveScanFailed(scanId = scanId, error = error.take(160))
        }
    }

    private fun scanTypeFor(stage: LibraryPipelineStage): String = when (stage) {
        LibraryPipelineStage.INITIAL_SCAN -> ScanRunEntity.TYPE_FULL
        LibraryPipelineStage.INCREMENTAL_SCAN -> ScanRunEntity.TYPE_INCREMENTAL
        LibraryPipelineStage.REBUILD_SCAN -> ScanRunEntity.TYPE_RECONCILIATION
        else -> error("Not a scan stage: $stage")
    }

    /** Called only after the matching core scan committed canonical data. */
    suspend fun onCoreScanCompleted(
        scanId: String,
        generation: Long,
        changedCount: Int,
    ) = mutex.withLock {
        val state = requireNotNull(dao.get())
        val current = LibraryPipelineStage.fromPersisted(state.stage)
        if (state.activeRunId != scanId || state.candidateGeneration != generation || !current.isScan) {
            Log.w(TAG, "ignored stale core callback scanIdHash=${scanId.hashCode()} stage=$current")
            return@withLock
        }
        if (!advanceCoreRunToThumbnailsLocked(current, scanId, generation)) return@withLock
        Log.i(TAG, "core complete stage=$current changed=$changedCount generation=$generation")
        LibraryPipelineWorker.appendSuccessor(context)
    }

    private suspend fun advanceCoreRunToThumbnailsLocked(
        current: LibraryPipelineStage,
        scanId: String,
        generation: Long,
    ): Boolean {
        val next = when (current) {
            LibraryPipelineStage.INITIAL_SCAN -> LibraryPipelineStage.INITIAL_THUMBNAILS
            LibraryPipelineStage.INCREMENTAL_SCAN -> LibraryPipelineStage.INCREMENTAL_THUMBNAILS
            LibraryPipelineStage.REBUILD_SCAN -> LibraryPipelineStage.REBUILD_THUMBNAILS
            else -> return false
        }
        val taskDao = database.thumbnailTaskDao()
        return database.withTransaction {
            val run = database.scanRunDao().getById(scanId) ?: return@withTransaction false
            if (run.generation != generation || run.scanType != scanTypeFor(current)) {
                return@withTransaction false
            }
            if (run.coreScanState == CoreScanState.PUBLISHING.name) {
                if (database.scanRunDao().markSnapshotPublished(scanId, System.currentTimeMillis()) != 1) {
                    return@withTransaction false
                }
            } else if (run.coreScanState != CoreScanState.COMPLETED.name) {
                return@withTransaction false
            }
            if (dao.transitionActiveRun(current.name, next.name, scanId, generation) != 1) {
                return@withTransaction false
            }
            if (current == LibraryPipelineStage.INITIAL_SCAN || current == LibraryPipelineStage.REBUILD_SCAN) {
                taskDao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            } else {
                taskDao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            }
            // Scan-owned workers admit only QUEUED/RUNNING runs. Marking an empty run queued is
            // harmless and lets the same durable transition cover zero-media libraries.
            database.scanRunDao().markEnhancementQueued(scanId)
            updateThumbnailProgressLocked(scanId, generation, next)
            true
        }
    }

    suspend fun onThumbnailQueueChanged(scanId: String) = mutex.withLock {
        advanceThumbnailGateLocked(expectedScanId = scanId)
    }

    /**
     * A live UI lease keeps interactive ownership during scan seeding. Its completion still wakes the
     * current durable thumbnail gate, which identifies participation from the required source identity.
     */
    suspend fun onInteractiveThumbnailQueueChanged() = mutex.withLock {
        advanceThumbnailGateLocked(expectedScanId = null)
    }

    private suspend fun advanceThumbnailGateLocked(expectedScanId: String?) {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val scanId = state.activeRunId ?: return
        if (!stage.isThumbnail || (expectedScanId != null && expectedScanId != scanId)) return
        val generation = state.candidateGeneration
        val publish = when (stage) {
            LibraryPipelineStage.INITIAL_THUMBNAILS -> LibraryPipelineStage.INITIAL_PUBLISH
            LibraryPipelineStage.INCREMENTAL_THUMBNAILS -> LibraryPipelineStage.INCREMENTAL_PUBLISH
            LibraryPipelineStage.REBUILD_THUMBNAILS -> LibraryPipelineStage.REBUILD_PUBLISH
            else -> return
        }
        // The terminal snapshot and conditional stage transition share one database transaction.
        // A concurrent UI enqueue is therefore ordered entirely before the gate (and counted) or
        // after the durable Publish transition; it can never land in an unobserved count/transition gap.
        var repairedTargets = false
        val transitioned = database.withTransaction {
            val beforeRepair = database.thumbnailTaskDao().thumbnailGateSnapshot(scanId)
            val snapshot = updateThumbnailProgressLocked(scanId, generation, stage)
            repairedTargets = beforeRepair.repairable > 0
            snapshot.remaining == 0 &&
                dao.transitionActiveRun(stage.name, publish.name, scanId, generation) == 1
        }
        if (repairedTargets && expectedScanId == null && !transitioned) {
            // An interactive completion can discover and repair a scan-owned target while no
            // background consumer exists. Wake that independent lane exactly for this rare repair.
            ThumbnailWorker.appendBackgroundSuccessor(context)
        }
        // Never append an interactive WorkSpec merely because a background batch observed a live UI
        // participant. Producers/startup/final consumer checks exclusively drive the durable wake CAS.
        if (transitioned) LibraryPipelineWorker.appendSuccessor(context)
    }

    private suspend fun updateThumbnailProgressLocked(
        scanId: String,
        generation: Long,
        stage: LibraryPipelineStage,
    ): com.renyxin.localalbum.data.db.dao.ThumbnailGateSnapshot {
        val taskDao = database.thumbnailTaskDao()
        var gate = taskDao.thumbnailGateSnapshot(scanId)
        if (gate.repairable > 0) {
            // Missing or abnormally SUPERSEDED target identities are not terminal. Re-seeding is
            // idempotent, preserves every RUNNING lease, and gives each required identity an owner.
            if (stage == LibraryPipelineStage.INCREMENTAL_THUMBNAILS) {
                taskDao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            } else {
                taskDao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            }
            gate = taskDao.thumbnailGateSnapshot(scanId)
        }
        dao.updateThumbnailProgressExact(
            stage = stage.name,
            scanId = scanId,
            generation = generation,
            completed = gate.terminal,
            total = gate.total,
            failures = gate.failures,
        )
        return gate
    }

    /** Atomically switches the home projection, then admits analysis. */
    suspend fun publishCandidateAndAdvance() = mutex.withLock {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (!stage.isPublish) return@withLock
        val scanId = state.activeRunId ?: return@withLock
        val analysisStage = when (stage) {
            LibraryPipelineStage.INITIAL_PUBLISH -> LibraryPipelineStage.INITIAL_ANALYSIS
            LibraryPipelineStage.INCREMENTAL_PUBLISH -> LibraryPipelineStage.INCREMENTAL_ANALYSIS
            LibraryPipelineStage.REBUILD_PUBLISH -> LibraryPipelineStage.REBUILD_ANALYSIS
            else -> return@withLock
        }
        database.withTransaction {
            database.homeMediaSnapshotDao().replaceFromCanonical(state.candidateGeneration)
            check(
                dao.publishAndEnterAnalysis(
                    publishStage = stage.name,
                    analysisStage = analysisStage.name,
                    activeRunId = scanId,
                    generation = state.candidateGeneration,
                ) == 1,
            ) { "Pipeline publication transition lost: $stage -> $analysisStage" }
        }
        startOrFinishAnalysisLocked(scanId)
    }

    /** Seeds full analysis only for initial/rebuild; incremental tasks are expanded from this run's outbox. */
    suspend fun startOrFinishAnalysis(expectedScanId: String? = null) = mutex.withLock {
        startOrFinishAnalysisLocked(expectedScanId)
    }

    private suspend fun startOrFinishAnalysisLocked(expectedScanId: String? = null) {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (!stage.isAnalysis) return
        val scanId = state.activeRunId ?: return
        if (expectedScanId != null && expectedScanId != scanId) return
        val pipeline = analysisPipeline()
        val analysisDao = database.analysisTaskDao()
        val outboxDao = database.enhancementOutboxDao()

        if (stage == LibraryPipelineStage.INITIAL_ANALYSIS ||
            stage == LibraryPipelineStage.REBUILD_ANALYSIS
        ) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                pipeline.stageTaskScopes.values.forEach { scope ->
                    analysisDao.preparePipelineRunScope(
                        scope = scope,
                        scanId = scanId,
                        mediaType = MediaType.IMAGE.name,
                        priority = 0,
                        now = now,
                    )
                }
                // Full preparation covers every current image. Its per-change outbox is redundant and
                // must not be consumed later as a second incremental analysis pass.
                outboxDao.completeForScan(scanId, now)
            }
        } else if (outboxDao.countActiveForScan(scanId) > 0) {
            // Incremental analysis is admitted only after every row for this run has been expanded.
            // The handoff worker is itself stage-gated and cannot wake thumbnails or another run.
            EnhancementHandoffWorker.appendSuccessor(context)
            return
        }

        updateAnalysisProgressLocked(scanId, state.candidateGeneration, stage)
        if (analysisDao.countActiveForScan(scanId) > 0) {
            AnalysisWorker.appendSuccessor(context)
        } else {
            finishAnalysisIfIdleLocked(scanId)
        }
    }

    private suspend fun updateAnalysisProgressLocked(
        scanId: String,
        generation: Long,
        stage: LibraryPipelineStage,
    ) {
        val taskDao = database.analysisTaskDao()
        dao.updateProgress(
            stage = stage.name,
            scanId = scanId,
            generation = generation,
            completed = taskDao.countTerminalForScan(scanId),
            total = taskDao.countTotalForScan(scanId),
            failures = taskDao.countFailedForScan(scanId) +
                database.enhancementOutboxDao().countFailedForScan(scanId),
        )
    }

    suspend fun finishAnalysisIfIdle(expectedScanId: String) = mutex.withLock {
        finishAnalysisIfIdleLocked(expectedScanId)
    }

    private suspend fun finishAnalysisIfIdleLocked(expectedScanId: String) {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (!stage.isAnalysis) return
        val scanId = state.activeRunId ?: return
        if (scanId != expectedScanId) return
        val analysisDao = database.analysisTaskDao()
        val outboxDao = database.enhancementOutboxDao()
        if (outboxDao.countActiveForScan(scanId) > 0) {
            EnhancementHandoffWorker.appendSuccessor(context)
            return
        }
        if (analysisDao.countActiveForScan(scanId) > 0) return

        val failures = analysisDao.countFailedForScan(scanId) + outboxDao.countFailedForScan(scanId)
        val finished = database.withTransaction {
            database.scanRunDao().markEnhancementTerminal(
                scanId = scanId,
                state = if (failures > 0) {
                    com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED_WITH_FAILURES.name
                } else {
                    com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED.name
                },
                now = System.currentTimeMillis(),
            )
            dao.finishAnalysis(scanId, state.candidateGeneration, failures) == 1
        }
        if (finished) {
            val pipelineAdvanced = startQueuedWorkIfIdleLocked()
            when {
                pipelineAdvanced -> LibraryPipelineWorker.appendSuccessor(context)
                else -> {
                    val userTasks = admittedUserTasksLocked()
                    if (userTasks.hasWork) {
                        enqueueAdmittedUserTasks(userTasks, appendAnalysis = true)
                    }
                }
            }
        }
    }

    /** Explicit retry is admitted only when no automatic pipeline stage owns the resource lanes. */
    suspend fun retryFailedThumbnails(): FailedTaskRetryResult {
        var wakeRequired = false
        val result = mutex.withLock {
            val state = ensureStateLocked()
            if (!canAdmitUserTasks(state)) {
                return@withLock FailedTaskRetryResult(admitted = false)
            }
            val retried = database.thumbnailTaskDao().retryAllFailedInteractive(
                priority = ThumbnailSpec.PRIORITY_VISIBLE,
            )
            wakeRequired = retried.shouldWake
            FailedTaskRetryResult(retried = retried.accepted)
        }
        if (wakeRequired) {
            ThumbnailWorker.replayInteractiveWake(context, database.thumbnailTaskDao())
        }
        return result
    }

    /** Explicit retry is admitted only when no automatic pipeline stage owns the resource lanes. */
    suspend fun retryFailedAnalysis(): FailedTaskRetryResult {
        val result = mutex.withLock {
            val state = ensureStateLocked()
            if (!canAdmitUserTasks(state)) {
                return@withLock FailedTaskRetryResult(admitted = false)
            }
            val analysisDao = database.analysisTaskDao()
            val scopes = analysisPipeline().claimableTaskScopes
            if (scopes.isEmpty() || analysisDao.countFailedForScopes(scopes) == 0) {
                return@withLock FailedTaskRetryResult()
            }
            check(AnalysisResumePrefs.resumeUserWork(context)) {
                "Failed to persist explicit analysis retry admission"
            }
            FailedTaskRetryResult(
                retried = analysisDao.retryFailedAsUserForScopes(
                    scopes = scopes,
                    priority = AnalysisTaskEntity.PRIORITY_USER,
                ),
            )
        }
        if (result.retried > 0) AnalysisWorker.enqueue(context)
        return result
    }

    /**
     * Converts a bounded batch of failed handoff rows into independent user-owned work.
     * Unsupported analysis scopes remain FAILED and are never silently acknowledged.
     */
    suspend fun retryFailedEnhancementOutbox(
        limit: Int = OUTBOX_USER_RETRY_BATCH_SIZE,
    ): EnhancementOutboxRetryResult {
        require(limit > 0) { "limit must be positive" }
        val result = mutex.withLock {
            val state = ensureStateLocked()
            if (!canAdmitUserTasks(state)) {
                return@withLock EnhancementOutboxRetryResult(admitted = false)
            }

            val pipeline = analysisPipeline()
            val now = System.currentTimeMillis()
            database.withTransaction {
                val outboxDao = database.enhancementOutboxDao()
                val entries = outboxDao.getFailedForUserRetry(limit)
                if (entries.isEmpty()) {
                    return@withTransaction EnhancementOutboxRetryResult()
                }

                val analysisTasks = mutableListOf<AnalysisTaskEntity>()
                val thumbnailTasks = mutableListOf<ThumbnailTaskEntity>()
                val retryableOutboxIds = mutableListOf<Long>()
                val unsupportedOutboxIds = mutableListOf<Long>()

                entries.forEach { entry ->
                    val analysisRequested = entry.enqueueAnalysis &&
                        entry.mediaType == MediaType.IMAGE.name
                    val analysisScopes = if (analysisRequested) {
                        pipeline.taskScopesForOutbox(entry.pipelineScope)
                    } else {
                        emptyList()
                    }
                    val hasAnyRequestedWork = analysisRequested || entry.enqueueThumbnail

                    // A failed row with neither flag is not recoverable work. Keep it visible rather
                    // than acknowledging it as successfully expanded.
                    if (!hasAnyRequestedWork) {
                        unsupportedOutboxIds += entry.outboxId
                        return@forEach
                    }

                    // Treat an unsupported requested analysis as an all-or-nothing entry. This avoids
                    // creating a thumbnail that would be re-promoted on every retry while its
                    // analysis ownership can never be reconstructed under the current policy.
                    if (analysisRequested && analysisScopes.isEmpty()) {
                        unsupportedOutboxIds += entry.outboxId
                        return@forEach
                    }

                    analysisTasks += analysisScopes.map { taskScope ->
                        AnalysisTaskEntity(
                            filePath = entry.filePath,
                            sourceVersion = entry.sourceVersion,
                            pipelineScope = taskScope,
                            priority = AnalysisTaskEntity.PRIORITY_USER,
                            createdAt = now,
                            updatedAt = now,
                            scanId = null,
                        )
                    }
                    if (entry.enqueueThumbnail) {
                        thumbnailTasks += ThumbnailTaskEntity(
                            filePath = entry.filePath,
                            sizeClass = ThumbnailSpec.SIZE_GRID,
                            sourceVersion = entry.sourceVersion,
                            mediaType = entry.mediaType,
                            priority = ThumbnailSpec.PRIORITY_VISIBLE,
                            createdAt = now,
                            updatedAt = now,
                            scanId = null,
                        )
                    }
                    retryableOutboxIds += entry.outboxId
                }

                if (analysisTasks.isNotEmpty()) {
                    check(AnalysisResumePrefs.resumeUserWork(context)) {
                        "Failed to persist enhancement outbox analysis admission"
                    }
                    database.analysisTaskDao().enqueueAll(analysisTasks)
                }
                val thumbnailEnqueue = if (thumbnailTasks.isNotEmpty()) {
                    database.thumbnailTaskDao().enqueueInteractive(thumbnailTasks)
                } else {
                    com.renyxin.localalbum.data.db.dao.InteractiveThumbnailEnqueueResult(
                        accepted = 0,
                        shouldWake = false,
                    )
                }
                if (retryableOutboxIds.isNotEmpty()) {
                    check(
                        outboxDao.markRetriedForUser(retryableOutboxIds, now) ==
                            retryableOutboxIds.size,
                    ) { "Failed enhancement outbox changed during user retry" }
                }
                if (unsupportedOutboxIds.isNotEmpty()) {
                    check(
                        outboxDao.recordUserRetryFailure(
                            outboxIds = unsupportedOutboxIds,
                            error = "unsupported_analysis_pipeline_scope",
                            now = now,
                        ) == unsupportedOutboxIds.size,
                    ) { "Failed enhancement outbox failure state changed during user retry" }
                }

                EnhancementOutboxRetryResult(
                    outboxesRetried = retryableOutboxIds.size,
                    analysisTasks = analysisTasks.size,
                    thumbnailTasks = thumbnailEnqueue.accepted,
                    unsupportedOutboxes = unsupportedOutboxIds.size,
                    remainingFailed = outboxDao.countFailed(),
                    thumbnailWakeRequired = thumbnailEnqueue.shouldWake,
                )
            }
        }

        if (result.thumbnailWakeRequired) {
            ThumbnailWorker.replayInteractiveWake(context, database.thumbnailTaskDao())
        }
        if (result.analysisTasks > 0) AnalysisWorker.enqueue(context)
        return result
    }

    private fun canAdmitUserTasks(state: LibraryPipelineEntity): Boolean =
        LibraryPipelineStage.fromPersisted(state.stage) in USER_ANALYSIS_ADMISSION_STAGES &&
            !state.rebuildRequested

    companion object {
        private const val TAG = "LibraryPipeline"
        private val IDLE_ADMISSION_STAGES = setOf(
            LibraryPipelineStage.UNINITIALIZED,
            LibraryPipelineStage.READY,
            LibraryPipelineStage.NEEDS_REBUILD,
        )
        private const val OUTBOX_USER_RETRY_BATCH_SIZE = 100
        private val USER_ANALYSIS_ADMISSION_STAGES = setOf(
            LibraryPipelineStage.READY,
            LibraryPipelineStage.NEEDS_REBUILD,
            LibraryPipelineStage.FAILED,
        )
    }
}
