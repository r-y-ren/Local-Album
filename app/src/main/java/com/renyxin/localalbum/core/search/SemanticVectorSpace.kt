package com.renyxin.localalbum.core.search

import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import java.security.MessageDigest

/**
 * 语义向量空间的稳定身份。该身份与分析任务的 pipelineScope 正交：pipelineScope 描述整条
 * 分析管道，spaceId 只描述可进行相似度运算的向量格式，二者不得互相复用。
 */
data class SemanticVectorSpace(
    val providerId: String,
    val modelId: String,
    val modelVersion: Int,
    val dimension: Int,
    val codecId: String = EmbeddingCodec.CODEC_ID,
    val formatVersion: Int = EmbeddingCodec.FORMAT_VERSION,
) {
    init {
        require(providerId.isNotBlank())
        require(modelId.isNotBlank())
        require(modelVersion > 0)
        require(dimension > 0)
        require(codecId.isNotBlank())
        require(formatVersion > 0)
    }

    val spaceId: String by lazy {
        val canonical = listOf(
            "semantic-space-v1",
            providerId,
            modelId,
            modelVersion.toString(),
            dimension.toString(),
            codecId,
            formatVersion.toString(),
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        "sem:" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** v24 及更早记录的隔离空间；绝不能被当前 Provider 查询。 */
        const val LEGACY_SPACE_ID = "semantic:legacy:v24"

        fun from(provider: SemanticEmbedProvider): SemanticVectorSpace = SemanticVectorSpace(
            providerId = provider.providerId,
            modelId = provider.modelId,
            modelVersion = provider.modelVersion,
            dimension = provider.embeddingDim,
        )
    }
}
