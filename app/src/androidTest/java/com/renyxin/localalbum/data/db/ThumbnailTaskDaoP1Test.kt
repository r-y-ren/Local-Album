package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class ThumbnailTaskDaoP1Test {
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
    fun publishedV1RequestIsRejectedWithoutChangingPendingV2() = runBlocking {
        val path = "/published-stale-pending.jpg"
        val scanId = "stale-pending-v2"
        publishV1ThenCommitV2(path)
        insertRun(scanId, ScanRunEntity.TYPE_INCREMENTAL, generation = 2L)
        insertOutbox(scanId, path, "2:200")
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(listOf(task(path, "2:200", scanId = scanId, updatedAt = 20L)))
        val before = requireNotNull(taskByIdentity(path, "2:200"))
        assertEquals(1L, database.homeMediaSnapshotDao().getByPath(path)?.modifiedAtMs)

        val result = dao.enqueueInteractive(listOf(task(path, "1:100", updatedAt = 30L)))

        assertEquals(0, result.accepted)
        assertFalse(result.shouldWake)
        assertNull(taskByIdentity(path, "1:100"))
        assertEquals(before, taskByIdentity(path, "2:200"))
    }

    @Test
    fun publishedV1RequestCannotClearRunningV2OwnerOrLease() = runBlocking {
        val path = "/published-stale-running.jpg"
        val scanId = "stale-running-v2"
        publishV1ThenCommitV2(path)
        insertRun(scanId, ScanRunEntity.TYPE_INCREMENTAL, generation = 2L)
        insertOutbox(scanId, path, "2:200")
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(listOf(task(path, "2:200", scanId = scanId, updatedAt = 20L)))
        val running = dao.claimAutomaticBatch(
            now = 21L,
            limit = 1,
            leaseToken = "v2-owner-token",
            leaseDurationMs = 50_000L,
            scanId = scanId,
        ).single()

        val result = dao.enqueueInteractive(listOf(task(path, "1:100", updatedAt = 30L)))
        val after = requireNotNull(dao.getById(running.taskId))

        assertEquals(0, result.accepted)
        assertEquals(scanId, after.scanId)
        assertEquals(ThumbnailTaskEntity.STATUS_RUNNING, after.status)
        assertEquals(running.leaseToken, after.leaseToken)
        assertEquals(running.leaseUntil, after.leaseUntil)
    }

    @Test
    fun currentV2RequestBuildsNewIdentityWithoutTouchingRunningV1Lease() = runBlocking {
        val path = "/current-v2-old-running-v1.jpg"
        val oldScanId = "old-running-v1-scan"
        val dao = database.thumbnailTaskDao()
        database.mediaDao().insertAll(listOf(media(path, 1L, 100L)))
        insertRun(oldScanId, ScanRunEntity.TYPE_FULL, generation = 1L)
        dao.enqueueAll(listOf(task(path, "1:100", scanId = oldScanId, updatedAt = 10L)))
        val runningV1 = dao.claimAutomaticBatch(
            now = 11L,
            limit = 1,
            leaseToken = "v1-live-owner",
            leaseDurationMs = 80_000L,
            scanId = oldScanId,
        ).single()
        database.mediaDao().insertAll(listOf(media(path, 2L, 200L)))

        val result = dao.enqueueInteractive(listOf(task(path, "2:200", updatedAt = 20L)))
        val afterV1 = requireNotNull(dao.getById(runningV1.taskId))
        val v2 = requireNotNull(taskByIdentity(path, "2:200"))

        assertEquals(1, result.accepted)
        assertTrue(result.shouldWake)
        assertEquals(ThumbnailTaskEntity.STATUS_RUNNING, afterV1.status)
        assertEquals(oldScanId, afterV1.scanId)
        assertEquals(runningV1.leaseToken, afterV1.leaseToken)
        assertEquals(runningV1.leaseUntil, afterV1.leaseUntil)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING, v2.status)
        assertNull(v2.scanId)
    }

    @Test
    fun fullGateUsesOnlyCurrentCanonicalGridAndCountsInteractiveIdentity() = runBlocking {
        val scanId = "strict-full-gate"
        val target = "/full-target.jpg"
        val trashed = "/full-trashed.jpg"
        database.mediaDao().insertAll(
            listOf(
                media(target, 2L, 200L),
                media(trashed, 4L, 400L, isTrashed = true),
            ),
        )
        insertRun(scanId, ScanRunEntity.TYPE_FULL, generation = 3L)
        insertRun("other-full-scan", ScanRunEntity.TYPE_FULL, generation = 4L)
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(listOf(task(target, "2:200", updatedAt = 10L)))
        val live = dao.claimInteractiveBatch(
            now = 11L,
            limit = 1,
            leaseToken = "same-identity-ui-owner",
            leaseDurationMs = 90_000L,
        ).single()
        dao.enqueueAll(
            listOf(
                task(target, "2:200", sizeClass = ThumbnailSpec.SIZE_PREVIEW, scanId = scanId),
                task(target, "1:100", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = scanId),
                task("/unrelated-other-scan.jpg", "8:800", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = "other-full-scan"),
                task(trashed, "4:400", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = scanId),
            ),
        )

        assertEquals(1, dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L))
        val blocked = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, blocked.total)
        assertEquals(0, blocked.terminal)
        assertEquals(0, blocked.failures)
        assertEquals(1, blocked.interactiveActive)
        assertEquals(1, blocked.remaining)

        assertEquals(1, dao.markDone(live.taskId, "same-identity-ui-owner", 21L))
        val done = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, done.total)
        assertEquals(1, done.terminal)
        assertEquals(0, done.failures)
        assertEquals(0, done.remaining)
    }

    @Test
    fun rebuildGateUsesCurrentCanonicalIdentityAndIgnoresPreviewFailure() = runBlocking {
        val scanId = "strict-rebuild-gate"
        val path = "/rebuild-target.jpg"
        database.mediaDao().insertAll(listOf(media(path, 9L, 900L)))
        insertRun(scanId, ScanRunEntity.TYPE_RECONCILIATION, generation = 9L)
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(
            listOf(
                task(
                    path,
                    "9:900",
                    sizeClass = ThumbnailSpec.SIZE_PREVIEW,
                    status = ThumbnailTaskEntity.STATUS_FAILED,
                    scanId = scanId,
                ),
            ),
        )

        assertEquals(1, dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 10L))
        val gate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, gate.total)
        assertEquals(0, gate.terminal)
        assertEquals(0, gate.failures)
        assertEquals(1, gate.remaining)
    }

    @Test
    fun incrementalGateUsesOnlyCurrentScanOutboxCanonicalIdentity() = runBlocking {
        val scanId = "strict-incremental-gate"
        val otherScanId = "strict-incremental-other"
        val target = "/incremental-target.jpg"
        database.mediaDao().insertAll(
            listOf(
                media(target, 5L, 500L),
                media("/same-scan-unrelated.jpg", 6L, 600L),
                media("/other-scan-target.jpg", 7L, 700L),
            ),
        )
        insertRun(scanId, ScanRunEntity.TYPE_INCREMENTAL, generation = 5L)
        insertRun(otherScanId, ScanRunEntity.TYPE_INCREMENTAL, generation = 6L)
        insertOutbox(scanId, target, "5:500")
        insertOutbox(otherScanId, "/other-scan-target.jpg", "7:700")
        val dao = database.thumbnailTaskDao()
        dao.enqueueAll(
            listOf(
                task("/same-scan-unrelated.jpg", "6:600", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = scanId),
                task(target, "5:500", sizeClass = ThumbnailSpec.SIZE_PREVIEW, status = ThumbnailTaskEntity.STATUS_FAILED, scanId = scanId),
                task(target, "4:400", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = scanId),
                task("/other-scan-target.jpg", "7:700", status = ThumbnailTaskEntity.STATUS_FAILED, scanId = otherScanId),
            ),
        )

        assertEquals(
            1,
            dao.seedGridFromOutbox(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L),
        )
        val gate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, gate.total)
        assertEquals(0, gate.terminal)
        assertEquals(0, gate.failures)
        val targetTask = requireNotNull(taskByIdentity(target, "5:500"))
        val claimed = dao.claimAutomaticBatch(
            now = 21L,
            limit = 1,
            leaseToken = "incremental-target-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        assertEquals(targetTask.taskId, claimed.taskId)
        assertEquals(1, dao.markDone(claimed.taskId, "incremental-target-owner", 22L))
        assertEquals(0, dao.thumbnailGateSnapshot(scanId).remaining)
    }

    @Test
    fun permanentFailureIsStableTerminalAndProcessDeathRecoveryConverges() = runBlocking {
        val failedPath = "/permanent-failure.jpg"
        val failedScan = "permanent-failure-scan"
        database.mediaDao().insertAll(listOf(media(failedPath, 10L, 1_000L)))
        insertRun(failedScan, ScanRunEntity.TYPE_FULL, generation = 10L)
        val dao = database.thumbnailTaskDao()
        dao.seedAllGrid(failedScan, ThumbnailSpec.PRIORITY_BACKGROUND, now = 10L)
        val failedClaim = dao.claimAutomaticBatch(
            now = 11L,
            limit = 1,
            leaseToken = "permanent-failure-owner",
            leaseDurationMs = 10_000L,
            scanId = failedScan,
        ).single()
        assertEquals(
            1,
            dao.markFailed(
                taskId = failedClaim.taskId,
                leaseToken = "permanent-failure-owner",
                error = "decode_failed",
                nextRetryAt = 12L,
                maxAttempts = 1,
                now = 12L,
            ),
        )
        val failedGate = dao.thumbnailGateSnapshot(failedScan)
        assertEquals(1, failedGate.terminal)
        assertEquals(1, failedGate.failures)
        assertEquals(0, failedGate.remaining)

        database.mediaDao().clearAll()
        val recoveredPath = "/process-death.jpg"
        val recoveredScan = "process-death-scan"
        database.mediaDao().insertAll(listOf(media(recoveredPath, 11L, 1_100L)))
        insertRun(recoveredScan, ScanRunEntity.TYPE_FULL, generation = 11L)
        dao.seedAllGrid(recoveredScan, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L)
        val interrupted = dao.claimAutomaticBatch(
            now = 21L,
            limit = 1,
            leaseToken = "dead-process-owner",
            leaseDurationMs = 100_000L,
            scanId = recoveredScan,
        ).single()
        assertEquals(1, dao.thumbnailGateSnapshot(recoveredScan).remaining)
        assertTrue(dao.recoverThumbnailProcessBoundary("resumed-process",22L) >= 1)
        assertEquals(1, dao.thumbnailGateSnapshot(recoveredScan).remaining)
        val resumed = dao.claimAutomaticBatch(
            now = 23L,
            limit = 1,
            leaseToken = "resumed-process-owner",
            leaseDurationMs = 10_000L,
            scanId = recoveredScan,
        ).single()
        assertEquals(interrupted.taskId, resumed.taskId)
        assertEquals(1, dao.markDone(resumed.taskId, "resumed-process-owner", 24L))
        assertEquals(0, dao.thumbnailGateSnapshot(recoveredScan).remaining)
    }

    @Test
    fun supersededTargetBlocksUntilSeedReactivatesAndDeletionOrIdentityChangeUpdatesTargetSet() = runBlocking {
        val path = "/target-lifecycle.jpg"
        val scanId = "target-lifecycle-scan"
        database.mediaDao().insertAll(listOf(media(path, 12L, 1_200L)))
        insertRun(scanId, ScanRunEntity.TYPE_FULL, generation = 12L)
        val dao = database.thumbnailTaskDao()
        dao.enqueueInteractive(listOf(task(path, "12:1200", updatedAt = 10L)))
        database.mediaDao().insertAll(listOf(media(path, 13L, 1_300L)))
        dao.enqueueInteractive(listOf(task(path, "13:1300", updatedAt = 11L)))
        assertEquals(
            ThumbnailTaskEntity.STATUS_SUPERSEDED,
            taskByIdentity(path, "12:1200")?.status,
        )
        database.mediaDao().insertAll(listOf(media(path, 12L, 1_200L)))

        val supersededGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, supersededGate.total)
        assertEquals(0, supersededGate.terminal)
        assertEquals(1, supersededGate.repairable)
        assertEquals(1, supersededGate.remaining)
        dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 20L)
        assertEquals(0, dao.thumbnailGateSnapshot(scanId).repairable)
        assertEquals(
            ThumbnailTaskEntity.STATUS_PENDING,
            taskByIdentity(path, "12:1200")?.status,
        )

        database.mediaDao().deleteByPaths(listOf(path))
        val deletedGate = dao.thumbnailGateSnapshot(scanId)
        assertEquals(0, deletedGate.total)
        assertEquals(0, deletedGate.remaining)

        database.mediaDao().insertAll(listOf(media(path, 13L, 1_300L)))
        val changedBeforeSeed = dao.thumbnailGateSnapshot(scanId)
        assertEquals(1, changedBeforeSeed.total)
        assertEquals(0, changedBeforeSeed.terminal)
        assertEquals(1, changedBeforeSeed.remaining)
        assertEquals(
            ThumbnailTaskEntity.STATUS_SUPERSEDED,
            taskByIdentity(path, "13:1300")?.status,
        )
        dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 30L)
        val current = requireNotNull(taskByIdentity(path, "13:1300"))
        val claimed = dao.claimAutomaticBatch(
            now = 31L,
            limit = 1,
            leaseToken = "changed-identity-owner",
            leaseDurationMs = 10_000L,
            scanId = scanId,
        ).single()
        assertEquals(current.taskId, claimed.taskId)
        assertEquals(1, dao.markDone(current.taskId, "changed-identity-owner", 32L))
        assertEquals(0, dao.thumbnailGateSnapshot(scanId).remaining)
    }

    @Test
    fun producerTransactionsPreservePendingLevelAcrossConcurrentAndLaterCommits() = runBlocking {
        val dao = database.thumbnailTaskDao()
        database.mediaDao().insertAll(
            listOf(
                media("/producer-a.jpg", 20L, 2_000L),
                media("/producer-b.jpg", 21L, 2_100L),
                media("/producer-c.jpg", 22L, 2_200L),
                media("/producer-d.jpg", 23L, 2_300L),
            ),
        )

        val concurrentResults = withContext(Dispatchers.IO) {
            listOf(
                async {
                    dao.enqueueInteractive(
                        listOf(task("/producer-a.jpg", "20:2000", updatedAt = 20L)),
                    )
                },
                async {
                    dao.enqueueInteractive(
                        listOf(task("/producer-b.jpg", "21:2100", updatedAt = 21L)),
                    )
                },
            ).awaitAll()
        }
        assertEquals(2, concurrentResults.sumOf { it.accepted })
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,
            dao.laneState(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID)?.deliveryState,
        )

        val activeBatch = dao.enqueueInteractive(
            listOf(
                task("/producer-c.jpg", "22:2200", updatedAt = 22L),
                task("/producer-d.jpg", "23:2300", updatedAt = 23L),
            ),
        )
        assertEquals(2, activeBatch.accepted)

        val claimed = dao.claimInteractiveBatch(
            now = 30L,
            limit = 10,
            leaseToken = "drain-owner",
            leaseDurationMs = 10_000L,
        )
        assertEquals(4, claimed.size)
        claimed.forEach { claimedTask ->
            assertEquals(1, dao.markDone(claimedTask.taskId, "drain-owner", 31L))
        }
        assertEquals(0, dao.countInteractiveActive())
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_QUIESCENT,
            dao.recomputeLaneLevel(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,31L).deliveryState,
        )

        database.mediaDao().insertAll(listOf(media("/after-final-empty.jpg", 24L, 2_400L)))
        val afterFinalEmpty = dao.enqueueInteractive(
            listOf(task("/after-final-empty.jpg", "24:2400", updatedAt = 32L)),
        )
        assertEquals(1, afterFinalEmpty.accepted)
        assertTrue(afterFinalEmpty.shouldWake)
    }

    @Test
    fun staleV1CannotPublishAfterV2ForImageOrVideoAndLeaseCasIsolated() = runBlocking {
        listOf(MediaType.IMAGE, MediaType.VIDEO).forEachIndexed { index, type ->
            val path = "/publish-toctou-$index"
            val dao = database.thumbnailTaskDao()
            database.mediaDao().insertAll(listOf(media(path, 1L, 100L, mediaType = type)))
            dao.enqueueInteractive(listOf(task(path, "1:100", mediaType = type, updatedAt = 1L)))
            val v1 = dao.claimInteractiveBatch(2L, 1, "v1-$index", 100_000L).single()

            // V1 passed its pre-decode canonical check; scanner commits V2 while it decodes.
            database.mediaDao().insertAll(listOf(media(path, 2L, 200L, mediaType = type)))
            dao.enqueueInteractive(listOf(task(path, "2:200", mediaType = type, updatedAt = 3L)))
            val v2 = dao.claimInteractiveBatch(4L, 1, "v2-$index", 100_000L).single()
            val v2Entry = cache(path, "2:200", "/cache/v2-$index.webp")
            assertEquals(
                com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
                dao.publishGeneratedThumbnail(v2, "v2-$index", v2Entry, 5L),
            )

            val v1Entry = cache(path, "1:100", "/cache/v1-$index.webp")
            assertEquals(
                com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.SUPERSEDED,
                dao.publishGeneratedThumbnail(v1, "v1-$index", v1Entry, 6L),
            )
            assertEquals("/cache/v2-$index.webp", database.mediaDao().getByFilePathLight(path)?.thumbnailPath)
            assertNull(readyByIdentity(path, "1:100"))
            assertEquals(v2Entry, readyByIdentity(path, "2:200"))
            assertEquals(ThumbnailTaskEntity.STATUS_SUPERSEDED, dao.getById(v1.taskId)?.status)
            assertEquals(ThumbnailTaskEntity.STATUS_DONE, dao.getById(v2.taskId)?.status)
            assertEquals(0, dao.markDone(v2.taskId, "v1-$index", 7L))
        }
    }

    @Test
    fun publicationRejectsTrashDeleteTypeChangeAndLostLease() = runBlocking {
        suspend fun assertCanonicalChangeRejected(
            suffix: String,
            mutate: suspend (String) -> Unit,
        ) {
            val path = "/publish-reject-$suffix.jpg"
            val dao = database.thumbnailTaskDao()
            database.mediaDao().insertAll(listOf(media(path, 10L, 1_000L)))
            dao.enqueueInteractive(listOf(task(path, "10:1000", updatedAt = 10L)))
            val claimed = dao.claimInteractiveBatch(11L, 1, "lease-$suffix", 100_000L).single()
            mutate(path)
            assertEquals(
                com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.SUPERSEDED,
                dao.publishGeneratedThumbnail(
                    claimed,
                    "lease-$suffix",
                    cache(path, "10:1000", "/cache/$suffix.webp"),
                    12L,
                ),
            )
            assertNull(readyByIdentity(path, "10:1000"))
        }
        assertCanonicalChangeRejected("trash") { path ->
            database.mediaDao().insertAll(listOf(media(path, 10L, 1_000L, isTrashed = true)))
        }
        assertCanonicalChangeRejected("delete") { path -> database.mediaDao().deleteByPaths(listOf(path)) }
        assertCanonicalChangeRejected("type") { path ->
            database.mediaDao().insertAll(
                listOf(media(path, 10L, 1_000L, mediaType = MediaType.VIDEO)),
            )
        }

        val path = "/publish-lost-lease.jpg"
        val dao = database.thumbnailTaskDao()
        database.mediaDao().insertAll(listOf(media(path, 11L, 1_100L)))
        dao.enqueueInteractive(listOf(task(path, "11:1100", updatedAt = 20L)))
        val old = dao.claimInteractiveBatch(21L, 1, "old-lease", 1L).single()
        dao.recoverExpiredLeases(23L)
        val current = dao.claimInteractiveBatch(24L, 1, "new-lease", 100_000L).single()
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.LEASE_LOST,
            dao.publishGeneratedThumbnail(
                old,
                "old-lease",
                cache(path, "11:1100", "/cache/old-lease.webp"),
                25L,
            ),
        )
        assertEquals("new-lease", dao.getById(current.taskId)?.leaseToken)
    }

    @Test
    fun failedDoneAndMissingCacheReactivationAreExplicitlySeparated() = runBlocking {
        val failedPath = "/ui-stable-failed.jpg"
        val donePath = "/ui-stable-done.jpg"
        val dao = database.thumbnailTaskDao()
        database.mediaDao().insertAll(
            listOf(media(failedPath, 40L, 4_000L), media(donePath, 41L, 4_100L)),
        )
        dao.enqueueInteractive(listOf(task(failedPath, "40:4000", updatedAt = 1L)))
        val failed = dao.claimInteractiveBatch(2L, 1, "failed-owner", 100L).single()
        dao.markFailed(failed.taskId, "failed-owner", "decode_failed", 3L, 1, 3L)
        val beforeFailure = requireNotNull(dao.getById(failed.taskId))
        repeat(3) {
            val ordinary = dao.enqueueInteractive(
                listOf(task(failedPath, "40:4000", updatedAt = 10L + it)),
            )
            assertEquals(0, ordinary.accepted)
            assertFalse(ordinary.shouldWake)
        }
        assertEquals(beforeFailure, dao.getById(failed.taskId))
        val retried = dao.retryAllFailedInteractive(ThumbnailSpec.PRIORITY_VISIBLE, now = 20L)
        assertEquals(1, retried.accepted)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING, dao.getById(failed.taskId)?.status)
        val retriedClaim = dao.claimInteractiveBatch(21L, 1, "retry-owner", 100L).single()
        assertEquals(failedPath, retriedClaim.filePath)
        assertTrue(dao.supersedeClaimedTask(retriedClaim.taskId, "retry-owner", 22L))

        dao.enqueueInteractive(listOf(task(donePath, "41:4100", updatedAt = 30L)))
        val done = dao.claimInteractiveBatch(31L, 1, "done-owner", 100L).single()
        val ready = cache(donePath, "41:4100", "/cache/valid-done.webp")
        assertEquals(
            com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult.PUBLISHED,
            dao.publishGeneratedThumbnail(done, "done-owner", ready, 32L),
        )
        val ordinary = dao.enqueueInteractive(listOf(task(donePath, "41:4100", updatedAt = 33L)))
        assertEquals(0, ordinary.accepted)
        assertFalse(ordinary.shouldWake)
        assertEquals(ThumbnailTaskEntity.STATUS_DONE, dao.getById(done.taskId)?.status)
        assertEquals(
            1,
            dao.reactivateMissingCacheDone(
                donePath,
                ThumbnailSpec.SIZE_GRID,
                "41:4100",
                MediaType.IMAGE.name,
                ready.path,
                ThumbnailSpec.PRIORITY_VISIBLE,
                now = 34L,
            ).accepted,
        )
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING, dao.getById(done.taskId)?.status)
    }

    @Test
    fun workerCanConsumeDispatchingBeforeEnqueuedMarkWithoutStateRegression() = runBlocking {
        val dao = database.thumbnailTaskDao()
        val path = "/dispatch-worker-race.jpg"
        database.mediaDao().insertAll(listOf(media(path,49L,4_900L)))
        dao.enqueueInteractive(listOf(task(path,"49:4900",updatedAt=1L)))
        val dispatch = requireNotNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            "dispatch-race",2L,100_000L,
        ))

        assertEquals(0,dao.startRun(dispatch.laneId,dispatch.revision,"stale-dispatch","stale-run",100_000L,3L))
        assertEquals(1,dao.startRun(dispatch.laneId,dispatch.revision,"dispatch-race","run-race",100_000L,3L))
        assertEquals(0,dao.markDispatchEnqueued(dispatch.laneId,dispatch.revision,"dispatch-race",4L))
        val running = requireNotNull(dao.laneState(dispatch.laneId))
        assertEquals(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_RUNNING,running.deliveryState)
        assertEquals("run-race",running.runToken)
        assertEquals("dispatch-race",running.dispatchToken)
    }

    @Test
    fun delayedRowsCannotSwallowImmediateLevelAndDispatchTokensAreCasSafe() = runBlocking {
        val dao = database.thumbnailTaskDao()
        val delayed = "/wake-delayed.jpg"
        val immediate = "/wake-immediate.jpg"
        database.mediaDao().insertAll(
            listOf(media(delayed,50L,5_000L),media(immediate,51L,5_100L)),
        )
        dao.enqueueInteractive(listOf(task(delayed,"50:5000",updatedAt=1L).copy(nextRetryAt=10_000L)))
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_DELAYED,
            dao.laneState(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID)?.deliveryState,
        )
        dao.enqueueInteractive(listOf(task(immediate,"51:5100",updatedAt=2L)))
        val dispatch = requireNotNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            "dispatch-a",3L,100_000L,
        ))
        assertNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            "dispatch-b",3L,100_000L,
        ))
        assertEquals(1,dao.markDispatchEnqueued(dispatch.laneId,dispatch.revision,"dispatch-a",4L))
        assertEquals(0,dao.startRun(dispatch.laneId,dispatch.revision,"stale-token","run-stale",100_000L,5L))
        assertEquals(1,dao.startRun(dispatch.laneId,dispatch.revision,"dispatch-a","run-a",100_000L,5L))
        val claimed = dao.claimInteractiveBatch(6L,1,"wake-owner",100_000L).single()
        assertEquals(immediate,claimed.filePath)
        assertEquals(1,dao.markDone(claimed.taskId,"wake-owner",7L))
        assertEquals(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_DELAYED,
            dao.finalizeRun(dispatch.laneId,"run-a",7L)?.deliveryState,
        )
    }

    @Test
    fun scanRepairDoesNotReopenCanonicalFailedTask() = runBlocking {
        val path = "/scan-stable-failed.jpg"
        val scanId = "scan-stable-failed"
        val dao = database.thumbnailTaskDao()
        database.mediaDao().insertAll(listOf(media(path, 60L, 6_000L)))
        insertRun(scanId, ScanRunEntity.TYPE_FULL, 60L)
        dao.enqueueAll(listOf(task(path, "60:6000", scanId = scanId, updatedAt = 1L)))
        val claimed = dao.claimAutomaticBatch(2L, 1, "scan-failed-owner", 100L, scanId).single()
        dao.markFailed(claimed.taskId, "scan-failed-owner", "decode_failed", 3L, 1, 3L)

        dao.seedAllGrid(scanId, ThumbnailSpec.PRIORITY_BACKGROUND, now = 4L)

        val after = requireNotNull(dao.getById(claimed.taskId))
        assertEquals(ThumbnailTaskEntity.STATUS_FAILED, after.status)
        assertEquals(1, after.attemptCount)
        assertEquals("decode_failed", after.lastError)
        assertEquals(scanId, after.scanId)
    }

    @Test
    fun processEpochRecoveryReclaimsBothLanesAndApplicationSingleCallDoesNotStealCurrentOwners() = runBlocking {
        val dao = database.thumbnailTaskDao()
        val oldEpoch = "old-process"
        val newEpoch = "new-process"
        val interactivePath = "/wake-zombie-ui.jpg"
        val automaticPath = "/wake-zombie-bg.jpg"
        val scanId = "wake-zombie-bg-scan"
        database.mediaDao().insertAll(listOf(
            media(interactivePath,61L,6_100L),media(automaticPath,62L,6_200L),
        ))
        insertRun(scanId,ScanRunEntity.TYPE_FULL,62L)
        dao.enqueueInteractive(listOf(task(interactivePath,"61:6100",updatedAt=1L)))
        dao.enqueueAll(listOf(task(automaticPath,"62:6200",scanId=scanId,updatedAt=1L)))
        val uiDispatch = requireNotNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"ui-dispatch"),2L,100_000L,
        ))
        val bgDispatch = requireNotNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID,
            com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"bg-dispatch"),2L,100_000L,
        ))
        val uiRun = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"ui-run")
        val bgRun = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"bg-run")
        assertEquals(1,dao.startRun(uiDispatch.laneId,uiDispatch.revision,requireNotNull(uiDispatch.dispatchToken),uiRun,100_000L,3L))
        assertEquals(1,dao.startRun(bgDispatch.laneId,bgDispatch.revision,requireNotNull(bgDispatch.dispatchToken),bgRun,100_000L,3L))
        val uiLease = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"ui-task")
        val bgLease = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(oldEpoch,"bg-task")
        val uiTask = dao.claimInteractiveBatch(4L,1,uiLease,100_000L).single()
        val bgTask = dao.claimAutomaticBatch(4L,1,bgLease,100_000L,scanId).single()

        assertTrue(dao.recoverThumbnailProcessBoundary(newEpoch,5L) >= 4)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING,dao.getById(uiTask.taskId)?.status)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING,dao.getById(bgTask.taskId)?.status)
        assertEquals(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,dao.laneState(uiDispatch.laneId)?.deliveryState)
        assertEquals(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,dao.laneState(bgDispatch.laneId)?.deliveryState)
        assertNotNull(dao.claimDispatch(uiDispatch.laneId,
            com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(newEpoch,"replay"),6L,100_000L))

        // 即使测试重复调用，当前 epoch token 也受保护，不会抢占本进程合法 Worker/task。
        val currentDispatch = requireNotNull(dao.claimDispatch(bgDispatch.laneId,
            com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(newEpoch,"bg-current"),6L,100_000L))
        val currentRun = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(newEpoch,"bg-run-current")
        assertEquals(1,dao.startRun(currentDispatch.laneId,currentDispatch.revision,requireNotNull(currentDispatch.dispatchToken),currentRun,100_000L,7L))
        val currentLease = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(newEpoch,"bg-task-current")
        val currentTask = dao.claimAutomaticBatch(8L,1,currentLease,100_000L,scanId).single()
        assertEquals(0,dao.recoverThumbnailProcessBoundary(newEpoch,9L))
        assertEquals(currentRun,dao.laneState(bgDispatch.laneId)?.runToken)
        assertEquals(currentLease,dao.getById(currentTask.taskId)?.leaseToken)
    }

    @Test
    fun cancellationSeamReleasesTaskAndExactFinalizesRun() = runBlocking {
        val dao = database.thumbnailTaskDao()
        val epoch = "cancel-process"
        val path = "/cancel-seam.jpg"
        database.mediaDao().insertAll(listOf(media(path,63L,6_300L)))
        dao.enqueueInteractive(listOf(task(path,"63:6300",updatedAt=1L)))
        val dispatchToken = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(epoch,"dispatch")
        val dispatch = requireNotNull(dao.claimDispatch(
            com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            dispatchToken,2L,100_000L,
        ))
        val runToken = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(epoch,"run")
        assertEquals(1,dao.startRun(dispatch.laneId,dispatch.revision,dispatchToken,runToken,100_000L,3L))
        val leaseToken = com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao.processOwnedToken(epoch,"task")
        val claimed = dao.claimInteractiveBatch(4L,1,leaseToken,100_000L).single()

        assertEquals(1,dao.releaseLease(leaseToken,5L))
        assertEquals(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,
            dao.finalizeRun(dispatch.laneId,runToken,5L)?.deliveryState)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING,dao.getById(claimed.taskId)?.status)
    }

    @Test
    fun metadataAbsentReopensOnlyExactCurrentDoneAndRaisesInteractiveLevel() = runBlocking {
        val dao = database.thumbnailTaskDao()
        val donePath = "/metadata-absent-done.jpg"
        val failedPath = "/metadata-absent-failed.jpg"
        database.mediaDao().insertAll(listOf(media(donePath,64L,6_400L),media(failedPath,65L,6_500L)))
        dao.enqueueInteractive(listOf(task(donePath,"64:6400",updatedAt=1L),task(failedPath,"65:6500",updatedAt=1L)))
        val claimed = dao.claimInteractiveBatch(2L,10,"metadata-owner",100_000L)
        val done = claimed.single { it.filePath == donePath }
        val failed = claimed.single { it.filePath == failedPath }
        assertEquals(1,dao.markDone(done.taskId,"metadata-owner",3L))
        assertEquals(1,dao.markFailed(failed.taskId,"metadata-owner","decode_failed",3L,1,3L))

        assertEquals(1,dao.reactivateInfrastructureDone(
            donePath,MediaType.IMAGE.name,canonicalVersion("64:6400"),ThumbnailSpec.SIZE_GRID,
            ThumbnailIdentity.CURRENT_FORMAT_VERSION,"",ThumbnailSpec.PRIORITY_VISIBLE,4L,
        ).accepted)
        assertEquals(0,dao.reactivateInfrastructureDone(
            failedPath,MediaType.IMAGE.name,canonicalVersion("65:6500"),ThumbnailSpec.SIZE_GRID,
            ThumbnailIdentity.CURRENT_FORMAT_VERSION,"",ThumbnailSpec.PRIORITY_VISIBLE,4L,
        ).accepted)
        assertEquals(ThumbnailTaskEntity.STATUS_PENDING,dao.getById(done.taskId)?.status)
        assertEquals(ThumbnailTaskEntity.STATUS_FAILED,dao.getById(failed.taskId)?.status)
        assertEquals(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.STATE_PENDING,
            dao.laneState(com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID)?.deliveryState)
    }

    @Test
    fun staleAndActiveDuplicateRequestsDoNotCreateFalseWake() = runBlocking {
        val path = "/duplicate-current.jpg"
        database.mediaDao().insertAll(listOf(media(path, 30L, 3_000L)))
        val dao = database.thumbnailTaskDao()
        val stale = dao.enqueueInteractive(listOf(task(path, "29:2900", updatedAt = 1L)))
        assertEquals(0, stale.accepted)
        assertFalse(stale.shouldWake)

        val first = dao.enqueueInteractive(listOf(task(path, "30:3000", updatedAt = 2L)))
        assertTrue(first.shouldWake)
        val duplicate = dao.enqueueInteractive(listOf(task(path, "30:3000", updatedAt = 3L)))
        assertFalse(duplicate.shouldWake)
        assertEquals(1, dao.countInteractiveActive())
        assertNotNull(taskByIdentity(path, "30:3000"))
    }

    private suspend fun publishV1ThenCommitV2(path: String) {
        database.mediaDao().insertAll(listOf(media(path, 1L, 100L)))
        database.homeMediaSnapshotDao().replaceFromCanonical(generation = 1L)
        database.mediaDao().insertAll(listOf(media(path, 2L, 200L)))
    }

    private suspend fun insertRun(scanId: String, scanType: String, generation: Long) {
        database.scanRunDao().insert(
            ScanRunEntity(
                scanId = scanId,
                generation = generation,
                scanType = scanType,
                status = ScanRunEntity.STATUS_COMPLETED,
                coreScanState = CoreScanState.COMPLETED.name,
                indexAvailability = IndexAvailability.PUBLISHED.name,
                enhancementState = EnhancementState.QUEUED.name,
            ),
        )
    }

    private suspend fun insertOutbox(scanId: String, path: String, sourceVersion: String) {
        database.enhancementOutboxDao().enqueueAll(
            listOf(
                EnhancementOutboxEntity(
                    scanId = scanId,
                    profileId = "p1-profile-$scanId",
                    filePath = path,
                    sourceVersion = sourceVersion,
                    mediaType = MediaType.IMAGE.name,
                    pipelineScope = "p1-scope-$scanId",
                    enqueueAnalysis = false,
                    enqueueThumbnail = true,
                    parentPath = "/",
                ),
            ),
        )
    }

    private fun cache(path: String, sourceVersion: String, cachePath: String) =
        ThumbnailCacheEntryEntity(
            filePath = path,
            sizeClass = ThumbnailSpec.SIZE_GRID,
            sourceVersion = canonicalVersion(sourceVersion),
            path = cachePath,
            byteSize = 100L,
            lastAccessAt = 1L,
            createdAt = 1L,
        )

    private fun task(
        path: String,
        sourceVersion: String,
        sizeClass: String = ThumbnailSpec.SIZE_GRID,
        status: String = ThumbnailTaskEntity.STATUS_PENDING,
        scanId: String? = null,
        updatedAt: Long = 1L,
        mediaType: MediaType = MediaType.IMAGE,
    ) = ThumbnailTaskEntity(
        filePath = path,
        sizeClass = sizeClass,
        sourceVersion = canonicalVersion(sourceVersion),
        mediaType = mediaType.name,
        priority = ThumbnailSpec.PRIORITY_VISIBLE,
        status = status,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        scanId = scanId,
    )

    private suspend fun taskByIdentity(path: String, sourceVersion: String) =
        database.thumbnailTaskDao().getByIdentityExact(
            path,MediaType.IMAGE.name,canonicalVersion(sourceVersion),ThumbnailSpec.SIZE_GRID,
            ThumbnailIdentity.CURRENT_FORMAT_VERSION,
        )

    private suspend fun readyByIdentity(path: String, sourceVersion: String) =
        database.thumbnailCacheDao().getReadyExact(
            path,MediaType.IMAGE.name,canonicalVersion(sourceVersion),ThumbnailSpec.SIZE_GRID,
            ThumbnailIdentity.CURRENT_FORMAT_VERSION,
        )

    private fun canonicalVersion(value: String): String {
        if (value.startsWith("sv1|")) return value
        val parts = value.split(':',limit=2)
        return SourceVersionCodec.encode(parts[0].toLong(),parts[1].toLong(),null)
    }

    private fun media(
        path: String,
        modifiedAtMs: Long,
        fileSize: Long,
        isTrashed: Boolean = false,
        mediaType: MediaType = MediaType.IMAGE,
    ) = MediaEntity(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        mediaType = mediaType,
        capturedAtMs = modifiedAtMs,
        modifiedAtMs = modifiedAtMs,
        parentPath = "/",
        fileSize = fileSize,
        isTrashed = isTrashed,
    )
}
