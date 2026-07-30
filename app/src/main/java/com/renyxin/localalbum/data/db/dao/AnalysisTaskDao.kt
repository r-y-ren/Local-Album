package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity

@Dao
abstract class AnalysisTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(tasks: List<AnalysisTaskEntity>): List<Long>

    @Query("UPDATE analysis_tasks SET status = 'SUPERSEDED', leaseUntil = 0, leaseToken = NULL, updatedAt = :now WHERE filePath = :path AND pipelineScope = :scope AND sourceVersion != :sourceVersion AND status IN ('PENDING', 'RUNNING', 'FAILED')")
    protected abstract suspend fun supersedeOldVersions(path: String, sourceVersion: String, scope: String, now: Long): Int

    @Query("UPDATE analysis_tasks SET status = 'PENDING', attemptCount = 0, nextRetryAt = 0, leaseUntil = 0, leaseToken = NULL, lastError = NULL, priority = :priority, updatedAt = :now WHERE filePath = :path AND sourceVersion = :sourceVersion AND pipelineScope = :scope AND status IN ('DONE', 'FAILED')")
    protected abstract suspend fun reactivateExisting(path: String, sourceVersion: String, scope: String, priority: Int, now: Long): Int

    @Transaction
    open suspend fun enqueueAll(tasks: List<AnalysisTaskEntity>): List<Long> {
        if (tasks.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        tasks.forEach { task ->
            // 必须按 (path, version) 成对淘汰；两个独立 IN 集合会在多文件批次中交叉匹配。
            supersedeOldVersions(task.filePath, task.sourceVersion, task.pipelineScope, now)
            // 显式重新投递同一任务时，允许 DONE/FAILED 回到 PENDING；RUNNING 不被抢占。
            reactivateExisting(task.filePath, task.sourceVersion, task.pipelineScope, task.priority, now)
        }
        return insertIgnore(tasks)
    }

    @Query("UPDATE analysis_tasks SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAt = :now WHERE status = 'RUNNING' AND leaseUntil <= :now")
    abstract suspend fun recoverExpiredLeases(now: Long): Int

    @Query("SELECT taskId FROM analysis_tasks WHERE status = 'PENDING' AND nextRetryAt <= :now AND pipelineScope = :scope ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    protected abstract suspend fun findClaimableIds(now: Long, limit: Int, scope: String): List<Long>

    @Query("UPDATE analysis_tasks SET status = 'RUNNING', leaseUntil = :leaseUntil, leaseToken = :leaseToken, attemptCount = attemptCount + 1, updatedAt = :now WHERE taskId IN (:ids) AND status = 'PENDING' AND nextRetryAt <= :now")
    protected abstract suspend fun markClaimed(ids: List<Long>, leaseToken: String, leaseUntil: Long, now: Long): Int

    @Query("SELECT * FROM analysis_tasks WHERE leaseToken = :leaseToken AND status = 'RUNNING' ORDER BY priority DESC, createdAt ASC")
    protected abstract suspend fun getByLeaseToken(leaseToken: String): List<AnalysisTaskEntity>

    @Transaction
    open suspend fun claimBatch(now: Long, limit: Int, leaseToken: String, leaseDurationMs: Long, scope: String = AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE): List<AnalysisTaskEntity> {
        recoverExpiredLeases(now)
        val ids = findClaimableIds(now, limit, scope)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Query("UPDATE analysis_tasks SET status = 'DONE', leaseUntil = 0, leaseToken = NULL, lastError = NULL, updatedAt = :now WHERE taskId IN (:taskIds) AND leaseToken = :leaseToken AND status = 'RUNNING'")
    abstract suspend fun markDone(taskIds: List<Long>, leaseToken: String, now: Long): Int

    @Query("UPDATE analysis_tasks SET leaseUntil = :leaseUntil, updatedAt = :now WHERE leaseToken = :leaseToken AND status = 'RUNNING'")
    abstract suspend fun renewLease(leaseToken: String, leaseUntil: Long, now: Long): Int

    @Query("UPDATE analysis_tasks SET status = CASE WHEN attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END, nextRetryAt = :nextRetryAt, leaseUntil = 0, leaseToken = NULL, lastError = :error, updatedAt = :now WHERE taskId IN (:taskIds) AND leaseToken = :leaseToken AND status = 'RUNNING'")
    abstract suspend fun markFailed(taskIds: List<Long>, leaseToken: String, error: String, nextRetryAt: Long, maxAttempts: Int, now: Long): Int

    @Query("UPDATE analysis_tasks SET status = 'PENDING', attemptCount = 0, nextRetryAt = 0, leaseUntil = 0, leaseToken = NULL, lastError = NULL, priority = :priority, updatedAt = :now WHERE filePath IN (:paths) AND pipelineScope = :scope AND status != 'SUPERSEDED'")
    abstract suspend fun resetByPaths(paths: List<String>, priority: Int, now: Long, scope: String = AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE): Int

    @Query("UPDATE analysis_tasks SET status = 'PENDING', attemptCount = 0, nextRetryAt = 0, leaseUntil = 0, leaseToken = NULL, lastError = NULL, priority = :priority, updatedAt = :now WHERE pipelineScope = :scope AND status != 'SUPERSEDED'")
    abstract suspend fun resetAll(priority: Int, now: Long, scope: String = AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE): Int

    @Query("DELETE FROM analysis_tasks WHERE filePath IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>)

    @Query("SELECT COUNT(*) FROM analysis_tasks WHERE status IN ('PENDING', 'RUNNING') AND pipelineScope = :scope")
    abstract suspend fun countActive(scope: String = AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE): Int
}
