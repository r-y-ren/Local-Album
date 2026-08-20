package com.renyxin.localalbum.data.repo

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.ThumbnailRepairAdmissionFacts
import com.renyxin.localalbum.core.thumbnail.ThumbnailRepairPolicy
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

/**
 * WorkManager actions returned only after the corresponding Room transaction and coordinator mutex
 * have completed. Thumbnail and library continuations stay separate so a terminal gate selects the
 * publish pump instead of waking a QUIESCENT thumbnail lane.
 */
private data class PipelineWakePlan(
    val libraryWorker: Boolean = false,
    val libraryWake: Boolean = false,
    val thumbnailWorker: Boolean = false,
    val handoffWorker: Boolean = false,
    val analysisWorker: Boolean = false,
    val userTasks: UserTaskAdmission = UserTaskAdmission(),
    val appendUserAnalysis: Boolean = false,
) {
    operator fun plus(other: PipelineWakePlan): PipelineWakePlan = PipelineWakePlan(
        libraryWorker = libraryWorker || other.libraryWorker,
        libraryWake = libraryWake || other.libraryWake,
        thumbnailWorker = thumbnailWorker || other.thumbnailWorker,
        handoffWorker = handoffWorker || other.handoffWorker,
        analysisWorker = analysisWorker || other.analysisWorker,
        userTasks = UserTaskAdmission(
            analysis = userTasks.analysis || other.userTasks.analysis,
            thumbnails = userTasks.thumbnails || other.userTasks.thumbnails,
        ),
        appendUserAnalysis = appendUserAnalysis || other.appendUserAnalysis,
    )
}

