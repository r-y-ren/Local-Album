package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
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
        dao.insert(ScanRunEntity("scan-3", 3L, ScanRunEntity.TYPE_FULL))

        assertEquals(1, dao.abortStaleRunning(30L))
        val stored = dao.getById("scan-3")
        assertEquals(ScanRunEntity.STATUS_ABORTED, stored?.status)
        assertEquals("process_restarted", stored?.errorType)
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
}
