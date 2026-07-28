package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity
import kotlinx.coroutines.flow.Flow

/**
 * 插件清单 DAO（Phase 4.2）。
 *
 * 提供 PluginManifestEntity 的 CRUD 操作。
 * 查询方法返回 [Flow] 以支持 Reactive UI 观察。
 */
@Dao
interface PluginManifestDao {

    /**
     * 插入插件清单，如 pluginId 冲突则替换。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PluginManifestEntity): Long

    /**
     * 更新插件清单。
     */
    @Update
    suspend fun update(entity: PluginManifestEntity)

    /**
     * 删除插件清单。
     */
    @Delete
    suspend fun delete(entity: PluginManifestEntity)

    /**
     * 按 pluginId 查询插件清单。
     */
    @Query("SELECT * FROM plugin_manifest WHERE pluginId = :pluginId LIMIT 1")
    suspend fun getByPluginId(pluginId: String): PluginManifestEntity?

    /**
     * 获取所有插件清单（Flow，Reactive 观察）。
     * 按 pipelineStage 分组 → orderIndex 排序，再以 createdAtMs 降序兜底。
     */
    @Query("SELECT * FROM plugin_manifest ORDER BY pipelineStage ASC, orderIndex ASC, createdAtMs DESC")
    fun getAllFlow(): Flow<List<PluginManifestEntity>>

    /**
     * 获取所有插件清单（一次性查询）。
     */
    @Query("SELECT * FROM plugin_manifest ORDER BY pipelineStage ASC, orderIndex ASC, createdAtMs DESC")
    suspend fun getAll(): List<PluginManifestEntity>

    /**
     * 获取所有已启用的插件清单。
     */
    @Query("SELECT * FROM plugin_manifest WHERE isEnabled = 1 ORDER BY pipelineStage ASC, orderIndex ASC, createdAtMs DESC")
    suspend fun getEnabled(): List<PluginManifestEntity>

    /**
     * 按 pipelineStage 查询插件清单。
     */
    @Query("SELECT * FROM plugin_manifest WHERE pipelineStage = :stage ORDER BY orderIndex ASC, createdAtMs DESC")
    suspend fun getByPipelineStage(stage: String): List<PluginManifestEntity>

    /**
     * 按 pluginId 更新启用状态。
     */
    @Query("UPDATE plugin_manifest SET isEnabled = :enabled, updatedAtMs = :updatedAtMs WHERE pluginId = :pluginId")
    suspend fun setEnabled(pluginId: String, enabled: Boolean, updatedAtMs: Long = System.currentTimeMillis())

    /**
     * 按 pluginId 更新排序索引（Phase 5 R1）。
     */
    @Query("UPDATE plugin_manifest SET orderIndex = :orderIndex, updatedAtMs = :updatedAtMs WHERE pluginId = :pluginId")
    suspend fun updateOrder(pluginId: String, orderIndex: Int, updatedAtMs: Long = System.currentTimeMillis())

    /**
     * 删除所有插件清单。
     */
    @Query("DELETE FROM plugin_manifest")
    suspend fun deleteAll()

    /**
     * 获取插件总数。
     */
    @Query("SELECT COUNT(*) FROM plugin_manifest")
    suspend fun count(): Int
}