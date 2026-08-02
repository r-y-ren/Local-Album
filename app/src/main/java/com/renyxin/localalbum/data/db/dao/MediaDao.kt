package com.renyxin.localalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    // ---- 基础查询 ----
    /**
     * 仅供 DatabaseExporter 的历史单 JSON 格式兼容导出。
     * 新备份和应用运行路径必须使用分页接口，禁止扩散此全量读取边界。
     */
    @Deprecated("Legacy single-JSON export only; use getExportPageAfter for bounded export")
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    suspend fun getAllForLegacyExport(): List<MediaEntity>

    // ---- 分页查询 (Paging 3 支持) ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<MediaEntity>

    /** P2-A 备份稳定 keyset；包含回收站记录，避免恢复后丢失用户状态。 */
    @Query("SELECT * FROM media_items WHERE :afterPath IS NULL OR filePath > :afterPath ORDER BY filePath LIMIT :limit")
    suspend fun getExportPageAfter(afterPath: String?, limit: Int): List<MediaEntity>

    /**
     * 完全重复维护的有界 keyset 输入。
     * SQL 先按 fileSize 聚合排除不可能重复的文件，再返回有限轻量行；不读取整库路径或实体。
     */
    @Query("""
        SELECT filePath, fileSize, qualityScore, modifiedAtMs
        FROM media_items
        WHERE isTrashed = 0 AND fileSize > 0
          AND fileSize IN (
              SELECT fileSize FROM media_items
              WHERE isTrashed = 0 AND fileSize > 0
              GROUP BY fileSize HAVING COUNT(*) >= 2
          )
          AND (:afterPath IS NULL OR filePath > :afterPath)
        ORDER BY filePath ASC LIMIT :limit
    """)
    suspend fun getDuplicateCandidatesAfter(afterPath: String?, limit: Int): List<DuplicateCandidate>

    // ---- Paging 3 PagingSource (Room 原生分页) ----
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 ORDER BY capturedAtMs DESC")
    fun pagingSource(): PagingSource<Int, MediaEntity>

    /** 目录、类型和有限排序均由类型安全查询模型映射为受观察的 SQL。 */
    @RawQuery(observedEntities = [MediaEntity::class])
    fun directoryPagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    /** P1-A：关键词、类型、日期与机型全部在 SQL 中过滤，避免只过滤 Paging 当前窗口。 */
    @Query("""
        SELECT m.* FROM media_items m
        INNER JOIN media_items_fts f ON m.filePath = f.filePath
        WHERE media_items_fts MATCH :ftsQuery AND m.isTrashed = 0
          AND (:mediaType IS NULL OR m.mediaType = :mediaType)
          AND (:startMs IS NULL OR m.capturedAtMs >= :startMs)
          AND (:endMs IS NULL OR m.capturedAtMs <= :endMs)
          AND (:model IS NULL OR m.model = :model)
        ORDER BY m.capturedAtMs DESC, m.filePath ASC
    """)
    fun searchPagingSource(
        ftsQuery: String,
        mediaType: String?,
        startMs: Long?,
        endMs: Long?,
        model: String?,
    ): PagingSource<Int, MediaEntity>

    /** P1-A：回收站不再常驻完整实体 Flow。 */
    @Query("SELECT * FROM media_items WHERE isTrashed = 1 ORDER BY deletedAtMs DESC, filePath ASC")
    fun trashPagingSource(): PagingSource<Int, MediaEntity>

    /** P1-A：人物详情按媒体表分页，不再一次读取人物全部照片。 */
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND faceClusterId = :clusterId ORDER BY capturedAtMs DESC, filePath ASC")
    fun faceClusterPagingSource(clusterId: String): PagingSource<Int, MediaEntity>

    /** 查看器以初始媒体在稳定排序中的偏移作为 Paging initialKey。 */
    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0 AND (capturedAtMs > :capturedAtMs OR (capturedAtMs = :capturedAtMs AND filePath < :path))")
    suspend fun timelineOffset(capturedAtMs: Long, path: String): Int

    @RawQuery
    suspend fun directoryOffset(query: SupportSQLiteQuery): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0 AND isFavorite = 1 AND (capturedAtMs > :capturedAtMs OR (capturedAtMs = :capturedAtMs AND filePath < :path))")
    suspend fun favoritesOffset(capturedAtMs: Long, path: String): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0 AND faceClusterId = :clusterId AND (capturedAtMs > :capturedAtMs OR (capturedAtMs = :capturedAtMs AND filePath < :path))")
    suspend fun faceClusterOffset(clusterId: String, capturedAtMs: Long, path: String): Int

    @Query("""
        SELECT COUNT(*) FROM media_items m
        INNER JOIN media_items_fts f ON m.filePath = f.filePath
        WHERE media_items_fts MATCH :ftsQuery AND m.isTrashed = 0
          AND (:mediaType IS NULL OR m.mediaType = :mediaType)
          AND (:startMs IS NULL OR m.capturedAtMs >= :startMs)
          AND (:endMs IS NULL OR m.capturedAtMs <= :endMs)
          AND (:model IS NULL OR m.model = :model)
          AND (m.capturedAtMs > :capturedAtMs OR (m.capturedAtMs = :capturedAtMs AND m.filePath < :path))
    """)
    suspend fun searchOffset(
        ftsQuery: String,
        mediaType: String?,
        startMs: Long?,
        endMs: Long?,
        model: String?,
        capturedAtMs: Long,
        path: String,
    ): Int

    /** 搜索机型选项由数据库去重，不依赖当前结果窗口。 */
    @Query("SELECT DISTINCT model FROM media_items WHERE isTrashed = 0 AND model IS NOT NULL AND model != '' ORDER BY model")
    suspend fun getDistinctModels(): List<String>

    /**
     * 目录摘要，仅含构建相册树所需的信息。通过相关子查询取最新封面，避免全量媒体实体。
     */
    @Query("""
        SELECT parentPath, COUNT(*) AS mediaCount, MAX(capturedAtMs) AS latestCapturedAtMs,
               (SELECT m2.thumbnailPath FROM media_items m2
                WHERE m2.parentPath = m1.parentPath AND m2.isTrashed = 0
                ORDER BY m2.capturedAtMs DESC LIMIT 1) AS coverThumbnailPath,
               (SELECT m2.filePath FROM media_items m2
                WHERE m2.parentPath = m1.parentPath AND m2.isTrashed = 0
                ORDER BY m2.capturedAtMs DESC LIMIT 1) AS coverFilePath
        FROM media_items m1
        WHERE isTrashed = 0
        GROUP BY parentPath
        ORDER BY latestCapturedAtMs DESC
    """)
    suspend fun getDirectorySummaries(): List<DirectorySummary>

    /**
     * 精选推荐池的稳定 keyset 分页输入。
     * 推荐轮换要求覆盖全部文件，因此按路径分批读取，避免 OFFSET 在大图库上的扫描放大。
     */
    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND (:afterPath IS NULL OR filePath > :afterPath) ORDER BY filePath ASC LIMIT :limit")
    suspend fun getRecommendationPageAfter(afterPath: String?, limit: Int): List<MediaEntity>

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
    /** 收藏页的大图库数据源，避免为展示收藏项订阅整个 media_items 表。 */
    @Query("SELECT * FROM media_items WHERE isFavorite = 1 AND isTrashed = 0 ORDER BY capturedAtMs DESC")
    fun favoritesPagingSource(): PagingSource<Int, MediaEntity>

    /** 轻量查询单条媒体的收藏状态，避免 toggleFavorite 全量加载 */
    @Query("SELECT isFavorite FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getFavoriteByPath(path: String): Boolean?

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE filePath = :path")
    suspend fun setFavorite(path: String, favorite: Boolean)

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE filePath IN (:paths)")
    suspend fun batchSetFavorite(paths: List<String>, favorite: Boolean)

    // ---- 回收站 ----
    // P2-3: 使用 COUNT 查询获取回收站数量，避免加载全部行到内存
    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 1")
    suspend fun getTrashedCount(): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 1")
    fun getTrashedCountFlow(): Flow<Int>

    /** 自动清理仅领取有限路径，避免加载完整回收站实体集合。 */
    @Query("SELECT filePath FROM media_items WHERE isTrashed = 1 AND deletedAtMs > 0 AND deletedAtMs <= :before ORDER BY deletedAtMs ASC, filePath ASC LIMIT :limit")
    suspend fun getExpiredTrashPaths(before: Long, limit: Int): List<String>

    /**
     * 用户清空回收站时按路径 keyset 读取有限批次。
     * afterPath 让物理删除失败项不会反复占据首批并形成死循环。
     */
    @Query("SELECT filePath FROM media_items WHERE isTrashed = 1 AND filePath > :afterPath ORDER BY filePath ASC LIMIT :limit")
    suspend fun getTrashedPathsAfter(afterPath: String, limit: Int): List<String>

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
    @Query("UPDATE media_items SET scanGeneration = :generation WHERE filePath IN (:paths)")
    suspend fun markScanGeneration(paths: List<String>, generation: Long)

    /** 扫描结束后只领取有限孤儿路径，避免构造全库路径集合。 */
    @Query("SELECT filePath FROM media_items WHERE scanGeneration != :generation ORDER BY filePath ASC LIMIT :limit")
    suspend fun getPathsOutsideGeneration(generation: Long, limit: Int): List<String>

    @Query("SELECT filePath, modifiedAtMs, fingerprintHead FROM media_items")
    suspend fun getModifiedTimeMap(): List<PathModifiedTime>

    @Query("SELECT filePath, modifiedAtMs, fingerprintHead FROM media_items WHERE filePath = :path LIMIT 1")
    suspend fun getModifiedTimeForPath(path: String): PathModifiedTime?

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

    @Query("SELECT filePath, fileName, ocrText, make, model FROM media_items_fts LIMIT :limit OFFSET :offset")
    suspend fun getPagedFtsEntries(limit: Int, offset: Int): List<com.renyxin.localalbum.data.db.entity.MediaFts>

    @Query("SELECT filePath, fileName, ocrText, make, model FROM media_items_fts WHERE :afterPath IS NULL OR filePath > :afterPath ORDER BY filePath LIMIT :limit")
    suspend fun getFtsEntriesAfter(afterPath: String?, limit: Int): List<com.renyxin.localalbum.data.db.entity.MediaFts>

    @Query("SELECT COUNT(*) FROM media_items_fts")
    suspend fun getFtsCount(): Int

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
        ) ORDER BY capturedAtMs DESC LIMIT :limit
    """)
    suspend fun searchWithOcr(query: String, limit: Int = 200): List<MediaEntity>

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

    /** 按路径 keyset 分页建立持久化任务，避免失败项阻塞后续媒体或 OFFSET 放大。 */
    @Query("SELECT * FROM media_items WHERE thumbnailPath IS NULL AND isTrashed = 0 AND filePath > :afterPath ORDER BY filePath ASC LIMIT :limit")
    suspend fun getMissingThumbnailsAfter(afterPath: String, limit: Int): List<MediaEntity>

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

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND faceClusterId IS NOT NULL ORDER BY capturedAtMs DESC")
    suspend fun getWithFaceCluster(): List<MediaEntity>
}

data class DirectorySummary(
    val parentPath: String,
    val mediaCount: Int,
    val latestCapturedAtMs: Long,
    val coverThumbnailPath: String?,
    val coverFilePath: String?,
)

data class PathModifiedTime(
    val filePath: String,
    val modifiedAtMs: Long,
    val fingerprintHead: String? = null,
)

data class TimelineBucket(
    val bucket: Long,
    val cnt: Int,
)

/** 重复维护 Worker 所需的最小媒体投影。 */
data class DuplicateCandidate(
    val filePath: String,
    val fileSize: Long,
    val qualityScore: Float,
    val modifiedAtMs: Long,
)

data class SceneTypeStat(
    val sceneType: String,
    val cnt: Int
)
