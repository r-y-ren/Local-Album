package com.renyxin.localalbum.core.plugin.model

import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat

/**
 * 远程模型源接口（Phase 5a — 远程模型仓库集成）。
 *
 * 每种模型托管平台（HuggingFace、GitHub Releases、自建服务器等）
 * 实现此接口，将平台上的模型索引转换为统一的 [ModelInfo] 列表。
 *
 * @see HuggingFaceModelSource HuggingFace 实现
 * @see CompositeModelCatalog 多源合并
 */
interface RemoteModelSource {
    /** 源名称（如 "HuggingFace", "GitHub"），用于 UI 标识 */
    val sourceName: String

    /**
     * 从远程获取模型列表。
     *
     * @param query 搜索关键词，null 表示获取推荐/热门模型
     * @param limit 最大返回数量
     * @return 模型信息列表，网络错误时返回空列表
     */
    suspend fun fetchModels(query: String? = null, limit: Int = 50): List<ModelInfo>

    /**
     * 获取单个模型的详细信息。
     *
     * @param modelId 模型 ID
     * @return 模型详细信息，不存在或网络错误时返回 null
     */
    suspend fun fetchModelDetail(modelId: String): ModelInfo?

    /**
     * 根据模型 ID 构造下载 URL。
     *
     * @param modelId 模型 ID
     * @param fileName 文件名（如 "model.tflite"）
     * @return 完整的下载 URL
     */
    fun buildDownloadUrl(modelId: String, fileName: String): String
}
