package com.renyxin.localalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    // ---- 基础查询 ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    fun getAllFlow(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    suspend fun getAll(): List<MediaEntity>

    // ---- 分页查询 (Paging 3 支持) ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<MediaEntity>

    // ---- Paging 3 PagingSource (Room 原生分页) ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    fun pagingSource(): PagingSource<Int, MediaEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0")
    suspend fun getCount(): Int

    // ---- 全文搜索 ----
    @Query(
        """SELECT * FROM media_items WHERE isTrashed = 0 AND (
            fileName LIKE '%' || :query || '%' OR
            make LIKE '%' || :query || '%' OR
            model LIKE '%' || :query || '%' OR
            sceneType LIKE '%' || :query || '%'
        ) ORDER BY capturedAtMs DESC"""
    )
    suspend fun search(query: String): List<MediaEntity>

    @Query(
        """SELECT * FROM media_items WHERE isTrashed = 0 AND (
            fileName LIKE '%' || :query || '%' OR
            make LIKE '%' || :query || '%' OR
            model LIKE '%' || :query || '%' OR
            sceneType LIKE '%' || :query || '%'
        ) ORDER BY capturedAtMs DESC LIMIT :limit OFFSET :offset"""
    )
    suspend fun searchPaged(query: String, limit: Int, offset: Int): List<MediaEntity>

    // ---- 按时间范围搜索 ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND capturedAtMs BETWEEN :startMs AND :endMs ORDER BY capturedAtMs DESC")
    suspend fun searchByDateRange(startMs: Long, endMs: Long): List<MediaEntity>

    // ---- 按场景类型过滤 ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND sceneType = :sceneType ORDER BY capturedAtMs DESC")
    suspend fun getBySceneType(sceneType: String): List<MediaEntity>

    // ---- 收藏管理 ----
    @Query("SELECT * FROM media_items WHERE isFavorite = 1 AND isTrashed = 0 ORDER BY capturedAtMs DESC")
    fun getFavorites(): Flow<List<MediaEntity>>

    /** 轻量查询单条媒体的收藏状态，避免 toggleFavorite 全量加载 */
    @Query("SELECT isFavorite FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getFavoriteByPath(path: String): Boolean?

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE filePath = :path")
    suspend fun setFavorite(path: String, favorite: Boolean)

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE filePath IN (:paths)")
    suspend fun batchSetFavorite(paths: List<String>, favorite: Boolean)

    // ---- 回收站 ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 1 ORDER BY modifiedAtMs DESC")
    suspend fun getTrashed(): List<MediaEntity>

    // P2-3: 使用 COUNT 查询获取回收站数量，避免加载全部行到内存
    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 1")
    suspend fun getTrashedCount(): Int

    @Query("SELECT * FROM media_items WHERE isTrashed = 1 ORDER BY modifiedAtMs DESC")
    fun getTrashedFlow(): Flow<List<MediaEntity>>

    @Query("UPDATE media_items SET isTrashed = 1, deletedAtMs = :deletedAtMs WHERE filePath IN (:paths)")
    suspend fun moveToTrash(paths: List<String>, deletedAtMs: Long)

    @Query("UPDATE media_items SET isTrashed = 0, deletedAtMs = 0 WHERE filePath IN (:paths)")
    suspend fun restoreFromTrash(paths: List<String>)

    // ---- 批量操作 (事务性) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media_items WHERE filePath IN (:paths)")
    @Transaction
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM media_items WHERE isTrashed = 1")
    @Transaction
    suspend fun clearTrash()

    @Query("DELETE FROM media_items")
    @Transaction
    suspend fun clearAll()

    // ---- 增量扫描辅助 ----
    @Query("SELECT filePath, modifiedAtMs, fingerprintHead FROM media_items")
    suspend fun getModifiedTimeMap(): List<PathModifiedTime>

    @Query("SELECT filePath, modifiedAtMs FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getModifiedTimeForPath(path: String): PathModifiedTime?

    @Query("SELECT filePath FROM media_items")
    suspend fun getAllPaths(): List<String>

    // ---- 时间线查询 (v2) ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    suspend fun getAllTimelineItems(): List<MediaEntity>

    @Query("""
        SELECT * FROM media_items 
        WHERE isTrashed = 0 AND capturedAtMs BETWEEN :startMs AND :endMs 
        ORDER BY capturedAtMs DESC
    """)
    suspend fun getTimelineItemsByRange(startMs: Long, endMs: Long): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0 AND capturedAtMs BETWEEN :startMs AND :endMs")
    suspend fun getCountByDateRange(startMs: Long, endMs: Long): Int

    // ---- FTS4 全文搜索 (v3) ----
    @Query("""
        SELECT m.* FROM media_items m
        INNER JOIN media_items_fts f ON m.filePath = f.filePath
        WHERE media_items_fts MATCH :ftsQuery AND m.isTrashed = 0
        ORDER BY capturedAtMs DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchFts(ftsQuery: String, limit: Int, offset: Int): List<MediaEntity>

    @Query("""
        SELECT COUNT(*) FROM media_items m
        INNER JOIN media_items_fts f ON m.filePath = f.filePath
        WHERE media_items_fts MATCH :ftsQuery AND m.isTrashed = 0
    """)
    suspend fun countFts(ftsQuery: String): Int

    // ---- FTS 索引维护 ----
    @Query("DELETE FROM media_items_fts WHERE filePath = :filePath")
    suspend fun deleteFtsEntry(filePath: String)

    @Query("DELETE FROM media_items_fts WHERE filePath IN (:filePaths)")
    suspend fun deleteFtsEntries(filePaths: List<String>)

    /**
     * 查询全部 FTS 索引条目（Phase 4.2 数据库导出使用）。
     */
    @Query("SELECT filePath, fileName, ocrText, make, model FROM media_items_fts")
    suspend fun getAllFtsEntries(): List<com.renyxin.localalbum.data.db.entity.MediaFts>

    /**
     * 清空 FTS 索引表（Phase 4.2 数据库导入使用）。
     */
    @Query("DELETE FROM media_items_fts")
    @Transaction
    suspend fun clearAllFts()

    /**
     * 插入单条 FTS 索引条目（Phase 4.2 数据库导入使用）。
     * FTS4 虚拟表不支持 @Insert 注解，使用原生 INSERT SQL。
     */
    @Query("INSERT INTO media_items_fts (filePath, fileName, ocrText, make, model) VALUES (:filePath, :fileName, :ocrText, :make, :model)")
    suspend fun insertFtsEntry(filePath: String, fileName: String, ocrText: String?, make: String?, model: String?)

    /** 批量插入 FTS 索引条目（配合 deleteFtsEntries 实现批量重建，避免逐条 autocommit） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFtsAll(entries: List<com.renyxin.localalbum.data.db.entity.MediaFts>)

    // ---- 全文搜索回退 (LIKE, 含 OCR) ----
    @Query("""
        SELECT * FROM media_items WHERE isTrashed = 0 AND (
            fileName LIKE '%' || :query || '%' OR
            make LIKE '%' || :query || '%' OR
            model LIKE '%' || :query || '%' OR
            sceneType LIKE '%' || :query || '%' OR
            ocrText LIKE '%' || :query || '%'
        ) ORDER BY capturedAtMs DESC
    """)
    suspend fun searchWithOcr(query: String): List<MediaEntity>

    // ---- 地理位置聚类 (v2 + Phase 3.4) ----
    @Query("UPDATE media_items SET geoClusterId = :clusterId WHERE filePath IN (:paths)")
    suspend fun updateGeoClusterId(paths: List<String>, clusterId: String)

    @Query("UPDATE media_items SET geoClusterId = :clusterId WHERE filePath = :path")
    suspend fun setGeoClusterId(path: String, clusterId: String)

    @Query("UPDATE media_items SET geoClusterId = NULL WHERE filePath IN (:paths)")
    suspend fun clearGeoClusterId(paths: List<String>)

    @Query("UPDATE media_items SET geoClusterId = NULL")
    suspend fun clearAllGeoClusterIds()

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND geoClusterId = :clusterId ORDER BY capturedAtMs DESC")
    suspend fun getByGeoCluster(clusterId: String): List<MediaEntity>

    @Query("SELECT DISTINCT geoClusterId FROM media_items WHERE isTrashed = 0 AND geoClusterId IS NOT NULL")
    suspend fun getGeoClusterIds(): List<String>

    /** 一次查询各地理聚类的照片数量，避免 getGeoStats 的 N+1 查询 */
    @Query("SELECT geoClusterId AS clusterId, COUNT(*) AS photoCount FROM media_items WHERE isTrashed = 0 AND geoClusterId IS NOT NULL GROUP BY geoClusterId")
    suspend fun getGeoClusterCounts(): List<GeoClusterCount>

    /**
     * 查询每个地理聚类的摘要信息（clusterId + 照片数量 + 中心坐标 + 代表缩略图）。
     * 中心坐标取该聚类中最早拍摄的照片的 GPS 坐标。
     */
    @Query("""
        SELECT geoClusterId AS clusterId,
               COUNT(*) AS photoCount,
               (SELECT latitude FROM media_items m2
                WHERE m2.geoClusterId = m1.geoClusterId AND m2.isTrashed = 0
                ORDER BY m2.capturedAtMs ASC LIMIT 1) AS centerLatitude,
               (SELECT longitude FROM media_items m2
                WHERE m2.geoClusterId = m1.geoClusterId AND m2.isTrashed = 0
                ORDER BY m2.capturedAtMs ASC LIMIT 1) AS centerLongitude,
               (SELECT thumbnailPath FROM media_items m3
                WHERE m3.geoClusterId = m1.geoClusterId AND m3.isTrashed = 0
                ORDER BY m3.capturedAtMs DESC LIMIT 1) AS representativeThumb
        FROM media_items m1
        WHERE isTrashed = 0 AND geoClusterId IS NOT NULL
        GROUP BY geoClusterId
        ORDER BY photoCount DESC
    """)
    suspend fun getGeoClusterSummaries(): List<GeoClusterSummary>

    // ---- 质量评分 (v2) ----
    @Query("UPDATE media_items SET qualityScore = :score WHERE filePath = :path")
    suspend fun updateQualityScore(path: String, score: Float)

    @Query("UPDATE media_items SET qualityScore = :score WHERE filePath = :path")
    suspend fun setQualityScore(path: String, score: Float)

    @Query("UPDATE media_items SET perceptualHash = :hash WHERE filePath = :path")
    suspend fun updatePerceptualHash(path: String, hash: Long)

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND perceptualHash != 0")
    suspend fun getWithPerceptualHash(): List<MediaEntity>

    // ---- 场景分类 ----
    @Query("UPDATE media_items SET sceneType = :sceneType WHERE filePath = :path")
    suspend fun setSceneType(path: String, sceneType: String)

    // ---- 综合分析字段更新 ----
    // 修复：qualityScore 和 sceneType 使用 COALESCE 保护，避免调用方传入零值/空串时
    // 覆盖其他并行 Stage 已写入的有效结果（如 OcrStage 调用时不应清零 SceneStage/QualityStage 的结果）。
    @Query("""
        UPDATE media_items SET
            qualityScore = CASE WHEN :score > 0 THEN :score ELSE qualityScore END,
            sceneType = COALESCE(NULLIF(:sceneType, ''), sceneType),
            ocrText = COALESCE(:ocrText, ocrText)
        WHERE filePath = :path
    """)
    suspend fun setAnalysisFields(path: String, score: Float, sceneType: String, ocrText: String?)

    /**
     * 仅更新 OCR 文本字段，不影响 qualityScore 和 sceneType。
     *
     * 供 [com.renyxin.localalbum.core.pipeline.stages.OcrStage] 使用，
     * 避免与同层并行的 SceneStage/QualityStage 产生竞态覆盖。
     * 当 [ocrText] 为 null 时保留已有值（COALESCE 语义）。
     */
    @Query("UPDATE media_items SET ocrText = COALESCE(:ocrText, ocrText) WHERE filePath = :path")
    suspend fun setOcrText(path: String, ocrText: String?)

    // ---- 相似组 (v2) ----
    @Query("UPDATE media_items SET similarGroupId = :groupId WHERE filePath = :path")
    suspend fun setSimilarGroupId(path: String, groupId: String)

    @Query("UPDATE media_items SET similarGroupId = :groupId WHERE filePath IN (:paths)")
    suspend fun updateSimilarGroupId(paths: List<String>, groupId: String)

    @Query("UPDATE media_items SET similarGroupId = NULL WHERE filePath IN (:paths)")
    suspend fun clearSimilarGroupId(paths: List<String>)

    /**
     * 查询所有非空 similarGroupId（去重），用于相似照片管理界面分组展示。
     */
    @Query("SELECT DISTINCT similarGroupId FROM media_items WHERE isTrashed = 0 AND similarGroupId IS NOT NULL")
    suspend fun getSimilarGroupIds(): List<String>

    /**
     * 查询某个相似组内的所有媒体（按质量评分降序，便于"保留最佳"展示）。
     */
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND similarGroupId = :groupId ORDER BY qualityScore DESC, capturedAtMs DESC")
    suspend fun getPhotosInSimilarGroup(groupId: String): List<MediaEntity>

    /**
     * 查询所有相似组及其成员（一次性查询，用于管理界面批量加载）。
     * 返回每条记录的 groupId + filePath + qualityScore，由调用方在内存中分组。
     */
    @Query("SELECT similarGroupId AS groupId, filePath, qualityScore FROM media_items WHERE isTrashed = 0 AND similarGroupId IS NOT NULL ORDER BY similarGroupId, qualityScore DESC")
    suspend fun getAllSimilarGroupMembers(): List<SimilarGroupMember>

    @Query("SELECT filePath FROM media_items WHERE isTrashed = 0 AND latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun getGeoPaths(): List<String>

    // ---- 年/月/日统计 (v2 时间线) ----
    @Query("""
        SELECT 
            CAST(strftime('%Y', capturedAtMs / 1000, 'unixepoch') AS INTEGER) AS bucket,
            COUNT(*) AS cnt
        FROM media_items WHERE isTrashed = 0
        GROUP BY bucket ORDER BY bucket DESC
    """)
    suspend fun getYearBuckets(): List<TimelineBucket>

    // ---- 地理位置 ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun getWithLocation(): List<MediaEntity>

    // ---- 统计 ----
    @Query("SELECT sceneType, COUNT(*) as cnt FROM media_items WHERE isTrashed = 0 AND sceneType IS NOT NULL GROUP BY sceneType")
    suspend fun getSceneTypeStats(): List<SceneTypeStat>

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0")
    fun getTotalCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isFavorite = 1 AND isTrashed = 0")
    fun getFavoriteCountFlow(): Flow<Int>

    // ---- 损坏文件标记 ----
    @Query("UPDATE media_items SET isCorrupted = 1 WHERE filePath = :path")
    suspend fun markCorrupted(path: String)

    // ---- 缩略图补齐 (供 ThumbnailWorker 使用) ----
    @Query("SELECT * FROM media_items WHERE thumbnailPath IS NULL AND isTrashed = 0 LIMIT :limit")
    suspend fun getMissingThumbnails(limit: Int): List<MediaEntity>

    // P0-4: 仅更新缩略图路径，避免 REPLACE 整行覆盖分析管道写入的 AI 字段
    @Query("UPDATE media_items SET thumbnailPath = :thumbPath WHERE filePath = :filePath")
    suspend fun updateThumbnail(filePath: String, thumbPath: String)

    // ---- 单条轻量查询（Phase 4.1 语义嵌入使用） ----
    @Query("SELECT * FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getByFilePathLight(path: String): MediaEntity?

    // Phase 3: 批量轻量查询，替代增量更新路径的 N+1 逐条查询（调用方需 chunked 规避 999 变量上限）
    @Query("SELECT * FROM media_items WHERE filePath IN (:paths)")
    suspend fun getByFilePathsLight(paths: List<String>): List<MediaEntity>

    // ---- 人脸聚类 (Phase 3.3) ----
    @Query("UPDATE media_items SET faceClusterId = :clusterId WHERE filePath = :path")
    suspend fun setFaceClusterId(path: String, clusterId: String)

    @Query("UPDATE media_items SET faceClusterId = :clusterId WHERE filePath IN (:paths)")
    suspend fun updateFaceClusterId(paths: List<String>, clusterId: String)

    @Query("UPDATE media_items SET faceClusterId = NULL WHERE filePath IN (:paths)")
    suspend fun clearFaceClusterId(paths: List<String>)

    @Query("SELECT DISTINCT faceClusterId FROM media_items WHERE isTrashed = 0 AND faceClusterId IS NOT NULL")
    suspend fun getFaceClusterIds(): List<String>

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND faceClusterId = :clusterId ORDER BY capturedAtMs DESC")
    suspend fun getByFaceCluster(clusterId: String): List<MediaEntity>

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND faceClusterId IS NOT NULL ORDER BY capturedAtMs DESC")
    suspend fun getWithFaceCluster(): List<MediaEntity>
}

data class PathModifiedTime(
    val filePath: String,
    val modifiedAtMs: Long,
    val fingerprintHead: String? = null,
)

data class TimelineBucket(
    val bucket: Long,
    val cnt: Int,
)

data class SceneTypeStat(
    val sceneType: String,
    val cnt: Int
)

/**
 * 相似组成员投影，用于一次性批量加载所有相似组。
 */
data class SimilarGroupMember(
    val groupId: String,
    val filePath: String,
    val qualityScore: Float,
)

/**
 * 地理聚类摘要（Phase 3.4），用于地图视图分组展示。
 */
data class GeoClusterSummary(
    val clusterId: String,
    val photoCount: Int,
    val centerLatitude: Double?,
    val centerLongitude: Double?,
    val representativeThumb: String?,
)

/** 地理聚类计数投影，用于 getGeoStats 的一次性聚合查询 */
data class GeoClusterCount(
    val clusterId: String,
    val photoCount: Int,
)