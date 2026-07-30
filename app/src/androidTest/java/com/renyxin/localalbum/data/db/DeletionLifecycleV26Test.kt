package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionLifecycleV26Test {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun item(path: String, now: Long = 1_000) = DeletionTombstoneEntity(
        pathKey = "key-${path.hashCode()}", filePath = path, operation = DeletionTombstoneEntity.OP_USER_PERMANENT,
        sourceVersion = "1:1", deletedAtMs = now, createdAt = now, updatedAt = now,
    )

    @Test fun schemaContainsPrivateDeletionControlPlane() {
        val sqlite = db.openHelper.readableDatabase
        sqlite.query("PRAGMA table_info(deletion_tombstones)").use { cursor ->
            val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            assertTrue(columns.containsAll(listOf("pathKey", "filePath", "operation", "state", "attemptCount", "nextRetryAt", "lastErrorType", "lastErrorCode", "lastErrorMessage", "sourceVersion", "firstAttemptAt", "lastAttemptAt", "leaseUntil", "leaseToken")))
        }
    }

    @Test fun claimUsesLeaseAndRestoreClearsTombstone() = kotlinx.coroutines.runBlocking {
        val dao = db.deletionTombstoneDao()
        dao.upsert(item("/private/a.jpg"))
        val first = dao.claimDue(2_000, 10, 60_000, "worker-a")
        val second = dao.claimDue(2_000, 10, 60_000, "worker-b")
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, dao.clearForRestore(listOf("/private/a.jpg")))
        assertNull(dao.getByKey(first.single().pathKey))
    }

    @Test fun expiredLeaseCanBeReclaimed() = kotlinx.coroutines.runBlocking {
        val dao = db.deletionTombstoneDao()
        val original = item("/private/b.jpg")
        dao.upsert(original)
        assertEquals(1, dao.claimDue(2_000, 1, 10, "old").size)
        val reclaimed = dao.claimDue(2_011, 1, 10, "new")
        assertEquals(1, reclaimed.size)
        assertNotNull(dao.getByKey(original.pathKey))
    }
}
