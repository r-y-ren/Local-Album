package com.renyxin.localalbum.core.plugin.model

import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat

/**
 * 多源合并模型目录（Phase 5a — 远程模型仓库集成）。
 *
 * 将本地 [LocalModelCatalog] 与多个 [RemoteModelSource] 合并，
 * 提供统一的模型浏览和搜索体验。
 *
 * ## 去重策略
 * - 按 modelId 去重
 * - 远程模型若已在本地存在，保留本地版本（含实际文件大小）
 * - 本地模型优先于远程模型
 *
 * @param localCatalog 本地模型目录
 * @param remoteSources 远程模型源列表
 */
class CompositeModelCatalog(
    private val localCatalog: ModelCatalog,
    private val remoteSources: List<RemoteModelSource>,
) : ModelCatalog {

    companion object {
        private const val TAG = "CompositeCatalog"
    }

    override suspend fun listModels(): List<ModelInfo> {
        val localModels = try {
            localCatalog.listModels()
        } catch (e: Exception) {
            Log.w(TAG, "本地模型扫描失败", e)
            emptyList()
        }

        val remoteModels = mutableListOf<ModelInfo>()
        for (source in remoteSources) {
            try {
                remoteModels.addAll(source.fetchModels())
            } catch (e: Exception) {
                Log.w(TAG, "远程源 ${source.sourceName} 获取失败", e)
            }
        }

        return mergeAndDedupe(localModels, remoteModels)
    }

    override suspend fun search(query: String): List<ModelInfo> {
        val lower = query.lowercase()

        // 本地搜索
        val localResults = try {
            localCatalog.search(query)
        } catch (e: Exception) { emptyList() }

        // 远程搜索
        val remoteResults = mutableListOf<ModelInfo>()
        for (source in remoteSources) {
            try {
                remoteResults.addAll(source.fetchModels(query))
            } catch (e: Exception) {
                Log.w(TAG, "远程搜索失败: ${source.sourceName}", e)
            }
        }

        // 合并 + 额外客户端过滤（兜底）
        val merged = mergeAndDedupe(localResults, remoteResults)
        return merged.filter {
            it.displayName.lowercase().contains(lower) ||
                it.description.lowercase().contains(lower) ||
                it.tags.any { t -> t.lowercase().contains(lower) } ||
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
        // 优先本地
        val local = localCatalog.getModel(modelId)
        if (local != null) return local

        // 查询远程
        for (source in remoteSources) {
            try {
                val remote = source.fetchModelDetail(modelId)
                if (remote != null) return remote
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun getTotalStorageBytes(): Long {
        return localCatalog.getTotalStorageBytes()
    }

    // ---- 内部 ----

    private fun mergeAndDedupe(
        local: List<ModelInfo>,
        remote: List<ModelInfo>,
    ): List<ModelInfo> {
        val localIds = local.map { it.modelId }.toSet()
        val result = mutableListOf<ModelInfo>()

        // 本地模型优先
        result.addAll(local)

        // 远程模型中排除已在本地存在的
        for (remoteModel in remote) {
            if (remoteModel.modelId !in localIds) {
                result.add(remoteModel)
            } else {
                // 远程模型已在本地存在，更新本地模型的远程元数据
                val idx = result.indexOfFirst { it.modelId == remoteModel.modelId }
                if (idx >= 0) {
                    val localModel = result[idx]
                    result[idx] = localModel.copy(
                        downloadUrl = remoteModel.downloadUrl ?: localModel.downloadUrl,
                        tags = (localModel.tags + remoteModel.tags).distinct(),
                        rating = if (remoteModel.rating > 0) remoteModel.rating else localModel.rating,
                        downloadCount = maxOf(localModel.downloadCount, remoteModel.downloadCount),
                    )
                }
            }
        }

        return result
    }
}
