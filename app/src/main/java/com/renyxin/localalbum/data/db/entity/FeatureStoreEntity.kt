package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 异构特征存储实体（Phase 1.3 动态 AI 插件系统）。
 *
 * 通用弹性存储表，用于持久化由外部未知模型插件生成的任意异构数据，
 * 包括任意维度的特征向量、自定义分类标签、特殊坐标等。
 *
 * ## 设计原理
 *
 * 消除对原有固定数据库表结构（如 [MediaEmbedding] 固定 32 维、[FaceEntity] 固定 21 维）
 * 的依赖。所有插件输出统一以 JSON Blob 形式存储，结构由 [schemaJson] 描述。
 *
 * - [dataJson]：实际特征数据，序列化为 JSON 字符串
 * - [schemaJson]：[com.renyxin.localalbum.core.plugin.FeatureSchema] 序列化，
 *   描述字段名、数据类型、维度等元信息
 *
 * 查询时按 pluginId / featureType 过滤，反序列化后由应用层解释。
 *
 * @property id 自增主键
 * @property filePath 所属媒体文件路径（外键语义，对应 media_items.filePath）
 * @property pluginId 生成该特征的插件 ID
 * @property featureType 特征类型（如 "embedding"、"classification"、"detection_box"）
 * @property dataJson 特征数据（JSON Blob）
 * @property schemaJson 特征 Schema 描述（JSON Blob）
 * @property modelVersion 生成该特征所用模型版本号
 * @property generatedAtMs 生成时间戳
 */
@Entity(
    tableName = "feature_store",
    indices = [
        Index(value = ["filePath", "pluginId"], unique = true),
        Index(value = ["pluginId"]),
        Index(value = ["featureType"]),
        Index(value = ["modelVersion"]),
    ],
)
data class FeatureStoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val filePath: String,
    val pluginId: String,
    val featureType: String,
    val dataJson: String,
    val schemaJson: String,
    val modelVersion: Int = 1,
    val generatedAtMs: Long = System.currentTimeMillis(),
)
