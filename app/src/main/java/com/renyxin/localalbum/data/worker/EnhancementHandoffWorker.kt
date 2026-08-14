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
        val database = app.container.database
        val pipeline = app.container.pluginAnalysisPipeline
        val outboxDao = database.enhancementOutboxDao()
        val touchedScans = linkedSetOf<String>()
        var seededAnalysis = false
        var seededThumbnails = false
        var interruptedLeasesRecovered = false

        return try {
            if (app.container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) {
                return Result.retry()
            }
            var batches = 0
            while (batches < MAX_BATCHES_PER_RUN) {
                if (isStopped || app.container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) break
                val outcome = EnhancementResourceGate.tryWithAutomaticEnhancement {
                    val now = System.currentTimeMillis()
                    if (!interruptedLeasesRecovered) {
                        // Owning the automatic lane proves there is no live handoff consumer in
                        // this process. RUNNING rows can only be a killed process's leases.
                        outboxDao.recoverInterruptedLeases(now)
                        interruptedLeasesRecovered = true
                    }
                    val leaseToken = UUID.randomUUID().toString()
                    val entries = outboxDao.claimBatch(
                        now = now,
                        limit = BATCH_SIZE,
                        leaseToken = leaseToken,
                        leaseDurationMs = LEASE_DURATION_MS,
                    )
                    if (entries.isEmpty()) return@tryWithAutomaticEnhancement false

                    touchedScans += entries.map { it.scanId }
                    try {
                        val seeded = seedClaimedEntries(
                            database = database,
                            pipeline = pipeline,
                            entries = entries,
                            leaseToken = leaseToken,
                            now = now,
                        )
                        seededAnalysis = seededAnalysis || seeded.analysis
                        seededThumbnails = seededThumbnails || seeded.thumbnails
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

            val hasRunnableEntries = EnhancementResourceGate.tryWithAutomaticEnhancement {
                // The terminal cross-table read/write is part of the same maintenance exclusion
                // contract as handoff transactions. Never inspect tables while import may replace
                // them after the final batch released its lane.
                finalizeEmptyScans(database, touchedScans)
                outboxDao.hasRunnableEntries()
            } ?: return Result.retry()

            // Scheduling itself does not touch production tables. A worker that starts after an
            // exclusive request will be rejected by the gate and recover from its durable queue.
            if (seededAnalysis) AnalysisWorker.enqueue(applicationContext)
            if (seededThumbnails) ThumbnailWorker.enqueueBackground(applicationContext)

            if (hasRunnableEntries) Result.retry() else Result.success()
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

                    val thumbnails = scanEntries
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

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<EnhancementHandoffWorker>().build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        internal fun retryDelayMs(attempt: Int): Long =
            BASE_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 8))
    }
}
