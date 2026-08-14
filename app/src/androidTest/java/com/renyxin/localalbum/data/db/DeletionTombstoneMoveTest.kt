package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity
import com.renyxin.localalbum.data.repo.DeletionFailurePolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeletionTombstoneMoveTest {
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
    fun moveTransfersDeletionIntentAndResetsExecutionLease() = runBlocking {
        val dao = database.deletionTombstoneDao()
        val oldPath = "/DCIM/old.jpg"
        val newPath = "/Pictures/new.jpg"
        val oldKey = DeletionFailurePolicy.pathKey(oldPath)
        val newKey = DeletionFailurePolicy.pathKey(newPath)
        dao.upsert(
            DeletionTombstoneEntity(
                pathKey = oldKey,
                filePath = oldPath,
                operation = DeletionTombstoneEntity.OP_USER_PERMANENT,
                state = DeletionTombstoneEntity.STATE_LEASED,
                attemptCount = 4,
                nextRetryAt = 500L,
                leaseUntil = 900L,
                leaseToken = "old-lease",
                lastErrorType = "IOException",
                lastErrorCode = "EACCES",
                lastErrorMessage = "redacted",
                sourceVersion = "1:10",
                deletedAtMs = 100L,
                firstAttemptAt = 200L,
                lastAttemptAt = 300L,
                createdAt = 50L,
                updatedAt = 300L,
            ),
        )

        assertTrue(
            dao.moveToPath(
                oldPath = oldPath,
                newPath = newPath,
                newPathKey = newKey,
                sourceVersion = "2:10",
                now = 1_000L,
            ),
        )

        assertNull(dao.getByKey(oldKey))
        val moved = dao.getByKey(newKey) ?: error("new tombstone missing")
        assertEquals(newPath, moved.filePath)
        assertEquals(DeletionTombstoneEntity.OP_USER_PERMANENT, moved.operation)
        assertEquals(DeletionTombstoneEntity.STATE_PENDING, moved.state)
        assertEquals(0, moved.attemptCount)
        assertEquals(0L, moved.nextRetryAt)
        assertEquals(0L, moved.leaseUntil)
        assertNull(moved.leaseToken)
        assertNull(moved.lastErrorType)
        assertNull(moved.lastErrorCode)
        assertNull(moved.lastErrorMessage)
        assertEquals("2:10", moved.sourceVersion)
        assertEquals(100L, moved.deletedAtMs)
        assertEquals(0L, moved.firstAttemptAt)
        assertEquals(0L, moved.lastAttemptAt)
        assertEquals(50L, moved.createdAt)
        assertEquals(1_000L, moved.updatedAt)
    }

    @Test
    fun moveWithoutOldIntentIsNoOp() = runBlocking {
        val moved = database.deletionTombstoneDao().moveToPath(
            oldPath = "/missing.jpg",
            newPath = "/new.jpg",
            newPathKey = DeletionFailurePolicy.pathKey("/new.jpg"),
            sourceVersion = "1:1",
            now = 10L,
        )

        assertFalse(moved)
        assertTrue(database.deletionTombstoneDao().getByPaths(listOf("/new.jpg")).isEmpty())
    }
}
