package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotDirectoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlbumSnapshotDaoTest {
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
    fun patchReplacesOnlyAffectedDirectoriesAndPublishesOneMeta() = runBlocking {
        val dao = database.albumSnapshotDao()
        dao.replaceSnapshot(
            entries = listOf(entry("/keep", 1, 10L, 100L), entry("/changed", 2, 20L, 100L)),
            version = 100L,
            updatedAtMs = 100L,
        )

        dao.patchSnapshot(
            affectedDirectoryPaths = listOf("/changed", "/changed"),
            entries = listOf(entry("/changed", 5, 50L, 200L)),
            version = 200L,
            updatedAtMs = 201L,
        )

        val directories = dao.getDirectories().associateBy { it.directoryPath }
        assertEquals(2, directories.size)
        assertEquals(1, directories["/keep"]?.mediaCount)
        assertEquals(5, directories["/changed"]?.mediaCount)
        assertEquals(100L, directories["/keep"]?.snapshotVersion)
        assertEquals(200L, directories["/changed"]?.snapshotVersion)
        assertEquals(200L, dao.getMeta()?.activeVersion)
        assertEquals(201L, dao.getMeta()?.updatedAtMs)
    }

    @Test
    fun patchWithEmptyEntriesRemovesDirectoryAndCanPublishEmptySnapshot() = runBlocking {
        val dao = database.albumSnapshotDao()
        dao.replaceSnapshot(
            entries = listOf(entry("/emptying", 1, 10L, 1L), entry("/keep", 1, 20L, 1L)),
            version = 1L,
            updatedAtMs = 1L,
        )

        dao.patchSnapshot(
            affectedDirectoryPaths = listOf("/emptying"),
            entries = emptyList(),
            version = 2L,
            updatedAtMs = 3L,
        )

        assertNull(dao.getDirectories().singleOrNull { it.directoryPath == "/emptying" })
        assertTrue(dao.getDirectories().any { it.directoryPath == "/keep" })
        assertEquals(2L, dao.getMeta()?.activeVersion)
        assertEquals(3L, dao.getMeta()?.updatedAtMs)
    }

    @Test
    fun patchWithNoAffectedDirectoriesLeavesDirectoriesByteForByteEquivalent() = runBlocking {
        val dao = database.albumSnapshotDao()
        dao.replaceSnapshot(
            entries = listOf(entry("/keep", 3, 30L, 7L)),
            version = 7L,
            updatedAtMs = 8L,
        )
        val before = dao.getDirectories()

        dao.patchSnapshot(
            affectedDirectoryPaths = emptyList(),
            entries = emptyList(),
            version = 9L,
            updatedAtMs = 10L,
        )

        assertEquals(before, dao.getDirectories())
        assertEquals(9L, dao.getMeta()?.activeVersion)
        assertEquals(10L, dao.getMeta()?.updatedAtMs)
    }

    private fun entry(
        path: String,
        count: Int,
        capturedAtMs: Long,
        version: Long,
    ) = AlbumSnapshotDirectoryEntity(
        directoryPath = path,
        mediaCount = count,
        latestCapturedAtMs = capturedAtMs,
        coverThumbnailPath = "$path/thumb.jpg",
        coverFilePath = "$path/cover.jpg",
        snapshotVersion = version,
    )
}
