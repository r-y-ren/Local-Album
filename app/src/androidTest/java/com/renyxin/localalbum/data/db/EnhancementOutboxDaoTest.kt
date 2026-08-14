package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.pipeline.AnalysisPlanType
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.worker.EnhancementHandoffWorker
import com.renyxin.localalbum.edition.EditionConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnhancementOutboxDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun outboxCannotBeClaimedBeforeCoreSnapshotPublication() = runBlocking {
        val scanId = "scan-barrier"
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 1L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                coreScanState = CoreScanState.PUBLISHING.name,
                indexAvailability = IndexAvailability.PARTIAL.name,
            ),
        )
        database.enhancementOutboxDao().enqueueAll(listOf(entry(scanId)))

        assertTrue(database.enhancementOutboxDao().claimBatch(10L, 10, "early", 100L).isEmpty())
        database.openHelper.writableDatabase.execSQL(
            """UPDATE scan_runs SET status='COMPLETED', coreScanState='COMPLETED',
               indexAvailability='PUBLISHED', enhancementState='QUEUED' WHERE scanId=?""",
            arrayOf(scanId),
        )

        val claimed = database.enhancementOutboxDao().claimBatch(20L, 10, "released", 100L)
        assertEquals(listOf("/photo.jpg"), claimed.map { it.filePath })
    }

    @Test
    fun replayMergesSameSourceVersionAndExpiredLeaseRecovers() = runBlocking {
        val scanId = "scan-replay"
        insertCompletedScan(scanId)
        val dao = database.enhancementOutboxDao()
        dao.enqueueAll(listOf(entry(scanId, analysis = false)))
        dao.enqueueAll(listOf(entry(scanId, analysis = true)))

        assertEquals(1, dao.countActiveForScan(scanId))
        val first = dao.claimBatch(10L, 10, "lease-old", 10L)
        assertEquals(1, first.size)
        assertTrue(first.single().enqueueAnalysis)
        assertTrue(dao.claimBatch(15L, 10, "lease-live", 10L).isEmpty())
        val recovered = dao.claimBatch(21L, 10, "lease-new", 10L)
        assertEquals(1, recovered.size)
        assertEquals("/photo.jpg", recovered.single().filePath)
        assertEquals(1, dao.markDone(recovered.map { it.outboxId }, "lease-new", 22L))
        assertFalse(dao.hasRunnableEntries())
    }

    @Test
    fun newerCoreReplayTransfersInterruptedRunningOutboxOwnership() = runBlocking {
        val oldScanId = "scan-old-owner"
        val newScanId = "scan-new-owner"
        insertCompletedScan(oldScanId)
        insertCompletedScan(newScanId)
        val dao = database.enhancementOutboxDao()
        dao.enqueueAll(listOf(entry(oldScanId, analysis = false)))
        val interrupted = dao.claimBatch(10L, 10, "old-process", 10_000L)
        assertEquals(1, interrupted.size)

        dao.enqueueAll(
            listOf(
                entry(newScanId, analysis = true).copy(
                    enqueueThumbnail = true,
                    updatedAt = 11L,
                ),
            ),
        )

        assertEquals(0, dao.countActiveForScan(oldScanId))
        assertEquals(1, dao.countActiveForScan(newScanId))
        assertEquals(0, dao.markDone(interrupted.map { it.outboxId }, "old-process", 12L))
        val replayed = dao.claimBatch(13L, 10, "new-process", 10_000L)
        assertEquals(1, replayed.size)
        assertEquals(newScanId, replayed.single().scanId)
        assertTrue(replayed.single().enqueueAnalysis)
        assertTrue(replayed.single().enqueueThumbnail)
    }

    @Test
    fun compiledEditionHandoffPersistsIndependentPolicyAdmittedStageTasks() = runBlocking {
        val features = EditionConfiguration.features
        val admittedStageIds = features.scanFeaturePolicy
            .stageIds(AnalysisPlanType.ENHANCEMENT)
        val pipeline = PluginAnalysisPipeline(
            stages = admittedStageIds.map(::StubStage),
            pipelineScopeOverride = "pipeline:test|policy=${features.editionId}",
        )
        val scanId = "scan-${features.editionId}-stage-handoff"
        insertCompletedScan(scanId)
        val outboxDao = database.enhancementOutboxDao()
        outboxDao.enqueueAll(
            listOf(
                entry(scanId).copy(
                    profileId = features.editionId,
                    pipelineScope = pipeline.pipelineScope,
                ),
            ),
        )
        val claimed = outboxDao.claimBatch(
            now = 10L,
            limit = 10,
            leaseToken = "stage-handoff",
            leaseDurationMs = 1_000L,
        )
        assertEquals(1, claimed.size)

        val outcome = EnhancementHandoffWorker.seedClaimedEntries(
            database = database,
            pipeline = pipeline,
            entries = claimed,
            leaseToken = "stage-handoff",
            now = 11L,
        )

        assertEquals(admittedStageIds.isNotEmpty(), outcome.analysis)
        assertTrue(outcome.thumbnails)
        val persistedScopes = database.openHelper.writableDatabase.query(
            "SELECT pipelineScope FROM analysis_tasks WHERE scanId = ? ORDER BY taskId",
            arrayOf(scanId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val expectedScopes = admittedStageIds.map { stageId ->
            pipeline.stageTaskScopes.getValue(stageId)
        }
        assertEquals(expectedScopes, persistedScopes)
        assertEquals(expectedScopes.size, persistedScopes.toSet().size)
        assertEquals(
            admittedStageIds.toSet(),
            persistedScopes.flatMap { pipeline.requiredStageIdsForTaskScope(it) }.toSet(),
        )
        if (admittedStageIds.size > 1) {
            assertEquals(admittedStageIds.size, pipeline.stageTaskScopes.values.toSet().size)
        }
        if (features.editionId == "lite") {
            assertTrue(admittedStageIds.isEmpty())
            assertTrue(persistedScopes.isEmpty())
            assertTrue(
                persistedScopes.none { scope ->
                    listOf(
                        AnalysisStage.STAGE_FACE,
                        AnalysisStage.STAGE_SEMANTIC,
                        AnalysisStage.STAGE_OCR,
                    ).any(scope::contains)
                },
            )
        }
        assertEquals(1, database.thumbnailTaskDao().countActiveForScan(scanId))
        assertEquals(0, outboxDao.countActiveForScan(scanId))
        assertFalse(outboxDao.hasRunnableEntries())
        database.openHelper.writableDatabase.query(
            "SELECT status FROM enhancement_outbox WHERE scanId = ?",
            arrayOf(scanId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EnhancementOutboxEntity.STATUS_DONE, cursor.getString(0))
        }
        val scan = requireNotNull(database.scanRunDao().getById(scanId))
        assertEquals(CoreScanState.COMPLETED.name, scan.coreScanState)
        assertEquals(EnhancementState.QUEUED.name, scan.enhancementState)
    }

    @Test
    fun cancellationReleasesLiveLeaseImmediately() = runBlocking {
        val scanId = "scan-release"
        insertCompletedScan(scanId)
        val dao = database.enhancementOutboxDao()
        dao.enqueueAll(listOf(entry(scanId)))

        assertEquals(1, dao.claimBatch(10L, 10, "lease-cancelled", 10_000L).size)
        assertEquals(1, dao.releaseLease("lease-cancelled", 11L))
        assertEquals(
            1,
            dao.claimBatch(12L, 10, "lease-resumed", 10_000L).size,
        )
    }

    private suspend fun insertCompletedScan(scanId: String) {
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = 2L,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
    }

    private class StubStage(
        override val stageId: String,
    ) : AnalysisStage {
        override val stageType: StageType = StageType.BUILTIN
        override val displayName: String = stageId
        override val dependencies: List<String> = emptyList()

        override suspend fun execute(
            filePaths: List<String>,
            progressCallback: suspend (processed: Int, total: Int) -> Unit,
        ): StageResult = StageResult(filePaths.size, 0)
    }

    private fun entry(scanId: String, analysis: Boolean = true) = EnhancementOutboxEntity(
        scanId = scanId,
        profileId = "lite",
        filePath = "/photo.jpg",
        sourceVersion = "2:3",
        mediaType = "IMAGE",
        pipelineScope = "pipeline:v1|plan=enhancement|policy=lite@1|stage=core:quality|stage=core:scene",
        enqueueAnalysis = analysis,
        enqueueThumbnail = true,
        parentPath = "/",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