private data class ThumbnailRunBinding(
    val scanId: String,
    val generation: Long,
    val stage: LibraryPipelineStage,
)

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
    /** Plans are accumulated only while [mutex] is held and dispatched after Room work commits. */
    private var pendingWakePlan = PipelineWakePlan()

    /** UI admission derives from the same persisted rule used by every explicit retry mutation. */
    val userTaskRetryAllowed: Flow<Boolean> = dao.observe()
        .map { state -> state?.let(::canAdmitUserTasks) ?: false }
        .distinctUntilChanged()

    suspend fun ensureState(): LibraryPipelineEntity = withLockAndDispatch {
        ensureStateLocked()
    }

    private suspend fun <T> withLockAndDispatch(
        planTransform: (PipelineWakePlan) -> PipelineWakePlan = { it },
        block: suspend () -> T,
    ): T {
        var plan = PipelineWakePlan()
        return try {
            mutex.withLock {
                try {
                    block()
                } finally {
                    // A transition may have committed before a later invariant fails. Always drain its
                    // durable continuation so an exception cannot strand the new persisted stage.
                    plan = takeWakePlanLocked()
                }
            }
        } finally {
            dispatchWakePlan(planTransform(plan))
        }
    }

    private fun recordWakePlanLocked(plan: PipelineWakePlan) {
        pendingWakePlan += plan
    }

    private fun takeWakePlanLocked(): PipelineWakePlan {
        val plan = pendingWakePlan
        pendingWakePlan = PipelineWakePlan()
        return plan
    }

    /** WorkManager is touched only after the mutex-protected Room section has returned. */
    private suspend fun dispatchWakePlan(plan: PipelineWakePlan) {
        if (plan.libraryWorker) {
            LibraryPipelineWorker.appendSuccessor(context)
        } else if (plan.libraryWake) {
            LibraryPipelineWorker.enqueue(context)
        }
        if (plan.thumbnailWorker) ThumbnailWorker.appendBackgroundSuccessor(context)
        if (plan.handoffWorker) EnhancementHandoffWorker.appendSuccessor(context)
        if (plan.analysisWorker) AnalysisWorker.appendSuccessor(context)
        if (plan.userTasks.hasWork) {
            enqueueAdmittedUserTasks(
                admission = plan.userTasks,
                appendAnalysis = plan.appendUserAnalysis,
            )
        }
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
     * Coordinator-owned repair barrier for the v33 cache-identity cutover. Run binding and current-grid
     * seed commit atomically; the exact progress-only projection and conditional publish transition then
     * converge in separate transactions. A later wake can rediscover the deterministic run after a crash.
     */
    private suspend fun ensureThumbnailRepairLocked(state: LibraryPipelineEntity): Boolean {
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (stage !in IDLE_ADMISSION_STAGES) return false
        // The durable media journal must be known and empty before repairing a published baseline.
        // Lightweight test-only indexers may omit that DAO; unknown is fail-closed so production
        // admission can never silently interpret a missing dependency as an empty changed-set.
        if (indexer.hasOutstandingMediaChangesOrNull() != false) return false
        val runDao = database.scanRunDao()
        val taskDao = database.thumbnailTaskDao()
        val visibleCount = taskDao.countVisibleGridMedia()
        val gapCount = taskDao.countCurrentGridGaps()
        val facts = ThumbnailRepairAdmissionFacts(
            hasPublishedBaseline = state.hasPublishedBaseline,
            visibleGridMediaCount = visibleCount,
            eligibleGapCount = gapCount,
            idleStage = stage == LibraryPipelineStage.READY ||
                stage == LibraryPipelineStage.NEEDS_REBUILD,
            hasActiveRun = state.activeRunId != null,
            rebuildRequested = state.rebuildRequested,
        )
        if (!ThumbnailRepairPolicy.shouldAdmit(facts)) return false

        val now = System.currentTimeMillis()
        val binding: ThumbnailRunBinding? = database.withTransaction {
            // Re-evaluate every mutable admission fact at the commit boundary. A media notification
            // inserted after the optimistic checks above is ordered before this transaction (and
            // blocks repair) or after the complete run/pipeline/task commit (and queues the next scan).
            if (indexer.hasOutstandingMediaChangesOrNull() != false) return@withTransaction null
            val current = requireNotNull(dao.get())
            val currentStage = LibraryPipelineStage.fromPersisted(current.stage)
            val currentFacts = ThumbnailRepairAdmissionFacts(
                hasPublishedBaseline = current.hasPublishedBaseline,
                visibleGridMediaCount = taskDao.countVisibleGridMedia(),
                eligibleGapCount = taskDao.countCurrentGridGaps(),
                idleStage = currentStage == LibraryPipelineStage.READY ||
                    currentStage == LibraryPipelineStage.NEEDS_REBUILD,
                hasActiveRun = current.activeRunId != null,
                rebuildRequested = current.rebuildRequested,
            )
            if (!ThumbnailRepairPolicy.shouldAdmit(currentFacts)) return@withTransaction null

            val repairId = ThumbnailRepairPolicy.repairRunId(
                publishedGeneration = current.publishedGeneration,
                formatVersion = ThumbnailIdentity.CURRENT_FORMAT_VERSION,
            )
            val existing = runDao.getById(repairId)
            check(existing == null || existing.scanType == ScanRunEntity.TYPE_THUMBNAIL_REPAIR) {
                "Deterministic thumbnail repair id is owned by another run type"
            }
            // A permanent failure is stable while that exact current identity remains FAILED.
            // Explicit retry changes it to PENDING, which authorizes this same deterministic run to
            // reopen and republish the repaired home projection without creating a second run row.
            if (existing?.enhancementState ==
                com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED_WITH_FAILURES.name
            ) {
                val previousGate = taskDao.thumbnailGateSnapshot(repairId)
                if (previousGate.failures > 0 || previousGate.interactiveActive > 0) {
                    return@withTransaction null
                }
            }

            if (existing == null) {
                runDao.insertIfAbsent(
                    ScanRunEntity(
                        scanId = repairId,
                        generation = maxOf(
                            now,
                            current.publishedGeneration,
                            (runDao.getMaxGeneration() ?: 0L) + 1L,
                        ),
                        scanType = ScanRunEntity.TYPE_THUMBNAIL_REPAIR,
                        status = ScanRunEntity.STATUS_COMPLETED,
                        mediaStoreCompleted = true,
                        fileSystemCompleted = true,
                        startedAt = now,
                        completedAt = now,
                        indexAvailability = com.renyxin.localalbum.data.db.entity.IndexAvailability.PUBLISHED.name,
                        coreScanState = CoreScanState.COMPLETED.name,
                        enhancementState = com.renyxin.localalbum.data.db.entity.EnhancementState.QUEUED.name,
                        firstBatchCommittedAt = now,
                        indexAvailableAt = now,
                        coreCompletedAt = now,
                    ),
                )
            }
            val persisted = requireNotNull(runDao.getById(repairId))
            if (persisted.enhancementState in setOf(
                    com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED.name,
                    com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED_WITH_FAILURES.name,
                    com.renyxin.localalbum.data.db.entity.EnhancementState.CANCELLED.name,
                )
            ) {
                check(runDao.requeueThumbnailRepair(repairId) == 1) {
                    "Thumbnail repair run could not be reopened"
                }
            }
            val targetStage = if (current.rebuildRequired) {
                LibraryPipelineStage.REBUILD_THUMBNAILS
            } else {
                LibraryPipelineStage.INCREMENTAL_THUMBNAILS
            }
            check(
                dao.transition(
                    from = setOf(
                        LibraryPipelineStage.READY,
                        LibraryPipelineStage.NEEDS_REBUILD,
                    ),
                    to = targetStage,
                    activeRunId = repairId,
                    candidateGeneration = persisted.generation,
                    now = now,
                ),
            ) { "thumbnail repair admission lost" }
            taskDao.seedAllGrid(
                scanId = repairId,
                priority = ThumbnailSpec.PRIORITY_BACKGROUND,
                now = now,
                repairMissingDone = true,
            )
            ThumbnailRunBinding(repairId, persisted.generation, targetStage)
        }
        if (binding == null) return false
        recordWakePlanLocked(convergeSeededThumbnailGateLocked(binding))
        return true
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

    private suspend fun preparePipelinePassLocked(): Boolean {
        ensureStateLocked()
        // A process can die after the final task commit but before its Worker callback advances the
        // gate. Exact convergence commits progress first and then evaluates the transition in a separate
        // transaction: a terminal queue is intentionally QUIESCENT and cannot wake itself.
        recordWakePlanLocked(advanceThumbnailGateLocked(expectedScanId = null))
        return startQueuedWorkIfIdleLocked()
    }

    /**
     * One coordinator-owned preparation pass for the durable library pump. The current pump consumes
     * any library continuation itself; only a non-library lane is dispatched after the lock is released.
     */
    suspend fun prepareLibraryWorkerPass(): LibraryPipelineEntity = withLockAndDispatch(
        planTransform = { it.copy(libraryWorker = false, libraryWake = false) },
    ) {
        preparePipelinePassLocked()
        requireNotNull(dao.get())
    }

    /** Startup/user wake-up resumes the one Worker admitted by durable state. */
    suspend fun wake() = withLockAndDispatch {
        val pipelineAdvanced = preparePipelinePassLocked()
        val userAdmission = if (pipelineAdvanced) UserTaskAdmission() else admittedUserTasksLocked()
        if (userAdmission.hasWork) {
            recordWakePlanLocked(PipelineWakePlan(userTasks = userAdmission))
        }
        val stage = LibraryPipelineStage.fromPersisted(requireNotNull(dao.get()).stage)
        val plan = pendingWakePlan
        if (!userAdmission.hasWork && !stage.isThumbnail &&
            !plan.libraryWorker && !plan.libraryWake && !plan.thumbnailWorker &&
            !plan.handoffWorker && !plan.analysisWorker
        ) {
            recordWakePlanLocked(PipelineWakePlan(libraryWake = true))
        }
    }

    /**
     * MediaStore events are already durable. While another stage is busy they deliberately do not
     * enqueue any Worker; the current analysis/thumbnail stage completes and consumes the journal next.
     */
    suspend fun onMediaChangesRecorded() {
        withLockAndDispatch {
            val state = ensureStateLocked()
            val stage = LibraryPipelineStage.fromPersisted(state.stage)
            if (stage in IDLE_ADMISSION_STAGES) startQueuedWorkIfIdleLocked()
        }
    }

    /** Normal scan action: explicitly authorizes the first traversal, otherwise only drains journals. */
    suspend fun requestUserScan() {
        withLockAndDispatch {
            val state = ensureStateLocked()
            val stage = LibraryPipelineStage.fromPersisted(state.stage)
            if (
                !state.hasPublishedBaseline && state.activeRunId == null &&
                stage in setOf(
                    LibraryPipelineStage.UNINITIALIZED,
                    LibraryPipelineStage.INITIAL_SCAN,
                )
            ) {
                dao.requestRebuild("user_requested_initial_scan")
            }
        }
        wake()
    }

    suspend fun requestExplicitRebuild(reason: String = "user_requested") {
        val advanced = withLockAndDispatch {
            ensureStateLocked()
            // This marker is the durable user intent for both the first traversal and later rebuilds.
            // It remains set through scan-stage admission and is consumed only when a run is bound.
            dao.requestRebuild(reason.take(80))
            startQueuedWorkIfIdleLocked()
        }
        if (!advanced) LibraryPipelineWorker.enqueue(context)
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
    suspend fun markScanScopeChanged(reason: String) = withLockAndDispatch {
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
    suspend fun startQueuedWorkIfIdle(): Boolean = withLockAndDispatch {
        val pipelineAdvanced = startQueuedWorkIfIdleLocked()
        if (!pipelineAdvanced) {
            val userTasks = admittedUserTasksLocked()
            if (userTasks.hasWork) recordWakePlanLocked(PipelineWakePlan(userTasks = userTasks))
        }
        pipelineAdvanced
    }

    private suspend fun startQueuedWorkIfIdleLocked(): Boolean {
        var state = requireNotNull(dao.get())
        var stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (stage !in IDLE_ADMISSION_STAGES) return false

        // Merely creating the control row or configuring roots is not authority to traverse them.
        // A durable explicit request admits the first scan/rebuild and remains set until run binding.
        if (state.rebuildRequested) {
            val advanced = dao.startRequestedRebuild() == 1
            if (advanced) recordWakePlanLocked(PipelineWakePlan(libraryWorker = true))
            return advanced
        }

        // Reconciliation hints are advisories, precise identities are durable work. Both must be
        // handled before the repair barrier so a cold-start gap cannot outrun a queued media change.
        if (indexer.consumeReconciliationHintAsAdvisory()) {
            dao.requireRebuild("ambiguous_media_store_change")
        }
        if (indexer.hasOutstandingMediaChanges()) {
            val advanced = dao.transition(
                from = setOf(
                    LibraryPipelineStage.READY,
                    LibraryPipelineStage.NEEDS_REBUILD,
                ),
                to = LibraryPipelineStage.INCREMENTAL_SCAN,
            )
            if (advanced) recordWakePlanLocked(PipelineWakePlan(libraryWorker = true))
            return advanced
        }

        state = requireNotNull(dao.get())
        stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (stage !in IDLE_ADMISSION_STAGES) return false
        return ensureThumbnailRepairLocked(state)
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
    suspend fun getOrCreateAdmittedScanRun(): ScanRunEntity = withLockAndDispatch {
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
            return@withLockAndDispatch existing
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
    ) = withLockAndDispatch {
        val state = requireNotNull(dao.get())
        val current = LibraryPipelineStage.fromPersisted(state.stage)
        if (state.activeRunId != scanId || state.candidateGeneration != generation || !current.isScan) {
            Log.w(TAG, "ignored stale core callback scanIdHash=${scanId.hashCode()} stage=$current")
        } else if (advanceCoreRunToThumbnailsLocked(current, scanId, generation)) {
            Log.i(TAG, "core complete stage=$current changed=$changedCount generation=$generation")
        } else {
            Unit
        }
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
        val binding: ThumbnailRunBinding? = database.withTransaction {
            val run = database.scanRunDao().getById(scanId) ?: return@withTransaction null
            if (run.generation != generation || run.scanType != scanTypeFor(current)) {
                return@withTransaction null
            }
            if (run.coreScanState == CoreScanState.PUBLISHING.name) {
                if (database.scanRunDao().markSnapshotPublished(scanId, System.currentTimeMillis()) != 1) {
                    return@withTransaction null
                }
            } else if (run.coreScanState != CoreScanState.COMPLETED.name) {
                return@withTransaction null
            }
            if (dao.transitionActiveRun(current.name, next.name, scanId, generation) != 1) {
                return@withTransaction null
            }
            if (current == LibraryPipelineStage.INITIAL_SCAN || current == LibraryPipelineStage.REBUILD_SCAN) {
                taskDao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            } else {
                taskDao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            }
            // Scan-owned workers admit only QUEUED/RUNNING runs. Marking an empty run queued is
            // harmless and lets the same durable transition cover zero-media libraries.
            database.scanRunDao().markEnhancementQueued(scanId)
            ThumbnailRunBinding(scanId, generation, next)
        }
        if (binding == null) return false
        recordWakePlanLocked(convergeSeededThumbnailGateLocked(binding))
        return true
    }

    /** Persists only the current thumbnail gate; it never transitions the durable pipeline stage. */
    suspend fun onThumbnailProgressChanged(scanId: String) = withLockAndDispatch {
        persistThumbnailProgressLocked(expectedScanId = scanId)
    }

    suspend fun onThumbnailQueueChanged(scanId: String) {
        convergeCurrentThumbnailGate(expectedScanId = scanId)
    }

    /** Library pump/process recovery continuation; stale and non-thumbnail stages are idempotent no-ops. */
    suspend fun convergeCurrentThumbnailGate(expectedScanId: String? = null) = withLockAndDispatch {
        recordWakePlanLocked(advanceThumbnailGateLocked(expectedScanId))
    }

    /**
     * A live UI lease keeps interactive ownership during scan seeding. Its completion still wakes the
     * current durable thumbnail gate, which identifies participation from the required source identity.
     */
    suspend fun onInteractiveThumbnailQueueChanged() {
        withLockAndDispatch {
            // An explicit retry may have repaired the last FAILED Grid identity while the pipeline
            // was idle. Re-admit the same deterministic repair row after consuming any journal hint;
            // the following exact convergence advances immediately when all targets are terminal.
            ensureStateLocked()
            recordWakePlanLocked(advanceThumbnailGateLocked(expectedScanId = null))
            if (LibraryPipelineStage.fromPersisted(requireNotNull(dao.get()).stage) in IDLE_ADMISSION_STAGES) {
                startQueuedWorkIfIdleLocked()
            }
        }
    }

    private suspend fun advanceThumbnailGateLocked(expectedScanId: String?): PipelineWakePlan {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val scanId = state.activeRunId ?: return PipelineWakePlan()
        if (!stage.isThumbnail || (expectedScanId != null && expectedScanId != scanId)) {
            return PipelineWakePlan()
        }
        return convergeThumbnailGateLocked(
            scanId = scanId,
            generation = state.candidateGeneration,
            stage = stage,
        )
    }

    /**
     * Single post-seed/post-queue convergence path. It intentionally commits the progress-only
     * projection before the conditional thumbnail -> publish transition. A terminal gate therefore
     * never depends on an automatic lane consumer, while a non-terminal gate is the only case that
     * asks the automatic lane to deliver a thumbnail worker.
     */
    private suspend fun convergeSeededThumbnailGateLocked(
        binding: ThumbnailRunBinding,
    ): PipelineWakePlan = convergeThumbnailGateLocked(
        scanId = binding.scanId,
        generation = binding.generation,
        stage = binding.stage,
    )

    private suspend fun convergeThumbnailGateLocked(
        scanId: String,
        generation: Long,
        stage: LibraryPipelineStage,
    ): PipelineWakePlan {
        require(stage.isThumbnail) { "Not a thumbnail stage: $stage" }
        val current = requireNotNull(dao.get())
        if (current.activeRunId != scanId || current.candidateGeneration != generation ||
            LibraryPipelineStage.fromPersisted(current.stage) != stage
        ) return PipelineWakePlan()

        val taskDao = database.thumbnailTaskDao()
        val initialGate = taskDao.thumbnailGateSnapshot(scanId)
        if (initialGate.repairable > 0) {
            // SUPERSEDED/missing identities are not terminal. Repair the target set first, then take
            // a fresh exact snapshot for the progress-only commit below.
            database.withTransaction {
                repairThumbnailGateLocked(scanId, stage, initialGate)
            }
        }

        // This transaction contains only the exact gate read and its progress projection. It must
        // commit before the transition transaction, so observers can see the first DONE as non-zero.
        val gate = database.withTransaction {
            val exact = taskDao.thumbnailGateSnapshot(scanId)
            check(
                dao.updateThumbnailProgressExact(
                    stage = stage.name,
                    scanId = scanId,
                    generation = generation,
                    completed = exact.terminal,
                    total = exact.total,
                    failures = exact.failures,
                ) == 1,
            ) { "Thumbnail progress projection lost for scanId=$scanId" }
            exact
        }

        val publish = when (stage) {
            LibraryPipelineStage.INITIAL_THUMBNAILS -> LibraryPipelineStage.INITIAL_PUBLISH
            LibraryPipelineStage.INCREMENTAL_THUMBNAILS -> LibraryPipelineStage.INCREMENTAL_PUBLISH
            LibraryPipelineStage.REBUILD_THUMBNAILS -> LibraryPipelineStage.REBUILD_PUBLISH
            else -> error("Not a thumbnail stage: $stage")
        }
        if (gate.remaining == 0) {
            // Re-read inside the CAS transaction. A producer may have seeded/reopened a target after
            // the progress-only commit; that target must block publication and be delivered normally.
            var transitionGate = gate
            val transitioned = database.withTransaction {
                transitionGate = taskDao.thumbnailGateSnapshot(scanId)
                if (transitionGate.remaining == 0) {
                    dao.transitionActiveRun(stage.name, publish.name, scanId, generation) == 1
                } else {
                    check(
                        dao.updateThumbnailProgressExact(
                            stage = stage.name,
                            scanId = scanId,
                            generation = generation,
                            completed = transitionGate.terminal,
                            total = transitionGate.total,
                            failures = transitionGate.failures,
                        ) == 1,
                    ) { "Thumbnail progress projection lost after gate reopened for scanId=$scanId" }
                    false
                }
            }
            if (transitioned) {
                // The library pump is the publish continuation. It is recorded while the mutex is held,
                // but dispatched only after every Room transaction above has committed.
                return PipelineWakePlan(libraryWorker = true)
            }
            if (transitionGate.remaining == 0) {
                val currentStage = LibraryPipelineStage.fromPersisted(requireNotNull(dao.get()).stage)
                if (currentStage != stage) return PipelineWakePlan()
            }
        }

        // Do not force a QUIESCENT lane to run. Recompute from durable automatic facts and only kick
        // when this exact gate has claimable/delayed automatic work; an interactive lease will wake
        // its own completion path instead.
        val lane = taskDao.recomputeLaneLevel(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID,
        )
        return PipelineWakePlan(
            thumbnailWorker = lane.deliveryState ==
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING ||
                lane.deliveryState ==
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_DELAYED,
        )
    }

    private suspend fun persistThumbnailProgressLocked(
        expectedScanId: String?,
    ): com.renyxin.localalbum.data.db.dao.ThumbnailGateSnapshot? {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        val scanId = state.activeRunId ?: return null
        if (!stage.isThumbnail || (expectedScanId != null && expectedScanId != scanId)) return null
        val gate = database.thumbnailTaskDao().thumbnailGateSnapshot(scanId)
        dao.updateThumbnailProgressExact(
            stage = stage.name,
            scanId = scanId,
            generation = state.candidateGeneration,
            completed = gate.terminal,
            total = gate.total,
            failures = gate.failures,
        )
        return gate
    }

    private suspend fun repairThumbnailGateLocked(
        scanId: String,
        stage: LibraryPipelineStage,
        initial: com.renyxin.localalbum.data.db.dao.ThumbnailGateSnapshot? = null,
    ): com.renyxin.localalbum.data.db.dao.ThumbnailGateSnapshot {
        val taskDao = database.thumbnailTaskDao()
        var gate = initial ?: taskDao.thumbnailGateSnapshot(scanId)
        if (gate.repairable > 0) {
            // Missing or abnormally SUPERSEDED target identities are not terminal. Re-seeding is
            // idempotent, preserves every RUNNING lease, and gives each required identity an owner.
            // A thumbnail-only repair has no enhancement outbox; it must always derive its target
            // set from the current published media baseline, even when it uses the incremental
            // thumbnail stage for ordinary pipeline admission.
            val thumbnailOnlyRepair = database.scanRunDao().getById(scanId)?.scanType ==
                ScanRunEntity.TYPE_THUMBNAIL_REPAIR
            if (thumbnailOnlyRepair || stage != LibraryPipelineStage.INCREMENTAL_THUMBNAILS) {
                taskDao.seedAllGrid(
                    scanId = scanId,
                    priority = ThumbnailSpec.PRIORITY_BACKGROUND,
                    repairMissingDone = thumbnailOnlyRepair,
                )
            } else {
                taskDao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND)
            }
            gate = taskDao.thumbnailGateSnapshot(scanId)
        }
        return gate
    }

    /** Atomically switches the home projection, then admits analysis or finishes a repair-only run. */
    suspend fun publishCandidateAndAdvance() = withLockAndDispatch {
        val state = requireNotNull(dao.get())
        val stage = LibraryPipelineStage.fromPersisted(state.stage)
        if (!stage.isPublish) return@withLockAndDispatch
        val scanId = state.activeRunId ?: return@withLockAndDispatch
        val run = requireNotNull(database.scanRunDao().getById(scanId))
        val analysisStage = when (stage) {
            LibraryPipelineStage.INITIAL_PUBLISH -> LibraryPipelineStage.INITIAL_ANALYSIS
            LibraryPipelineStage.INCREMENTAL_PUBLISH -> LibraryPipelineStage.INCREMENTAL_ANALYSIS
            LibraryPipelineStage.REBUILD_PUBLISH -> LibraryPipelineStage.REBUILD_ANALYSIS
            else -> return@withLockAndDispatch
        }
        val thumbnailOnlyRepair = run.scanType == ScanRunEntity.TYPE_THUMBNAIL_REPAIR
        val failures = database.thumbnailTaskDao().thumbnailGateSnapshot(scanId).failures
        val publicationGeneration = if (thumbnailOnlyRepair) {
            state.publishedGeneration
        } else {
            state.candidateGeneration
        }
        database.withTransaction {
            database.homeMediaSnapshotDao().replaceFromCanonical(publicationGeneration)
            if (thumbnailOnlyRepair) {
                check(
                    database.scanRunDao().markThumbnailRepairTerminal(
                        scanId = scanId,
                        generation = state.candidateGeneration,
                        state = if (failures > 0) {
                            com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED_WITH_FAILURES.name
                        } else {
                            com.renyxin.localalbum.data.db.entity.EnhancementState.COMPLETED.name
                        },
                        now = System.currentTimeMillis(),
                    ),
                ) { "Thumbnail repair run terminal transition lost" }
                check(
                    dao.finishThumbnailRepair(
                        scanId = scanId,
                        generation = state.candidateGeneration,
                        failures = failures,
                    ) == 1,
                ) { "Thumbnail repair publication transition lost: $stage" }
            } else {
                check(
                    dao.publishAndEnterAnalysis(
                        publishStage = stage.name,
                        analysisStage = analysisStage.name,
                        activeRunId = scanId,
                        generation = state.candidateGeneration,
                    ) == 1,
                ) { "Pipeline publication transition lost: $stage -> $analysisStage" }
            }
        }
        if (thumbnailOnlyRepair) {
            startQueuedWorkIfIdleLocked()
        } else {
            startOrFinishAnalysisLocked(scanId)
        }
    }

    /** Seeds full analysis only for initial/rebuild; incremental tasks are expanded from this run's outbox. */
    suspend fun startOrFinishAnalysis(expectedScanId: String? = null) = withLockAndDispatch {
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
            recordWakePlanLocked(PipelineWakePlan(handoffWorker = true))
            return
        }

        updateAnalysisProgressLocked(scanId, state.candidateGeneration, stage)
        if (analysisDao.countActiveForScan(scanId) > 0) {
            recordWakePlanLocked(PipelineWakePlan(analysisWorker = true))
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

    suspend fun finishAnalysisIfIdle(expectedScanId: String) = withLockAndDispatch {
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
            recordWakePlanLocked(PipelineWakePlan(handoffWorker = true))
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
            if (!pipelineAdvanced) {
                val userTasks = admittedUserTasksLocked()
                if (userTasks.hasWork) {
                    recordWakePlanLocked(
                        PipelineWakePlan(
                            userTasks = userTasks,
                            appendUserAnalysis = true,
                        ),
                    )
                }
            }
        }
    }

    /** Explicit retry is admitted only when no automatic pipeline stage owns the resource lanes. */
    suspend fun retryFailedThumbnails(): FailedTaskRetryResult = withLockAndDispatch {
        val state = ensureStateLocked()
        if (!canAdmitUserTasks(state)) {
            return@withLockAndDispatch FailedTaskRetryResult(admitted = false)
        }
        val retried = database.thumbnailTaskDao().retryAllFailedInteractive(
            priority = ThumbnailSpec.PRIORITY_VISIBLE,
        )
        if (retried.shouldWake) {
            recordWakePlanLocked(
                PipelineWakePlan(
                    userTasks = UserTaskAdmission(thumbnails = true),
                ),
            )
        }
        FailedTaskRetryResult(retried = retried.accepted)
    }

    /** Explicit retry is admitted only when no automatic pipeline stage owns the resource lanes. */
    suspend fun retryFailedAnalysis(): FailedTaskRetryResult = withLockAndDispatch {
        val state = ensureStateLocked()
        if (!canAdmitUserTasks(state)) {
            return@withLockAndDispatch FailedTaskRetryResult(admitted = false)
        }
        val analysisDao = database.analysisTaskDao()
        val scopes = analysisPipeline().claimableTaskScopes
        if (scopes.isEmpty() || analysisDao.countFailedForScopes(scopes) == 0) {
            return@withLockAndDispatch FailedTaskRetryResult()
        }
        check(AnalysisResumePrefs.resumeUserWork(context)) {
            "Failed to persist explicit analysis retry admission"
        }
        val retried = analysisDao.retryFailedAsUserForScopes(
            scopes = scopes,
            priority = AnalysisTaskEntity.PRIORITY_USER,
        )
        if (retried > 0) {
            recordWakePlanLocked(
                PipelineWakePlan(
                    userTasks = UserTaskAdmission(analysis = true),
                ),
            )
        }
        FailedTaskRetryResult(retried = retried)
    }

    /**
     * Converts a bounded batch of failed handoff rows into independent user-owned work.
     * Unsupported analysis scopes remain FAILED and are never silently acknowledged.
     */
    suspend fun retryFailedEnhancementOutbox(
        limit: Int = OUTBOX_USER_RETRY_BATCH_SIZE,
    ): EnhancementOutboxRetryResult {
        require(limit > 0) { "limit must be positive" }
        return withLockAndDispatch {
            val state = ensureStateLocked()
            if (!canAdmitUserTasks(state)) {
                return@withLockAndDispatch EnhancementOutboxRetryResult(admitted = false)
            }

            val pipeline = analysisPipeline()
            val now = System.currentTimeMillis()
            val result = database.withTransaction {
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
            if (result.thumbnailWakeRequired || result.analysisTasks > 0) {
                recordWakePlanLocked(
                    PipelineWakePlan(
                        userTasks = UserTaskAdmission(
                            analysis = result.analysisTasks > 0,
                            thumbnails = result.thumbnailWakeRequired,
                        ),
                    ),
                )
            }
            result
        }
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
