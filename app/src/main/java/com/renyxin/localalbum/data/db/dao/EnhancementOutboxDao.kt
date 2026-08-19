package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity

/** Durable, bounded handoff from committed core changes to post-core enhancement tasks. */
@Dao
abstract class EnhancementOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(entries: List<EnhancementOutboxEntity>): List<Long>

    @Query(
        """UPDATE enhancement_outbox
           SET scanId = :scanId,
               enqueueAnalysis = CASE WHEN enqueueAnalysis = 1 OR :enqueueAnalysis = 1 THEN 1 ELSE 0 END,
               enqueueThumbnail = CASE WHEN enqueueThumbnail = 1 OR :enqueueThumbnail = 1 THEN 1 ELSE 0 END,
               parentPath = :parentPath,
               status = 'PENDING', attemptCount = 0, nextRetryAt = 0,
               leaseUntil = 0, leaseToken = NULL, lastError = NULL, updatedAt = :now
           WHERE profileId = :profileId AND filePath = :filePath
             AND sourceVersion = :sourceVersion AND pipelineScope = :pipelineScope""",
    )
    protected abstract suspend fun mergeExisting(
        scanId: String,
        profileId: String,
        filePath: String,
        sourceVersion: String,
        pipelineScope: String,
        enqueueAnalysis: Boolean,
        enqueueThumbnail: Boolean,
        parentPath: String,
        now: Long,
    ): Int

    /**
     * The unique source-version key makes replay idempotent. Production callers write this outbox
     * only while the core automatic-resource lane is exclusive, so a same-key RUNNING row cannot
     * have a live handoff owner: it is an interrupted-process lease and is safely transferred to
     * the latest scan. This prevents stale ownership from making the new ScanRun complete early.
     */
    @Transaction
    open suspend fun enqueueAll(entries: List<EnhancementOutboxEntity>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        // The common changed/new-media path performs one batched INSERT-IGNORE call. Only replayed
        // unique keys pay for an UPDATE, avoiding the previous unconditional UPDATE + INSERT pair
        // for every newly committed media row while preserving latest-scan ownership transfer.
        val insertResults = insertIgnore(entries)
        entries.forEachIndexed { index, entry ->
            if (insertResults[index] == -1L) {
                check(
                    mergeExisting(
                        scanId = entry.scanId,
                        profileId = entry.profileId,
                        filePath = entry.filePath,
                        sourceVersion = entry.sourceVersion,
                        pipelineScope = entry.pipelineScope,
                        enqueueAnalysis = entry.enqueueAnalysis,
                        enqueueThumbnail = entry.enqueueThumbnail,
                        parentPath = entry.parentPath,
                        now = entry.updatedAt,
                    ) == 1,
                ) { "Enhancement outbox conflict disappeared during enqueue" }
            }
        }
        return insertResults.filter { it != -1L }
    }

    @Query(
        """UPDATE enhancement_outbox
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING' AND leaseUntil <= :now""",
    )
    abstract suspend fun recoverExpiredLeases(now: Long): Int

    /** Caller must own the process-wide automatic lane, proving no live handoff consumer exists. */
    @Query(
        """UPDATE enhancement_outbox
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING'""",
    )
    abstract suspend fun recoverInterruptedLeases(now: Long): Int

    @Query(
        """SELECT outboxId FROM enhancement_outbox
           WHERE status = 'PENDING' AND nextRetryAt <= :now
             AND scanId IN (
                 SELECT scanId FROM scan_runs
                 WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                   AND enhancementState IN ('NOT_SCHEDULED', 'QUEUED', 'RUNNING')
             )
           ORDER BY createdAt ASC, outboxId ASC LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableIds(now: Long, limit: Int): List<Long>

    @Query(
        """SELECT outboxId FROM enhancement_outbox
           WHERE scanId = :scanId AND status = 'PENDING' AND nextRetryAt <= :now
             AND EXISTS(
                 SELECT 1 FROM scan_runs run
                 WHERE run.scanId = :scanId AND run.coreScanState = 'COMPLETED'
                   AND run.indexAvailability = 'PUBLISHED'
                   AND run.enhancementState IN ('NOT_SCHEDULED', 'QUEUED', 'RUNNING')
             )
           ORDER BY createdAt ASC, outboxId ASC LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableIdsForScan(
        scanId: String,
        now: Long,
        limit: Int,
    ): List<Long>

    @Query(
        """UPDATE enhancement_outbox
           SET status = 'RUNNING', leaseUntil = :leaseUntil, leaseToken = :leaseToken,
               attemptCount = attemptCount + 1, updatedAt = :now
           WHERE outboxId IN (:ids) AND status = 'PENDING' AND nextRetryAt <= :now""",
    )
    protected abstract suspend fun markClaimed(
        ids: List<Long>,
        leaseToken: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        """SELECT * FROM enhancement_outbox
           WHERE leaseToken = :leaseToken AND status = 'RUNNING'
           ORDER BY createdAt ASC, outboxId ASC""",
    )
    protected abstract suspend fun getByLeaseToken(leaseToken: String): List<EnhancementOutboxEntity>

    @Transaction
    open suspend fun claimBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<EnhancementOutboxEntity> {
        recoverExpiredLeases(now)
        val ids = findClaimableIds(now, limit)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Transaction
    open suspend fun claimBatchForScan(
        scanId: String,
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<EnhancementOutboxEntity> {
        recoverExpiredLeases(now)
        val ids = findClaimableIdsForScan(scanId, now, limit)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Query(
        """UPDATE enhancement_outbox
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL,
               updatedAt = :now
           WHERE leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun releaseLease(leaseToken: String, now: Long): Int

    @Query(
        """UPDATE enhancement_outbox
           SET status = 'DONE', leaseUntil = 0, leaseToken = NULL,
               lastError = NULL, updatedAt = :now
           WHERE outboxId IN (:ids) AND leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun markDone(ids: List<Long>, leaseToken: String, now: Long): Int

    @Query(
        """UPDATE enhancement_outbox
           SET status = CASE WHEN attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
               nextRetryAt = CASE WHEN attemptCount >= :maxAttempts THEN nextRetryAt ELSE :nextRetryAt END,
               leaseUntil = 0, leaseToken = NULL, lastError = :error, updatedAt = :now
           WHERE outboxId IN (:ids) AND leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun markFailed(
        ids: List<Long>,
        leaseToken: String,
        error: String,
        nextRetryAt: Long,
        maxAttempts: Int,
        now: Long,
    ): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM enhancement_outbox
           WHERE status IN ('PENDING', 'RUNNING')
              AND scanId IN (
                  SELECT scanId FROM scan_runs
                  WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                    AND enhancementState IN ('NOT_SCHEDULED', 'QUEUED', 'RUNNING')
              ))""",
    )
    abstract suspend fun hasRunnableEntries(): Boolean

    /** Distinguishes ordinary bounded continuation from rows intentionally waiting for retry backoff. */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM enhancement_outbox
           WHERE status = 'PENDING' AND nextRetryAt <= :now
              AND scanId IN (
                  SELECT scanId FROM scan_runs
                  WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                    AND enhancementState IN ('NOT_SCHEDULED', 'QUEUED', 'RUNNING')
              ))""",
    )
    abstract suspend fun hasClaimableEntries(now: Long): Boolean

    @Query("SELECT COUNT(*) FROM enhancement_outbox WHERE scanId = :scanId AND status IN ('PENDING', 'RUNNING')")
    abstract suspend fun countActiveForScan(scanId: String): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM enhancement_outbox
           WHERE scanId = :scanId AND status = 'PENDING' AND nextRetryAt <= :now)""",
    )
    abstract suspend fun hasClaimableForScan(scanId: String, now: Long): Boolean

    @Query(
        """UPDATE enhancement_outbox
           SET status = 'DONE', leaseUntil = 0, leaseToken = NULL,
               lastError = NULL, updatedAt = :now
           WHERE scanId = :scanId AND status IN ('PENDING', 'RUNNING', 'FAILED')""",
    )
    abstract suspend fun completeForScan(scanId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM enhancement_outbox WHERE scanId = :scanId AND status = 'FAILED'")
    abstract suspend fun countFailedForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM enhancement_outbox WHERE status = 'FAILED'")
    abstract suspend fun countFailed(): Int

    @Query("SELECT COUNT(*) FROM enhancement_outbox WHERE status = 'FAILED'")
    abstract fun observeFailedCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query(
        """SELECT * FROM enhancement_outbox
           WHERE status = 'FAILED'
           ORDER BY updatedAt ASC, outboxId ASC
           LIMIT :limit""",
    )
    abstract suspend fun getFailedForUserRetry(limit: Int): List<EnhancementOutboxEntity>

    /** Marks only rows that were expanded into independent user-owned tasks. */
    @Query(
        """UPDATE enhancement_outbox
           SET status = 'DONE', leaseUntil = 0, leaseToken = NULL,
               lastError = NULL, updatedAt = :now
           WHERE outboxId IN (:outboxIds) AND status = 'FAILED'""",
    )
    abstract suspend fun markRetriedForUser(outboxIds: List<Long>, now: Long): Int

    /** Keeps an unsupported failed handoff visible instead of silently dropping its requested work. */
    @Query(
        """UPDATE enhancement_outbox
           SET lastError = :error, updatedAt = :now
           WHERE outboxId IN (:outboxIds) AND status = 'FAILED'""",
    )
    abstract suspend fun recordUserRetryFailure(
        outboxIds: List<Long>,
        error: String,
        now: Long,
    ): Int

    @Query("SELECT DISTINCT scanId FROM enhancement_outbox WHERE status IN ('PENDING', 'RUNNING')")
    abstract suspend fun getActiveScanIds(): List<String>

    @Query("DELETE FROM enhancement_outbox WHERE filePath IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM enhancement_outbox WHERE status = 'DONE' AND updatedAt < :before")
    abstract suspend fun deleteCompletedBefore(before: Long): Int
}
