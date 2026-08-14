package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity

@Dao
abstract class ThumbnailTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(tasks: List<ThumbnailTaskEntity>): List<Long>

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'SUPERSEDED', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE filePath = :filePath AND sizeClass = :sizeClass
             AND sourceVersion != :sourceVersion
             AND status IN ('PENDING', 'RUNNING', 'FAILED')""",
    )
    protected abstract suspend fun supersedeOldVersions(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_tasks
           SET priority = MAX(priority, :priority),
               scanId = CASE WHEN status = 'RUNNING' THEN scanId ELSE :scanId END,
               status = CASE WHEN status IN ('DONE', 'FAILED') THEN 'PENDING' ELSE status END,
               attemptCount = CASE WHEN status IN ('DONE', 'FAILED') THEN 0 ELSE attemptCount END,
               nextRetryAt = CASE WHEN status IN ('DONE', 'FAILED') THEN 0 ELSE nextRetryAt END,
               lastError = CASE WHEN status IN ('DONE', 'FAILED') THEN NULL ELSE lastError END,
               updatedAt = :now
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND sourceVersion = :sourceVersion""",
    )
    protected abstract suspend fun promoteExisting(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        priority: Int,
        scanId: String?,
        now: Long,
    ): Int

    /** Unique-key insertion/promotion; repeated UI or outbox requests never create duplicates. */
    @Transaction
    open suspend fun enqueueOrPromote(task: ThumbnailTaskEntity) {
        supersedeOldVersions(task.filePath, task.sizeClass, task.sourceVersion, task.updatedAt)
        val changed = promoteExisting(
            task.filePath,
            task.sizeClass,
            task.sourceVersion,
            task.priority,
            task.scanId,
            task.updatedAt,
        )
        if (changed == 0) insertIgnore(listOf(task))
    }

    @Transaction
    open suspend fun enqueueAll(tasks: List<ThumbnailTaskEntity>): List<Long> {
        if (tasks.isEmpty()) return emptyList()
        tasks.forEach { task -> enqueueOrPromote(task) }
        return emptyList()
    }

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING' AND leaseUntil <= :now""",
    )
    abstract suspend fun recoverExpiredLeases(now: Long): Int

    /**
     * Caller must own the automatic lane, proving no live background ThumbnailWorker owns a lease.
     * Interactive leases are deliberately excluded because they use an independent resource lane.
     */
    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING' AND scanId IS NOT NULL""",
    )
    abstract suspend fun recoverInterruptedAutomaticLeases(now: Long): Int

    /** Caller must own the interactive lane; automatic scan-owned leases are deliberately excluded. */
    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING' AND scanId IS NULL""",
    )
    abstract suspend fun recoverInterruptedInteractiveLeases(now: Long): Int

    @Query(
        """SELECT taskId FROM thumbnail_tasks
           WHERE status = 'PENDING' AND nextRetryAt <= :now
              AND (scanId IS NULL OR scanId IN (
                  SELECT scanId FROM scan_runs
                  WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                    AND enhancementState IN ('QUEUED', 'RUNNING')
              ))
           ORDER BY priority DESC, createdAt ASC
           LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableIds(now: Long, limit: Int): List<Long>

    @Query(
        """SELECT taskId FROM thumbnail_tasks
           WHERE status = 'PENDING' AND nextRetryAt <= :now AND scanId IS NULL
           ORDER BY priority DESC, createdAt ASC
           LIMIT :limit""",
    )
    protected abstract suspend fun findInteractiveClaimableIds(now: Long, limit: Int): List<Long>

    @Query(
        """SELECT taskId FROM thumbnail_tasks
           WHERE status = 'PENDING' AND nextRetryAt <= :now
             AND scanId IN (
                 SELECT scanId FROM scan_runs
                 WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                   AND enhancementState IN ('QUEUED', 'RUNNING')
             )
           ORDER BY priority DESC, createdAt ASC
           LIMIT :limit""",
    )
    protected abstract suspend fun findAutomaticClaimableIds(now: Long, limit: Int): List<Long>

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'RUNNING', leaseUntil = :leaseUntil, leaseToken = :leaseToken,
               attemptCount = attemptCount + 1, updatedAt = :now
           WHERE taskId IN (:ids) AND status = 'PENDING' AND nextRetryAt <= :now""",
    )
    protected abstract suspend fun markClaimed(
        ids: List<Long>,
        leaseToken: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query("SELECT * FROM thumbnail_tasks WHERE leaseToken = :leaseToken AND status = 'RUNNING' ORDER BY priority DESC, createdAt ASC")
    protected abstract suspend fun getByLeaseToken(leaseToken: String): List<ThumbnailTaskEntity>

    /** Room transaction makes candidate selection, conditional update and lease read atomic. */
    @Transaction
    open suspend fun claimBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredLeases(now)
        return claimSelected(
            now,
            leaseToken,
            leaseDurationMs,
            findClaimableIds(now, limit),
        )
    }

    /** Interactive workers must never smuggle scan-owned pre-generation through the UI lane. */
    @Transaction
    open suspend fun claimInteractiveBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredLeases(now)
        return claimSelected(
            now,
            leaseToken,
            leaseDurationMs,
            findInteractiveClaimableIds(now, limit),
        )
    }

    /** Automatic workers consume only post-core scan-owned tasks. */
    @Transaction
    open suspend fun claimAutomaticBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredLeases(now)
        return claimSelected(
            now,
            leaseToken,
            leaseDurationMs,
            findAutomaticClaimableIds(now, limit),
        )
    }

    private suspend fun claimSelected(
        now: Long,
        leaseToken: String,
        leaseDurationMs: Long,
        ids: List<Long>,
    ): List<ThumbnailTaskEntity> {
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun releaseLease(leaseToken: String, now: Long): Int

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'DONE', leaseUntil = 0, leaseToken = NULL, lastError = NULL, updatedAt = :now
           WHERE taskId = :taskId AND leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun markDone(taskId: Long, leaseToken: String, now: Long): Int

    @Query(
        """UPDATE thumbnail_tasks
           SET status = CASE WHEN attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
               nextRetryAt = CASE WHEN attemptCount >= :maxAttempts THEN nextRetryAt ELSE :nextRetryAt END,
               leaseUntil = 0, leaseToken = NULL, lastError = :error, updatedAt = :now
           WHERE taskId = :taskId AND leaseToken = :leaseToken AND status = 'RUNNING'""",
    )
    abstract suspend fun markFailed(
        taskId: Long,
        leaseToken: String,
        error: String,
        nextRetryAt: Long,
        maxAttempts: Int,
        now: Long,
    ): Int

    @Query("DELETE FROM thumbnail_tasks WHERE filePath IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>)

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING', 'RUNNING') AND scanId IS NULL")
    abstract suspend fun countInteractiveActive(): Int

    @Query(
        """SELECT COUNT(*) FROM thumbnail_tasks
           WHERE status IN ('PENDING', 'RUNNING')
             AND scanId IN (
                 SELECT scanId FROM scan_runs
                 WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                   AND enhancementState IN ('QUEUED', 'RUNNING')
             )""",
    )
    abstract suspend fun countAutomaticRunnable(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING', 'RUNNING')")
    abstract suspend fun countActive(): Int

    @Query(
        """SELECT COUNT(*) FROM thumbnail_tasks
           WHERE status IN ('PENDING', 'RUNNING')
             AND (scanId IS NULL OR scanId IN (
                 SELECT scanId FROM scan_runs
                 WHERE coreScanState = 'COMPLETED' AND indexAvailability = 'PUBLISHED'
                   AND enhancementState IN ('QUEUED', 'RUNNING')
             ))""",
    )
    abstract suspend fun countRunnable(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId = :scanId AND status IN ('PENDING', 'RUNNING')")
    abstract suspend fun countActiveForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId = :scanId AND status = 'FAILED'")
    abstract suspend fun countFailedForScan(scanId: String): Int
}
