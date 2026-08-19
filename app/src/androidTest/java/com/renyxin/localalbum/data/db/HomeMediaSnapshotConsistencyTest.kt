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
    fun coordinatorRecoversACommittedCoreRunOnlyOncePerProcess() = runBlocking {
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

    private fun cacheFile(prefix: String): File =
        File.createTempFile(prefix, ".webp", context.cacheDir).also { file ->
            file.writeBytes(byteArrayOf(1, 2, 3))
            createdFiles += file
        }
}
