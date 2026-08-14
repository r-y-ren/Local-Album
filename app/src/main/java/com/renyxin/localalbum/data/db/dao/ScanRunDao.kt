package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.ScanRunEntity

/**
 * 扫描运行状态机。
 *
 * 旧 [status] 仍是孤儿删除的硬门禁；新的核心/增强字段只允许通过这里迁移，
 * 使数据库、Worker 与 UI 使用同一份持久事实。
 */
@Dao
abstract class ScanRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(run: ScanRunEntity)

    @Query("UPDATE scan_runs SET mediaStoreCompleted = 1 WHERE scanId = :scanId AND status = 'RUNNING'")
    abstract suspend fun markMediaStoreCompleted(scanId: String): Int

    @Query("UPDATE scan_runs SET fileSystemCompleted = 1 WHERE scanId = :scanId AND status = 'RUNNING'")
    abstract suspend fun markFileSystemCompleted(scanId: String): Int

    @Query("SELECT * FROM scan_runs WHERE scanId = :scanId LIMIT 1")
    abstract suspend fun getById(scanId: String): ScanRunEntity?

    @Query("SELECT * FROM scan_runs ORDER BY generation DESC LIMIT 1")
    abstract suspend fun getLatest(): ScanRunEntity?

    @Query("SELECT * FROM scan_runs ORDER BY generation DESC LIMIT 1")
    abstract fun observeLatest(): kotlinx.coroutines.flow.Flow<ScanRunEntity?>

    @Query("SELECT MAX(generation) FROM scan_runs")
    abstract suspend fun getMaxGeneration(): Long?

    @Query(
        """SELECT status = 'RUNNING' AND mediaStoreCompleted = 1 AND fileSystemCompleted = 1
           FROM scan_runs WHERE scanId = :scanId LIMIT 1""",
    )
    abstract suspend fun canDeleteOrphans(scanId: String): Boolean?

    @Query(
        """UPDATE scan_runs
           SET indexAvailability = :availability,
               coreScanState = :coreState,
               firstBatchCommittedAt = CASE WHEN firstBatchCommittedAt = 0 THEN :now ELSE firstBatchCommittedAt END,
               indexAvailableAt = CASE WHEN indexAvailableAt = 0 THEN :now ELSE indexAvailableAt END
           WHERE scanId = :scanId AND status = 'RUNNING'
             AND indexAvailability = 'EMPTY'""",
    )
    abstract suspend fun markIndexAvailable(
        scanId: String,
        availability: String = IndexAvailability.PARTIAL.name,
        coreState: String = CoreScanState.APPLYING_CHANGES.name,
        now: Long,
    ): Int

    @Query(
        """UPDATE scan_runs SET coreScanState = :state
           WHERE scanId = :scanId AND status = 'RUNNING'""",
    )
    abstract suspend fun markCoreState(scanId: String, state: String): Int

    @Query(
        """UPDATE scan_runs
           SET coreScanState = 'PUBLISHING',
               errorType = NULL
           WHERE scanId = :scanId AND status = 'RUNNING'
             AND mediaStoreCompleted = 1 AND fileSystemCompleted = 1""",
    )
    protected abstract suspend fun completeCoreIfSourcesReady(scanId: String): Int

    /**
     * Source reconciliation is complete, but the repository still has to publish the
     * directory snapshot. The new core state therefore remains PUBLISHING for that interval.
     */
    @Transaction
    open suspend fun markCoreReadyForSnapshot(scanId: String): Boolean =
        completeCoreIfSourcesReady(scanId) == 1

    /**
     * Commits the user-visible core terminal state only after the snapshot pointer is published.
     */
    @Query(
        """UPDATE scan_runs
           SET status = 'COMPLETED',
               completedAt = :publishedAt,
               indexAvailability = 'PUBLISHED',
               indexAvailableAt = CASE WHEN indexAvailableAt = 0 THEN :publishedAt ELSE indexAvailableAt END,
               coreScanState = 'COMPLETED',
               coreCompletedAt = :publishedAt
           WHERE scanId = :scanId AND status = 'RUNNING' AND coreScanState = 'PUBLISHING'""",
    )
    abstract suspend fun markSnapshotPublished(scanId: String, publishedAt: Long): Int

    /** Legacy name retained for existing callers and tests. */
    @Transaction
    open suspend fun markCoreCompleted(scanId: String, completedAt: Long): Boolean {
        if (!markCoreReadyForSnapshot(scanId)) return false
        return markSnapshotPublished(scanId, completedAt) == 1
    }

    /** Legacy name retained for existing callers and tests. */
    @Transaction
    open suspend fun markCompleted(scanId: String, completedAt: Long): Boolean =
        markCoreCompleted(scanId, completedAt)

    @Query(
        """UPDATE scan_runs
           SET status = :status,
               completedAt = :completedAt,
               coreScanState = CASE
                   WHEN :status = 'FAILED' THEN 'FAILED'
                   WHEN :status = 'ABORTED' THEN 'CANCELLED'
                   ELSE coreScanState
               END,
               errorType = :errorType
           WHERE scanId = :scanId AND status = 'RUNNING'""",
    )
    abstract suspend fun markTerminal(
        scanId: String,
        status: String,
        completedAt: Long,
        errorType: String?,
    ): Int

    @Query(
        """UPDATE scan_runs
           SET status = 'ABORTED',
               completedAt = :now,
               coreScanState = 'PAUSED',
               errorType = 'process_restarted'
           WHERE status = 'RUNNING' AND startedAt < :startedBefore""",
    )
    abstract suspend fun abortStaleRunning(now: Long, startedBefore: Long = now): Int

    /** A killed process leaves enhancement/outbox leases to their DAOs; make the UI resumable. */
    @Query(
        """UPDATE scan_runs SET enhancementState = 'QUEUED'
           WHERE coreScanState = 'COMPLETED' AND enhancementState = 'RUNNING'""",
    )
    abstract suspend fun recoverRunningEnhancements(): Int

    @Query(
        """SELECT scanId FROM scan_runs
           WHERE coreScanState = 'COMPLETED'
              AND enhancementState IN ('QUEUED', 'RUNNING')
              AND (
                  EXISTS(SELECT 1 FROM enhancement_outbox o WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING'))
                  OR EXISTS(SELECT 1 FROM analysis_tasks a WHERE a.scanId = scan_runs.scanId AND a.status IN ('PENDING', 'RUNNING'))
                  OR EXISTS(SELECT 1 FROM thumbnail_tasks t WHERE t.scanId = scan_runs.scanId AND t.status IN ('PENDING', 'RUNNING'))
              )""",
    )
    abstract suspend fun getEnhancementScanIds(): List<String>

    /**
     * Includes queued/running runs whose final active task was just superseded. Consumers use this
     * broader set only while reconciling terminal state; normal scheduling still uses
     * [getEnhancementScanIds] and therefore never wakes an empty queue.
     */
    @Query(
        """SELECT scanId FROM scan_runs
           WHERE coreScanState = 'COMPLETED'
             AND enhancementState IN ('QUEUED', 'RUNNING')""",
    )
    abstract suspend fun getUnsettledEnhancementScanIds(): List<String>

    @Query(
        """UPDATE scan_runs
           SET enhancementState = CASE
               WHEN enhancementState = 'WAITING_FOR_CORE' THEN 'WAITING_FOR_CORE_PAUSED'
               ELSE 'PAUSED'
           END
           WHERE scanId = :scanId
             AND coreScanState = 'COMPLETED'
             AND enhancementState IN (
                 'NOT_SCHEDULED', 'WAITING_FOR_CORE', 'QUEUED', 'RUNNING', 'PREEMPTED'
             )""",
    )
    abstract suspend fun markEnhancementPaused(scanId: String): Int

    /** Core work may preempt automatic work, but must never overwrite a durable user pause. */
    @Query(
        """UPDATE scan_runs SET enhancementState = 'PREEMPTED'
           WHERE coreScanState = 'COMPLETED'
             AND enhancementState IN ('NOT_SCHEDULED', 'QUEUED', 'RUNNING')
             AND (
                 EXISTS(SELECT 1 FROM enhancement_outbox o WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM analysis_tasks a WHERE a.scanId = scan_runs.scanId AND a.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM thumbnail_tasks t WHERE t.scanId = scan_runs.scanId AND t.status IN ('PENDING', 'RUNNING'))
             )""",
    )
    abstract suspend fun preemptAllActiveEnhancements(): Int

    /** User cancellation persists across process restart and dominates core/pre-reconciliation waits. */
    @Query(
        """UPDATE scan_runs
           SET enhancementState = CASE
               WHEN enhancementState = 'WAITING_FOR_CORE' THEN 'WAITING_FOR_CORE_PAUSED'
               ELSE 'PAUSED'
           END
           WHERE coreScanState = 'COMPLETED'
             AND enhancementState IN (
                 'NOT_SCHEDULED', 'WAITING_FOR_CORE', 'QUEUED', 'RUNNING', 'PREEMPTED'
             )
             AND (
                 EXISTS(SELECT 1 FROM enhancement_outbox o WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM analysis_tasks a WHERE a.scanId = scan_runs.scanId AND a.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM thumbnail_tasks t WHERE t.scanId = scan_runs.scanId AND t.status IN ('PENDING', 'RUNNING'))
             )""",
    )
    abstract suspend fun pauseAllActiveEnhancements(): Int

    /** Automatic recovery is deliberately limited to core-preempted runs. */
    @Query(
        """UPDATE scan_runs SET enhancementState = 'QUEUED'
           WHERE coreScanState = 'COMPLETED' AND enhancementState = 'PREEMPTED'
             AND (
                 EXISTS(SELECT 1 FROM enhancement_outbox o WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM analysis_tasks a WHERE a.scanId = scan_runs.scanId AND a.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM thumbnail_tasks t WHERE t.scanId = scan_runs.scanId AND t.status IN ('PENDING', 'RUNNING'))
             )""",
    )
    abstract suspend fun resumePreemptedEnhancements(): Int

    /**
     * Only an explicit user action may resume durable pauses. A restore still waiting for its core
     * reconciliation returns to WAITING_FOR_CORE rather than bypassing that barrier.
     */
    @Query(
        """UPDATE scan_runs
           SET enhancementState = CASE
               WHEN enhancementState = 'WAITING_FOR_CORE_PAUSED' THEN 'WAITING_FOR_CORE'
               ELSE 'QUEUED'
           END
           WHERE coreScanState = 'COMPLETED'
             AND enhancementState IN ('PAUSED', 'WAITING_FOR_CORE_PAUSED')
             AND (
                 EXISTS(SELECT 1 FROM enhancement_outbox o WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM analysis_tasks a WHERE a.scanId = scan_runs.scanId AND a.status IN ('PENDING', 'RUNNING'))
                 OR EXISTS(SELECT 1 FROM thumbnail_tasks t WHERE t.scanId = scan_runs.scanId AND t.status IN ('PENDING', 'RUNNING'))
             )""",
    )
    abstract suspend fun resumeUserPausedEnhancements(): Int

    /** Releases restore-created work after a successful reconciliation without losing user pause. */
    @Query(
        """UPDATE scan_runs
           SET enhancementState = CASE
               WHEN enhancementState = 'WAITING_FOR_CORE_PAUSED' THEN 'PAUSED'
               ELSE 'QUEUED'
           END
           WHERE coreScanState = 'COMPLETED'
             AND enhancementState IN ('WAITING_FOR_CORE', 'WAITING_FOR_CORE_PAUSED')
             AND EXISTS(
                 SELECT 1 FROM enhancement_outbox o
                 WHERE o.scanId = scan_runs.scanId AND o.status IN ('PENDING', 'RUNNING')
             )""",
    )
    abstract suspend fun releaseWaitingForCoreEnhancements(): Int

    @Query(
        """UPDATE scan_runs
           SET coreScanState = 'CANCELLED', errorType = :errorType
           WHERE scanId = :scanId AND status IN ('RUNNING', 'COMPLETED')
             AND coreScanState NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')""",
    )
    abstract suspend fun markCoreCancelled(scanId: String, errorType: String = "cancelled"): Int

    @Query(
        """UPDATE scan_runs
           SET status = 'FAILED',
               completedAt = :now,
               coreScanState = 'FAILED',
               errorType = :errorType
           WHERE scanId = :scanId
             AND status = 'RUNNING'
             AND coreScanState = 'PUBLISHING'""",
    )
    abstract suspend fun markCoreFailedAfterIndex(
        errorType: String,
        scanId: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE scan_runs
           SET enhancementState = :state,
               enhancementStartedAt = CASE
                   WHEN :state = 'RUNNING' AND enhancementStartedAt = 0 THEN :now
                   ELSE enhancementStartedAt
               END
           WHERE scanId = :scanId
             AND coreScanState = 'COMPLETED'
             AND enhancementState IN ('NOT_SCHEDULED', 'QUEUED')""",
    )
    abstract suspend fun markEnhancementRunning(
        scanId: String,
        state: String = EnhancementState.RUNNING.name,
        now: Long,
    ): Int

    @Query(
        """UPDATE scan_runs
           SET enhancementState = 'QUEUED'
           WHERE scanId = :scanId
             AND coreScanState = 'COMPLETED'
             AND enhancementState IN ('NOT_SCHEDULED', 'RUNNING')""",
    )
    abstract suspend fun markEnhancementQueued(scanId: String): Int

    @Query(
        """UPDATE scan_runs
           SET enhancementState = :state,
               enhancementCompletedAt = :now
           WHERE scanId = :scanId
             AND coreScanState = 'COMPLETED'
             AND enhancementState IN ('QUEUED', 'RUNNING', 'PREEMPTED', 'PAUSED')""",
    )
    abstract suspend fun markEnhancementTerminal(scanId: String, state: String, now: Long): Int

    @Query("SELECT status = 'COMPLETED' FROM scan_runs WHERE scanId = :scanId LIMIT 1")
    abstract suspend fun isCompleted(scanId: String): Boolean?
}
