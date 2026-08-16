package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.FaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 人脸数据访问对象（Phase 3.3 人脸聚类）。
 *
 * 提供人脸记录的增删改查，以及按聚类分组查询的能力。
 */
@Dao
interface FaceDao {

    // ---- 插入 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    // ---- 删除 ----

    @Query("DELETE FROM faces WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)

    @Query("DELETE FROM faces WHERE filePath IN (:filePaths)")
    @Transaction
    suspend fun deleteByFilePaths(filePaths: List<String>)

    @Query("DELETE FROM faces")
    @Transaction
    suspend fun clearAll()

    // ---- 查询 ----

    /** 仅供 DatabaseExporter 历史单 JSON 兼容导出，运行时路径禁止调用。 */
    @Deprecated("Legacy single-JSON export only; use keyset paging for bounded export")
    @Query("SELECT * FROM faces")
    suspend fun getAllForLegacyExport(): List<FaceEntity>

    @Query("SELECT * FROM faces ORDER BY faceId LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE faceId > :afterId ORDER BY faceId LIMIT :limit")
    suspend fun getPagedAfter(afterId: Long, limit: Int): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE filePath = :filePath")
    suspend fun getByFilePath(filePath: String): List<FaceEntity>

    /** 仅读取当前分析批次的人脸，用于首次建立小范围人物簇。 */
    @Query("SELECT * FROM faces WHERE filePath IN (:filePaths)")
    suspend fun getByFilePaths(filePaths: List<String>): List<FaceEntity>

    /** 维护专用：按 faceId keyset 有界读取已有聚类，禁止 getAll/全量 DBSCAN。 */
    @Query("""
        SELECT * FROM faces
        WHERE faceId > :afterId AND clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'
        ORDER BY faceId LIMIT :limit
    """)
    suspend fun getClusteredAfter(afterId: Long, limit: Int): List<FaceEntity>

    /** 维护专用：pending 也按稳定 keyset 分批，供原型两阶段归并。 */
    @Query("SELECT * FROM faces WHERE faceId > :afterId ORDER BY faceId LIMIT :limit")
    suspend fun getMaintenanceBatchAfter(afterId: Long, limit: Int): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE clusterId = :clusterId ORDER BY detectedAtMs DESC")
    suspend fun getByCluster(clusterId: String): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE clusterId = :clusterId ORDER BY detectedAtMs DESC")
    fun getByClusterFlow(clusterId: String): Flow<List<FaceEntity>>

    /**
     * 查询所有非空 clusterId（去重），用于人脸相册分组展示。
     */
    @Query("SELECT DISTINCT clusterId FROM faces WHERE clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'")
    suspend fun getClusterIds(): List<String>

    /**
     * 查询每个聚类的代表信息（clusterId + 去重后的照片数量 + 代表缩略图路径）。
     * 一个文件可能检测到多张同一人物的人脸，界面展示的是照片而非人脸框，
     * 因此必须使用 COUNT(DISTINCT filePath)，使卡片数量与详情相册一致。
     */
    @Query("""
        SELECT clusterId, COUNT(DISTINCT filePath) AS faceCount,
               (SELECT thumbnailPath FROM faces f2
                 WHERE f2.clusterId = f1.clusterId
                 ORDER BY f2.detectedAtMs ASC LIMIT 1) AS representativeThumb,
               (SELECT filePath FROM faces f2
                 WHERE f2.clusterId = f1.clusterId
                 ORDER BY f2.detectedAtMs ASC LIMIT 1) AS representativeFilePath,
               COALESCE(
                 (SELECT personName FROM face_cluster_meta meta
                   WHERE meta.clusterId = f1.clusterId AND meta.personName IS NOT NULL
                   ORDER BY meta.generation DESC LIMIT 1),
                 (SELECT personName FROM faces f3
                   WHERE f3.clusterId = f1.clusterId AND f3.personName IS NOT NULL
                   LIMIT 1)
               ) AS personName
        FROM faces f1
        WHERE clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'
        GROUP BY clusterId
        ORDER BY faceCount DESC
    """)
    suspend fun getClusterSummaries(): List<FaceClusterSummary>

    /**
     * 查询每个聚类的代表信息（Flow 版本，用于 UI 实时更新）。
     */
    @Query("""
        SELECT clusterId, COUNT(DISTINCT filePath) AS faceCount,
               (SELECT thumbnailPath FROM faces f2
                 WHERE f2.clusterId = f1.clusterId
                 ORDER BY f2.detectedAtMs ASC LIMIT 1) AS representativeThumb,
               (SELECT filePath FROM faces f2
                 WHERE f2.clusterId = f1.clusterId
                 ORDER BY f2.detectedAtMs ASC LIMIT 1) AS representativeFilePath,
               COALESCE(
                 (SELECT personName FROM face_cluster_meta meta
                   WHERE meta.clusterId = f1.clusterId AND meta.personName IS NOT NULL
                   ORDER BY meta.generation DESC LIMIT 1),
                 (SELECT personName FROM faces f3
                   WHERE f3.clusterId = f1.clusterId AND f3.personName IS NOT NULL
                   LIMIT 1)
               ) AS personName
        FROM faces f1
        WHERE clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'
        GROUP BY clusterId
        ORDER BY faceCount DESC
    """)
    fun getClusterSummariesFlow(): Flow<List<FaceClusterSummary>>

    /** 单行聚合统计；faces 表任意批次提交后由 Room 自动重新发射。 */
    @Query("""
        SELECT COUNT(*) AS totalFaces,
               COALESCE(SUM(CASE WHEN clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'
                                 THEN 1 ELSE 0 END), 0) AS clusteredFaces,
               COUNT(DISTINCT CASE WHEN clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'
                                   THEN clusterId ELSE NULL END) AS clusterCount
        FROM faces
    """)
    fun getStatsFlow(): Flow<FaceStatsSummary>

    // ---- 更新 ----

    @Query("UPDATE faces SET clusterId = :clusterId WHERE faceId IN (:faceIds)")
    @Transaction
    suspend fun updateClusterIds(faceIds: List<Long>, clusterId: String)

    /** 增量人脸归类：按本批文件路径写入同一聚类 ID，避免回读所有人脸 ID。 */
    @Query("UPDATE faces SET clusterId = :clusterId WHERE filePath IN (:filePaths)")
    @Transaction
    suspend fun updateClusterIdsByFilePaths(filePaths: List<String>, clusterId: String)

    @Query("UPDATE faces SET clusterId = :clusterId WHERE faceId = :faceId")
    suspend fun updateClusterId(faceId: Long, clusterId: String)

    @Query("UPDATE faces SET clusterId = NULL")
    suspend fun clearAllClusterIds()

    @Query("UPDATE faces SET personName = :name WHERE clusterId = :clusterId")
    suspend fun setPersonName(clusterId: String, name: String?)

    // ---- 统计 ----

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM faces WHERE clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'")
    suspend fun getClusteredCount(): Int

    @Query("SELECT COUNT(DISTINCT clusterId) FROM faces WHERE clusterId IS NOT NULL AND clusterId NOT LIKE 'pending:%'")
    suspend fun getClusterCount(): Int
}

/** 人脸聚类摘要，用于人脸相册分组展示。 */
data class FaceClusterSummary(
    val clusterId: String,
    val faceCount: Int,
    val representativeThumb: String?,
    val representativeFilePath: String?,
    val personName: String? = null,
)

/** 人物页统计聚合 DTO。 */
data class FaceStatsSummary(
    val totalFaces: Int,
    val clusteredFaces: Int,
    val clusterCount: Int,
)
