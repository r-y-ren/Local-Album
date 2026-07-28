package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 模型存储管理器（Phase 4 — 模型生命周期管理）。
 *
 * 管理 AI 模型文件的存储空间，提供：
 * - 总存储占用查询
 * - 各模型大小明细
 * - 缓存文件清理
 * - 存储空间不足检测与警告
 *
 * @param context Android 上下文
 */
class ModelStorageManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ModelStorageMgr"
        private const val MODELS_DIR = "models"
        private const val CACHE_DIR = "model_cache"

        /** 存储空间不足警告阈值（低于此值触发警告） */
        const val LOW_STORAGE_THRESHOLD_MB = 100L

        /** 格式化字节数为人类可读字符串 */
        fun formatBytes(bytes: Long): String = when {
            bytes < 0 -> "未知"
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * 存储统计信息。
     *
     * @property totalBytes 模型总占用字节
     * @property modelCount 模型数量
     * @property cacheBytes 缓存占用字节
     * @property availableStorageBytes 设备可用存储字节
     */
    data class StorageStats(
        val totalBytes: Long = 0,
        val modelCount: Int = 0,
        val cacheBytes: Long = 0,
        val availableStorageBytes: Long = -1,
    ) {
        val totalFormatted: String get() = ModelStorageManager.formatBytes(totalBytes)
        val cacheFormatted: String get() = ModelStorageManager.formatBytes(cacheBytes)
        val availableFormatted: String get() = if (availableStorageBytes >= 0) ModelStorageManager.formatBytes(availableStorageBytes) else "未知"

        val isLowStorage: Boolean
            get() = availableStorageBytes >= 0 &&
                availableStorageBytes < LOW_STORAGE_THRESHOLD_MB * 1024 * 1024
    }

    /**
     * 单个模型的存储信息。
     *
     * @property modelId 模型 ID
     * @property displayName 显示名称
     * @property fileSizeBytes 文件大小（字节）
     * @property format 模型格式
     * @property lastModifiedMs 最后修改时间戳
     */
    data class ModelStorageInfo(
        val modelId: String,
        val displayName: String,
        val fileSizeBytes: Long,
        val format: String,
        val lastModifiedMs: Long,
    ) {
        val formattedSize: String get() = ModelStorageManager.formatBytes(fileSizeBytes)
    }

    /** 存储统计 StateFlow */
    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    /** 模型大小明细列表 */
    private val _modelDetails = MutableStateFlow<List<ModelStorageInfo>>(emptyList())
    val modelDetails: StateFlow<List<ModelStorageInfo>> = _modelDetails.asStateFlow()

    private val modelDir: File
        get() {
            val dir = File(context.filesDir, MODELS_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /**
     * 刷新存储统计信息。
     */
    fun refresh() {
        val details = scanModelFiles()
        val totalBytes = details.sumOf { it.fileSizeBytes }
        val cacheBytes = calculateCacheSize()
        val availableBytes = getAvailableStorage()

        _modelDetails.value = details.sortedByDescending { it.fileSizeBytes }
        _storageStats.value = StorageStats(
            totalBytes = totalBytes,
            modelCount = details.size,
            cacheBytes = cacheBytes,
            availableStorageBytes = availableBytes,
        )

        Log.d(TAG, "存储统计: ${details.size} 个模型, ${ModelStorageManager.formatBytes(totalBytes)}, 缓存 ${ModelStorageManager.formatBytes(cacheBytes)}")
    }

    /**
     * 清理模型推理缓存。
     *
     * @return 释放的字节数
     */
    fun clearCache(): Long {
        val dir = cacheDir
        var freedBytes = 0L
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    freedBytes += file.length()
                    file.delete()
                }
            }
        }
        Log.i(TAG, "缓存已清理，释放 ${ModelStorageManager.formatBytes(freedBytes)}")
        refresh()
        return freedBytes
    }

    /**
     * 删除指定模型文件。
     *
     * @return 是否删除成功
     */
    fun deleteModel(modelFileName: String): Boolean {
        val file = File(modelDir, modelFileName)
        val deleted = if (file.exists()) file.delete() else false
        if (deleted) {
            Log.i(TAG, "模型文件已删除: $modelFileName")
            refresh()
        }
        return deleted
    }

    // ---- 内部 ----

    private fun scanModelFiles(): List<ModelStorageInfo> {
        val dir = modelDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile }
            ?.map { file ->
                ModelStorageInfo(
                    modelId = file.nameWithoutExtension,
                    displayName = file.nameWithoutExtension.replace("_", " "),
                    fileSizeBytes = file.length(),
                    format = detectFormat(file.name),
                    lastModifiedMs = file.lastModified(),
                )
            }
            ?: emptyList()
    }

    private fun calculateCacheSize(): Long {
        val dir = cacheDir
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    private fun getAvailableStorage(): Long {
        return try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            -1L
        }
    }

    private fun detectFormat(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".tflite") -> "TFLite"
            lower.endsWith(".onnx") -> "ONNX"
            lower.endsWith(".pt") || lower.endsWith(".ptl") || lower.endsWith(".pth") -> "PyTorch"
            else -> "Unknown"
        }
    }
}
