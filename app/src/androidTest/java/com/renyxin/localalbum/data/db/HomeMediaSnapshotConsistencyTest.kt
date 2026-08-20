package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.ThumbnailRepairPolicy
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.HomeMediaSnapshotEntity
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.repo.LibraryPipelineCoordinator
import com.renyxin.localalbum.data.repo.MediaDeletionCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeMediaSnapshotConsistencyTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        createdFiles.forEach(File::delete)
    }

    @Test
    fun publicationAcceptsOnlyReadyGridForCurrentSourceVersion() = runBlocking {
        val currentPath = "/private/current.jpg"
        val stalePath = "/private/stale.jpg"
        val currentGrid = cacheFile("current-grid")
        val staleGrid = cacheFile("stale-grid")

        db.mediaDao().insertAll(
            listOf(
                media(currentPath, thumbnailPath = currentGrid.absolutePath),
                media(stalePath, thumbnailPath = staleGrid.absolutePath),
            ),
        )
        db.thumbnailCacheDao().upsert(cache(currentPath, currentGrid, sourceVersion = "10:20"))
        db.thumbnailCacheDao().upsert(cache(stalePath, staleGrid, sourceVersion = "9:20"))

        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 7L)

        val current = requireNotNull(db.homeMediaSnapshotDao().getByPath(currentPath))
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_READY, current.thumbnailState)
        assertEquals(currentGrid.absolutePath, current.thumbnailPath)

        val stale = requireNotNull(db.homeMediaSnapshotDao().getByPath(stalePath))
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_PLACEHOLDER, stale.thumbnailState)
        assertNull(stale.thumbnailPath)
    }

    @Test
    fun publicationInvalidatesExistingPagingSourceAndMakesNewSnapshotVisible() = runBlocking {
        val dao = db.homeMediaSnapshotDao()
        val source = dao.pagingSource()
        assertTrue(
            source.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 20,
                    placeholdersEnabled = false,
                ),
            ) is PagingSource.LoadResult.Page,
        )
        assertFalse(source.invalid)

        val path = "/private/paging-publication.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        dao.replaceFromCanonical(generation = 12L)

        assertTrue(source.invalid)
        val fresh = dao.pagingSource().load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page
        assertEquals(listOf(path), fresh.data.map { it.filePath })
    }

    @Test
    fun explicitRestoreCannotOpenInitialHomeGate() = runBlocking {
        val path = "/private/restore.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null, isTrashed = true)))
        db.mediaDao().restoreFromTrash(listOf(path))

        db.homeMediaSnapshotDao().restoreFromCanonical(listOf(path))
        assertNull(db.homeMediaSnapshotDao().getByPath(path))

        db.libraryPipelineDao().ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 11L,
        )
        db.homeMediaSnapshotDao().restoreFromCanonical(listOf(path))

        val restored = requireNotNull(db.homeMediaSnapshotDao().getByPath(path))
        assertEquals(11L, restored.publishedGeneration)
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_PLACEHOLDER, restored.thumbnailState)
    }

    @Test
    fun explicitFavoriteTrashAndRestoreKeepCanonicalAndHomeAtomic() = runBlocking {
        val path = "/private/user-operation.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 13L)
        db.libraryPipelineDao().ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 13L,
        )

        db.withTransaction {
            db.mediaDao().setFavorite(path, true)
            db.homeMediaSnapshotDao().setFavorite(path, true)
        }
        assertTrue(requireNotNull(db.mediaDao().getByFilePathLight(path)).isFavorite)
        assertTrue(requireNotNull(db.homeMediaSnapshotDao().getByPath(path)).isFavorite)

        db.withTransaction {
            db.mediaDao().moveToTrash(listOf(path), deletedAtMs = 1_000L)
            db.homeMediaSnapshotDao().deleteByPaths(listOf(path))
        }
        assertTrue(requireNotNull(db.mediaDao().getByFilePathLight(path)).isTrashed)
        assertNull(db.homeMediaSnapshotDao().getByPath(path))

        db.withTransaction {
            db.mediaDao().restoreFromTrash(listOf(path))
            db.homeMediaSnapshotDao().restoreFromCanonical(listOf(path))
        }
        assertFalse(requireNotNull(db.mediaDao().getByFilePathLight(path)).isTrashed)
        assertTrue(requireNotNull(db.homeMediaSnapshotDao().getByPath(path)).isFavorite)
    }

    @Test
    fun firstScanAdmissionRequiresRequestAndConsumesItOnlyWhenRunBinds() = runBlocking {
        val dao = db.libraryPipelineDao()
        dao.ensureInitialized(
            hasCanonicalMedia = false,
            hasPublishedSnapshot = false,
            publishedGeneration = 0L,
        )

        assertEquals(0, dao.startRequestedRebuild())
        assertEquals(LibraryPipelineStage.UNINITIALIZED.name, dao.get()?.stage)

        dao.requestRebuild("user_requested_initial_scan")
        assertEquals(1, dao.startRequestedRebuild())
        val admitted = requireNotNull(dao.get())
        assertEquals(LibraryPipelineStage.INITIAL_SCAN.name, admitted.stage)
        assertTrue(admitted.rebuildRequested)
        assertNull(admitted.activeRunId)

        val run = ScanRunEntity(
            scanId = "first-requested-run",
            generation = 101L,
            scanType = ScanRunEntity.TYPE_FULL,
        )
        db.scanRunDao().insert(run)
        assertEquals(
            1,
            dao.bindScanRun(
                stage = LibraryPipelineStage.INITIAL_SCAN.name,
                scanId = run.scanId,
                generation = run.generation,
            ),
        )
        val bound = requireNotNull(dao.get())
        assertEquals(run.scanId, bound.activeRunId)
        assertFalse(bound.rebuildRequested)
    }

    @Test
    fun rebuildRequirementWaitsForExplicitUserRequest() = runBlocking {
        val path = "/private/rebuild-advisory.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 15L)
        val dao = db.libraryPipelineDao()
        dao.ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 15L,
        )

        dao.requireRebuild("scan_scope_changed")

        val required = requireNotNull(dao.get())
        assertEquals(LibraryPipelineStage.NEEDS_REBUILD.name, required.stage)
        assertTrue(required.rebuildRequired)
        assertFalse(required.rebuildRequested)
        assertEquals(0, dao.startRequestedRebuild())
        assertEquals(LibraryPipelineStage.NEEDS_REBUILD.name, dao.get()?.stage)

        dao.requestRebuild("user_requested")
        assertEquals(1, dao.startRequestedRebuild())

        val admitted = requireNotNull(dao.get())
        assertEquals(LibraryPipelineStage.REBUILD_SCAN.name, admitted.stage)
        assertFalse(admitted.rebuildRequired)
        assertTrue(admitted.rebuildRequested)
        assertNull(admitted.activeRunId)
    }

    @Test
    fun preciseJournalStillRunsIncrementallyWhileRebuildAdvisoryRemainsPending() = runBlocking {
        val path = "/private/precise-with-advisory.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 16L)
        val pipelineDao = db.libraryPipelineDao()
        pipelineDao.ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 16L,
        )
        val changeDao = db.mediaChangeDao()
        changeDao.record(
            MediaChangeEventEntity(
                eventKey = "test:reconciliation",
                profileId = "test",
                eventType = MediaChangeEventEntity.TYPE_RECONCILIATION,
                firstObservedAtMs = 1L,
                observedAtMs = 1L,
            ),
        )
        changeDao.record(
            MediaChangeEventEntity(
                eventKey = "test:external:IMAGE:41",
                profileId = "test",
                eventType = MediaChangeEventEntity.TYPE_MEDIA,
                volumeName = "external",
                mediaType = MediaType.IMAGE.name,
                mediaStoreId = 41L,
                contentUri = "content://media/external/images/media/41",
                firstObservedAtMs = 2L,
                observedAtMs = 2L,
            ),
        )
        val coordinator = LibraryPipelineCoordinator(
            context = context,
            database = db,
            indexer = HybridIndexer(
                context = context,
                profileId = "test",
                mediaDao = db.mediaDao(),
                database = db,
                mediaChangeDao = changeDao,
            ),
            analysisPipeline = { PluginAnalysisPipeline(emptyList()) },
        )

        assertTrue(coordinator.startQueuedWorkIfIdle())

        val admitted = requireNotNull(pipelineDao.get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_SCAN.name, admitted.stage)
        assertTrue(admitted.rebuildRequired)
        assertFalse(admitted.rebuildRequested)
        assertEquals("ambiguous_media_store_change", admitted.rebuildReason)
        assertFalse(changeDao.hasReconciliationHint("test"))
        assertTrue(changeDao.hasOutstandingMediaChanges("test"))

        assertTrue(
            pipelineDao.transition(
                from = setOf(LibraryPipelineStage.INCREMENTAL_SCAN),
                to = LibraryPipelineStage.INCREMENTAL_ANALYSIS,
                activeRunId = "precise-incremental",
                candidateGeneration = 17L,
            ),
        )
        assertEquals(
            1,
            pipelineDao.finishAnalysis(
                scanId = "precise-incremental",
                generation = 17L,
                failures = 0,
            ),
        )
        val finished = requireNotNull(pipelineDao.get())
        assertEquals(LibraryPipelineStage.NEEDS_REBUILD.name, finished.stage)
        assertTrue(finished.rebuildRequired)
        assertFalse(finished.rebuildRequested)
    }

    @Test
    fun explicitRebuildRequestCannotPreemptBusyAnalysis() = runBlocking {
        val path = "/private/busy-analysis.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 16L)
        val dao = db.libraryPipelineDao()
        dao.ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 16L,
        )
        assertTrue(
            dao.transition(
                from = setOf(LibraryPipelineStage.READY),
                to = LibraryPipelineStage.INCREMENTAL_ANALYSIS,
                activeRunId = "active-analysis",
                candidateGeneration = 17L,
            ),
        )

        dao.requireRebuild("scan_root_added")
        dao.requestRebuild("user_requested")

        val busy = requireNotNull(dao.get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_ANALYSIS.name, busy.stage)
        assertEquals("active-analysis", busy.activeRunId)
        assertTrue(busy.rebuildRequired)
        assertTrue(busy.rebuildRequested)
        assertEquals(0, dao.startRequestedRebuild())

        assertEquals(
            1,
            dao.finishAnalysis(
                scanId = "active-analysis",
                generation = 17L,
                failures = 0,
            ),
        )
        assertEquals(LibraryPipelineStage.READY.name, dao.get()?.stage)
        assertEquals(1, dao.startRequestedRebuild())
        assertEquals(LibraryPipelineStage.REBUILD_SCAN.name, dao.get()?.stage)
        assertTrue(dao.get()?.rebuildRequested == true)
    }

    @Test
    fun thumbnailProgressUsesExactShrinkingGateWhileAnalysisProgressRemainsMonotonic() = runBlocking {
        val dao = db.libraryPipelineDao()
        dao.ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 30L,
        )
        assertTrue(
            dao.transition(
                from = setOf(LibraryPipelineStage.READY),
                to = LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
                activeRunId = "shrink-run",
                candidateGeneration = 31L,
            ),
        )
        assertEquals(
            1,
            dao.updateThumbnailProgressExact(
                stage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                scanId = "shrink-run",
                generation = 31L,
                completed = 7,
                total = 10,
                failures = 2,
            ),
        )
        assertEquals(
            1,
            dao.updateThumbnailProgressExact(
                stage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                scanId = "shrink-run",
                generation = 31L,
                completed = 5,
                total = 6,
                failures = 1,
            ),
        )
        val shrunk = requireNotNull(dao.get())
        assertEquals(5, shrunk.progressCompleted)
        assertEquals(6, shrunk.progressTotal)
        assertEquals(1, shrunk.failureCount)

        assertEquals(
            1,
            dao.transitionActiveRun(
                fromStage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                toStage = LibraryPipelineStage.INCREMENTAL_ANALYSIS.name,
                scanId = "shrink-run",
                generation = 31L,
            ),
        )
        assertEquals(
            1,
            dao.updateProgress(
                stage = LibraryPipelineStage.INCREMENTAL_ANALYSIS.name,
                scanId = "shrink-run",
                generation = 31L,
                completed = 8,
                total = 12,
                failures = 3,
            ),
        )
        assertEquals(
            1,
            dao.updateProgress(
                stage = LibraryPipelineStage.INCREMENTAL_ANALYSIS.name,
                scanId = "shrink-run",
                generation = 31L,
                completed = 4,
                total = 5,
                failures = 1,
            ),
        )
        val monotonic = requireNotNull(dao.get())
        assertEquals(8, monotonic.progressCompleted)
        assertEquals(12, monotonic.progressTotal)
        assertEquals(3, monotonic.failureCount)
    }

    @Test
    fun delayedWorkerIdentityCannotAdvanceTheCurrentRun() = runBlocking {
        val path = "/private/identity-gate.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 21L)
        val dao = db.libraryPipelineDao()
        dao.ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 21L,
        )
        assertTrue(
            dao.transition(
                from = setOf(LibraryPipelineStage.READY),
                to = LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
                activeRunId = "current-run",
                candidateGeneration = 22L,
            ),
        )

        assertEquals(
            0,
            dao.updateProgress(
                stage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                scanId = "stale-run",
                generation = 22L,
                completed = 9,
                total = 9,
                failures = 0,
            ),
        )
        assertEquals(
            0,
            dao.transitionActiveRun(
                fromStage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                toStage = LibraryPipelineStage.INCREMENTAL_PUBLISH.name,
                scanId = "current-run",
                generation = 999L,
            ),
        )
        val stillThumbnail = requireNotNull(dao.get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, stillThumbnail.stage)
        assertEquals(0, stillThumbnail.progressTotal)

        assertEquals(
            1,
            dao.transitionActiveRun(
                fromStage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name,
                toStage = LibraryPipelineStage.INCREMENTAL_PUBLISH.name,
                scanId = "current-run",
                generation = 22L,
            ),
        )
        assertEquals(
            0,
            dao.publishAndEnterAnalysis(
                publishStage = LibraryPipelineStage.INCREMENTAL_PUBLISH.name,
                analysisStage = LibraryPipelineStage.INCREMENTAL_ANALYSIS.name,
                activeRunId = "stale-run",
                generation = 22L,
            ),
        )
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, dao.get()?.stage)
        assertEquals(
            1,
            dao.publishAndEnterAnalysis(
                publishStage = LibraryPipelineStage.INCREMENTAL_PUBLISH.name,
                analysisStage = LibraryPipelineStage.INCREMENTAL_ANALYSIS.name,
                activeRunId = "current-run",
                generation = 22L,
            ),
        )
        assertEquals(
            0,
            dao.finishAnalysis(
                scanId = "stale-run",
                generation = 22L,
                failures = 0,
            ),
        )
        assertEquals(LibraryPipelineStage.INCREMENTAL_ANALYSIS.name, dao.get()?.stage)
        assertEquals(
            1,
            dao.finishAnalysis(
                scanId = "current-run",
                generation = 22L,
                failures = 0,
            ),
        )
        assertEquals(LibraryPipelineStage.READY.name, dao.get()?.stage)
    }

    @Test
    fun emptyLibraryDoesNotCreateThumbnailRepair() = runBlocking {
        val coordinator = coordinator()

        val state = coordinator.ensureState()

        assertEquals(LibraryPipelineStage.UNINITIALIZED.name, state.stage)
        assertNull(state.activeRunId)
        assertNull(db.scanRunDao().getLatest())
        assertEquals(0, db.thumbnailTaskDao().countActive())
    }

    @Test
    fun coreSeedWithEmptyGateConvergesThroughPublishToReady() = runBlocking {
        val pipelineDao = db.libraryPipelineDao()
        pipelineDao.ensureInitialized(
            hasCanonicalMedia = false,
            hasPublishedSnapshot = false,
            publishedGeneration = 0L,
        )
        assertTrue(
            pipelineDao.transition(
                from = setOf(LibraryPipelineStage.UNINITIALIZED),
                to = LibraryPipelineStage.INITIAL_SCAN,
            ),
        )
        val run = ScanRunEntity(
            scanId = "core-empty-thumbnail-gate",
            generation = 61L,
            scanType = ScanRunEntity.TYPE_FULL,
            mediaStoreCompleted = true,
            fileSystemCompleted = true,
            coreScanState = CoreScanState.PUBLISHING.name,
        )
        db.scanRunDao().insert(run)
        assertEquals(
            1,
            pipelineDao.bindScanRun(
                stage = LibraryPipelineStage.INITIAL_SCAN.name,
                scanId = run.scanId,
                generation = run.generation,
            ),
        )

        val coordinator = coordinator()
        coordinator.onCoreScanCompleted(
            scanId = run.scanId,
            generation = run.generation,
            changedCount = 0,
        )

        val publishing = requireNotNull(pipelineDao.get())
        assertEquals(LibraryPipelineStage.INITIAL_PUBLISH.name, publishing.stage)
        assertEquals(run.scanId, publishing.activeRunId)
        assertEquals(0, publishing.progressCompleted)
        assertEquals(0, publishing.progressTotal)
        assertEquals(0, db.thumbnailTaskDao().thumbnailGateSnapshot(run.scanId).total)

        coordinator.publishCandidateAndAdvance()

        val ready = requireNotNull(pipelineDao.get())
        assertEquals(LibraryPipelineStage.READY.name, ready.stage)
        assertTrue(ready.hasPublishedBaseline)
        assertEquals(61L, ready.publishedGeneration)
        assertNull(ready.activeRunId)
        assertEquals(
            EnhancementState.COMPLETED.name,
            requireNotNull(db.scanRunDao().getById(run.scanId)).enhancementState,
        )
        assertEquals(0, db.analysisTaskDao().countActive())
    }

    @Test
    fun coreSeedWithAllDoneGatePublishesWithoutReopeningAutomaticWork() = runBlocking {
        val path = "/private/core-all-done-gate.jpg"
        val grid = cacheFile("core-all-done-grid")
        val sourceVersion = SourceVersionCodec.encode(10L, 20L, null)
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        val taskDao = db.thumbnailTaskDao()
        assertEquals(
            1,
            taskDao.enqueueInteractive(
                listOf(
                    com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity(
                        filePath = path,
                        sourceVersion = sourceVersion,
                        mediaType = MediaType.IMAGE.name,
                        priority = ThumbnailSpec.PRIORITY_VISIBLE,
                    ),
                ),
            ).accepted,
        )
        val existing = taskDao.claimInteractiveBatch(
            now = 1L,
            limit = 1,
            leaseToken = "core-all-done-owner",
            leaseDurationMs = 10_000L,
        ).single()
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            taskDao.publishGeneratedThumbnail(
                task = existing,
                leaseToken = "core-all-done-owner",
                cacheEntry = cache(path, grid, sourceVersion),
                now = 2L,
            ),
        )

        val pipelineDao = db.libraryPipelineDao()
        pipelineDao.ensureInitialized(
            hasCanonicalMedia = false,
            hasPublishedSnapshot = false,
            publishedGeneration = 0L,
        )
        assertTrue(
            pipelineDao.transition(
                from = setOf(LibraryPipelineStage.UNINITIALIZED),
                to = LibraryPipelineStage.INITIAL_SCAN,
            ),
        )
        val run = ScanRunEntity(
            scanId = "core-all-done-thumbnail-gate",
            generation = 63L,
            scanType = ScanRunEntity.TYPE_FULL,
            mediaStoreCompleted = true,
            fileSystemCompleted = true,
            coreScanState = CoreScanState.PUBLISHING.name,
        )
        db.scanRunDao().insert(run)
        assertEquals(
            1,
            pipelineDao.bindScanRun(
                stage = LibraryPipelineStage.INITIAL_SCAN.name,
                scanId = run.scanId,
                generation = run.generation,
            ),
        )

        coordinator().onCoreScanCompleted(run.scanId, run.generation, changedCount = 0)

        val publishing = requireNotNull(pipelineDao.get())
        val gate = taskDao.thumbnailGateSnapshot(run.scanId)
        assertEquals(LibraryPipelineStage.INITIAL_PUBLISH.name, publishing.stage)
        assertEquals(1, publishing.progressCompleted)
        assertEquals(1, publishing.progressTotal)
        assertEquals(1, gate.total)
        assertEquals(1, gate.terminal)
        assertEquals(0, gate.remaining)
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity.STATUS_DONE,
            requireNotNull(taskDao.getById(existing.taskId)).status,
        )
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_QUIESCENT,
            taskDao.laneState(
                com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID,
            )?.deliveryState,
        )
    }

    @Test
    fun repairGateThatShrinksToZeroPublishesWithoutAnalysis() = runBlocking {
        val path = "/private/repair-shrinking-to-zero.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 62L)
        db.libraryPipelineDao().ensureInitialized(true, true, 62L)
        val coordinator = coordinator()

        assertTrue(coordinator.startQueuedWorkIfIdle())
        val admitted = requireNotNull(db.libraryPipelineDao().get())
        val scanId = requireNotNull(admitted.activeRunId)
        assertEquals(1, admitted.progressTotal)
        assertEquals(1, db.thumbnailTaskDao().thumbnailGateSnapshot(scanId).total)

        // The seed transaction has committed. A concurrent media deletion can shrink the exact gate
        // before the first worker callback; convergence must publish the now-empty target set.
        db.mediaDao().moveToTrash(listOf(path), deletedAtMs = 63L)
        coordinator.onThumbnailQueueChanged(scanId)

        val publishing = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, publishing.stage)
        assertEquals(0, publishing.progressCompleted)
        assertEquals(0, publishing.progressTotal)
        coordinator.publishCandidateAndAdvance()

        val finished = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.READY.name, finished.stage)
        assertNull(finished.activeRunId)
        assertEquals(62L, finished.publishedGeneration)
        assertEquals(0, db.analysisTaskDao().countActive())
        assertEquals(
            EnhancementState.COMPLETED.name,
            requireNotNull(db.scanRunDao().getById(scanId)).enhancementState,
        )
    }

    @Test
    fun outstandingMediaJournalIsAdmittedBeforeThumbnailRepair() = runBlocking {
        val path = "/private/repair-blocked-by-journal.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 40L)
        db.libraryPipelineDao().ensureInitialized(true, true, 40L)
        db.mediaChangeDao().record(
            MediaChangeEventEntity(
                eventKey = "test:external:IMAGE:40",
                profileId = "test",
                eventType = MediaChangeEventEntity.TYPE_MEDIA,
                volumeName = "external",
                mediaType = MediaType.IMAGE.name,
                mediaStoreId = 40L,
                contentUri = "content://media/external/images/media/40",
                firstObservedAtMs = 1L,
                observedAtMs = 1L,
            ),
        )
        val coordinator = coordinator()

        assertTrue(coordinator.startQueuedWorkIfIdle())
        val state = requireNotNull(db.libraryPipelineDao().get())

        assertEquals(LibraryPipelineStage.INCREMENTAL_SCAN.name, state.stage)
        assertNull(state.activeRunId)
        assertNull(db.scanRunDao().getLatest())
        assertEquals(0, db.thumbnailTaskDao().countActive())
    }

    @Test
    fun placeholderBaselineCreatesOneDeterministicGridRepairAndRepeatedEnsureIsIdempotent() = runBlocking {
        val path = "/private/repair-placeholder.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 41L)
        db.libraryPipelineDao().ensureInitialized(
            hasCanonicalMedia = true,
            hasPublishedSnapshot = true,
            publishedGeneration = 41L,
        )
        val coordinator = coordinator()
        val expectedRunId = ThumbnailRepairPolicy.repairRunId(
            publishedGeneration = 41L,
            formatVersion = ThumbnailIdentity.CURRENT_FORMAT_VERSION,
        )

        assertTrue(coordinator.startQueuedWorkIfIdle())
        val first = requireNotNull(db.libraryPipelineDao().get())
        coordinator.wake()
        coordinator.wake()
        assertFalse(coordinator.startQueuedWorkIfIdle())
        val second = coordinator.ensureState()

        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, first.stage)
        assertEquals(expectedRunId, first.activeRunId)
        assertEquals(expectedRunId, second.activeRunId)
        assertEquals(first.candidateGeneration, second.candidateGeneration)
        assertEquals(1, scanRunCount())
        assertEquals(
            ScanRunEntity.TYPE_THUMBNAIL_REPAIR,
            db.scanRunDao().getById(expectedRunId)?.scanType,
        )
        assertEquals(1, first.progressTotal)
        assertEquals(0, first.progressCompleted)
        assertEquals(0, first.failureCount)
        val task = requireNotNull(
            db.thumbnailTaskDao().getByIdentityExact(
                path,
                MediaType.IMAGE.name,
                SourceVersionCodec.encode(10L, 20L, null),
                ThumbnailSpec.SIZE_GRID,
                ThumbnailIdentity.CURRENT_FORMAT_VERSION,
            ),
        )
        assertEquals(expectedRunId, task.scanId)
    }

    @Test
    fun thumbnailRepairSuccessPublishesReadyWithoutChangingMediaGenerationOrStartingAnalysis() = runBlocking {
        val path = "/private/repair-success.jpg"
        val grid = cacheFile("repair-success-grid")
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 42L)
        db.libraryPipelineDao().ensureInitialized(true, true, 42L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val admitted = requireNotNull(db.libraryPipelineDao().get())
        val scanId = requireNotNull(admitted.activeRunId)
        val task = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "repair-success-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        val timestamp = System.currentTimeMillis()
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = task,
                leaseToken = "repair-success-owner",
                cacheEntry = cache(
                    path = path,
                    file = grid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = timestamp,
            ),
        )

        coordinator.onThumbnailProgressChanged(scanId)
        val terminalThumbnail = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, terminalThumbnail.stage)
        assertEquals(1, terminalThumbnail.progressCompleted)
        assertEquals(1, terminalThumbnail.progressTotal)
        coordinator.onThumbnailQueueChanged(scanId)
        val publish = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, publish.stage)
        assertEquals(1, publish.progressCompleted)
        assertEquals(1, publish.progressTotal)
        assertEquals(
            1,
            db.scanRunDao().markEnhancementTerminal(
                scanId = scanId,
                state = EnhancementState.COMPLETED.name,
                now = timestamp + 1L,
            ),
        )
        coordinator.publishCandidateAndAdvance()

        val finished = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.READY.name, finished.stage)
        assertEquals(42L, finished.publishedGeneration)
        assertNull(finished.activeRunId)
        assertEquals(1, finished.progressCompleted)
        assertEquals(1, finished.progressTotal)
        assertEquals(0, finished.failureCount)
        assertEquals(
            EnhancementState.COMPLETED.name,
            requireNotNull(db.scanRunDao().getById(scanId)).enhancementState,
        )
        val home = requireNotNull(db.homeMediaSnapshotDao().getByPath(path))
        assertEquals(42L, home.publishedGeneration)
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_READY, home.thumbnailState)
        assertEquals(grid.absolutePath, home.thumbnailPath)
        assertEquals(0, db.analysisTaskDao().countActive())
    }

    @Test
    fun failedThumbnailRepairConvergesOnceAndSuppressesAutomaticReadmission() = runBlocking {
        val path = "/private/repair-failed.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 43L)
        db.libraryPipelineDao().ensureInitialized(true, true, 43L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val admitted = requireNotNull(db.libraryPipelineDao().get())
        val scanId = requireNotNull(admitted.activeRunId)
        val task = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "repair-failed-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        assertEquals(
            1,
            db.thumbnailTaskDao().markFailed(
                taskId = task.taskId,
                leaseToken = "repair-failed-owner",
                error = "decode_failed",
                nextRetryAt = 0L,
                maxAttempts = 1,
                now = System.currentTimeMillis(),
            ),
        )

        coordinator.onThumbnailQueueChanged(scanId)
        coordinator.publishCandidateAndAdvance()
        val repeated = coordinator.ensureState()
        val home = requireNotNull(db.homeMediaSnapshotDao().getByPath(path))

        assertEquals(LibraryPipelineStage.READY.name, repeated.stage)
        assertNull(repeated.activeRunId)
        assertEquals(43L, repeated.publishedGeneration)
        assertEquals(1, repeated.failureCount)
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_PLACEHOLDER, home.thumbnailState)
        assertNull(home.thumbnailPath)
        assertEquals(
            EnhancementState.COMPLETED_WITH_FAILURES.name,
            requireNotNull(db.scanRunDao().getById(scanId)).enhancementState,
        )
        assertEquals(scanId, ThumbnailRepairPolicy.repairRunId(43L, ThumbnailIdentity.CURRENT_FORMAT_VERSION))
        assertEquals(1, db.scanRunDao().getLatest()?.let { 1 } ?: 0)
    }

    @Test
    fun explicitFailedRetryReusesRepairRunAndRepublishesHomeProjection() = runBlocking {
        val path = "/private/repair-explicit-retry.jpg"
        val grid = cacheFile("repair-explicit-retry-grid")
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 44L)
        db.libraryPipelineDao().ensureInitialized(true, true, 44L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val admitted = requireNotNull(db.libraryPipelineDao().get())
        val scanId = requireNotNull(admitted.activeRunId)
        val failed = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "repair-explicit-failed-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        assertEquals(
            1,
            db.thumbnailTaskDao().markFailed(
                taskId = failed.taskId,
                leaseToken = "repair-explicit-failed-owner",
                error = "decode_failed",
                nextRetryAt = 0L,
                maxAttempts = 1,
                now = System.currentTimeMillis(),
            ),
        )
        coordinator.onThumbnailQueueChanged(scanId)
        coordinator.publishCandidateAndAdvance()
        assertEquals(
            EnhancementState.COMPLETED_WITH_FAILURES.name,
            requireNotNull(db.scanRunDao().getById(scanId)).enhancementState,
        )

        val retried = coordinator.retryFailedThumbnails()
        assertEquals(1, retried.retried)
        assertTrue(retried.admitted)
        val interactive = db.thumbnailTaskDao().claimInteractiveBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "repair-explicit-retry-owner",
            leaseDurationMs = 10_000L,
        ).single()
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = interactive,
                leaseToken = "repair-explicit-retry-owner",
                cacheEntry = cache(
                    path = path,
                    file = grid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = System.currentTimeMillis(),
            ),
        )

        coordinator.onInteractiveThumbnailQueueChanged()
        val republishing = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, republishing.stage)
        assertEquals(scanId, republishing.activeRunId)
        coordinator.publishCandidateAndAdvance()

        val finished = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.READY.name, finished.stage)
        assertEquals(44L, finished.publishedGeneration)
        assertNull(finished.activeRunId)
        assertEquals(
            EnhancementState.COMPLETED.name,
            requireNotNull(db.scanRunDao().getById(scanId)).enhancementState,
        )
        assertEquals(ScanRunEntity.TYPE_THUMBNAIL_REPAIR, db.scanRunDao().getById(scanId)?.scanType)
        val home = requireNotNull(db.homeMediaSnapshotDao().getByPath(path))
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_READY, home.thumbnailState)
        assertEquals(grid.absolutePath, home.thumbnailPath)
    }

    @Test
    fun terminalAndPendingThumbnailTargetsKeepStageUntilPendingCompletes() = runBlocking {
        val firstPath = "/private/mixed-terminal-first.jpg"
        val secondPath = "/private/mixed-terminal-second.jpg"
        db.mediaDao().insertAll(
            listOf(
                media(firstPath, thumbnailPath = null),
                media(secondPath, thumbnailPath = null),
            ),
        )
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 64L)
        db.libraryPipelineDao().ensureInitialized(true, true, 64L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val scanId = requireNotNull(db.libraryPipelineDao().get()?.activeRunId)
        val first = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "mixed-terminal-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        val remainingPath = if (first.filePath == firstPath) secondPath else firstPath
        assertEquals(1, db.thumbnailTaskDao().markDone(first.taskId, "mixed-terminal-owner", 65L))

        coordinator.onThumbnailProgressChanged(scanId)
        coordinator.onThumbnailQueueChanged(scanId)
        val mixed = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, mixed.stage)
        assertEquals(1, mixed.progressCompleted)
        assertEquals(2, mixed.progressTotal)
        assertEquals(1, db.thumbnailTaskDao().thumbnailGateSnapshot(scanId).remaining)

        val second = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 1,
            leaseToken = "mixed-terminal-owner-2",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        assertEquals(remainingPath, second.filePath)
        assertEquals(1, db.thumbnailTaskDao().markDone(second.taskId, "mixed-terminal-owner-2", 66L))
        coordinator.onThumbnailQueueChanged(scanId)

        val publishing = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, publishing.stage)
        assertEquals(2, publishing.progressCompleted)
        assertEquals(2, publishing.progressTotal)
    }

    @Test
    fun thumbnailProgressCommitsEachDoneBeforeGateTransitionAndRetainsFinalTotal() = runBlocking {
        val firstPath = "/private/repair-progress-first.jpg"
        val secondPath = "/private/repair-progress-second.jpg"
        val firstGrid = cacheFile("repair-progress-first-grid")
        val secondGrid = cacheFile("repair-progress-second-grid")
        db.mediaDao().insertAll(
            listOf(
                media(firstPath, thumbnailPath = null),
                media(secondPath, thumbnailPath = null),
            ),
        )
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 45L)
        db.libraryPipelineDao().ensureInitialized(true, true, 45L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val scanId = requireNotNull(db.libraryPipelineDao().get()?.activeRunId)
        val tasks = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 2,
            leaseToken = "repair-progress-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).associateBy { it.filePath }

        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = requireNotNull(tasks[firstPath]),
                leaseToken = "repair-progress-owner",
                cacheEntry = cache(
                    path = firstPath,
                    file = firstGrid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = System.currentTimeMillis(),
            ),
        )
        coordinator.onThumbnailProgressChanged(scanId)
        val partial = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, partial.stage)
        assertEquals(1, partial.progressCompleted)
        assertEquals(2, partial.progressTotal)
        assertEquals(0, partial.failureCount)

        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = requireNotNull(tasks[secondPath]),
                leaseToken = "repair-progress-owner",
                cacheEntry = cache(
                    path = secondPath,
                    file = secondGrid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = System.currentTimeMillis(),
            ),
        )
        coordinator.onThumbnailProgressChanged(scanId)
        val completeBeforeGate = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, completeBeforeGate.stage)
        assertEquals(2, completeBeforeGate.progressCompleted)
        assertEquals(2, completeBeforeGate.progressTotal)

        coordinator.onThumbnailQueueChanged(scanId)
        val publishing = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_PUBLISH.name, publishing.stage)
        assertEquals(2, publishing.progressCompleted)
        assertEquals(2, publishing.progressTotal)
        coordinator.publishCandidateAndAdvance()

        val finished = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.READY.name, finished.stage)
        assertEquals(2, finished.progressCompleted)
        assertEquals(2, finished.progressTotal)
    }

    @Test
    fun repairableGateReseedCommitsCorrectedProgressBeforeRetry() = runBlocking {
        val firstPath = "/private/repairable-progress-first.jpg"
        val secondPath = "/private/repairable-progress-second.jpg"
        val firstGrid = cacheFile("repairable-progress-first-grid")
        val secondGrid = cacheFile("repairable-progress-second-grid")
        db.mediaDao().insertAll(
            listOf(
                media(firstPath, thumbnailPath = null),
                media(secondPath, thumbnailPath = null),
            ),
        )
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 47L)
        db.libraryPipelineDao().ensureInitialized(true, true, 47L)
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val scanId = requireNotNull(db.libraryPipelineDao().get()?.activeRunId)
        val tasks = db.thumbnailTaskDao().claimAutomaticBatch(
            now = System.currentTimeMillis(),
            limit = 2,
            leaseToken = "repairable-progress-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).associateBy { it.filePath }

        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = requireNotNull(tasks[firstPath]),
                leaseToken = "repairable-progress-owner",
                cacheEntry = cache(
                    path = firstPath,
                    file = firstGrid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = System.currentTimeMillis(),
            ),
        )
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            db.thumbnailTaskDao().publishGeneratedThumbnail(
                task = requireNotNull(tasks[secondPath]),
                leaseToken = "repairable-progress-owner",
                cacheEntry = cache(
                    path = secondPath,
                    file = secondGrid,
                    sourceVersion = SourceVersionCodec.encode(10L, 20L, null),
                ),
                now = System.currentTimeMillis(),
            ),
        )
        coordinator.onThumbnailProgressChanged(scanId)
        val completed = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(2, completed.progressCompleted)
        assertEquals(2, completed.progressTotal)

        // Simulate a deleted task row after the last progress commit. The gate must repair it and
        // publish a corrected snapshot instead of leaving the durable projection at stale 2/2.
        db.thumbnailTaskDao().deleteByPaths(listOf(firstPath))
        assertEquals(1, db.thumbnailTaskDao().thumbnailGateSnapshot(scanId).repairable)
        coordinator.onThumbnailQueueChanged(scanId)

        val repaired = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, repaired.stage)
        assertEquals(1, repaired.progressCompleted)
        assertEquals(2, repaired.progressTotal)
        assertEquals(0, repaired.failureCount)
        assertEquals(0, db.thumbnailTaskDao().thumbnailGateSnapshot(scanId).repairable)
    }

    @Test
    fun journalDrainCompletionAutomaticallyAdmitsOneRepairWithoutAnotherWake() = runBlocking {
        val path = "/private/repair-after-journal-drain.jpg"
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = null)))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 46L)
        db.libraryPipelineDao().ensureInitialized(true, true, 46L)
        db.mediaChangeDao().record(
            MediaChangeEventEntity(
                eventKey = "test:external:IMAGE:46",
                profileId = "test",
                eventType = MediaChangeEventEntity.TYPE_MEDIA,
                volumeName = "external",
                mediaType = MediaType.IMAGE.name,
                mediaStoreId = 46L,
                contentUri = "content://media/external/images/media/46",
                firstObservedAtMs = 1L,
                observedAtMs = 1L,
            ),
        )
        val coordinator = coordinator()
        assertTrue(coordinator.startQueuedWorkIfIdle())
        val incremental = coordinator.getOrCreateAdmittedScanRun()
        assertEquals(1, db.mediaChangeDao().clearEvents("test"))
        assertEquals(1, db.scanRunDao().markMediaStoreCompleted(incremental.scanId))
        assertEquals(1, db.scanRunDao().markFileSystemCompleted(incremental.scanId))
        assertTrue(db.scanRunDao().markCoreReadyForSnapshot(incremental.scanId))

        coordinator.onCoreScanCompleted(
            scanId = incremental.scanId,
            generation = incremental.generation,
            changedCount = 0,
        )
        coordinator.onThumbnailProgressChanged(incremental.scanId)
        coordinator.onThumbnailQueueChanged(incremental.scanId)
        coordinator.publishCandidateAndAdvance()

        val repairId = ThumbnailRepairPolicy.repairRunId(
            publishedGeneration = incremental.generation,
            formatVersion = ThumbnailIdentity.CURRENT_FORMAT_VERSION,
        )
        val repaired = requireNotNull(db.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.INCREMENTAL_THUMBNAILS.name, repaired.stage)
        assertEquals(repairId, repaired.activeRunId)
        assertEquals(ScanRunEntity.TYPE_THUMBNAIL_REPAIR, db.scanRunDao().getById(repairId)?.scanType)
        assertEquals(1, repaired.progressTotal)
        assertFalse(coordinator.startQueuedWorkIfIdle())
        assertEquals(repairId, db.libraryPipelineDao().get()?.activeRunId)
    }

    @Test
    fun coordinatorRecoversACommittedCoreRunOnlyOncePerProcess() = runBlocking {
        db.mediaDao().insertAll(listOf(media("/private/recovered-core.jpg", thumbnailPath = null)))
        val pipelineDao = db.libraryPipelineDao()
        pipelineDao.ensureInitialized(
            hasCanonicalMedia = false,
            hasPublishedSnapshot = false,
            publishedGeneration = 0L,
        )
        assertTrue(
            pipelineDao.transition(
                from = setOf(LibraryPipelineStage.UNINITIALIZED),
                to = LibraryPipelineStage.INITIAL_SCAN,
            ),
        )
        val run = ScanRunEntity(
            scanId = "committed-core",
            generation = 31L,
            scanType = ScanRunEntity.TYPE_FULL,
            mediaStoreCompleted = true,
            fileSystemCompleted = true,
            coreScanState = CoreScanState.PUBLISHING.name,
        )
        db.scanRunDao().insert(run)
        assertEquals(
            1,
            pipelineDao.bindScanRun(
                stage = LibraryPipelineStage.INITIAL_SCAN.name,
                scanId = run.scanId,
                generation = run.generation,
            ),
        )
        val coordinator = LibraryPipelineCoordinator(
            context = context,
            database = db,
            indexer = HybridIndexer(
                context = context,
                profileId = "test",
                mediaDao = db.mediaDao(),
            ),
            analysisPipeline = { PluginAnalysisPipeline(emptyList()) },
        )

        val recovered = coordinator.ensureState()

        assertEquals(LibraryPipelineStage.INITIAL_THUMBNAILS.name, recovered.stage)
        assertEquals(run.scanId, recovered.activeRunId)
        assertEquals(run.generation, recovered.candidateGeneration)
        val recoveredRun = requireNotNull(db.scanRunDao().getById(run.scanId))
        assertEquals(CoreScanState.COMPLETED.name, recoveredRun.coreScanState)
        assertEquals(EnhancementState.QUEUED.name, recoveredRun.enhancementState)

        assertEquals(1, db.scanRunDao().markEnhancementRunning(run.scanId, now = 40L))
        coordinator.ensureState()

        assertEquals(
            EnhancementState.RUNNING.name,
            requireNotNull(db.scanRunDao().getById(run.scanId)).enhancementState,
        )
        assertEquals(LibraryPipelineStage.INITIAL_THUMBNAILS.name, pipelineDao.get()?.stage)
    }

    @Test
    fun scanCleanupPreservesPublishedSnapshotAndGridUntilNextPublication() = runBlocking {
        val path = "/private/scan-removed.jpg"
        val grid = cacheFile("published-grid")
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = grid.absolutePath)))
        db.thumbnailCacheDao().upsert(cache(path, grid, sourceVersion = "10:20"))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 17L)

        MediaDeletionCoordinator(db, profileId = "test").purge(listOf(path))

        assertNull(db.mediaDao().getByFilePathLight(path))
        assertNotNull(db.homeMediaSnapshotDao().getByPath(path))
        assertTrue(grid.exists())
        assertNotNull(db.thumbnailCacheDao().getReady(path, "grid", "10:20"))

        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 18L)
        assertNull(db.homeMediaSnapshotDao().getByPath(path))
    }

    @Test
    fun explicitPurgeRemovesPublishedSnapshotAndGridTogether() = runBlocking {
        val path = "/private/user-removed.jpg"
        val grid = cacheFile("user-grid")
        db.mediaDao().insertAll(listOf(media(path, thumbnailPath = grid.absolutePath)))
        db.thumbnailCacheDao().upsert(cache(path, grid, sourceVersion = "10:20"))
        db.homeMediaSnapshotDao().replaceFromCanonical(generation = 19L)

        MediaDeletionCoordinator(db, profileId = "test").purge(
            paths = listOf(path),
            removeFromHomeSnapshot = true,
        )

        assertNull(db.mediaDao().getByFilePathLight(path))
        assertNull(db.homeMediaSnapshotDao().getByPath(path))
        assertFalse(grid.exists())
        assertNull(db.thumbnailCacheDao().getReady(path, "grid", "10:20"))
    }

    private fun coordinator() = LibraryPipelineCoordinator(
        context = context,
        database = db,
        indexer = HybridIndexer(
            context = context,
            profileId = "test",
            mediaDao = db.mediaDao(),
            database = db,
            mediaChangeDao = db.mediaChangeDao(),
        ),
        analysisPipeline = { PluginAnalysisPipeline(emptyList()) },
    )

    private fun media(
        path: String,
        thumbnailPath: String?,
        isTrashed: Boolean = false,
    ) = MediaEntity(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        mediaType = MediaType.IMAGE,
        capturedAtMs = 100L,
        modifiedAtMs = 10L,
        parentPath = "/private",
        fileSize = 20L,
        isTrashed = isTrashed,
        deletedAtMs = if (isTrashed) 500L else 0L,
        thumbnailPath = thumbnailPath,
    )

    private fun cache(
        path: String,
        file: File,
        sourceVersion: String,
    ) = ThumbnailCacheEntryEntity(
        filePath = path,
        sizeClass = "grid",
        sourceVersion = sourceVersion,
        path = file.absolutePath,
        byteSize = file.length(),
        lastAccessAt = 1L,
        createdAt = 1L,
    )

    private fun scanRunCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM scan_runs").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun cacheFile(prefix: String): File =
        File.createTempFile(prefix, ".webp", context.cacheDir).also { file ->
            file.writeBytes(byteArrayOf(1, 2, 3))
            createdFiles += file
        }
}
