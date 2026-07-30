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
    abstract suspend fun enqueueAll(tasks: List<ThumbnailTaskEntity>): List<Long>

    @Query(
        """UPDATE thumbnail_tasks
           SET priority = MAX(priority, :priority),
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
        now: Long,
    ): Int

    /** 唯一键插入或提升；重复 Compose 请求不会创建新任务。 */
    @Transaction
    open suspend fun enqueueOrPromote(task: ThumbnailTaskEntity) {
        val changed = promoteExisting(
            task.filePath,
            task.sizeClass,
            task.sourceVersion,
            task.priority,
            task.updatedAt,
        )
        if (changed == 0) enqueueAll(listOf(task))
    }

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE status = 'RUNNING' AND leaseUntil <= :now"""
    )
    abstract suspend fun recoverExpiredLeases(now: Long): Int

    @Query(
        """SELECT taskId FROM thumbnail_tasks
           WHERE status = 'PENDING' AND nextRetryAt <= :now
           ORDER BY priority DESC, createdAt ASC
           LIMIT :limit"""
    )
    protected abstract suspend fun findClaimableIds(now: Long, limit: Int): List<Long>

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'RUNNING', leaseUntil = :leaseUntil, leaseToken = :leaseToken,
               attemptCount = attemptCount + 1, updatedAt = :now
           WHERE taskId IN (:ids) AND status = 'PENDING' AND nextRetryAt <= :now"""
    )
    protected abstract suspend fun markClaimed(
        ids: List<Long>,
        leaseToken: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query("SELECT * FROM thumbnail_tasks WHERE leaseToken = :leaseToken AND status = 'RUNNING' ORDER BY priority DESC, createdAt ASC")
    protected abstract suspend fun getByLeaseToken(leaseToken: String): List<ThumbnailTaskEntity>

    /** Room 事务将候选选择、条件更新和本租约回读组成原子领取。 */
    @Transaction
    open suspend fun claimBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredLeases(now)
        val ids = findClaimableIds(now, limit)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'DONE', leaseUntil = 0, leaseToken = NULL, lastError = NULL, updatedAt = :now
           WHERE taskId = :taskId AND leaseToken = :leaseToken AND status = 'RUNNING'"""
    )
    abstract suspend fun markDone(taskId: Long, leaseToken: String, now: Long): Int

    @Query(
        """UPDATE thumbnail_tasks
           SET status = CASE WHEN attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
               nextRetryAt = CASE WHEN attemptCount >= :maxAttempts THEN nextRetryAt ELSE :nextRetryAt END,
               leaseUntil = 0, leaseToken = NULL, lastError = :error, updatedAt = :now
           WHERE taskId = :taskId AND leaseToken = :leaseToken AND status = 'RUNNING'"""
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

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING', 'RUNNING')")
    abstract suspend fun countActive(): Int
}
