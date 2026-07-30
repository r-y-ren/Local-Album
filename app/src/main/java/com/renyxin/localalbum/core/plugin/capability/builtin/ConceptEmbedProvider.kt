package com.renyxin.localalbum.core.plugin.capability.builtin

import com.renyxin.localalbum.core.analysis.SemanticEmbedder
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import java.io.File

/**
 * 基于概念向量的内置默认语义嵌入 Provider。
 *
 * 包裹项目现有的 [SemanticEmbedder]，将其适配为 [SemanticEmbedProvider] 接口。
 * 零模型依赖：32 维概念向量通过颜色/场景/OCR 等信号映射。
 *
 * 可被 CLIP/SigLIP 等真实跨模态嵌入模型替换，替换后 embeddingDim 和查询精度
 * 会显著提升。
 */
class ConceptEmbedProvider : SemanticEmbedProvider {

    override val providerId = "builtin:concept"
    override val modelId = "concept-embedding"
    override val modelVersion = SemanticEmbedder.MODEL_VERSION
    override val displayName = "概念向量语义嵌入 (默认)"
    override val embeddingDim = SemanticEmbedder.EMBEDDING_DIM // 32

    private val embedder = SemanticEmbedder()

    override suspend fun embedImage(
        file: File,
        context: SemanticEmbedProvider.ImageContext?,
    ): FloatArray? {
        return embedder.embedImage(file, entity = null)
    }

    override suspend fun embedText(query: String): FloatArray {
        return embedder.embedText(query)
    }

    override suspend fun release() {
        // 概念向量无资源需释放
    }

    /** 暴露原始 embedder 供批量嵌入时复用（如从已有 ImageFeatures 直接计算） */
    fun getEmbedder(): SemanticEmbedder = embedder
}
