package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.index.MediaStoreIdentity
import com.renyxin.localalbum.data.db.dao.MediaChangeDao
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaStoreReferenceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaChangeDaoTest {
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
    fun duplicateEventsMergeFlagsAndKeepObservationBounds() = runBlocking {
        val dao = database.mediaChangeDao()
        dao.record(event(id = 7L, flags = 0x01, firstObservedAtMs = 100L, observedAtMs = 200L))
        dao.record(event(id = 7L, flags = 0x04, firstObservedAtMs = 50L, observedAtMs = 300L))
        dao.record(event(id = 7L, flags = 0x02, firstObservedAtMs = 10L, observedAtMs = 150L))

        val claimed = dao.claimBatch(
            profileId = PROFILE,
            now = 400L,
            limit = 10,
            leaseDurationMs = 1_000L,
            leaseToken = "lease-dedup",
        )

        assertEquals(1, claimed.size)
        assertEquals(0x07, claimed.single().flags)
        assertEquals(10L, claimed.single().firstObservedAtMs)
        assertEquals(300L, claimed.single().observedAtMs)
        assertEquals(1, claimed.single().attemptCount)
    }

    @Test
    fun claimBatchIsBoundedToFiveHundredAndDoesNotClaimReconciliationHint() = runBlocking {
        val dao = database.mediaChangeDao()
        repeat(501) { index -> dao.record(event(id = index.toLong(), observedAtMs = index.toLong())) }
        dao.record(
            MediaChangeEventEntity(
                eventKey = "$PROFILE:reconciliation",
                profileId = PROFILE,
                eventType = MediaChangeEventEntity.TYPE_RECONCILIATION,
                firstObservedAtMs = 1L,
                observedAtMs = 1L,
            ),
        )

        val first = dao.claimBatch(
            profileId = PROFILE,
            now = 10_000L,
            limit = MediaChangeDao.MAX_CLAIM_SIZE,
            leaseDurationMs = 1_000L,
            leaseToken = "lease-500",
        )
        val second = dao.claimBatch(
            profileId = PROFILE,
            now = 10_001L,
            limit = MediaChangeDao.MAX_CLAIM_SIZE,
            leaseDurationMs = 1_000L,
            leaseToken = "lease-1",
        )

        assertEquals(500, first.size)
        assertEquals(1, second.size)
        assertTrue(dao.hasReconciliationHint(PROFILE))
        assertEquals(502, rowCount("media_change_events"))
        assertEquals(501, rowCountByStatus("media_change_events", "LEASED"))
    }

    @Test
    fun expiredLeaseBecomesClaimableButLiveLeaseDoesNot() = runBlocking {
        val dao = database.mediaChangeDao()
        dao.record(event(id = 11L, observedAtMs = 1L))

        val first = dao.claimBatch(
            profileId = PROFILE,
            now = 100L,
            limit = 10,
            leaseDurationMs = 100L,
            leaseToken = "lease-live",
        )
        assertEquals(1, first.size)

        val whileLive = dao.claimBatch(
            profileId = PROFILE,
            now = 150L,
            limit = 10,
            leaseDurationMs = 100L,
            leaseToken = "lease-too-early",
        )
        assertTrue(whileLive.isEmpty())

        val afterExpiry = dao.claimBatch(
            profileId = PROFILE,
            now = 201L,
            limit = 10,
            leaseDurationMs = 100L,
            leaseToken = "lease-recovered",
        )
        assertEquals(1, afterExpiry.size)
        assertEquals("lease-recovered", afterExpiry.single().leaseToken)
    }

    @Test
    fun newerNotificationInvalidatesOldLeaseAndOldAcknowledgeCannotDeleteReference() = runBlocking {
        val dao = database.mediaChangeDao()
        val identity = MediaStoreIdentity("external", MediaType.IMAGE, 21L)
        val key = identity.stableKey(PROFILE)
        dao.record(event(id = 21L, flags = 0x01, observedAtMs = 10L))
        dao.upsertReferences(
            listOf(
                MediaStoreReferenceEntity(
                    stableKey = key,
                    profileId = PROFILE,
                    volumeName = identity.volumeName,
                    mediaType = identity.mediaType.name,
                    mediaStoreId = identity.mediaStoreId,
                    contentUri = identity.canonicalContentUri(),
                    filePath = "/old.jpg",
                    sourceVersion = "1:1",
                    observedAtMs = 10L,
                ),
            ),
        )
        val old = dao.claimBatch(
            profileId = PROFILE,
            now = 20L,
            limit = 10,
            leaseDurationMs = 1_000L,
            leaseToken = "old-lease",
        )
        assertEquals(1, old.size)

        dao.record(event(id = 21L, flags = 0x04, observedAtMs = 30L))
        assertEquals(0, dao.acknowledgeLease("old-lease", listOf(key)))
        assertNotNull(dao.getReferences(listOf(key)).singleOrNull())

        val replacement = dao.claimBatch(
            profileId = PROFILE,
            now = 40L,
            limit = 10,
            leaseDurationMs = 1_000L,
            leaseToken = "new-lease",
        )
        assertEquals(1, replacement.size)
        assertEquals(0x05, replacement.single().flags)
        assertEquals(1, dao.acknowledgeLease("new-lease", listOf(key)))
        assertNull(dao.getReferences(listOf(key)).singleOrNull())
    }

    @Test
    fun profileIsolationAppliesToEventsAndReferencePaths() = runBlocking {
        val dao = database.mediaChangeDao()
        dao.record(event(profile = "full", id = 31L, observedAtMs = 1L))
        dao.record(event(profile = "lite", id = 31L, observedAtMs = 1L))
        val identity = MediaStoreIdentity("external", MediaType.IMAGE, 31L)
        dao.upsertReferences(
            listOf(
                reference(identity, "full", "/shared.jpg"),
                reference(identity, "lite", "/shared.jpg"),
            ),
        )

        assertEquals(1, dao.claimBatch("full", 10L, 10, 100L, "full-lease").size)
        assertEquals(1, dao.claimBatch("lite", 10L, 10, 100L, "lite-lease").size)
        assertEquals(2, dao.getReferences(listOf(identity.stableKey("full"), identity.stableKey("lite"))).size)
        assertEquals(1, dao.clearReferences("full"))
        assertEquals(1, dao.getReferences(listOf(identity.stableKey("lite"))).size)
    }

    @Test
    fun tenThousandDuplicateCallbacksLeaveOnePendingRow() = runBlocking {
        val dao = database.mediaChangeDao()
        repeat(10_000) { index ->
            dao.record(
                event(
                    id = 99L,
                    flags = index and 0x0F,
                    firstObservedAtMs = index.toLong(),
                    observedAtMs = (index + 1).toLong(),
                ),
            )
        }

        assertEquals(1, rowCount("media_change_events"))
        assertEquals(1, rowCountByStatus("media_change_events", MediaChangeEventEntity.STATUS_PENDING))
        assertTrue(dao.hasClaimableMediaChanges(PROFILE, 20_000L))
    }

    private fun event(
        profile: String = PROFILE,
        id: Long,
        flags: Int = 0,
        firstObservedAtMs: Long = 1L,
        observedAtMs: Long,
    ): MediaChangeEventEntity {
        val identity = MediaStoreIdentity("external", MediaType.IMAGE, id)
        return MediaChangeEventEntity(
            eventKey = identity.stableKey(profile),
            profileId = profile,
            eventType = MediaChangeEventEntity.TYPE_MEDIA,
            volumeName = identity.volumeName,
            mediaType = identity.mediaType.name,
            mediaStoreId = identity.mediaStoreId,
            contentUri = identity.canonicalContentUri(),
            flags = flags,
            firstObservedAtMs = firstObservedAtMs,
            observedAtMs = observedAtMs,
        )
    }

    private fun reference(
        identity: MediaStoreIdentity,
        profile: String,
        path: String,
    ) = MediaStoreReferenceEntity(
        stableKey = identity.stableKey(profile),
        profileId = profile,
        volumeName = identity.volumeName,
        mediaType = identity.mediaType.name,
        mediaStoreId = identity.mediaStoreId,
        contentUri = identity.canonicalContentUri(),
        filePath = path,
        sourceVersion = "1:1",
        observedAtMs = 1L,
    )

    private fun rowCount(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rowCountByStatus(table: String, status: String): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE status = ?",
            arrayOf(status),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val PROFILE = "lite"
    }
}
