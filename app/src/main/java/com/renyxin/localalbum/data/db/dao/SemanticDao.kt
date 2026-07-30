package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.SemanticClusterGenerationEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMemberEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity
import com.renyxin.localalbum.data.db.entity.SemanticMaintenanceRunEntity

@Dao
abstract class SemanticDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertIndexMeta(meta: SemanticIndexMetaEntity)

    @Query("SELECT * FROM semantic_index_meta WHERE spaceId = :spaceId LIMIT 1")
    abstract suspend fun getIndexMeta(spaceId: String): SemanticIndexMetaEntity?

    @Query("SELECT * FROM semantic_index_meta WHERE isActive = 1 LIMIT 1")
    abstract suspend fun getActiveIndexMeta(): SemanticIndexMetaEntity?

    @Query("UPDATE semantic_index_meta SET isActive = 0 WHERE isActive = 1 AND spaceId != :spaceId")
    protected abstract suspend fun deactivateOtherSpaces(spaceId: String)

    @Query("UPDATE semantic_index_meta SET isActive = 1, status = 'READY', updatedAt = :now WHERE spaceId = :spaceId")
    protected abstract suspend fun activateSpace(spaceId: String, now: Long): Int

    @Transaction
    open suspend fun activate(spaceId: String, now: Long): Boolean {
        deactivateOtherSpaces(spaceId)
        return activateSpace(spaceId, now) == 1
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMaintenanceRun(run: SemanticMaintenanceRunEntity)

    @Query("SELECT COALESCE(MAX(generation), 0) FROM semantic_maintenance_runs WHERE taskType = :taskType")
    abstract suspend fun maxMaintenanceGeneration(taskType: String): Long

    @Query("SELECT * FROM semantic_maintenance_runs WHERE taskType = :taskType AND status = 'RUNNING' ORDER BY generation DESC LIMIT 1")
    abstract suspend fun resumableMaintenanceRun(taskType: String): SemanticMaintenanceRunEntity?

    @Query("""
        UPDATE semantic_maintenance_runs SET cursorPath = :cursorPath,
          scannedCount = scannedCount + :scanned, convertedCount = convertedCount + :converted,
          failedCount = failedCount + :failed, coverageCount = coverageCount + :covered,
          lastError = :lastError, updatedAt = :now
        WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'
    """)
    abstract suspend fun advanceMaintenance(
        taskType: String,
        generation: Long,
        cursorPath: String,
        scanned: Int,
        converted: Int,
        failed: Int,
        covered: Int,
        lastError: String?,
        now: Long,
    ): Int

    @Query("UPDATE semantic_maintenance_runs SET status = 'COMPLETED', isActive = 1, completedAt = :now, updatedAt = :now WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'")
    abstract suspend fun completeMaintenance(taskType: String, generation: Long, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertClusterGeneration(generation: SemanticClusterGenerationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertClusters(clusters: List<SemanticClusterMetaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertClusterMembers(members: List<SemanticClusterMemberEntity>)

    @Query("DELETE FROM semantic_cluster_meta WHERE spaceId = :spaceId AND generation = :generation")
    abstract suspend fun clearClusters(spaceId: String, generation: Long)

    @Query("DELETE FROM semantic_cluster_members WHERE spaceId = :spaceId AND generation = :generation")
    abstract suspend fun clearClusterMembers(spaceId: String, generation: Long)

    @Query("UPDATE semantic_cluster_generations SET isActive = 0 WHERE spaceId = :spaceId AND isActive = 1")
    protected abstract suspend fun deactivateClusterGenerations(spaceId: String)

    @Query("UPDATE semantic_cluster_generations SET status = 'COMPLETED', isActive = 1, clusterCount = :clusterCount, completedAt = :now WHERE spaceId = :spaceId AND generation = :generation AND status = 'BUILDING'")
    protected abstract suspend fun publishClusterGeneration(spaceId: String, generation: Long, clusterCount: Int, now: Long): Int

    @Transaction
    open suspend fun replaceAndPublishClusters(
        generation: SemanticClusterGenerationEntity,
        clusters: List<SemanticClusterMetaEntity>,
        members: List<SemanticClusterMemberEntity>,
        now: Long,
    ): Boolean {
        clearClusterMembers(generation.spaceId, generation.generation)
        clearClusters(generation.spaceId, generation.generation)
        insertClusterGeneration(generation)
        if (clusters.isNotEmpty()) insertClusters(clusters)
        if (members.isNotEmpty()) insertClusterMembers(members)
        deactivateClusterGenerations(generation.spaceId)
        return publishClusterGeneration(generation.spaceId, generation.generation, clusters.size, now) == 1
    }

    @Query("SELECT COALESCE(MAX(generation), 0) FROM semantic_cluster_generations WHERE spaceId = :spaceId")
    abstract suspend fun maxClusterGeneration(spaceId: String): Long

    @Query("SELECT * FROM semantic_cluster_generations WHERE spaceId = :spaceId AND isActive = 1 AND status = 'COMPLETED' LIMIT 1")
    abstract suspend fun getActiveClusterGeneration(spaceId: String): SemanticClusterGenerationEntity?

    @Query("SELECT * FROM semantic_cluster_meta WHERE spaceId = :spaceId AND generation = :generation AND status = 'READY' ORDER BY score DESC, clusterId ASC")
    abstract suspend fun getClusters(spaceId: String, generation: Long): List<SemanticClusterMetaEntity>

    @Query("SELECT * FROM semantic_cluster_members WHERE spaceId = :spaceId AND generation = :generation AND clusterId = :clusterId ORDER BY rank ASC LIMIT :limit")
    abstract suspend fun getClusterMembers(spaceId: String, generation: Long, clusterId: String, limit: Int): List<SemanticClusterMemberEntity>

    /** 删除单路径成员并失效受影响 cluster；generation/index metadata 是全局控制面，不得随路径删除。 */
    @Query("UPDATE semantic_cluster_meta SET status = 'STALE' WHERE EXISTS (SELECT 1 FROM semantic_cluster_members m WHERE m.spaceId = semantic_cluster_meta.spaceId AND m.generation = semantic_cluster_meta.generation AND m.clusterId = semantic_cluster_meta.clusterId AND m.filePath IN (:paths))")
    abstract suspend fun invalidateClustersContaining(paths: List<String>): Int

    @Query("DELETE FROM semantic_cluster_members WHERE filePath IN (:paths)")
    abstract suspend fun deleteClusterMembersByPaths(paths: List<String>): Int
}
