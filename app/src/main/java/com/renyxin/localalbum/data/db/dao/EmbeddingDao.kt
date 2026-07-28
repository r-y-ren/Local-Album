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

    @Query("SELECT * FROM media_embeddings WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): MediaEmbedding?

    @Query("SELECT * FROM media_embeddings")
    suspend fun getAll(): List<MediaEmbedding>

    @Query("SELECT filePath FROM media_embeddings")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT COUNT(*) FROM media_embeddings")
    suspend fun getCount(): Int

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
