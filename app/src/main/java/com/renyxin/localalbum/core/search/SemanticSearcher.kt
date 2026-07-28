package com.renyxin.localalbum.core.search

import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 语义搜索引擎（Phase 4.1 语义搜索 / 重构为 Provider 驱动）。
 *
 * 工作流：
 * 1. 将用户文本查询通过 [provider.embedText] 编码为语义向量
 * 2. 从 [EmbeddingDao] 全量加载已索引的媒体嵌入向量
 * 3. 计算查询向量与每条媒体向量的余弦相似度
 * 4. 按相似度降序排序，返回 Top-K 结果
 *
 * 重构说明：原实现硬编码 [com.renyxin.localalbum.core.analysis.SemanticEmbedder]（概念向量），
 * 现改为注入任意 [SemanticEmbedProvider]（概念向量 / CLIP / SigLIP…），
 * 使搜索侧与索引侧使用同一 Provider，保证向量空间一致。
 *
 * 向量序列化格式为逗号分隔的 Float 字符串，与 Provider 无关，由本类的 [serialize]/[deserialize] 统一处理。
 *
 * @param provider 当前激活的语义嵌入 Provider
 * @param embeddingDao 嵌入向量 DAO
 * @param modelVersion 当前模型版本号（用于统计）
 */
class SemanticSearcher(
    private val provider: SemanticEmbedProvider,
    private val embeddingDao: EmbeddingDao,
    private val modelVersion: Int = provider.modelVersion(),
    private val embeddingDim: Int = provider.embeddingDim,
) {
    companion object {
        private const val TAG = "SemanticSearcher"
        /** 默认返回的 Top-K 结果数 */
        const val DEFAULT_TOP_K = 50
        /** 最低相似度阈值，低于此值不返回。
         *  CLIP 跨模态（文本→图像）余弦相似度通常较低（0.10~0.35），
         *  降至 0.10 以保留更多有效匹配。 */
        const val MIN_SIMILARITY = 0.10f
    }

    /**
     * 安全日志输出，避免在单元测试中因 android.util.Log 未 mock 而崩溃。
     */
    private fun safeLog(level: String, msg: String, throwable: Throwable? = null) {
        try {
            val clz = Class.forName("android.util.Log")
            val method = clz.getMethod(level, String::class.java, String::class.java)
            method.invoke(null, TAG, msg)
            if (throwable != null) {
                val methodWithThrowable = clz.getMethod(
                    level, String::class.java, String::class.java, Throwable::class.java,
                )
                methodWithThrowable.invoke(null, TAG, msg, throwable)
            }
        } catch (_: Throwable) {
            // 在非 Android 环境（单元测试）中静默忽略日志
        }
    }

    /**
     * 单条语义搜索结果。
     *
     * @param filePath 媒体文件路径
     * @param similarity 与查询的余弦相似度（0~1）
     */
    data class SearchResult(
        val filePath: String,
        val similarity: Float,
    )

    /**
     * 搜索诊断信息，用于区分"无匹配"与"搜索失败"。
     *
     * @param results 搜索结果列表
     * @param queryEncoded 查询文本是否成功编码为有效向量（false 表示模型加载失败）
     * @param indexSize 索引中的嵌入总数（0 表示尚未完成分析）
     */
    data class SearchDiagnostics(
        val results: List<SearchResult>,
        val queryEncoded: Boolean,
        val indexSize: Int,
    )

    /**
     * 执行语义搜索。
     *
     * @param query 自然语言查询（中文或英文）
     * @param topK 返回的最大结果数
     * @param minSimilarity 最低相似度阈值
     * @return 按相似度降序排列的结果列表
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        minSimilarity: Float = MIN_SIMILARITY,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. 编码查询
        val queryVec = provider.embedText(query)
        if (queryVec.all { it == 0f }) {
            safeLog("i", "查询 \"$query\" 无法编码为有效语义向量")
            return@withContext emptyList()
        }

        // 2. 加载所有嵌入向量
        val allEmbeddings = embeddingDao.getAll()
        if (allEmbeddings.isEmpty()) {
            safeLog("i", "无已索引的语义嵌入，跳过搜索")
            return@withContext emptyList()
        }

        // 3. 计算相似度并排序
        val results = ArrayList<SearchResult>(allEmbeddings.size)
        for (emb in allEmbeddings) {
            val mediaVec = try {
                deserialize(emb.embedding)
            } catch (e: Exception) {
                safeLog("w", "反序列化嵌入失败: ${emb.filePath}", e)
                continue
            }
            if (mediaVec.size != queryVec.size) {
                safeLog("w", "向量维度不匹配: ${emb.filePath} (${mediaVec.size} vs ${queryVec.size})")
                continue
            }
            val sim = cosineSimilarity(queryVec, mediaVec)
            if (sim >= minSimilarity) {
                results.add(SearchResult(emb.filePath, sim))
            }
        }

        // 4. 排序并截取 Top-K
        results.sortByDescending { it.similarity }
        val finalResults = if (results.size > topK) results.take(topK) else results

        safeLog("i", "语义搜索 \"$query\": ${allEmbeddings.size} 条嵌入, 命中 ${finalResults.size} 条, " +
                "最高相似度 ${finalResults.firstOrNull()?.similarity ?: 0f}")

        finalResults
    }

    /**
     * 执行语义搜索并返回诊断信息。
     *
     * 与 [search] 的区别：返回 [SearchDiagnostics]，使调用方能区分
     * "无匹配结果"与"模型未就绪/索引为空"等失败场景，便于 UI 给出准确反馈。
     */
    suspend fun searchDetailed(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        minSimilarity: Float = MIN_SIMILARITY,
    ): SearchDiagnostics = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext SearchDiagnostics(emptyList(), queryEncoded = false, indexSize = 0)
        }

        // 1. 编码查询
        val queryVec = provider.embedText(query)
        val queryEncoded = !queryVec.all { it == 0f }
        if (!queryEncoded) {
            safeLog("w", "查询 \"$query\" 无法编码为有效语义向量（模型可能未加载）")
            val indexSize = embeddingDao.getCount()
            return@withContext SearchDiagnostics(emptyList(), queryEncoded = false, indexSize = indexSize)
        }

        // 2. 加载所有嵌入向量
        val allEmbeddings = embeddingDao.getAll()
        if (allEmbeddings.isEmpty()) {
            safeLog("i", "无已索引的语义嵌入，跳过搜索")
            return@withContext SearchDiagnostics(emptyList(), queryEncoded = true, indexSize = 0)
        }

        // 3. 计算相似度并排序
        val results = ArrayList<SearchResult>(allEmbeddings.size)
        for (emb in allEmbeddings) {
            val mediaVec = try {
                deserialize(emb.embedding)
            } catch (e: Exception) {
                safeLog("w", "反序列化嵌入失败: ${emb.filePath}", e)
                continue
            }
            if (mediaVec.size != queryVec.size) {
                safeLog("w", "向量维度不匹配: ${emb.filePath} (${mediaVec.size} vs ${queryVec.size})")
                continue
            }
            val sim = cosineSimilarity(queryVec, mediaVec)
            if (sim >= minSimilarity) {
                results.add(SearchResult(emb.filePath, sim))
            }
        }

        // 4. 排序并截取 Top-K
        results.sortByDescending { it.similarity }
        val finalResults = if (results.size > topK) results.take(topK) else results

        safeLog("i", "语义搜索 \"$query\": ${allEmbeddings.size} 条嵌入, 命中 ${finalResults.size} 条, " +
                "最高相似度 ${finalResults.firstOrNull()?.similarity ?: 0f}")

        SearchDiagnostics(finalResults, queryEncoded = true, indexSize = allEmbeddings.size)
    }

    /**
     * 查找与指定媒体文件语义最相似的其他文件（以图搜图）。
     *
     * @param filePath 参考媒体文件路径
     * @param topK 返回的最大结果数
     * @param excludeSelf 是否排除参考文件本身
     * @return 按相似度降序排列的结果列表
     */
    suspend fun findSimilar(
        filePath: String,
        topK: Int = DEFAULT_TOP_K,
        excludeSelf: Boolean = true,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val refEmbedding = embeddingDao.getByFilePath(filePath) ?: return@withContext emptyList()
        val refVec = deserialize(refEmbedding.embedding)

        val allEmbeddings = embeddingDao.getAll()
        val results = ArrayList<SearchResult>(allEmbeddings.size)

        for (emb in allEmbeddings) {
            if (excludeSelf && emb.filePath == filePath) continue
            val mediaVec = try {
                deserialize(emb.embedding)
            } catch (e: Exception) {
                continue
            }
            if (mediaVec.size != refVec.size) continue
            val sim = cosineSimilarity(refVec, mediaVec)
            if (sim >= MIN_SIMILARITY) {
                results.add(SearchResult(emb.filePath, sim))
            }
        }

        results.sortByDescending { it.similarity }
        if (results.size > topK) results.take(topK) else results
    }

    /**
     * 获取语义嵌入索引统计信息。
     */
    suspend fun getStats(): SemanticSearchStats = withContext(Dispatchers.IO) {
        SemanticSearchStats(
            totalEmbeddings = embeddingDao.getCount(),
            modelVersion = modelVersion,
            embeddingDim = embeddingDim,
        )
    }

    // ---- 通用向量编解码（与 Provider 无关，逗号分隔 Float 字符串） ----

    /**
     * 将向量序列化为逗号分隔的字符串（用于数据库存储）。
     */
    fun serialize(vec: FloatArray): String {
        return vec.joinToString(",") { "%.6f".format(it) }
    }

    /**
     * 从逗号分隔的字符串反序列化为向量。
     */
    fun deserialize(str: String): FloatArray {
        return str.split(",").mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }
}

/**
 * 语义搜索索引统计信息。
 */
data class SemanticSearchStats(
    val totalEmbeddings: Int = 0,
    val modelVersion: Int = 0,
    val embeddingDim: Int = 0,
)

/**
 * 从 [SemanticEmbedProvider] 推断模型版本号。
 * CLIP 类 Provider 通过常量暴露版本；概念向量 Provider 退回 1。
 */
private fun SemanticEmbedProvider.modelVersion(): Int = when (this) {
    is com.renyxin.localalbum.core.plugin.capability.builtin.Eva02ClipProvider ->
        com.renyxin.localalbum.core.plugin.capability.builtin.Eva02ClipProvider.MODEL_VERSION
    is com.renyxin.localalbum.core.plugin.capability.builtin.ConceptEmbedProvider ->
        com.renyxin.localalbum.core.analysis.SemanticEmbedder.MODEL_VERSION
    else -> 1
}
