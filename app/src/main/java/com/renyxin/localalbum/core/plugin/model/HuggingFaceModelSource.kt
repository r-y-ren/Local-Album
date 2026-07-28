package com.renyxin.localalbum.core.plugin.model

import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HuggingFace 模型源（Phase 5a — 远程模型仓库集成）。
 *
 * 通过 HuggingFace Hub API 获取 AI 模型列表，筛选支持移动端部署的格式
 * （TFLite, ONNX, PyTorch Mobile），按下载量排序。
 *
 * ## API 端点
 * - 搜索: `GET https://huggingface.co/api/models?search=<q>&filter=tflite,onnx`
 * - 详情: `GET https://huggingface.co/api/models/{modelId}`
 * - 下载: `https://huggingface.co/{modelId}/resolve/main/{filename}`
 *
 * ## 限流处理
 * - 429 响应时使用 Exponential Backoff 重试（1s → 2s → 4s）
 * - 结果缓存在内存中 1 小时（TTL）
 *
 * ## 依赖
 * - 零外部依赖：使用 `java.net.HttpURLConnection` + `org.json`
 */
class HuggingFaceModelSource : RemoteModelSource {

    companion object {
        private const val TAG = "HuggingFaceSrc"
        private const val API_BASE = "https://huggingface.co/api"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }

    override val sourceName = "HuggingFace"

    private var cachedModels: List<ModelInfo>? = null
    private var cacheTimestamp: Long = 0

    override suspend fun fetchModels(query: String?, limit: Int): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            // 检查缓存
            val cacheAge = System.currentTimeMillis() - cacheTimestamp
            if (cachedModels != null && cacheAge < CACHE_TTL_MS && query == null) {
                Log.d(TAG, "使用缓存模型列表 (${cachedModels!!.size} 个)")
                return@withContext cachedModels!!
            }

            try {
                val url = buildString {
                    append("$API_BASE/models")
                    if (!query.isNullOrBlank()) {
                        append("?search=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                        append("&filter=tflite,onnx&sort=downloads&direction=-1")
                    } else {
                        append("?filter=tflite,onnx&sort=downloads&direction=-1&limit=$limit")
                    }
                }

                val models = fetchWithRetry(url, parser = { jsonArray ->
                    parseModelList(jsonArray)
                })

                if (query == null) {
                    cachedModels = models
                    cacheTimestamp = System.currentTimeMillis()
                }

                Log.i(TAG, "获取到 ${models.size} 个模型 (query=${query ?: "popular"})")
                models
            } catch (e: Exception) {
                Log.w(TAG, "获取模型列表失败", e)
                cachedModels ?: emptyList()
            }
        }

    override suspend fun fetchModelDetail(modelId: String): ModelInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$API_BASE/models/$modelId"
                fetchWithRetry(url, parser = { json ->
                    parseModelDetail(json)
                })
            } catch (e: Exception) {
                Log.w(TAG, "获取模型详情失败: $modelId", e)
                null
            }
        }

    override fun buildDownloadUrl(modelId: String, fileName: String): String {
        return "https://huggingface.co/$modelId/resolve/main/$fileName"
    }

    // ---- 内部 ----

    private suspend fun <T> fetchWithRetry(
        urlString: String,
        parser: (JSONObject) -> T,
        maxRetries: Int = 3,
    ): T {
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "LocalAlbum/1.0")

                val responseCode = connection.responseCode

                when {
                    responseCode == HttpURLConnection.HTTP_OK -> {
                        val jsonText = connection.inputStream.bufferedReader().readText()
                        connection.disconnect()

                        val root = if (jsonText.trimStart().startsWith("[")) {
                            JSONObject().apply { put("data", JSONArray(jsonText)) }
                        } else {
                            JSONObject(jsonText)
                        }
                        return parser(root)
                    }
                    responseCode == 429 -> {
                        val waitMs = 1000L * (1 shl attempt)
                        Log.w(TAG, "API 限流 (429)，${waitMs}ms 后重试 (${attempt + 1}/$maxRetries)")
                        connection.disconnect()
                        delay(waitMs)
                    }
                    else -> {
                        connection.disconnect()
                        throw RuntimeException("HTTP $responseCode: $urlString")
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    delay(1000L * (1 shl attempt))
                }
            }
        }

        throw lastError ?: RuntimeException("Unknown error fetching $urlString")
    }

    private fun parseModelList(json: JSONObject): List<ModelInfo> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val models = mutableListOf<ModelInfo>()

        for (i in 0 until dataArray.length()) {
            try {
                val item = dataArray.getJSONObject(i)
                val model = parseModelItem(item)
                if (model != null) models.add(model)
            } catch (_: Exception) {
                // 跳过解析失败的条目
            }
        }

        return models
    }

    private fun parseModelDetail(json: JSONObject): ModelInfo? {
        return parseModelItem(json)
    }

    private fun parseModelItem(item: JSONObject): ModelInfo? {
        val modelId = item.optString("modelId", item.optString("id", ""))
        if (modelId.isBlank()) return null

        val pipelineTag = item.optString("pipeline_tag", "")
        val tags = item.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val format = when {
            tags.any { it.contains("tflite", ignoreCase = true) } -> ModelFormat.TFLITE
            tags.any { it.contains("onnx", ignoreCase = true) } -> ModelFormat.ONNX
            tags.any { it.contains("pytorch", ignoreCase = true) } -> ModelFormat.PYTORCH
            else -> ModelFormat.TFLITE
        }

        val displayName = modelId.substringAfterLast("/")
            .replace("-", " ")
            .replace("_", " ")

        val downloads = item.optLong("downloads", 0)
        val likes = item.optInt("likes", 0)
        val rating = if (likes > 0) (likes / 100f).coerceIn(0f, 5f) else 0f

        val siblings = item.optJSONArray("siblings")
        val modelFileName = findModelFileName(siblings, format)

        return ModelInfo(
            modelId = modelId,
            displayName = displayName,
            description = pipelineTag.ifBlank { "AI Model" },
            format = format,
            fileSizeBytes = -1,
            author = modelId.substringBefore("/"),
            tags = tags + pipelineTag,
            rating = rating,
            downloadCount = downloads,
            source = ModelSource.REMOTE_DOWNLOAD,
            downloadUrl = if (modelFileName != null) buildDownloadUrl(modelId, modelFileName) else null,
        )
    }

    private fun findModelFileName(siblings: JSONArray?, format: ModelFormat): String? {
        if (siblings == null) return null
        for (i in 0 until siblings.length()) {
            val sibling = siblings.getJSONObject(i)
            val rfilename = sibling.optString("rfilename", "")
            when (format) {
                ModelFormat.TFLITE -> if (rfilename.endsWith(".tflite")) return rfilename
                ModelFormat.ONNX -> if (rfilename.endsWith(".onnx")) return rfilename
                ModelFormat.PYTORCH -> if (rfilename.endsWith(".pt") || rfilename.endsWith(".ptl")) return rfilename
            }
        }
        return null
    }
}
