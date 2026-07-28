package com.renyxin.localalbum.core.plugin.demo

import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import java.io.File
import kotlin.random.Random

/**
 * Demo 语义嵌入 Provider — 随机向量生成（仅演示）。
 *
 * 提供 `demo:random_embed` 作为语义槽位的第二选项。
 * 生成的嵌入无实际语义，仅用于展示 Provider 切换和搜索管道流程。
 */
class DemoSemanticProvider : SemanticEmbedProvider {
    override val providerId = "demo:random_embed"
    override val displayName = "随机语义嵌入 (Demo 演示)"
    override val embeddingDim = 32

    private val random = Random(42) // 固定种子保证一致性

    override suspend fun embedImage(file: File, context: SemanticEmbedProvider.ImageContext?): FloatArray {
        val vec = FloatArray(embeddingDim) { random.nextFloat() }
        // L2 归一化
        val norm = kotlin.math.sqrt(vec.map { it * it }.sum())
        if (norm > 0) vec.forEachIndexed { i, v -> vec[i] = v / norm }
        return vec
    }

    override suspend fun embedText(query: String): FloatArray {
        // 基于查询字符串生成确定性向量
        val seed = query.hashCode()
        val rng = Random(seed)
        val vec = FloatArray(embeddingDim) { rng.nextFloat() }
        val norm = kotlin.math.sqrt(vec.map { it * it }.sum())
        if (norm > 0) vec.forEachIndexed { i, v -> vec[i] = v / norm }
        return vec
    }

    override suspend fun release() {}
}
