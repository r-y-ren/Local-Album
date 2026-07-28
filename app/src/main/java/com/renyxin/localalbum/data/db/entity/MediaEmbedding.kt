package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 语义嵌入向量存储实体（Phase 4.1 语义搜索）。
 *
 * 每条记录对应一张媒体文件的语义嵌入向量，用于自然语言搜索。
 * 向量维度由 [com.renyxin.localalbum.core.analysis.SemanticEmbedder.EMBEDDING_DIM] 决定，
 * 归一化后以逗号分隔的 Float 字符串形式存储，查询时全量加载计算余弦相似度。
 *
 * @param filePath 媒体文件路径（主键，外键语义对应 media_items.filePath）
 * @param embedding 归一化语义向量（逗号分隔的 Float 字符串）
 * @param modelVersion 生成该向量所用的模型版本号，用于模型升级后增量重建
 * @param generatedAtMs 向量生成时间戳
 * @param source 生成来源标识（"concept" 概念模型 / "clip" CLIP 模型）
 */
@Entity(
    tableName = "media_embeddings",
    indices = [
        Index(value = ["modelVersion"]),
    ],
)
data class MediaEmbedding(
    @PrimaryKey val filePath: String,
    val embedding: String,
    val modelVersion: Int,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val source: String = "concept",
)
