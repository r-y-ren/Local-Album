package com.renyxin.localalbum.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 插件清单 Room 实体（Phase 4.2）。
 *
 * 持久化用户通过可视化向导或 JSON 编辑器导入的第三方 AI 插件配置。
 * manifestJson 字段存储完整的 [PluginManifest] JSON 字符串，
 * 在加载时通过 [PluginJsonCodec] 反序列化。
 *
 * @property id 自增主键
 * @property pluginId 插件唯一标识（与 PluginManifest.pluginId 一致）
 * @property manifestJson 完整 PluginManifest JSON 字符串
 * @property isEnabled 是否启用
 * @property pipelineStage 管道阶段名称
 * @property orderIndex 在同一 pipelineStage 内的排序索引（越小越先执行）
 * @property createdAtMs 创建时间戳（毫秒）
 * @property updatedAtMs 最后更新时间戳（毫秒）
 */
@Entity(
    tableName = "plugin_manifest",
    indices = [
        Index(value = ["pluginId"], unique = true),
        Index(value = ["isEnabled"]),
        Index(value = ["pipelineStage"]),
    ],
)
data class PluginManifestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "pluginId")
    val pluginId: String,

    @ColumnInfo(name = "manifestJson")
    val manifestJson: String,

    @ColumnInfo(name = "isEnabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "pipelineStage")
    val pipelineStage: String,

    @ColumnInfo(name = "orderIndex")
    val orderIndex: Int = 0,

    @ColumnInfo(name = "createdAtMs")
    val createdAtMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updatedAtMs")
    val updatedAtMs: Long = System.currentTimeMillis(),
)