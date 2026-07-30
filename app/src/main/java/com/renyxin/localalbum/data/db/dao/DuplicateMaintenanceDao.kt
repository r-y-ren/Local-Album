package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.DuplicateGroupEntity
import com.renyxin.localalbum.data.db.entity.DuplicateHashStagingEntity
import com.renyxin.localalbum.data.db.entity.DuplicateMemberEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DuplicateMaintenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRun(run: MaintenanceRunEntity)

    @Query("SELECT COALESCE(MAX(generation), 0) FROM maintenance_runs WHERE taskType = :taskType")
    abstract suspend fun maxGeneration(taskType: String): Long

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND status IN ('RUNNING', 'FINALIZING') ORDER BY generation DESC LIMIT 1")
    abstract suspend fun resumableRun(taskType: String): MaintenanceRunEntity?

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND isActive = 1 ORDER BY generation DESC LIMIT 1")
    abstract fun observeActiveRun(taskType: String): Flow<MaintenanceRunEntity?>

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND isActive = 1 ORDER BY generation DESC LIMIT 1")
    abstract suspend fun getActiveRun(taskType: String): MaintenanceRunEntity?

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType ORDER BY generation DESC LIMIT 1")
    abstract fun observeLatestRun(taskType: String): Flow<MaintenanceRunEntity?>

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND generation = :generation LIMIT 1")
    abstract suspend fun getRun(taskType: String, generation: Long): MaintenanceRunEntity?

    @Query("""
        UPDATE maintenance_runs SET cursorPath = :cursorPath,
            scannedCount = scannedCount + :scanned,
            eligibleCount = eligibleCount + :eligible,
            failedCount = failedCount + :failed,
            updatedAt = :now, error = NULL
        WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'
    """)
    abstract suspend fun advanceCursor(
        taskType: String,
        generation: Long,
        cursorPath: String,
        scanned: Int,
        eligible: Int,
        failed: Int,
        now: Long,
    ): Int

    @Query("UPDATE maintenance_runs SET status = 'FINALIZING', updatedAt = :now WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'")
    abstract suspend fun markFinalizing(taskType: String, generation: Long, now: Long): Int

    @Query("UPDATE maintenance_runs SET status = 'FAILED', error = :error, updatedAt = :now WHERE taskType = :taskType AND generation = :generation AND status IN ('RUNNING', 'FINALIZING')")
    abstract suspend fun markFailed(taskType: String, generation: Long, error: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHashes(items: List<DuplicateHashStagingEntity>)

    @Query("DELETE FROM duplicate_groups WHERE generation = :generation")
    abstract suspend fun clearGroups(generation: Long)

    @Query("DELETE FROM duplicate_members WHERE generation = :generation")
    abstract suspend fun clearMembers(generation: Long)

    @Query("""
        INSERT INTO duplicate_groups
            (generation, groupId, exactHash, memberCount, totalBytes, wastedBytes,
             recommendedKeepPath, invalidated, createdAt)
        SELECT generation, exactHash, exactHash, COUNT(*), SUM(fileSize),
               SUM(fileSize) - MAX(fileSize), MIN(filePath), 0, :now
        FROM duplicate_hash_staging
        WHERE generation = :generation
        GROUP BY generation, exactHash HAVING COUNT(*) >= 2
    """)
    abstract suspend fun materializeGroups(generation: Long, now: Long)

    @Query("""
        INSERT INTO duplicate_members
            (generation, groupId, filePath, fileSize, qualityScore, modifiedAtMs, isRecommendedKeep)
        SELECT s.generation, s.exactHash, s.filePath, s.fileSize, s.qualityScore, s.modifiedAtMs,
               CASE WHEN s.filePath = g.recommendedKeepPath THEN 1 ELSE 0 END
        FROM duplicate_hash_staging s
        INNER JOIN duplicate_groups g
          ON g.generation = s.generation AND g.exactHash = s.exactHash
        WHERE s.generation = :generation
    """)
    abstract suspend fun materializeMembers(generation: Long)

    @Query("SELECT COUNT(*) FROM duplicate_groups WHERE generation = :generation AND invalidated = 0")
    abstract suspend fun countGroups(generation: Long): Long

    @Query("SELECT COUNT(*) FROM duplicate_members WHERE generation = :generation")
    abstract suspend fun countMembers(generation: Long): Long

    @Query("UPDATE maintenance_runs SET isActive = 0 WHERE taskType = :taskType AND isActive = 1")
    protected abstract suspend fun deactivateRuns(taskType: String)

    @Query("""
        UPDATE maintenance_runs SET status = 'COMPLETED', isActive = 1,
            groupCount = :groupCount, memberCount = :memberCount,
            error = NULL, updatedAt = :now, completedAt = :now
        WHERE taskType = :taskType AND generation = :generation AND status = 'FINALIZING'
    """)
    protected abstract suspend fun publishRun(
        taskType: String,
        generation: Long,
        groupCount: Long,
        memberCount: Long,
        now: Long,
    ): Int

    @Query("DELETE FROM duplicate_hash_staging WHERE generation = :generation")
    abstract suspend fun deleteStaging(generation: Long)

    @Transaction
    open suspend fun finalizeAndPublish(taskType: String, generation: Long, now: Long): Boolean {
        clearMembers(generation)
        clearGroups(generation)
        materializeGroups(generation, now)
        materializeMembers(generation)
        val groups = countGroups(generation)
        val members = countMembers(generation)
        deactivateRuns(taskType)
        val published = publishRun(taskType, generation, groups, members, now) == 1
        if (published) deleteStaging(generation)
        return published
    }

    @Query("""
        SELECT g.* FROM duplicate_groups g
        INNER JOIN maintenance_runs r
          ON r.generation = g.generation AND r.taskType = :taskType AND r.isActive = 1
        WHERE g.invalidated = 0
        ORDER BY g.wastedBytes DESC, g.groupId ASC
        LIMIT :limit OFFSET :offset
    """)
    abstract fun observeActiveGroups(taskType: String, limit: Int, offset: Int): Flow<List<DuplicateGroupEntity>>

    @Query("SELECT * FROM duplicate_members WHERE generation = :generation AND groupId = :groupId ORDER BY isRecommendedKeep DESC, filePath ASC LIMIT :limit")
    abstract suspend fun getMembersBounded(generation: Long, groupId: String, limit: Int): List<DuplicateMemberEntity>

    /** 删除路径也用稳定 keyset 分批领取，单个超大重复组不会一次进入内存。 */
    @Query("""
        SELECT filePath FROM duplicate_members
        WHERE generation = :generation AND groupId = :groupId
          AND filePath != :keepPath AND filePath > :afterPath
        ORDER BY filePath ASC LIMIT :limit
    """)
    abstract suspend fun getDeletionPathsAfter(
        generation: Long,
        groupId: String,
        keepPath: String,
        afterPath: String,
        limit: Int,
    ): List<String>

    @Query("SELECT * FROM duplicate_groups WHERE generation = :generation AND groupId = :groupId AND invalidated = 0 LIMIT 1")
    abstract suspend fun getValidGroup(generation: Long, groupId: String): DuplicateGroupEntity?

    @Query("UPDATE duplicate_groups SET invalidated = 1 WHERE generation = :generation AND groupId = :groupId AND invalidated = 0")
    abstract suspend fun invalidateGroup(generation: Long, groupId: String): Int

    @Query("DELETE FROM duplicate_members WHERE generation = :generation AND groupId = :groupId AND filePath IN (:paths)")
    abstract suspend fun deleteMembers(generation: Long, groupId: String, paths: List<String>)

    /** 路径移除后旧离线结果整体失效；不在删除事务中昂贵重算推荐保留项与字节统计。 */
    @Query("UPDATE duplicate_groups SET invalidated = 1 WHERE EXISTS (SELECT 1 FROM duplicate_members m WHERE m.generation = duplicate_groups.generation AND m.groupId = duplicate_groups.groupId AND m.filePath IN (:paths))")
    abstract suspend fun invalidateGroupsContaining(paths: List<String>): Int

    @Query("DELETE FROM duplicate_members WHERE filePath IN (:paths)")
    abstract suspend fun deleteMembersByPaths(paths: List<String>): Int

    @Query("DELETE FROM duplicate_hash_staging WHERE filePath IN (:paths)")
    abstract suspend fun deleteStagingByPaths(paths: List<String>): Int
}
