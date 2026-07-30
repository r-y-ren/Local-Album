package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.MediaEmbedding

/**
 * 语义嵌入向量数据访问对象（Phase 4.1 语义搜索）。
 *
 * 提供嵌入向量的插入、查询、按相似度检索等能力。
 * 由于 SQLite 原生不支持向量运算，余弦相似度计算在应用层完成：
 * 全量加载归一化向量后内存计算（媒体库 < 50K 时可接受）。
 */
@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: MediaEmbedding)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertEmbeddings(embeddings: List<MediaEmbedding>)

    /** 兼容非检索业务；搜索/推荐必须使用带 spaceId 的接口。 */
    @Query("SELECT * FROM media_embeddings WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): MediaEmbedding?

    @Query("SELECT * FROM media_embeddings WHERE filePath = :filePath AND spaceId = :spaceId LIMIT 1")
    suspend fun getByFilePathInSpace(filePath: String, spaceId: String): MediaEmbedding?

    /** 仅供 DatabaseExporter 历史单 JSON 兼容导出，运行时路径禁止调用。 */
    @Deprecated("Legacy single-JSON export only; use keyset paging for bounded export")
    @Query("SELECT * FROM media_embeddings")
    suspend fun getAllForLegacyExport(): List<MediaEmbedding>

    @Query("SELECT * FROM media_embeddings LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<MediaEmbedding>

    /** 仅备份/maintenance 可跨空间分页；运行时检索禁止调用。 */
    @Query("SELECT * FROM media_embeddings WHERE :afterPath IS NULL OR filePath > :afterPath ORDER BY filePath LIMIT :limit")
    suspend fun getPagedAfter(afterPath: String?, limit: Int): List<MediaEmbedding>

    /** 架构边界：Exact 搜索和聚类只能显式扫描一个向量空间。 */
    @Query("SELECT * FROM media_embeddings WHERE spaceId = :spaceId AND (:afterPath IS NULL OR filePath > :afterPath) ORDER BY filePath LIMIT :limit")
    suspend fun getPagedAfterInSpace(spaceId: String, afterPath: String?, limit: Int): List<MediaEmbedding>

    /** 回填候选可跨 legacy；Worker 以 filePath keyset 有界处理。 */
    @Query("SELECT * FROM media_embeddings WHERE spaceId = :legacySpaceId AND (:afterPath IS NULL OR filePath > :afterPath) ORDER BY filePath LIMIT :limit")
    suspend fun getLegacyBackfillPage(legacySpaceId: String, afterPath: String?, limit: Int): List<MediaEmbedding>

    @Query("""
        UPDATE media_embeddings SET embeddingBlob = :blob, providerId = :providerId,
          modelId = :modelId, dimension = :dimension, generation = :generation,
          codecId = :codecId, formatVersion = :formatVersion
        WHERE filePath = :filePath AND spaceId = :legacySpaceId
    """)
    suspend fun updateLegacyPayloadMetadata(
        filePath: String,
        legacySpaceId: String,
        blob: ByteArray,
        providerId: String,
        modelId: String,
        dimension: Int,
        generation: Long,
        codecId: String,
        formatVersion: Int,
    ): Int

    @Query("SELECT filePath FROM media_embeddings")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT COUNT(*) FROM media_embeddings")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM media_embeddings WHERE spaceId = :spaceId")
    suspend fun getCountInSpace(spaceId: String): Int

    @Query("SELECT COUNT(*) FROM media_embeddings WHERE modelVersion = :modelVersion")
    suspend fun getCountByModelVersion(modelVersion: Int): Int

    @Query("DELETE FROM media_embeddings WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)

    @Query("DELETE FROM media_embeddings WHERE filePath IN (:filePaths)")
    @Transaction
    suspend fun deleteByFilePaths(filePaths: List<String>)

    @Query("DELETE FROM media_embeddings")
    @Transaction
    suspend fun clearAll()

    /**
     * 查询媒体库中存在但缺少语义嵌入的媒体路径（用于增量补齐）。
     */
    @Query("""
        SELECT m.filePath FROM media_items m
        LEFT JOIN media_embeddings e ON m.filePath = e.filePath
        WHERE m.isTrashed = 0 AND e.filePath IS NULL
        LIMIT :limit
    """)
    suspend fun getMissingEmbeddings(limit: Int): List<String>

    /**
     * 查询模型版本过期的嵌入记录路径（模型升级后需重建）。
     */
    @Query("SELECT filePath FROM media_embeddings WHERE modelVersion != :currentVersion")
    suspend fun getStaleEmbeddings(currentVersion: Int): List<String>
}
