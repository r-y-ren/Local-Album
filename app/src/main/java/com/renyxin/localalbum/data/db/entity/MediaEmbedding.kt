package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 语义嵌入向量存储实体（Phase 4.1 语义搜索）。
 *
 * 每条记录对应一张媒体文件的语义嵌入向量，用于自然语言搜索。
 * 向量维度由 [com.renyxin.localalbum.core.analysis.SemanticEmbedder.EMBEDDING_DIM] 决定，
 * 新写入使用 [embeddingBlob] 保存小端 Float32 二进制；[embedding] 保留为旧版本
 * 数据与备份兼容字段，读取端会优先使用二进制向量。
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
        Index(value = ["spaceId", "filePath"]),
        Index(value = ["providerId", "modelId", "modelVersion"]),
    ],
)
data class MediaEmbedding(
    @PrimaryKey val filePath: String,
    val embedding: String,
    val modelVersion: Int,
    val generatedAtMs: Long = System.currentTimeMillis(),
    /** v24 兼容来源字段；禁止将其当作 providerId 或 spaceId。 */
    val source: String = "concept",
    /** 小端 Float32 编码的向量；仅 maintenance 可对 legacy CSV 回退。 */
    val embeddingBlob: ByteArray? = null,
    val providerId: String = "legacy",
    val modelId: String = "legacy",
    val dimension: Int = 0,
    val spaceId: String = com.renyxin.localalbum.core.search.SemanticVectorSpace.LEGACY_SPACE_ID,
    val generation: Long = 0,
    val codecId: String = com.renyxin.localalbum.core.search.EmbeddingCodec.CODEC_ID,
    val formatVersion: Int = com.renyxin.localalbum.core.search.EmbeddingCodec.FORMAT_VERSION,
)
