package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanRunDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun orphanGateOpensOnlyAfterBothSourcesComplete() = runBlocking {
        val dao = database.scanRunDao()
        val run = ScanRunEntity("scan-1", 1L, ScanRunEntity.TYPE_FULL)
        dao.insert(run)

        assertFalse(dao.canDeleteOrphans(run.scanId) == true)
        dao.markMediaStoreCompleted(run.scanId)
        assertFalse(dao.canDeleteOrphans(run.scanId) == true)
        dao.markFileSystemCompleted(run.scanId)
        assertTrue(dao.canDeleteOrphans(run.scanId) == true)
        assertTrue(dao.markCompleted(run.scanId, 10L))
        assertTrue(dao.isCompleted(run.scanId) == true)
    }

    @Test
    fun failedRunCanNeverReopenOrphanGate() = runBlocking {
        val dao = database.scanRunDao()
        val run = ScanRunEntity("scan-2", 2L, ScanRunEntity.TYPE_INCREMENTAL)
        dao.insert(run)
        dao.markMediaStoreCompleted(run.scanId)
        dao.markTerminal(run.scanId, ScanRunEntity.STATUS_FAILED, 10L, "SecurityException")
        dao.markFileSystemCompleted(run.scanId)

        assertFalse(dao.canDeleteOrphans(run.scanId) == true)
        assertFalse(dao.markCompleted(run.scanId, 20L))
        assertEquals(ScanRunEntity.STATUS_FAILED, dao.getById(run.scanId)?.status)
    }

    @Test
    fun staleRunningScansAreAbortedAfterProcessRestart() = runBlocking {
        val dao = database.scanRunDao()
        dao.insert(
            ScanRunEntity(
                "scan-3",
                3L,
                ScanRunEntity.TYPE_FULL,
                startedAt = 10L,
            ),
        )
        dao.insert(
            ScanRunEntity(
                "scan-current-process",
                4L,
                ScanRunEntity.TYPE_INCREMENTAL,
                startedAt = 25L,
            ),
        )

        assertEquals(1, dao.abortStaleRunning(now = 30L, startedBefore = 20L))
        val stored = dao.getById("scan-3")
        assertEquals(ScanRunEntity.STATUS_ABORTED, stored?.status)
        assertEquals(CoreScanState.PAUSED.name, stored?.coreScanState)
        assertEquals("process_restarted", stored?.errorType)
        assertEquals(
            ScanRunEntity.STATUS_RUNNING,
            dao.getById("scan-current-process")?.status,
        )
    }

    @Test
    fun stagingMergesBothSourcesAndPaginatesByPath() = runBlocking {
        val dao = database.scanStagingDao()
        dao.mergeBatch(listOf(
            ScanStagingEntity("scan-4", "/a.jpg", 1, mediaStoreJson = "media-a"),
            ScanStagingEntity("scan-4", "/b.jpg", 1, mediaStoreJson = "media-b"),
        ))
        dao.mergeBatch(listOf(
            ScanStagingEntity("scan-4", "/a.jpg", 2, fileSystemJson = "file-a"),
        ))

        assertEquals(2, dao.count("scan-4"))
        val first = dao.getAfter("scan-4", "", 1).single()
        assertEquals("/a.jpg", first.filePath)
        assertEquals(3, first.sourceMask)
        assertEquals("media-a", first.mediaStoreJson)
        assertEquals("file-a", first.fileSystemJson)
        assertEquals("/b.jpg", dao.getAfter("scan-4", first.filePath, 1).single().filePath)
        assertEquals(2, dao.deleteByScanId("scan-4"))
    }

    @Test
    fun newSourceVersionSupersedesOldTaskAndScopeIsolatesClaiming() = runBlocking {
        val dao = database.analysisTaskDao()
        val old = AnalysisTaskEntity(filePath = "/a.jpg", sourceVersion = "1:10", pipelineScope = "scope-a")
        dao.enqueueAll(listOf(old))
        dao.enqueueAll(listOf(AnalysisTaskEntity(filePath = "/a.jpg", sourceVersion = "2:10", pipelineScope = "scope-a")))
        dao.enqueueAll(listOf(AnalysisTaskEntity(filePath = "/b.jpg", sourceVersion = "1:10", pipelineScope = "scope-b")))

        val claimed = dao.claimBatch(1L, 10, "lease-a", 100L, "scope-a")
        assertEquals(listOf("2:10"), claimed.map { it.sourceVersion })
        assertEquals(1, dao.renewLease("lease-a", 500L, 2L))
    }

    @Test
    fun batchSupersedingComparesEachPathWithItsOwnVersion() = runBlocking {
        val dao = database.analysisTaskDao()
        dao.enqueueAll(listOf(
            AnalysisTaskEntity(filePath = "/a.jpg", sourceVersion = "v1", pipelineScope = "scope-a"),
            AnalysisTaskEntity(filePath = "/b.jpg", sourceVersion = "v2", pipelineScope = "scope-a"),
        ))
        dao.enqueueAll(listOf(
            AnalysisTaskEntity(filePath = "/a.jpg", sourceVersion = "v2", pipelineScope = "scope-a"),
            AnalysisTaskEntity(filePath = "/b.jpg", sourceVersion = "v1", pipelineScope = "scope-a"),
        ))

        val claimed = dao.claimBatch(1L, 10, "lease-pairs", 100L, "scope-a")
        assertEquals(
            setOf("/a.jpg:v2", "/b.jpg:v1"),
            claimed.map { "${it.filePath}:${it.sourceVersion}" }.toSet(),
        )
    }

    @Test
    fun explicitEnqueueReactivatesDoneTaskButDoesNotReviveSupersededVersion() = runBlocking {
        val dao = database.analysisTaskDao()
        val task = AnalysisTaskEntity(filePath = "/a.jpg", sourceVersion = "v1", pipelineScope = "scope-a")
        dao.enqueueAll(listOf(task))
        val claimed = dao.claimBatch(1L, 10, "lease-done", 100L, "scope-a")
        dao.markDone(claimed.map { it.taskId }, "lease-done", 2L)
        dao.enqueueAll(listOf(task))
        assertEquals(1, dao.claimBatch(3L, 10, "lease-reactivated", 100L, "scope-a").size)

        dao.enqueueAll(listOf(task.copy(sourceVersion = "v2")))
        dao.resetAll(AnalysisTaskEntity.PRIORITY_USER, 4L, "scope-a")
        val current = dao.claimBatch(5L, 10, "lease-current", 100L, "scope-a")
        assertEquals(listOf("v2"), current.map { it.sourceVersion })
    }

    @Test
    fun retiredLiteAutomaticPolicyTasksAreSupersededWithoutTouchingFullOrManualScopes() = runBlocking {
        val taskDao = database.analysisTaskDao()
        val retiredAggregate = PluginAnalysisPipeline.buildPipelineScope(
            listOf(
                "plan=enhancement",
                "policy=lite@1",
                "scene=test:scene",
                "core:scene@1",
            ),
        )
        val retiredStage = PluginAnalysisPipeline.buildStageTaskScope(
            pipelineScope = retiredAggregate,
            stageId = "core:scene",
            modelVersion = 1,
        )
        val currentLite = PluginAnalysisPipeline.buildPipelineScope(
            listOf("admission=disabled", "plan=enhancement", "policy=lite@2"),
        )
        val fullScope = PluginAnalysisPipeline.buildPipelineScope(
            listOf("plan=enhancement", "policy=full@1", "scene=test:scene"),
        )
        val manualScope = PluginAnalysisPipeline.buildPipelineScope(
            listOf("plan=manual", "policy=lite@1", "scene=test:scene"),
        )
        val similarPolicyScope = PluginAnalysisPipeline.buildPipelineScope(
            listOf("plan=enhancement", "policy=lite@10", "scene=test:scene"),
        )
        listOf(
            retiredAggregate,
            retiredStage,
            currentLite,
            fullScope,
            manualScope,
            similarPolicyScope,
        ).forEachIndexed { index, scope ->
            taskDao.enqueueAll(
                listOf(
                    AnalysisTaskEntity(
                        filePath = "/retire-$index.jpg",
                        sourceVersion = "v1",
                        pipelineScope = scope,
                    ),
                ),
            )
        }
        val selector = PluginAnalysisPipeline.retiredPolicyScopeSelector(
            planType = com.renyxin.localalbum.core.pipeline.AnalysisPlanType.ENHANCEMENT,
            policyId = "lite",
            policyVersion = 1,
        )

        val changed = taskDao.supersedeRetiredPolicyScopes(
            pipelinePrefix = selector.pipelinePrefix,
            planName = selector.planName,
            policyIdentity = selector.policyIdentity,
            reason = "retired_automatic_policy",
            now = 10L,
        )

        assertTrue(retiredAggregate.startsWith("pipeline:v1|core:scene@1|"))
        assertEquals(2, changed)
        assertEquals(0, taskDao.countActive(retiredAggregate))
        assertEquals(0, taskDao.countActive(retiredStage))
        assertEquals(1, taskDao.countActive(currentLite))
        assertEquals(1, taskDao.countActive(fullScope))
        assertEquals(1, taskDao.countActive(manualScope))
        assertEquals(1, taskDao.countActive(similarPolicyScope))
    }

    @Test
    fun unsettledEnhancementRunsRemainDiscoverableAfterTheirLastTaskIsSuperseded() = runBlocking {
        val scanId = "scan-retired-policy"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 9L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        val scope = PluginAnalysisPipeline.buildPipelineScope(
            listOf("plan=enhancement", "policy=lite@1", "scene=test:scene", "core:scene@1"),
        )
        database.analysisTaskDao().enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/old-scene.jpg",
                    sourceVersion = "v1",
                    pipelineScope = scope,
                ),
            ),
            scanId,
        )
        val selector = PluginAnalysisPipeline.retiredPolicyScopeSelector(
            planType = com.renyxin.localalbum.core.pipeline.AnalysisPlanType.ENHANCEMENT,
            policyId = "lite",
            policyVersion = 1,
        )
        database.analysisTaskDao().supersedeRetiredPolicyScopes(
            pipelinePrefix = selector.pipelinePrefix,
            planName = selector.planName,
            policyIdentity = selector.policyIdentity,
            reason = "retired_automatic_policy",
            now = 11L,
        )

        assertTrue(database.scanRunDao().getEnhancementScanIds().isEmpty())
        assertEquals(listOf(scanId), database.scanRunDao().getUnsettledEnhancementScanIds())
        assertEquals(0, database.analysisTaskDao().countActiveForScan(scanId))
        com.renyxin.localalbum.data.worker.EnhancementHandoffWorker.settleEnhancementScans(
            database = database,
            scanIds = setOf(scanId),
            now = 12L,
        )
        assertEquals(
            EnhancementState.COMPLETED.name,
            database.scanRunDao().getById(scanId)?.enhancementState,
        )
    }

    @Test
    fun corePublicationKeepsPartialPublishingAndPublishedStatesDistinct() = runBlocking {
        val dao = database.scanRunDao()
        val run = ScanRunEntity("scan-lifecycle", 10L, ScanRunEntity.TYPE_FULL, startedAt = 1L)
        dao.insert(run)

        assertEquals(1, dao.markIndexAvailable(run.scanId, now = 5L))
        var stored = requireNotNull(dao.getById(run.scanId))
        assertEquals(IndexAvailability.PARTIAL.name, stored.indexAvailability)
        assertEquals(CoreScanState.APPLYING_CHANGES.name, stored.coreScanState)
        assertEquals(5L, stored.firstBatchCommittedAt)
        assertEquals(5L, stored.indexAvailableAt)

        dao.markMediaStoreCompleted(run.scanId)
        dao.markFileSystemCompleted(run.scanId)
        assertTrue(dao.markCoreReadyForSnapshot(run.scanId))
        stored = requireNotNull(dao.getById(run.scanId))
        assertEquals(ScanRunEntity.STATUS_RUNNING, stored.status)
        assertEquals(CoreScanState.PUBLISHING.name, stored.coreScanState)
        assertEquals(IndexAvailability.PARTIAL.name, stored.indexAvailability)

        assertEquals(1, dao.markSnapshotPublished(run.scanId, 20L))
        stored = requireNotNull(dao.getById(run.scanId))
        assertEquals(ScanRunEntity.STATUS_COMPLETED, stored.status)
        assertEquals(IndexAvailability.PUBLISHED.name, stored.indexAvailability)
        assertEquals(CoreScanState.COMPLETED.name, stored.coreScanState)
        assertEquals(20L, stored.coreCompletedAt)
    }

    @Test
    fun cancellationAndSnapshotFailureNeverReportCoreCompleted() = runBlocking {
        val dao = database.scanRunDao()
        dao.insert(ScanRunEntity("scan-cancelled", 11L, ScanRunEntity.TYPE_INCREMENTAL))
        dao.markTerminal(
            "scan-cancelled",
            ScanRunEntity.STATUS_ABORTED,
            30L,
            "cancelled",
        )
        assertEquals(
            CoreScanState.CANCELLED.name,
            dao.getById("scan-cancelled")?.coreScanState,
        )

        dao.insert(ScanRunEntity("scan-publish-failed", 12L, ScanRunEntity.TYPE_FULL))
        dao.markMediaStoreCompleted("scan-publish-failed")
        dao.markFileSystemCompleted("scan-publish-failed")
        assertTrue(dao.markCoreReadyForSnapshot("scan-publish-failed"))
        assertEquals(
            1,
            dao.markCoreFailedAfterIndex(
                errorType = "SQLiteException",
                scanId = "scan-publish-failed",
                now = 40L,
            ),
        )
        val failed = requireNotNull(dao.getById("scan-publish-failed"))
        assertEquals(ScanRunEntity.STATUS_FAILED, failed.status)
        assertEquals(CoreScanState.FAILED.name, failed.coreScanState)
        assertEquals(40L, failed.completedAt)
    }

    @Test
    fun enhancementLifecyclePausesRecoversAndTerminatesIndependently() = runBlocking {
        val scanDao = database.scanRunDao()
        val run = ScanRunEntity(
            scanId = "scan-enhancement",
            generation = 13L,
            scanType = ScanRunEntity.TYPE_FULL,
            status = ScanRunEntity.STATUS_COMPLETED,
            coreScanState = CoreScanState.COMPLETED.name,
            indexAvailability = IndexAvailability.PUBLISHED.name,
            enhancementState = EnhancementState.QUEUED.name,
        )
        scanDao.insert(run)
        database.analysisTaskDao().enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/lifecycle.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            run.scanId,
        )

        assertEquals(1, scanDao.markEnhancementRunning(run.scanId, now = 50L))
        assertEquals(1, scanDao.markEnhancementPaused(run.scanId))
        assertEquals(EnhancementState.PAUSED.name, scanDao.getById(run.scanId)?.enhancementState)
        assertEquals(0, scanDao.markEnhancementQueued(run.scanId))
        assertEquals(0, scanDao.markEnhancementRunning(run.scanId, now = 60L))
        assertEquals(1, scanDao.resumeUserPausedEnhancements())
        assertEquals(1, scanDao.markEnhancementRunning(run.scanId, now = 60L))
        assertEquals(1, scanDao.recoverRunningEnhancements())
        assertEquals(EnhancementState.QUEUED.name, scanDao.getById(run.scanId)?.enhancementState)
        assertEquals(
            1,
            scanDao.markEnhancementTerminal(
                run.scanId,
                EnhancementState.COMPLETED_WITH_FAILURES.name,
                70L,
            ),
        )
        val terminal = requireNotNull(scanDao.getById(run.scanId))
        assertEquals(EnhancementState.COMPLETED_WITH_FAILURES.name, terminal.enhancementState)
        assertEquals(CoreScanState.COMPLETED.name, terminal.coreScanState)
        assertEquals(50L, terminal.enhancementStartedAt)
        assertEquals(70L, terminal.enhancementCompletedAt)
    }

    @Test
    fun duplicatePendingTaskTransfersOwnershipToNewestScan() = runBlocking {
        val scanDao = database.scanRunDao()
        val taskDao = database.analysisTaskDao()
        scanDao.insert(
            ScanRunEntity(
                scanId = "scan-new",
                generation = 14L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        val task = AnalysisTaskEntity(
            filePath = "/owned.jpg",
            sourceVersion = "v1",
            pipelineScope = "scope-a",
            scanId = "scan-old",
        )
        taskDao.enqueueAll(listOf(task))
        taskDao.enqueueAllForScan(listOf(task), "scan-new")

        val claimed = taskDao.claimBatch(1L, 10, "lease-owned", 100L, "scope-a")
        assertEquals("scan-new", claimed.single().scanId)
        assertEquals(1, taskDao.countActiveForScan("scan-new"))
        assertEquals(0, taskDao.countActiveForScan("scan-old"))
    }

    @Test
    fun pausedEnhancementTasksAreNotClaimedOrCountedAsRunnable() = runBlocking {
        val scanDao = database.scanRunDao()
        val taskDao = database.analysisTaskDao()
        listOf(
            "scan-paused" to EnhancementState.PAUSED,
            "scan-preempted" to EnhancementState.PREEMPTED,
            "scan-queued" to EnhancementState.QUEUED,
        ).forEachIndexed { index, (scanId, enhancementState) ->
            scanDao.insert(
                ScanRunEntity(
                    scanId = scanId,
                    generation = 20L + index,
                    scanType = ScanRunEntity.TYPE_INCREMENTAL,
                    status = ScanRunEntity.STATUS_COMPLETED,
                    coreScanState = CoreScanState.COMPLETED.name,
                    indexAvailability = IndexAvailability.PUBLISHED.name,
                    enhancementState = enhancementState.name,
                ),
            )
        }
        taskDao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/paused.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            "scan-paused",
        )
        taskDao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/preempted.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            "scan-preempted",
        )
        taskDao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/queued.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            "scan-queued",
        )
        taskDao.enqueueAll(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/manual.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
        )

        assertEquals(4, taskDao.countActive("scope-a"))
        assertEquals(2, taskDao.countRunnable("scope-a"))
        val claimed = taskDao.claimBatch(1L, 10, "lease-runnable", 100L, "scope-a")
        assertEquals(setOf("/queued.jpg", "/manual.jpg"), claimed.map { it.filePath }.toSet())
        assertEquals(1, taskDao.countActiveForScan("scan-paused"))
    }

    @Test
    fun interruptedEnhancementLeaseReturnsToPendingQueue() = runBlocking {
        val scanDao = database.scanRunDao()
        val taskDao = database.analysisTaskDao()
        scanDao.insert(
            ScanRunEntity(
                scanId = "scan-recovery",
                generation = 14L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.RUNNING.name,
            ),
        )
        taskDao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/resume.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            "scan-recovery",
        )
        assertEquals(1, taskDao.claimBatch(1L, 10, "lease-before-kill", 10_000L, "scope-a").size)

        assertEquals(1, scanDao.recoverRunningEnhancements())
        assertEquals(1, taskDao.recoverInterruptedLeases(2L))
        assertEquals(
            1,
            taskDao.claimBatch(3L, 10, "lease-after-restart", 10_000L, "scope-a").size,
        )
        assertEquals(EnhancementState.QUEUED.name, scanDao.getById("scan-recovery")?.enhancementState)
    }

    @Test
    fun corePreemptionAutoResumesButUserPauseRequiresExplicitResume() = runBlocking {
        val scanId = "scan-preempted"
        val scanDao = database.scanRunDao()
        scanDao.insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 29L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        database.analysisTaskDao().enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/preempted.jpg",
                    sourceVersion = "v1",
                    pipelineScope = "scope-a",
                ),
            ),
            scanId,
        )

        assertEquals(1, scanDao.preemptAllActiveEnhancements())
        assertEquals(EnhancementState.PREEMPTED.name, scanDao.getById(scanId)?.enhancementState)
        assertFalse(scanId in scanDao.getEnhancementScanIds())
        assertEquals(1, scanDao.resumePreemptedEnhancements())
        assertTrue(scanId in scanDao.getEnhancementScanIds())

        assertEquals(1, scanDao.pauseAllActiveEnhancements())
        assertEquals(EnhancementState.PAUSED.name, scanDao.getById(scanId)?.enhancementState)
        assertEquals(0, scanDao.resumePreemptedEnhancements())
        assertFalse(scanId in scanDao.getEnhancementScanIds())
        assertEquals(1, scanDao.resumeUserPausedEnhancements())
        assertTrue(scanId in scanDao.getEnhancementScanIds())
    }

    @Test
    fun restoreWaitingStateRequiresReconciliationAndPreservesUserPauseIntent() = runBlocking {
        val scanDao = database.scanRunDao()
        val outboxDao = database.enhancementOutboxDao()

        suspend fun seedRestore(scanId: String, path: String, generation: Long) {
            scanDao.insert(
                ScanRunEntity(
                    scanId = scanId,
                    generation = generation,
                    scanType = ScanRunEntity.TYPE_RECONCILIATION,
                    status = ScanRunEntity.STATUS_COMPLETED,
                    coreScanState = CoreScanState.COMPLETED.name,
                    indexAvailability = IndexAvailability.PUBLISHED.name,
                    enhancementState = EnhancementState.WAITING_FOR_CORE.name,
                ),
            )
            outboxDao.enqueueAll(
                listOf(
                    EnhancementOutboxEntity(
                        scanId = scanId,
                        profileId = "lite",
                        filePath = path,
                        sourceVersion = "v1",
                        mediaType = "IMAGE",
                        pipelineScope = "scope-lite",
                        enqueueAnalysis = true,
                        enqueueThumbnail = true,
                        parentPath = "/",
                    ),
                ),
            )
        }

        seedRestore("restore-explicit-resume", "/explicit.jpg", 31L)
        assertFalse(outboxDao.hasRunnableEntries())
        assertTrue(outboxDao.claimBatch(1L, 10, "waiting", 100L).isEmpty())

        assertEquals(1, scanDao.markEnhancementPaused("restore-explicit-resume"))
        assertEquals(
            EnhancementState.WAITING_FOR_CORE_PAUSED.name,
            scanDao.getById("restore-explicit-resume")?.enhancementState,
        )
        assertEquals(0, scanDao.resumePreemptedEnhancements())
        assertEquals(1, scanDao.resumeUserPausedEnhancements())
        assertEquals(
            EnhancementState.WAITING_FOR_CORE.name,
            scanDao.getById("restore-explicit-resume")?.enhancementState,
        )
        assertFalse(outboxDao.hasRunnableEntries())

        assertEquals(1, scanDao.releaseWaitingForCoreEnhancements())
        assertEquals(
            EnhancementState.QUEUED.name,
            scanDao.getById("restore-explicit-resume")?.enhancementState,
        )
        val released = outboxDao.claimBatch(2L, 10, "released", 100L)
        assertEquals(listOf("/explicit.jpg"), released.map { it.filePath })
        assertEquals(1, outboxDao.markDone(released.map { it.outboxId }, "released", 3L))

        seedRestore("restore-still-paused", "/paused-restore.jpg", 32L)
        assertEquals(1, scanDao.markEnhancementPaused("restore-still-paused"))
        assertEquals(1, scanDao.releaseWaitingForCoreEnhancements())
        assertEquals(
            EnhancementState.PAUSED.name,
            scanDao.getById("restore-still-paused")?.enhancementState,
        )
        assertFalse(outboxDao.hasRunnableEntries())
        assertTrue(outboxDao.claimBatch(4L, 10, "still-paused", 100L).isEmpty())
        assertEquals(1, scanDao.resumeUserPausedEnhancements())
        assertEquals(
            EnhancementState.QUEUED.name,
            scanDao.getById("restore-still-paused")?.enhancementState,
        )
    }

    @Test
    fun thumbnailQueuesSeparateInteractiveFromAutomaticAndReleaseLease() = runBlocking {
        val scanId = "scan-thumbnail"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 30L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/interactive.jpg",
                    sourceVersion = "v1",
                    mediaType = "IMAGE",
                    priority = 100,
                ),
                ThumbnailTaskEntity(
                    filePath = "/automatic.jpg",
                    sourceVersion = "v1",
                    mediaType = "IMAGE",
                    scanId = scanId,
                ),
            ),
        )

        val interactive = dao.claimInteractiveBatch(1L, 10, "thumb-ui", 10_000L)
        assertEquals(listOf("/interactive.jpg"), interactive.map { it.filePath })
        assertEquals(1, dao.releaseLease("thumb-ui", 2L))
        val automatic = dao.claimAutomaticBatch(3L, 10, "thumb-bg", 10_000L)
        assertEquals(listOf("/automatic.jpg"), automatic.map { it.filePath })
        assertEquals(1, dao.countInteractiveActive())
        assertEquals(1, dao.countAutomaticRunnable())
    }

    @Test
    fun interruptedThumbnailRecoveryIsImmediateAndStrictlyLaneScoped() = runBlocking {
        val scanId = "scan-thumbnail-interrupted"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 31L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/interactive-interrupted.jpg",
                    sourceVersion = "v1",
                    mediaType = "IMAGE",
                    priority = 100,
                ),
                ThumbnailTaskEntity(
                    filePath = "/automatic-interrupted.jpg",
                    sourceVersion = "v1",
                    mediaType = "IMAGE",
                    scanId = scanId,
                ),
            ),
        )

        assertEquals(
            listOf("/interactive-interrupted.jpg"),
            dao.claimInteractiveBatch(1L, 10, "live-ui", 100_000L).map { it.filePath },
        )
        assertEquals(
            listOf("/automatic-interrupted.jpg"),
            dao.claimAutomaticBatch(1L, 10, "dead-bg", 100_000L).map { it.filePath },
        )

        assertEquals(1, dao.recoverInterruptedAutomaticLeases(2L))
        assertTrue(dao.claimInteractiveBatch(3L, 10, "must-not-steal-ui", 100L).isEmpty())
        assertEquals(
            listOf("/automatic-interrupted.jpg"),
            dao.claimAutomaticBatch(3L, 10, "recovered-bg", 100_000L).map { it.filePath },
        )

        assertEquals(1, dao.recoverInterruptedInteractiveLeases(4L))
        assertTrue(dao.claimAutomaticBatch(5L, 10, "must-not-steal-bg", 100L).isEmpty())
        assertEquals(
            listOf("/interactive-interrupted.jpg"),
            dao.claimInteractiveBatch(5L, 10, "recovered-ui", 100L).map { it.filePath },
        )
        assertEquals(1, dao.releaseLease("recovered-bg", 6L))
        assertEquals(1, dao.releaseLease("recovered-ui", 6L))
    }
}
