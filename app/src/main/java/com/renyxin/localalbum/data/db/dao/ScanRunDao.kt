package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.ScanRunEntity

/** 扫描运行状态机；所有状态迁移都要求当前状态仍为 RUNNING。 */
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

    @Query("SELECT MAX(generation) FROM scan_runs")
    abstract suspend fun getMaxGeneration(): Long?

    @Query(
        """SELECT status = 'RUNNING' AND mediaStoreCompleted = 1 AND fileSystemCompleted = 1
           FROM scan_runs WHERE scanId = :scanId LIMIT 1""",
    )
    abstract suspend fun canDeleteOrphans(scanId: String): Boolean?

    @Query(
        """UPDATE scan_runs SET status = 'COMPLETED', completedAt = :completedAt, errorType = NULL
           WHERE scanId = :scanId AND status = 'RUNNING'
             AND mediaStoreCompleted = 1 AND fileSystemCompleted = 1""",
    )
    protected abstract suspend fun completeIfSourcesReady(scanId: String, completedAt: Long): Int

    @Transaction
    open suspend fun markCompleted(scanId: String, completedAt: Long): Boolean =
        completeIfSourcesReady(scanId, completedAt) == 1

    @Query(
        """UPDATE scan_runs SET status = :status, completedAt = :completedAt, errorType = :errorType
           WHERE scanId = :scanId AND status = 'RUNNING'""",
    )
    abstract suspend fun markTerminal(
        scanId: String,
        status: String,
        completedAt: Long,
        errorType: String?,
    ): Int

    @Query("SELECT status = 'COMPLETED' FROM scan_runs WHERE scanId = :scanId LIMIT 1")
    abstract suspend fun isCompleted(scanId: String): Boolean?

    @Query(
        """UPDATE scan_runs SET status = 'ABORTED', completedAt = :now, errorType = 'process_restarted'
           WHERE status = 'RUNNING'""",
    )
    abstract suspend fun abortStaleRunning(now: Long): Int
}
