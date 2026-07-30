package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity

@Dao
abstract class DeletionTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: DeletionTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(items: List<DeletionTombstoneEntity>)

    @Query("SELECT * FROM deletion_tombstones WHERE filePath IN (:paths)")
    abstract suspend fun getByPaths(paths: List<String>): List<DeletionTombstoneEntity>

    @Query("SELECT * FROM deletion_tombstones WHERE pathKey = :pathKey LIMIT 1")
    abstract suspend fun getByKey(pathKey: String): DeletionTombstoneEntity?

    @Query("DELETE FROM deletion_tombstones WHERE filePath IN (:paths)")
    abstract suspend fun clearForRestore(paths: List<String>): Int

    @Query(
        """UPDATE deletion_tombstones SET state = 'COMPLETED', attemptCount = attemptCount + 1,
           nextRetryAt = 0, leaseUntil = 0, leaseToken = NULL, lastErrorType = NULL,
           lastErrorCode = NULL, lastErrorMessage = NULL,
           firstAttemptAt = CASE WHEN firstAttemptAt = 0 THEN :now ELSE firstAttemptAt END,
           lastAttemptAt = :now, updatedAt = :now
           WHERE pathKey = :pathKey""",
    )
    abstract suspend fun markCompleted(pathKey: String, now: Long): Int

    @Query(
        """UPDATE deletion_tombstones SET state = :state, attemptCount = attemptCount + 1,
           nextRetryAt = :nextRetryAt, leaseUntil = 0, leaseToken = NULL,
           lastErrorType = :errorType, lastErrorCode = :errorCode, lastErrorMessage = :errorMessage,
           firstAttemptAt = CASE WHEN firstAttemptAt = 0 THEN :now ELSE firstAttemptAt END,
           lastAttemptAt = :now, updatedAt = :now
           WHERE pathKey = :pathKey""",
    )
    abstract suspend fun markFailed(
        pathKey: String,
        state: String,
        nextRetryAt: Long,
        errorType: String,
        errorCode: String?,
        errorMessage: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE deletion_tombstones SET state = 'PENDING', leaseUntil = 0, leaseToken = NULL,
           updatedAt = :now WHERE state = 'LEASED' AND leaseUntil <= :now""",
    )
    abstract suspend fun releaseExpiredLeases(now: Long): Int

    @Query(
        """SELECT pathKey FROM deletion_tombstones
           WHERE state = 'PENDING' AND nextRetryAt <= :now AND leaseUntil <= :now
           ORDER BY nextRetryAt ASC, pathKey ASC LIMIT :limit""",
    )
    protected abstract suspend fun dueKeys(now: Long, limit: Int): List<String>

    @Query(
        """UPDATE deletion_tombstones SET state = 'LEASED', leaseUntil = :leaseUntil,
           leaseToken = :leaseToken, updatedAt = :now
           WHERE pathKey IN (:keys) AND state = 'PENDING' AND nextRetryAt <= :now AND leaseUntil <= :now""",
    )
    protected abstract suspend fun leaseKeys(
        keys: List<String>,
        now: Long,
        leaseUntil: Long,
        leaseToken: String,
    ): Int

    @Query("SELECT * FROM deletion_tombstones WHERE leaseToken = :leaseToken AND state = 'LEASED' ORDER BY nextRetryAt, pathKey")
    protected abstract suspend fun leasedByToken(leaseToken: String): List<DeletionTombstoneEntity>

    /** 单事务领取，唯一 token 与租约共同阻止多个 Worker 重复执行。 */
    @Transaction
    open suspend fun claimDue(now: Long, limit: Int, leaseMs: Long, leaseToken: String): List<DeletionTombstoneEntity> {
        releaseExpiredLeases(now)
        val keys = dueKeys(now, limit)
        if (keys.isEmpty()) return emptyList()
        leaseKeys(keys, now, now + leaseMs, leaseToken)
        return leasedByToken(leaseToken)
    }
}
