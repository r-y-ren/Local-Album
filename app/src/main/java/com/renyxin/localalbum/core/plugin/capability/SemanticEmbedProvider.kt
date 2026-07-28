package com.renyxin.localalbum.core.plugin.capability

import java.io.File

/**
 * 语义嵌入提供者接口。
 *
 * 将图像与自然语言查询映射到同一向量空间，支持"日落时分的海滩"这类自然语言检索。
 * 每种实现（概念向量、CLIP、SigLIP 等）提供不同的语义理解精度。
 *
 * ## 使用场景
 * - 管道批处理：[SemanticStage] 逐图调用 [embedImage]
 * - 搜索：用户输入查询 → [embedText] → 余弦相似度检索
 *
 * @see com.renyxin.localalbum.core.plugin.capability.builtin.ConceptEmbedProvider 内置默认实现
 */
interface SemanticEmbedProvider {
    /** Provider 唯一标识 */
    val providerId: String

    /** 人类可读显示名称 */
    val displayName: String

    /** 嵌入向量维度 */
    val embeddingDim: Int

    /**
     * 将图片编码为语义嵌入向量。
     *
     * @param file 图片文件
     * @param context 可选的图像上下文（如已分析的场景标签/OCR 文本，用于辅助编码）
     * @return L2 归一化向量，失败返回 null
     */
    suspend fun embedImage(file: File, context: ImageContext? = null): FloatArray?

    /**
     * 将自然语言查询编码为语义嵌入向量。
     *
     * @param query 用户查询文本（中文或英文）
     * @return L2 归一化向量
     */
    suspend fun embedText(query: String): FloatArray

    /** 释放模型资源 */
    suspend fun release()

    /**
     * 图像上下文，辅助语义编码。
     *
     * @property sceneType 已分析的场景标签（可选）
     * @property ocrText 已识别的 OCR 文本（可选）
     * @property qualityScore 质量评分（可选）
     */
    data class ImageContext(
        val sceneType: String? = null,
        val ocrText: String? = null,
        val qualityScore: Float = 0f,
    )
}
