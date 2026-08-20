package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun liteSettlementExcludesThumbnailRepairAtQueryAndProcessingBoundaries() = runBlocking {
        val genericScanId = "scan-lite-generic-settlement"
        val repairScanId = "scan-lite-repair-settlement"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = genericScanId,
                generation = 90L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = repairScanId,
                generation = 91L,
                scanType = ScanRunEntity.TYPE_THUMBNAIL_REPAIR,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )

        assertEquals(
            listOf(genericScanId),
            database.scanRunDao().getUnsettledEnhancementScanIds(),
        )
        com.renyxin.localalbum.data.worker.EnhancementHandoffWorker.settleEnhancementScans(
            database = database,
            scanIds = setOf(genericScanId, repairScanId),
            now = 92L,
        )

        assertEquals(
            EnhancementState.COMPLETED.name,
            database.scanRunDao().getById(genericScanId)?.enhancementState,
        )
        assertEquals(
            EnhancementState.QUEUED.name,
            database.scanRunDao().getById(repairScanId)?.enhancementState,
        )
    }

    @Test
    fun thumbnailRepairTerminalIsIdempotentButRejectsIdentityMismatches() = runBlocking {
        val dao = database.scanRunDao()
        val repair = ScanRunEntity(
            scanId = "repair-terminal-idempotent",
            generation = 93L,
            scanType = ScanRunEntity.TYPE_THUMBNAIL_REPAIR,
            status = ScanRunEntity.STATUS_COMPLETED,
            coreScanState = CoreScanState.COMPLETED.name,
            indexAvailability = IndexAvailability.PUBLISHED.name,
            enhancementState = EnhancementState.QUEUED.name,
        )
        dao.insert(repair)

        assertTrue(
            dao.markThumbnailRepairTerminal(
                scanId = repair.scanId,
                generation = repair.generation,
                state = EnhancementState.COMPLETED.name,
                now = 94L,
            ),
        )
        assertTrue(
            dao.markThumbnailRepairTerminal(
                scanId = repair.scanId,
                generation = repair.generation,
                state = EnhancementState.COMPLETED.name,
                now = 95L,
            ),
        )
        assertEquals(94L, dao.getById(repair.scanId)?.enhancementCompletedAt)
        assertFalse(
            dao.markThumbnailRepairTerminal(
                scanId = "missing-repair-terminal",
                generation = repair.generation,
                state = EnhancementState.COMPLETED.name,
                now = 96L,
            ),
        )

        val generationMismatch = runCatching {
            dao.markThumbnailRepairTerminal(
                scanId = repair.scanId,
                generation = repair.generation + 1L,
                state = EnhancementState.COMPLETED.name,
                now = 97L,
            )
        }.exceptionOrNull()
        assertTrue(generationMismatch is IllegalStateException)

        val wrongType = ScanRunEntity(
            scanId = "repair-terminal-wrong-type",
            generation = 98L,
            scanType = ScanRunEntity.TYPE_INCREMENTAL,
            status = ScanRunEntity.STATUS_COMPLETED,
            coreScanState = CoreScanState.COMPLETED.name,
            indexAvailability = IndexAvailability.PUBLISHED.name,
            enhancementState = EnhancementState.QUEUED.name,
        )
        dao.insert(wrongType)
        val typeMismatch = runCatching {
            dao.markThumbnailRepairTerminal(
                scanId = wrongType.scanId,
                generation = wrongType.generation,
                state = EnhancementState.COMPLETED.name,
                now = 99L,
            )
        }.exceptionOrNull()
        assertTrue(typeMismatch is IllegalStateException)
        assertEquals(EnhancementState.QUEUED.name, dao.getById(wrongType.scanId)?.enhancementState)
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
    fun reservedCoreRunResumesWithoutChangingIdentityOrGeneration() = runBlocking {
        val dao = database.scanRunDao()
        val run = ScanRunEntity(
            scanId = "reserved-core",
            generation = 13L,
            scanType = ScanRunEntity.TYPE_INCREMENTAL,
            status = ScanRunEntity.STATUS_ABORTED,
            mediaStoreCompleted = true,
            fileSystemCompleted = true,
            completedAt = 99L,
            errorType = "process_restarted",
            coreScanState = CoreScanState.PAUSED.name,
        )
        dao.insert(run)

        assertEquals(
            1,
            dao.resumeReservedCoreRun(run.scanId, run.generation, run.scanType),
        )
        val resumed = requireNotNull(dao.getById(run.scanId))
        assertEquals(run.scanId, resumed.scanId)
        assertEquals(run.generation, resumed.generation)
        assertEquals(ScanRunEntity.STATUS_RUNNING, resumed.status)
        assertEquals(CoreScanState.DISCOVERING.name, resumed.coreScanState)
        assertFalse(resumed.mediaStoreCompleted)
        assertFalse(resumed.fileSystemCompleted)
        assertEquals(0L, resumed.completedAt)
        assertEquals(null, resumed.errorType)
        assertEquals(0, dao.resumeReservedCoreRun(run.scanId, run.generation + 1L, run.scanType))
        assertEquals(0, dao.resumeReservedCoreRun(run.scanId, run.generation, ScanRunEntity.TYPE_FULL))
    }

    @Test
    fun pipelineEnhancementRecoveryIsScopedAndPreservesUserPause() = runBlocking {
        val dao = database.scanRunDao()
        listOf(
            ScanRunEntity(
                scanId = "pipeline-running",
                generation = 14L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                enhancementState = EnhancementState.RUNNING.name,
            ),
            ScanRunEntity(
                scanId = "pipeline-preempted",
                generation = 15L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                enhancementState = EnhancementState.PREEMPTED.name,
            ),
            ScanRunEntity(
                scanId = "pipeline-paused",
                generation = 16L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                enhancementState = EnhancementState.PAUSED.name,
            ),
        ).forEach { dao.insert(it) }

        assertEquals(1, dao.recoverPipelineEnhancement("pipeline-preempted"))
        assertEquals(
            EnhancementState.QUEUED.name,
            dao.getById("pipeline-preempted")?.enhancementState,
        )
        assertEquals(
            EnhancementState.RUNNING.name,
            dao.getById("pipeline-running")?.enhancementState,
        )
        assertEquals(0, dao.recoverPipelineEnhancement("pipeline-paused"))
        assertEquals(
            EnhancementState.PAUSED.name,
            dao.getById("pipeline-paused")?.enhancementState,
        )
    }

    @Test
    fun enhancementLifecyclePausesRecoversAndTerminatesIndependently() = runBlocking {
        val scanDao = database.scanRunDao()
        val run = ScanRunEntity(
            scanId = "scan-enhancement",
            generation = 17L,
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
                generation = 18L,
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
        assertEquals(1, taskDao.countRunnable("scope-a", includeUserTasks = false))
        assertEquals(1, taskDao.countActiveScanOwnedEnhancementTasks())
        assertEquals(1, taskDao.countActiveUserTasks())
        val scanOnly = taskDao.claimBatch(
            now = 1L,
            limit = 10,
            leaseToken = "lease-scan-only",
            leaseDurationMs = 100L,
            scope = "scope-a",
            includeUserTasks = false,
        )
        assertEquals(listOf("/queued.jpg"), scanOnly.map { it.filePath })
        assertEquals(1, taskDao.markDone(scanOnly.map { it.taskId }, "lease-scan-only", 2L))
        val user = taskDao.claimBatch(3L, 10, "lease-user", 100L, "scope-a")
        assertEquals(listOf("/manual.jpg"), user.map { it.filePath })
        assertEquals(1, taskDao.countActiveForScan("scan-paused"))
    }

    @Test
    fun fullReanalysisDetachesTerminalScanAndCanBeTriggeredRepeatedly() = runBlocking {
        val scope = "scope-user-reanalysis"
        val path = "/terminal-owned.jpg"
        database.mediaDao().insertAll(listOf(media(path, modifiedAtMs = 10L, fileSize = 100L)))
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = "scan-terminal-owner",
                generation = 40L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.COMPLETED.name,
            ),
        )
        val dao = database.analysisTaskDao()
        dao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = path,
                    sourceVersion = "10:100",
                    pipelineScope = scope,
                ),
            ),
            "scan-terminal-owner",
        )
        assertEquals(0, dao.countRunnable(scope))

        val first = dao.prepareFullReanalysisScope(
            scope = scope,
            scanId = null,
            mediaType = MediaType.IMAGE.name,
            priority = AnalysisTaskEntity.PRIORITY_USER,
            now = 1L,
        )
        assertEquals(1, first.reset)
        val firstLease = dao.claimBatch(2L, 10, "user-first", 10_000L, scope)
        assertEquals(listOf(path), firstLease.map { it.filePath })
        assertEquals(null, firstLease.single().scanId)
        assertEquals(1, dao.markDone(firstLease.map { it.taskId }, "user-first", 3L))

        val second = dao.prepareFullReanalysisScope(
            scope = scope,
            scanId = null,
            mediaType = MediaType.IMAGE.name,
            priority = AnalysisTaskEntity.PRIORITY_USER,
            now = 4L,
        )
        assertEquals(1, second.reset)
        assertEquals(
            listOf(path),
            dao.claimBatch(5L, 10, "user-second", 10_000L, scope).map { it.filePath },
        )
    }

    @Test
    fun fullReanalysisPreparationNeverStealsLiveLease() = runBlocking {
        val scope = "scope-live-user-reanalysis"
        database.mediaDao().insertAll(listOf(media("/live.jpg", modifiedAtMs = 20L, fileSize = 200L)))
        val dao = database.analysisTaskDao()
        dao.prepareFullReanalysisScope(
            scope = scope,
            scanId = null,
            mediaType = MediaType.IMAGE.name,
            priority = AnalysisTaskEntity.PRIORITY_USER,
            now = 1L,
        )
        val live = dao.claimBatch(2L, 10, "live-owner", 10_000L, scope)
        assertEquals(1, live.size)

        val repeated = dao.prepareFullReanalysisScope(
            scope = scope,
            scanId = null,
            mediaType = MediaType.IMAGE.name,
            priority = AnalysisTaskEntity.PRIORITY_USER,
            now = 3L,
        )
        assertEquals(0, repeated.reset)
        assertTrue(dao.claimBatch(4L, 10, "must-not-steal", 10_000L, scope).isEmpty())
        assertEquals(1, dao.markDone(live.map { it.taskId }, "live-owner", 5L))
    }

    @Test
    fun setBasedFullReanalysisConvergesBeyondSingleWorkerWindow() = runBlocking {
        val scope = "scope-large-user-reanalysis"
        val total = 1_001
        database.mediaDao().insertAll(
            (0 until total).map { index ->
                media("/large-$index.jpg", modifiedAtMs = index.toLong(), fileSize = index + 1L)
            },
        )
        val dao = database.analysisTaskDao()
        val prepared = dao.prepareFullReanalysisScope(
            scope = scope,
            scanId = null,
            mediaType = MediaType.IMAGE.name,
            priority = AnalysisTaskEntity.PRIORITY_USER,
            now = 1L,
        )
        assertEquals(total, prepared.inserted)
        assertEquals(total, dao.countActiveEnhancementTasks())

        var completed = 0
        var leaseIndex = 0
        while (true) {
            val token = "large-lease-${leaseIndex++}"
            val batch = dao.claimBatch(2L, 250, token, 10_000L, scope)
            if (batch.isEmpty()) break
            completed += batch.size
            assertEquals(batch.size, dao.markDone(batch.map { it.taskId }, token, 3L))
        }
        assertEquals(total, completed)
        assertEquals(0, dao.countRunnable(scope))
    }

    @Test
    fun interruptedEnhancementLeaseReturnsToPendingQueue() = runBlocking {
        val scanDao = database.scanRunDao()
        val taskDao = database.analysisTaskDao()
        scanDao.insert(
            ScanRunEntity(
                scanId = "scan-recovery",
                generation = 19L,
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
        admitAutomaticThumbnailRun(scanId, 30L)
        database.mediaDao().insertAll(
            listOf(
                media("/interactive.jpg", modifiedAtMs = 1L, fileSize = 100L),
                media("/automatic.jpg", modifiedAtMs = 2L, fileSize = 200L),
            ),
        )
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/interactive.jpg",
                    sourceVersion = "1:100",
                    mediaType = "IMAGE",
                    priority = 100,
                ),
            ),
        )
        dao.enqueueAll(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/automatic.jpg",
                    sourceVersion = "2:200",
                    mediaType = "IMAGE",
                    scanId = scanId,
                ),
            ),
        )

        val interactive = dao.claimInteractiveBatch(1L, 10, "thumb-ui", 10_000L)
        assertEquals(listOf("/interactive.jpg"), interactive.map { it.filePath })
        assertEquals(1, dao.releaseLease("thumb-ui", 2L))
        val automatic = dao.claimAutomaticBatch(3L, 10, "thumb-bg", 10_000L, scanId)
        assertEquals(listOf("/automatic.jpg"), automatic.map { it.filePath })
        assertEquals(1, dao.countInteractiveActive())
        assertEquals(1, dao.countAutomaticRunnable())
    }

    @Test
    fun processBoundaryThumbnailRecoveryIsImmediateForBothLanes() = runBlocking {
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
        admitAutomaticThumbnailRun(scanId, 31L)
        database.mediaDao().insertAll(
            listOf(
                media("/interactive-interrupted.jpg", modifiedAtMs = 3L, fileSize = 300L),
                media("/automatic-interrupted.jpg", modifiedAtMs = 4L, fileSize = 400L),
            ),
        )
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/interactive-interrupted.jpg",
                    sourceVersion = "3:300",
                    mediaType = "IMAGE",
                    priority = 100,
                ),
            ),
        )
        dao.enqueueAll(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/automatic-interrupted.jpg",
                    sourceVersion = "4:400",
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
            dao.claimAutomaticBatch(1L, 10, "dead-bg", 100_000L, scanId).map { it.filePath },
        )

        assertEquals(2, dao.recoverThumbnailProcessBoundary("new-process",2L))
        assertEquals(
            listOf("/automatic-interrupted.jpg"),
            dao.claimAutomaticBatch(3L, 10, "recovered-bg", 100_000L, scanId).map { it.filePath },
        )
        assertEquals(
            listOf("/interactive-interrupted.jpg"),
            dao.claimInteractiveBatch(3L, 10, "recovered-ui", 100L).map { it.filePath },
        )
        assertEquals(1, dao.releaseLease("recovered-bg", 6L))
        assertEquals(1, dao.releaseLease("recovered-ui", 6L))
    }

    @Test
    fun fullThumbnailSeedPreservesLiveInteractiveLeaseAndGateConverges() = runBlocking {
        val scanId = "scan-full-seed-live-ui"
        val path = "/full-seed-live-ui.jpg"
        val leaseToken = "full-seed-ui-owner"
        database.mediaDao().insertAll(listOf(media(path, modifiedAtMs = 40L, fileSize = 400L)))
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 34L,
                scanType = ScanRunEntity.TYPE_FULL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        admitAutomaticThumbnailRun(scanId, 34L)
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(
            listOf(
                ThumbnailTaskEntity(
                    filePath = path,
                    sourceVersion = "40:400",
                    mediaType = MediaType.IMAGE.name,
                    priority = ThumbnailSpec.PRIORITY_VISIBLE,
                ),
            ),
        )
        val live = dao.claimInteractiveBatch(10L, 1, leaseToken, 100_000L).single()

        assertEquals(1, dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L))
        val seeded = requireNotNull(dao.getById(live.taskId))
        assertEquals(ThumbnailTaskEntity.STATUS_RUNNING, seeded.status)
        assertNull(seeded.scanId)
        assertEquals(leaseToken, seeded.leaseToken)
        assertEquals(live.leaseUntil, seeded.leaseUntil)
        assertEquals(leaseToken, dao.getById(live.taskId)?.leaseToken)

        val blockedGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, blockedGate.total)
        assertEquals(0, blockedGate.terminal)
        assertEquals(1, blockedGate.remaining)
        assertEquals(1, dao.markDone(live.taskId, leaseToken, 22L))
        val completedGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, completedGate.total)
        assertEquals(1, completedGate.terminal)
        assertEquals(0, completedGate.remaining)
    }

    @Test
    fun incrementalOutboxSeedPreservesLiveInteractiveLeaseAndGateConverges() = runBlocking {
        val scanId = "scan-incremental-seed-live-ui"
        val path = "/incremental-seed-live-ui.jpg"
        val sourceVersion = "50:500"
        val leaseToken = "incremental-seed-ui-owner"
        database.mediaDao().insertAll(listOf(media(path, modifiedAtMs = 50L, fileSize = 500L)))
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 35L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        admitAutomaticThumbnailRun(scanId, 35L)
        database.enhancementOutboxDao().enqueueAll(
            listOf(
                EnhancementOutboxEntity(
                    scanId = scanId,
                    profileId = "test-profile",
                    filePath = path,
                    sourceVersion = sourceVersion,
                    mediaType = MediaType.IMAGE.name,
                    pipelineScope = "test-scope",
                    enqueueAnalysis = false,
                    enqueueThumbnail = true,
                    parentPath = "/",
                ),
            ),
        )
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(
            listOf(
                ThumbnailTaskEntity(
                    filePath = path,
                    sourceVersion = sourceVersion,
                    mediaType = MediaType.IMAGE.name,
                    priority = ThumbnailSpec.PRIORITY_VISIBLE,
                ),
            ),
        )
        val live = dao.claimInteractiveBatch(10L, 1, leaseToken, 100_000L).single()

        assertEquals(
            1,
            dao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L),
        )
        val seeded = requireNotNull(dao.getById(live.taskId))
        assertEquals(ThumbnailTaskEntity.STATUS_RUNNING, seeded.status)
        assertNull(seeded.scanId)
        assertEquals(leaseToken, seeded.leaseToken)
        assertEquals(live.leaseUntil, seeded.leaseUntil)
        assertEquals(leaseToken, dao.getById(live.taskId)?.leaseToken)

        val blockedGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, blockedGate.total)
        assertEquals(0, blockedGate.terminal)
        assertEquals(1, blockedGate.remaining)

        // Simulate process death after seeding. Only the interactive lane may recover this retained
        // owner; the old token becomes invalid and the durable task is reclaimable by a successor.
        assertTrue(dao.recoverThumbnailProcessBoundary("incremental-resume",22L) >= 1)
        assertEquals(0, dao.markDone(live.taskId, leaseToken, 23L))
        val resumed = dao.claimInteractiveBatch(
            now = 24L,
            limit = 1,
            leaseToken = "incremental-seed-ui-resumed",
            leaseDurationMs = 100_000L,
        ).single()
        assertEquals(live.taskId, resumed.taskId)
        assertEquals(1, dao.markDone(resumed.taskId, "incremental-seed-ui-resumed", 25L))
        val completedGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, completedGate.total)
        assertEquals(1, completedGate.terminal)
        assertEquals(0, completedGate.remaining)
    }

    @Test
    fun failedScanOwnedThumbnailRetryBecomesInteractiveClaimable() = runBlocking {
        val scanId = "scan-failed-thumbnail"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 32L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        admitAutomaticThumbnailRun(scanId, 32L)
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(
            listOf(
                ThumbnailTaskEntity(
                    filePath = "/failed-thumbnail.jpg",
                    sourceVersion = "v1",
                    mediaType = "IMAGE",
                    scanId = scanId,
                ),
            ),
        )

        val claimed = dao.claimAutomaticBatch(
            now = 1L,
            limit = 10,
            leaseToken = "failed-thumbnail-lease",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        )
        assertEquals(1, claimed.size)
        assertEquals(
            1,
            dao.markFailed(
                taskId = claimed.single().taskId,
                leaseToken = "failed-thumbnail-lease",
                error = "decode_failed",
                nextRetryAt = 2L,
                maxAttempts = 1,
                now = 2L,
            ),
        )
        assertEquals(1, dao.countFailed())

        val retried = dao.retryAllFailedInteractive(
            priority = ThumbnailSpec.PRIORITY_VISIBLE,
            now = 3L,
        )
        assertEquals(1, retried.accepted)
        assertTrue(retried.shouldWake)
        assertEquals(0, dao.countFailed())
        assertEquals(1, dao.countInteractiveClaimable(3L))
        val interactive = dao.claimInteractiveBatch(
            now = 3L,
            limit = 10,
            leaseToken = "retried-thumbnail-lease",
            leaseDurationMs = 10_000L,
        )
        assertEquals(1, interactive.size)
        assertNull(interactive.single().scanId)
        assertEquals(ThumbnailSpec.PRIORITY_VISIBLE, interactive.single().priority)
        assertEquals(1, dao.releaseLease("retried-thumbnail-lease", 4L))
    }

    @Test
    fun failedScanOwnedAnalysisRetryBecomesUserClaimable() = runBlocking {
        val scanId = "scan-failed-analysis"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 33L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
        val dao = database.analysisTaskDao()
        val retiredScope = "pipeline:v1|plan=retired|policy=retired@1"
        dao.enqueueAllForScan(
            listOf(
                AnalysisTaskEntity(
                    filePath = "/failed-analysis.jpg",
                    sourceVersion = "v1",
                ),
                AnalysisTaskEntity(
                    filePath = "/retired-failed-analysis.jpg",
                    sourceVersion = "v1",
                    pipelineScope = retiredScope,
                ),
            ),
            scanId,
        )

        val claimed = dao.claimBatch(
            now = 1L,
            limit = 10,
            leaseToken = "failed-analysis-lease",
            leaseDurationMs = 10_000L,
            includeUserTasks = false,
            admittedScanId = scanId,
            admitAnyScan = false,
        )
        assertEquals(listOf("/failed-analysis.jpg"), claimed.map { it.filePath })
        assertEquals(
            1,
            dao.markFailed(
                taskIds = claimed.map { it.taskId },
                leaseToken = "failed-analysis-lease",
                error = "analysis_failed",
                nextRetryAt = 2L,
                maxAttempts = 1,
                now = 2L,
            ),
        )
        val retiredClaimed = dao.claimBatch(
            now = 1L,
            limit = 10,
            leaseToken = "retired-failed-analysis-lease",
            leaseDurationMs = 10_000L,
            scope = retiredScope,
            includeUserTasks = false,
            admittedScanId = scanId,
            admitAnyScan = false,
        )
        assertEquals(listOf("/retired-failed-analysis.jpg"), retiredClaimed.map { it.filePath })
        assertEquals(
            1,
            dao.markFailed(
                taskIds = retiredClaimed.map { it.taskId },
                leaseToken = "retired-failed-analysis-lease",
                error = "retired_analysis_failed",
                nextRetryAt = 2L,
                maxAttempts = 1,
                now = 2L,
            ),
        )
        assertEquals(2, dao.countFailed())

        assertEquals(
            1,
            dao.retryFailedAsUserForScopes(
                scopes = listOf(AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE),
                priority = AnalysisTaskEntity.PRIORITY_USER,
                now = 3L,
            ),
        )
        assertEquals(1, dao.countFailed())
        assertEquals(1, dao.countFailedForScopes(listOf(retiredScope)))
        assertEquals(1, dao.countActiveUserTasks())
        val user = dao.claimBatch(
            now = 3L,
            limit = 10,
            leaseToken = "retried-analysis-lease",
            leaseDurationMs = 10_000L,
            includeUserTasks = true,
            admittedScanId = null,
            admitAnyScan = false,
        )
        assertEquals(listOf("/failed-analysis.jpg"), user.map { it.filePath })
        assertNull(user.single().scanId)
        assertEquals(AnalysisTaskEntity.PRIORITY_USER, user.single().priority)
        assertEquals(1, dao.markDone(user.map { it.taskId }, "retried-analysis-lease", 4L))
    }

    private suspend fun admitAutomaticThumbnailRun(scanId: String, generation: Long) {
        val pipelineDao = database.libraryPipelineDao()
        pipelineDao.ensureInitialized(true, true, generation)
        check(
            pipelineDao.transition(
                from = setOf(LibraryPipelineStage.READY, LibraryPipelineStage.NEEDS_REBUILD),
                to = LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
                activeRunId = scanId,
                candidateGeneration = generation,
            ),
        )
    }

    private fun media(path: String, modifiedAtMs: Long, fileSize: Long) = MediaEntity(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        mediaType = MediaType.IMAGE,
        capturedAtMs = modifiedAtMs,
        modifiedAtMs = modifiedAtMs,
        parentPath = "/",
        fileSize = fileSize,
    )
}
