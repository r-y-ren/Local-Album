package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.FaceClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.FaceClusterPrototypeEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity

/** v23 人物原型与 generation 发布边界。所有常规读取必须通过 active run 关联。 */
@Dao
abstract class FaceClusterDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRun(run: MaintenanceRunEntity)

    @Query("SELECT COALESCE(MAX(generation), 0) FROM maintenance_runs WHERE taskType = :taskType")
    abstract suspend fun maxGeneration(taskType: String): Long

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND status IN ('RUNNING','FINALIZING') ORDER BY generation DESC LIMIT 1")
    abstract suspend fun resumableRun(taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES): MaintenanceRunEntity?

    @Query("SELECT * FROM maintenance_runs WHERE taskType = :taskType AND isActive = 1 ORDER BY generation DESC LIMIT 1")
    abstract suspend fun activeRun(taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES): MaintenanceRunEntity?

    /** 单页上限由调用者限制为 MAX_PROTOTYPES_IN_MEMORY；禁止一次读完整 generation。 */
    @Query("""
        SELECT p.* FROM face_cluster_prototypes p
        INNER JOIN maintenance_runs r ON r.generation = p.generation
          AND r.taskType = :taskType AND r.isActive = 1
        WHERE p.providerScope = :providerScope AND p.modelScope = :modelScope
          AND (p.clusterId > :afterClusterId OR (p.clusterId = :afterClusterId AND p.prototypeIndex > :afterPrototypeIndex))
        ORDER BY p.clusterId, p.prototypeIndex LIMIT :limit
    """)
    abstract suspend fun activePrototypesAfter(
        providerScope: String,
        modelScope: String,
        afterClusterId: String,
        afterPrototypeIndex: Int,
        limit: Int,
        taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES,
    ): List<FaceClusterPrototypeEntity>

    @Query("""
        SELECT * FROM face_cluster_prototypes
        WHERE generation = :generation
          AND (clusterId > :afterClusterId OR (clusterId = :afterClusterId AND prototypeIndex > :afterPrototypeIndex))
        ORDER BY clusterId, prototypeIndex LIMIT :limit
    """)
    abstract suspend fun generationPrototypesAfter(
        generation: Long,
        afterClusterId: String,
        afterPrototypeIndex: Int,
        limit: Int,
    ): List<FaceClusterPrototypeEntity>

    @Query("SELECT * FROM face_cluster_prototypes WHERE generation = :generation AND clusterId = :clusterId ORDER BY prototypeIndex LIMIT :limit")
    abstract suspend fun prototypesForCluster(generation: Long, clusterId: String, limit: Int): List<FaceClusterPrototypeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPrototype(prototype: FaceClusterPrototypeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPrototypes(prototypes: List<FaceClusterPrototypeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMeta(meta: FaceClusterMetaEntity)

    @Query("SELECT * FROM face_cluster_meta WHERE generation = :generation AND clusterId = :clusterId LIMIT 1")
    abstract suspend fun getMeta(generation: Long, clusterId: String): FaceClusterMetaEntity?

    @Query("SELECT personName FROM face_cluster_meta WHERE clusterId = :clusterId AND personName IS NOT NULL ORDER BY generation DESC LIMIT 1")
    abstract suspend fun latestPersonName(clusterId: String): String?

    @Query("UPDATE face_cluster_meta SET personName = :name, updatedAt = :now WHERE clusterId = :clusterId")
    protected abstract suspend fun updateNameInAllGenerations(clusterId: String, name: String?, now: Long)

    @Query("UPDATE faces SET personName = :name WHERE clusterId = :clusterId")
    protected abstract suspend fun updateLegacyFaceNames(clusterId: String, name: String?)

    /** 人工名称双写稳定 metadata 与 v22 faces 兼容字段。 */
    @Transaction
    open suspend fun setPersonName(clusterId: String, name: String?, now: Long = System.currentTimeMillis()) {
        updateNameInAllGenerations(clusterId, name, now)
        updateLegacyFaceNames(clusterId, name)
    }

    @Query("""
        UPDATE maintenance_runs SET cursorPath = :cursorFaceId,
          scannedCount = scannedCount + :scanned, eligibleCount = eligibleCount + :eligible,
          failedCount = failedCount + :failed, updatedAt = :now, error = NULL
        WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'
    """)
    abstract suspend fun advanceCursor(
        generation: Long,
        cursorFaceId: String,
        scanned: Int,
        eligible: Int,
        failed: Int,
        now: Long,
        taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES,
    ): Int

    @Query("UPDATE maintenance_runs SET status = 'FINALIZING', updatedAt = :now WHERE taskType = :taskType AND generation = :generation AND status = 'RUNNING'")
    abstract suspend fun markFinalizing(generation: Long, now: Long, taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES): Int

    @Query("UPDATE maintenance_runs SET status = 'FAILED', error = :error, updatedAt = :now WHERE taskType = :taskType AND generation = :generation AND status IN ('RUNNING','FINALIZING')")
    abstract suspend fun markFailed(generation: Long, error: String, now: Long, taskType: String = MaintenanceRunEntity.TASK_FACE_PROTOTYPES): Int

    @Query("UPDATE maintenance_runs SET isActive = 0 WHERE taskType = :taskType AND isActive = 1")
    protected abstract suspend fun deactivateActive(taskType: String)

    @Query("""
        UPDATE maintenance_runs SET status = 'COMPLETED', isActive = 1, error = NULL,
          updatedAt = :now, completedAt = :now
        WHERE taskType = :taskType AND generation = :generation AND status = 'FINALIZING'
    """)
    protected abstract suspend fun activate(generation: Long, now: Long, taskType: String): Int

    /** 短事务原子切换 active generation；旧 generation 原型不会混入常规读取。 */
    @Transaction
    open suspend fun publish(generation: Long, now: Long = System.currentTimeMillis()): Boolean {
        val taskType = MaintenanceRunEntity.TASK_FACE_PROTOTYPES
        deactivateActive(taskType)
        return activate(generation, now, taskType) == 1
    }
}
