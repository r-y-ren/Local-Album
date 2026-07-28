package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat
import java.io.File

/**
 * 模型目录条目 — 描述一个可用的 AI 模型。
 *
 * @property modelId 模型唯一标识（如 "mobilenet_v2_quant"）
 * @property displayName 人类可读的名称
 * @property description 模型描述
 * @property format 模型格式
 * @property fileSizeBytes 模型文件大小（字节），-1 表示未知
 * @property version 模型版本
 * @property author 模型作者/发布者
 * @property tags 分类标签（如 ["image", "classification", "mobile"]）
 * @property rating 用户评分 (0-5)
 * @property downloadCount 下载次数（仅远程模型）
 * @property source 模型来源
 * @property localFilePath 本地文件路径（本地模型），null 表示远程模型
 * @property downloadUrl 下载 URL（远程模型），null 表示本地模型
 * @property sha256 SHA-256 校验和
 * @property requiredBy 依赖此模型的 Provider ID 列表
 */
data class ModelInfo(
    val modelId: String,
    val displayName: String,
    val description: String = "",
    val format: ModelFormat = ModelFormat.TFLITE,
    val fileSizeBytes: Long = -1,
    val version: String = "1.0.0",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val rating: Float = 0f,
    val downloadCount: Long = 0,
    val source: ModelSource = ModelSource.LOCAL,
    val localFilePath: String? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val requiredBy: List<String> = emptyList(),
) {
    /** 格式化文件大小 */
    val formattedSize: String
        get() = when {
            fileSizeBytes < 0 -> "未知"
            fileSizeBytes < 1024 -> "${fileSizeBytes} B"
            fileSizeBytes < 1024 * 1024 -> "${fileSizeBytes / 1024} KB"
            else -> "%.1f MB".format(fileSizeBytes / (1024.0 * 1024.0))
        }
}

/**
 * 模型来源枚举。
 */
enum class ModelSource {
    /** 内置模型（随 App 发布） */
    BUILTIN,
    /** 用户从本地导入 */
    USER_IMPORTED,
    /** 从远程下载 */
    REMOTE_DOWNLOAD,
    /** 本地文件系统中的模型 */
    LOCAL,
}

/**
 * 模型目录接口（Phase 4 — 模型生命周期管理）。
 *
 * 提供模型的浏览、搜索、筛选能力，供 UI 层渲染"模型市场"和"已安装模型"视图。
 *
 * ## 实现
 * - [LocalModelCatalog] — 扫描本地 models/ 目录
 * - RemoteModelCatalog（未来）— 从远程仓库（HuggingFace/GitHub）获取模型索引
 */
interface ModelCatalog {
    /**
     * 获取所有可用模型列表。
     */
    suspend fun listModels(): List<ModelInfo>

    /**
     * 按关键词搜索模型（匹配名称、描述、标签）。
     */
    suspend fun search(query: String): List<ModelInfo>

    /**
     * 按格式筛选模型。
     */
    suspend fun filterByFormat(format: ModelFormat): List<ModelInfo>

    /**
     * 按标签筛选模型。
     */
    suspend fun filterByTag(tag: String): List<ModelInfo>

    /**
     * 获取单个模型详细信息。
     */
    suspend fun getModel(modelId: String): ModelInfo?

    /**
     * 获取本地已安装模型的总存储占用（字节）。
     */
    suspend fun getTotalStorageBytes(): Long
}

/**
 * 本地模型目录实现（Phase 4）。
 *
 * 扫描应用的 models/ 目录，基于实际文件系统构建 [ModelInfo] 列表。
 * 同时整合 [ModelManager] 中已注册的模型描述信息。
 *
 * @param context Android 上下文
 * @param modelManager 模型管理器（用于获取已注册模型的元数据）
 */
class LocalModelCatalog(
    private val context: Context,
    private val modelManager: ModelManager? = null,
) : ModelCatalog {

    companion object {
        private const val TAG = "LocalModelCatalog"
    }

    private val modelDir: File
        get() {
            val dir = File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    override suspend fun listModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()

        // 1. 扫描本地 models/ 目录
        val dir = modelDir
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val format = detectFormat(file.name)
                    if (format != null) {
                        models.add(
                            ModelInfo(
                                modelId = file.nameWithoutExtension,
                                displayName = file.nameWithoutExtension.replace("_", " "),
                                format = format,
                                fileSizeBytes = file.length(),
                                source = ModelSource.LOCAL,
                                localFilePath = file.absolutePath,
                                description = "本地模型文件 (${format.name})",
                            )
                        )
                    }
                }
            }
        }

        // 2. 整合 ModelManager 中已注册的模型
        modelManager?.let { mgr ->
            val registeredStates = mgr.getAllModelStates().value
            for (state in registeredStates) {
                val existing = models.find { it.modelId == state.modelId }
                if (existing != null) {
                    // 更新已有模型的状态信息
                    val updated = existing.copy(
                        fileSizeBytes = if (existing.fileSizeBytes <= 0) -1 else existing.fileSizeBytes,
                    )
                    models[models.indexOf(existing)] = updated
                }
            }
        }

        Log.d(TAG, "扫描本地模型目录: ${models.size} 个模型")
        return models.sortedBy { it.displayName }
    }

    override suspend fun search(query: String): List<ModelInfo> {
        val lower = query.lowercase()
        return listModels().filter {
            it.displayName.lowercase().contains(lower) ||
                it.description.lowercase().contains(lower) ||
                it.tags.any { tag -> tag.lowercase().contains(lower) } ||
                it.modelId.lowercase().contains(lower)
        }
    }

    override suspend fun filterByFormat(format: ModelFormat): List<ModelInfo> {
        return listModels().filter { it.format == format }
    }

    override suspend fun filterByTag(tag: String): List<ModelInfo> {
        val lower = tag.lowercase()
        return listModels().filter { it.tags.any { t -> t.lowercase() == lower } }
    }

    override suspend fun getModel(modelId: String): ModelInfo? {
        return listModels().find { it.modelId == modelId }
    }

    override suspend fun getTotalStorageBytes(): Long {
        return listModels().sumOf { it.fileSizeBytes.coerceAtLeast(0) }
    }

    private fun detectFormat(fileName: String): ModelFormat? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".tflite") -> ModelFormat.TFLITE
            lower.endsWith(".onnx") -> ModelFormat.ONNX
            lower.endsWith(".pt") || lower.endsWith(".ptl") || lower.endsWith(".pth") -> ModelFormat.PYTORCH
            else -> null
        }
    }
}
