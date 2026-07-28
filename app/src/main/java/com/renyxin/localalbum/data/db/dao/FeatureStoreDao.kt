package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import kotlinx.coroutines.flow.Flow

/**
 * 异构特征存储数据访问对象（Phase 1.3 动态 AI 插件系统）。
 *
 * 提供对 [FeatureStoreEntity] 的增删改查能力。
 * 由于数据以 JSON Blob 形式存储，向量运算（如余弦相似度）在应用层完成：
 * 按 pluginId / featureType 过滤加载后，反序列化计算。
 *
 * ## 典型查询场景
 *
 * - 按文件路径查询该文件的所有插件特征：[getByFilePath]
 * - 按插件查询所有特征（用于全库相似度检索）：[getByPlugin]
 * - 按特征类型查询（如所有 embedding 类型）：[getByFeatureType]
 * - 查询缺失某插件特征的文件（增量补齐）：[getMissingFeatures]
 * - 查询模型版本过期的特征（模型升级后重建）：[getStaleFeatures]
 */
@Dao
interface FeatureStoreDao {

    // ---- 插入 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FeatureStoreEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertAll(entities: List<FeatureStoreEntity>)

    // ---- 删除 ----

    @Query("DELETE FROM feature_store WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)

    @Query("DELETE FROM feature_store WHERE filePath IN (:filePaths)")
    @Transaction
    suspend fun deleteByFilePaths(filePaths: List<String>)

    @Query("DELETE FROM feature_store WHERE pluginId = :pluginId")
    suspend fun deleteByPlugin(pluginId: String)

    @Query("DELETE FROM feature_store WHERE pluginId = :pluginId AND filePath IN (:filePaths)")
    @Transaction
    suspend fun deleteByPluginAndFilePaths(pluginId: String, filePaths: List<String>)

    @Query("DELETE FROM feature_store")
    @Transaction
    suspend fun clearAll()

    // ---- 查询 ----

    @Query("SELECT * FROM feature_store WHERE filePath = :filePath")
    suspend fun getByFilePath(filePath: String): List<FeatureStoreEntity>

    @Query("SELECT * FROM feature_store WHERE filePath = :filePath")
    fun getByFilePathFlow(filePath: String): Flow<List<FeatureStoreEntity>>

    @Query("SELECT * FROM feature_store WHERE pluginId = :pluginId")
    suspend fun getByPlugin(pluginId: String): List<FeatureStoreEntity>

    @Query("SELECT * FROM feature_store WHERE pluginId = :pluginId")
    fun getByPluginFlow(pluginId: String): Flow<List<FeatureStoreEntity>>

    @Query("SELECT * FROM feature_store WHERE featureType = :featureType")
    suspend fun getByFeatureType(featureType: String): List<FeatureStoreEntity>

    @Query("SELECT * FROM feature_store WHERE filePath = :filePath AND pluginId = :pluginId LIMIT 1")
    suspend fun getByFilePathAndPlugin(filePath: String, pluginId: String): FeatureStoreEntity?

    @Query("SELECT * FROM feature_store")
    suspend fun getAll(): List<FeatureStoreEntity>

    @Query("SELECT COUNT(*) FROM feature_store")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM feature_store WHERE pluginId = :pluginId")
    suspend fun getCountByPlugin(pluginId: String): Int

    @Query("SELECT COUNT(*) FROM feature_store WHERE pluginId = :pluginId AND modelVersion = :modelVersion")
    suspend fun getCountByPluginAndVersion(pluginId: String, modelVersion: Int): Int

    @Query("SELECT DISTINCT pluginId FROM feature_store")
    suspend fun getPluginIds(): List<String>

    @Query("SELECT DISTINCT featureType FROM feature_store")
    suspend fun getFeatureTypes(): List<String>

    // ---- 增量补齐 ----

    /**
     * 查询媒体库中存在但缺少指定插件特征的媒体路径（用于增量补齐）。
     */
    @Query("""
        SELECT m.filePath FROM media_items m
        LEFT JOIN feature_store f ON m.filePath = f.filePath AND f.pluginId = :pluginId
        WHERE m.isTrashed = 0 AND f.filePath IS NULL
        LIMIT :limit
    """)
    suspend fun getMissingFeatures(pluginId: String, limit: Int): List<String>

    /**
     * 查询模型版本过期的特征记录路径（模型升级后需重建）。
     */
    @Query("SELECT filePath FROM feature_store WHERE pluginId = :pluginId AND modelVersion != :currentVersion")
    suspend fun getStaleFeatures(pluginId: String, currentVersion: Int): List<String>
}
