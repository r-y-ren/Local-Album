package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import java.util.UUID

/**
 * Bounded post-core seeder. It is the only automatic bridge from committed scan changes to
 * analysis/thumbnail queues, and therefore the durable enhancement scheduling barrier.
 */
class EnhancementHandoffWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        val database = container.database
        val pipeline = container.pluginAnalysisPipeline
        val outboxDao = database.enhancementOutboxDao()
        val pipelineState = database.libraryPipelineDao().get() ?: return Result.success()
        val pipelineStage = com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
            .fromPersisted(pipelineState.stage)
        if (!pipelineStage.isAnalysis) return Result.success()
        val admittedScanId = pipelineState.activeRunId ?: return Result.success()
        var seededAnalysis = false
        var interruptedLeasesRecovered = false

        return try {
            if (container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) {
                return Result.retry()
            }
            var batches = 0
            while (batches < MAX_BATCHES_PER_RUN) {
                if (isStopped || container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) break
                val outcome = EnhancementResourceGate.tryWithAutomaticEnhancement {
                    val now = System.currentTimeMillis()
                    if (!interruptedLeasesRecovered) {
                        // Owning the automatic lane proves there is no live handoff consumer in
                        // this process. RUNNING rows can only be a killed process's leases.
                        outboxDao.recoverInterruptedLeases(now)
                        interruptedLeasesRecovered = true
                    }
                    val leaseToken = UUID.randomUUID().toString()
                    val entries = outboxDao.claimBatchForScan(
                        scanId = admittedScanId,
                        now = now,
                        limit = BATCH_SIZE,
                        leaseToken = leaseToken,
                        leaseDurationMs = LEASE_DURATION_MS,
                    )
                    if (entries.isEmpty()) return@tryWithAutomaticEnhancement false

                    try {
                        val seeded = seedClaimedEntries(
                            database = database,
                            pipeline = pipeline,
                            entries = entries,
                            leaseToken = leaseToken,
                            now = now,
                            includeThumbnails = false,
                        )
                        seededAnalysis = seededAnalysis || seeded.analysis
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            outboxDao.releaseLease(leaseToken, System.currentTimeMillis())
                        }
                        throw cancelled
                    } catch (error: Throwable) {
                        val failedAt = System.currentTimeMillis()
                        outboxDao.markFailed(
                            ids = entries.map { it.outboxId },
                            leaseToken = leaseToken,
                            error = error.javaClass.simpleName.take(MAX_ERROR_LENGTH),
                            nextRetryAt = failedAt + retryDelayMs(entries.maxOfOrNull { it.attemptCount } ?: 1),
                            maxAttempts = MAX_ATTEMPTS,
                            now = failedAt,
                        )
                        throw error
                    }
                    true
                }
                if (outcome == null || outcome == false) break
                batches++
            }

            val queueState = EnhancementResourceGate.tryWithAutomaticEnhancement {
                val now = System.currentTimeMillis()
                val active = outboxDao.countActiveForScan(admittedScanId) > 0
                val claimable = outboxDao.hasClaimableForScan(admittedScanId, now)
                active to claimable
            } ?: return Result.retry()
            val (hasActiveEntries, hasClaimableEntries) = queueState
            val activeAnalysisTasks = database.analysisTaskDao().countActiveForScan(admittedScanId)
            val decision = continuationDecision(hasActiveEntries, hasClaimableEntries)
            Log.i(
                TAG,
                "handoff diagnostic: scanIdHash=${admittedScanId.hashCode()} batches=$batches " +
                    "seededAnalysis=$seededAnalysis activeOutbox=$hasActiveEntries " +
                    "claimableOutbox=$hasClaimableEntries activeAnalysis=$activeAnalysisTasks " +
                    "decision=$decision",
            )

            when (decision) {
                ContinuationDecision.ENQUEUE_SUCCESSOR -> {
                    appendSuccessor(applicationContext)
                    Result.success()
                }
                ContinuationDecision.RETRY_BACKOFF -> Result.retry()
                ContinuationDecision.COMPLETE -> {
                    container.libraryPipelineCoordinator.startOrFinishAnalysis(admittedScanId)
                    Result.success()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "增强 outbox 交接失败", error)
            Result.retry()
        }
    }

    private suspend fun finalizeEmptyScans(
        database: com.renyxin.localalbum.data.db.AppDatabase,
        scanIds: Set<String>,
    ) = settleEnhancementScans(database, scanIds)

    internal enum class ContinuationDecision {
        ENQUEUE_SUCCESSOR,
        RETRY_BACKOFF,
        COMPLETE,
    }

    companion object {
        internal data class SeedOutcome(
            val analysis: Boolean,
            val thumbnails: Boolean,
        )

        /**
         * Single production transaction for durable handoff. Keeping task expansion here lets Room
         * instrumentation prove that the compiled edition creates only policy-admitted Stage IDs.
         */
        internal suspend fun settleEnhancementScans(
            database: AppDatabase,
            scanIds: Set<String>,
            now: Long = System.currentTimeMillis(),
        ) {
            scanIds.forEach { scanId ->
                val run = database.scanRunDao().getById(scanId) ?: return@forEach
                // Thumbnail-only repair is coordinated by LibraryPipelineCoordinator. Keeping this
                // guard in addition to the DAO query makes direct/test callers equally fail-closed.
                if (run.scanType == com.renyxin.localalbum.data.db.entity.ScanRunEntity.TYPE_THUMBNAIL_REPAIR) {
                    return@forEach
                }
                val active = database.enhancementOutboxDao().countActiveForScan(scanId) +
                    database.analysisTaskDao().countActiveForScan(scanId) +
                    database.thumbnailTaskDao().countActiveForScan(scanId)
                if (active == 0) {
                    val failed = database.enhancementOutboxDao().countFailedForScan(scanId) +
                        database.analysisTaskDao().countFailedForScan(scanId) +
                        database.thumbnailTaskDao().countFailedForScan(scanId)
                    database.scanRunDao().markEnhancementTerminal(
                        scanId = scanId,
                        state = if (failed > 0) {
                            EnhancementState.COMPLETED_WITH_FAILURES.name
                        } else {
                            EnhancementState.COMPLETED.name
                        },
                        now = now,
                    )
                }
            }
        }

        internal suspend fun seedClaimedEntries(
            database: AppDatabase,
            pipeline: PluginAnalysisPipeline,
            entries: List<EnhancementOutboxEntity>,
            leaseToken: String,
            now: Long,
            includeThumbnails: Boolean = true,
        ): SeedOutcome {
            var analysisSeeded = false
            var thumbnailsSeeded = false
            database.withTransaction {
                entries.groupBy { it.scanId }.forEach { (scanId, scanEntries) ->
                    val analysis = scanEntries
                        .filter { it.enqueueAnalysis && it.mediaType == MediaType.IMAGE.name }
                        .flatMap { entry ->
                            pipeline.taskScopesForOutbox(entry.pipelineScope).map { taskScope ->
                                AnalysisTaskEntity(
                                    filePath = entry.filePath,
                                    sourceVersion = entry.sourceVersion,
                                    pipelineScope = taskScope,
                                    scanId = scanId,
                                    createdAt = now,
                                    updatedAt = now,
                                )
                            }
                        }
                    if (analysis.isNotEmpty()) {
                        database.analysisTaskDao().enqueueAllForScan(analysis, scanId)
                        analysisSeeded = true
                    }

                    val thumbnails = if (includeThumbnails) {
                        scanEntries
                            .filter { it.enqueueThumbnail }
                            .map { entry ->
                                ThumbnailTaskEntity(
                                    filePath = entry.filePath,
                                    sizeClass = ThumbnailSpec.SIZE_GRID,
                                    sourceVersion = entry.sourceVersion,
                                    mediaType = entry.mediaType,
                                    priority = ThumbnailSpec.PRIORITY_BACKGROUND,
                                    scanId = scanId,
                                    createdAt = now,
                                    updatedAt = now,
                                )
                            }
                    } else {
                        emptyList()
                    }
                    if (thumbnails.isNotEmpty()) {
                        database.thumbnailTaskDao().enqueueAll(thumbnails)
                        thumbnailsSeeded = true
                    }
                    database.scanRunDao().markEnhancementQueued(scanId)
                }
                check(
                    database.enhancementOutboxDao().markDone(
                        entries.map { it.outboxId },
                        leaseToken,
                        now,
                    ) == entries.size,
                ) { "Enhancement outbox lease changed during handoff" }
            }
            return SeedOutcome(
                analysis = analysisSeeded,
                thumbnails = thumbnailsSeeded,
            )
        }

        private const val TAG = "EnhancementHandoff"
        private const val WORK_NAME = "enhancement_handoff_queue"
        private const val BATCH_SIZE = 250
        private const val MAX_BATCHES_PER_RUN = 4
        private const val MAX_ATTEMPTS = 4
        private const val LEASE_DURATION_MS = 10 * 60 * 1000L
        private const val BASE_RETRY_DELAY_MS = 60_000L
        private const val MAX_ERROR_LENGTH = 80

        /** Coordinator admission is level-triggered; one active or pending handoff is sufficient. */
        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        /** A bounded handoff or newly admitted analysis stage appends one ordered successor. */
        internal fun appendSuccessor(context: Context) =
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                policy,
                OneTimeWorkRequestBuilder<EnhancementHandoffWorker>().build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        internal fun retryDelayMs(attempt: Int): Long =
            BASE_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 8))

        internal fun continuationDecision(
            hasActiveEntries: Boolean,
            hasClaimableEntries: Boolean,
        ): ContinuationDecision = when {
            hasClaimableEntries -> ContinuationDecision.ENQUEUE_SUCCESSOR
            hasActiveEntries -> ContinuationDecision.RETRY_BACKOFF
            else -> ContinuationDecision.COMPLETE
        }
    }
}
