package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.analysis.SemanticEmbedder
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 语义聚类推荐策略（Phase 5 推荐增强）。
 *
 * 利用已存储在 [media_embeddings] 表中的 32 维概念向量，通过简化 K-Means 聚类
 * 将语义相近的图片分组，每个聚类取质量 Top-N 组成一个"主题精选"推荐。
 *
 * 聚类中心向量中最大分量对应的 [SemanticEmbedder.CONCEPTS] 概念名作为推荐标题。
 *
 * 评分公式：0.3 * 簇内平均质量 + 0.3 * 新鲜度 + 0.2 * 簇规模 + 0.2 * 簇内凝聚度
 *
 * 无嵌入数据时静默返回空列表，不影响其他推荐策略。
 *
 * @param clock 时钟，用于新鲜度计算
 * @param embeddingDao 嵌入向量 DAO（可选，为 null 时跳过）
 * @param k 聚类数量，默认 8
 * @param maxIterations K-Means 最大迭代次数，默认 20
 * @param topNPerCluster 每个聚类取质量最高的前 N 张，默认 20
 * @param minClusterSize 形成推荐所需的最少图片数，默认 5
 */
class SemanticClusterRecommender(
    private val clock: Clock = Clock.systemUTC(),
    private val embeddingDao: EmbeddingDao? = null,
    private val k: Int = 8,
    private val maxIterations: Int = 20,
    private val topNPerCluster: Int = 20,
    private val minClusterSize: Int = 5,
) {
    /**
     * 基于语义嵌入向量生成聚类推荐。
     *
     * @param allItems 全部媒体项（用于获取质量分、时间、收藏等元数据）
     * @return 语义聚类推荐列表，按评分降序排列；无嵌入数据时返回空列表
     */
    suspend fun generate(allItems: List<MediaItem>): List<Recommendation> = withContext(Dispatchers.IO) {
        if (embeddingDao == null || allItems.size < minClusterSize) return@withContext emptyList()

        // 1. 加载全部嵌入向量
        val embeddings = embeddingDao.getAll()
        if (embeddings.size < minClusterSize) return@withContext emptyList()

        // 2. 建立 filePath → MediaItem 映射，过滤无对应媒体的嵌入
        val itemByPath = allItems.associateBy { it.filePath }
        val validEmbeddings = embeddings.filter { it.filePath in itemByPath }
        if (validEmbeddings.size < minClusterSize) return@withContext emptyList()

        // 3. 解析向量为 FloatArray
        val vectors = validEmbeddings.map { it.filePath to parseVector(it.embedding) }
        val validVectors = vectors.filter { it.second.isNotEmpty() }
        if (validVectors.size < minClusterSize) return@withContext emptyList()

        // 4. 执行 K-Means 聚类
        val actualK = k.coerceAtMost(validVectors.size / minClusterSize.coerceAtLeast(1)).coerceAtLeast(1)
        val clusters = kMeans(validVectors, actualK)

        // 5. 为每个聚类生成推荐
        val now = Instant.now(clock)
        val result = mutableListOf<Recommendation>()

        clusters.forEach { cluster ->
            if (cluster.members.size < minClusterSize) return@forEach

            val clusterItems = cluster.members
                .mapNotNull { (path, _) -> itemByPath[path] }
                .sortedWith(compareByDescending<MediaItem> { it.qualityScore }.thenByDescending { it.capturedAt })
                .take(topNPerCluster)

            if (clusterItems.size < minClusterSize) return@forEach

            // 评分维度
            val avgQuality = clusterItems.map { it.qualityScore.toDouble() }.average()
            val freshness = freshnessFactor(clusterItems.maxOf { it.capturedAt }, now)
            val sizeFactor = 1.0 - exp(-clusterItems.size / 10.0)
            val cohesion = computeCohesion(cluster.members.map { it.second }, cluster.centroid)

            val score = (0.3 * avgQuality) +
                (0.3 * freshness) +
                (0.2 * sizeFactor) +
                (0.2 * cohesion)

            val sorted = clusterItems.sortedBy { it.capturedAt }
            val conceptName = conceptNameForCentroid(cluster.centroid)

            result += Recommendation(
                albumId = "semantic-${conceptName.enName}",
                albumName = "${conceptName.zhName}主题",
                directoryPath = "语义聚合",
                windowStart = sorted.first().capturedAt,
                windowEnd = sorted.last().capturedAt,
                mediaItems = sorted,
                reason = buildReason(avgQuality, clusterItems.count { it.isFavorite }, clusterItems.size),
                score = score,
                category = RecommendationCategory.SEMANTIC_CLUSTER,
            )
        }

        result.sortedByDescending { it.score }
    }

    // ---- K-Means 实现 ----

    /** 单个聚类结果 */
    private data class Cluster(
        val centroid: FloatArray,
        val members: List<Pair<String, FloatArray>>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Cluster) return false
            return centroid.contentEquals(other.centroid) && members == other.members
        }

        override fun hashCode(): Int = centroid.contentHashCode() * 31 + members.hashCode()
    }

    /**
     * 简化 K-Means 聚类（余弦距离）。
     * 使用 K-Means++ 初始化以获得更稳定的聚类结果。
     */
    private fun kMeans(
        data: List<Pair<String, FloatArray>>,
        actualK: Int,
    ): List<Cluster> {
        if (actualK <= 0 || data.isEmpty()) return emptyList()
        val dim = data.first().second.size

        // K-Means++ 初始化
        var centroids = initPlusPlus(data, actualK, dim)

        var assignments = IntArray(data.size) { -1 }

        for (iter in 0 until maxIterations) {
            var changed = false

            // 分配步骤：将每个点分配到最近的中心
            for (i in data.indices) {
                val bestCentroid = findNearestCentroid(data[i].second, centroids)
                if (assignments[i] != bestCentroid) {
                    assignments[i] = bestCentroid
                    changed = true
                }
            }

            if (!changed && iter > 0) break

            // 更新步骤：重新计算中心
            centroids = updateCentroids(data, assignments, actualK, dim, centroids)
        }

        // 收集聚类
        return (0 until actualK).map { ci ->
            val members = data.filterIndexed { i, _ -> assignments[i] == ci }
            Cluster(centroid = centroids[ci], members = members)
        }.filter { it.members.isNotEmpty() }
    }

    /** K-Means++ 初始化：选择距离已选中心最远的点作为新中心 */
    private fun initPlusPlus(
        data: List<Pair<String, FloatArray>>,
        k: Int,
        dim: Int,
    ): Array<FloatArray> {
        val centroids = Array(k) { FloatArray(dim) }
        // 随机选第一个中心
        val firstIdx = Random.nextInt(data.size)
        centroids[0] = data[firstIdx].second.copyOf()

        for (c in 1 until k) {
            // 计算每个点到最近中心的距离
            val dists = FloatArray(data.size) { i ->
                var minDist = Float.MAX_VALUE
                for (j in 0 until c) {
                    val d = cosineDistance(data[i].second, centroids[j])
                    if (d < minDist) minDist = d
                }
                minDist
            }
            // 选择距离最大的点（带概率加权）
            val total = dists.sum()
            if (total <= 0f) {
                centroids[c] = data[Random.nextInt(data.size)].second.copyOf()
            } else {
                var r = Random.nextFloat() * total
                var selected = 0
                for (i in dists.indices) {
                    r -= dists[i]
                    if (r <= 0f) { selected = i; break }
                }
                centroids[c] = data[selected].second.copyOf()
            }
        }
        return centroids
    }

    /** 找到距离向量最近的中心索引 */
    private fun findNearestCentroid(vec: FloatArray, centroids: Array<FloatArray>): Int {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in centroids.indices) {
            val d = cosineDistance(vec, centroids[i])
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return bestIdx
    }

    /** 更新中心：取簇内成员向量的均值 */
    private fun updateCentroids(
        data: List<Pair<String, FloatArray>>,
        assignments: IntArray,
        k: Int,
        dim: Int,
        oldCentroids: Array<FloatArray>,
    ): Array<FloatArray> {
        val newCentroids = Array(k) { FloatArray(dim) }
        val counts = IntArray(k)

        for (i in data.indices) {
            val ci = assignments[i]
            if (ci < 0 || ci >= k) continue
            val vec = data[i].second
            for (d in 0 until dim) {
                newCentroids[ci][d] += vec[d]
            }
            counts[ci]++
        }

        for (c in 0 until k) {
            if (counts[c] > 0) {
                val count = counts[c].toFloat()
                for (d in 0 until dim) {
                    newCentroids[c][d] = newCentroids[c][d] / count
                }
            } else {
                // 空簇保留旧中心
                newCentroids[c] = oldCentroids[c].copyOf()
            }
        }
        return newCentroids
    }

    // ---- 辅助函数 ----

    /** 簇内凝聚度：平均到中心余弦距离的倒数，归一化到 [0,1] */
    private fun computeCohesion(members: List<FloatArray>, centroid: FloatArray): Double {
        if (members.isEmpty()) return 0.0
        val avgDist = members.map { cosineDistance(it, centroid).toDouble() }.average()
        return (1.0 - avgDist / 2.0).coerceIn(0.0, 1.0)
    }

    /** 解析逗号分隔的向量字符串 */
    private fun parseVector(str: String): FloatArray {
        return str.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
    }

    /** 余弦距离 = 1 - cos(θ)，范围 [0, 2] */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 2f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-6f) return 2f
        return (1f - dot / denom).coerceIn(0f, 2f)
    }

    /** 根据中心向量最大分量找到对应概念名 */
    private fun conceptNameForCentroid(centroid: FloatArray): SemanticEmbedder.Concept {
        var maxIdx = 0
        var maxVal = -Float.MAX_VALUE
        for (i in centroid.indices) {
            if (centroid[i] > maxVal) {
                maxVal = centroid[i]
                maxIdx = i
            }
        }
        return SemanticEmbedder.CONCEPTS.getOrElse(maxIdx) { SemanticEmbedder.CONCEPTS.last() }
    }

    /** 新鲜度因子：越老分越高（保留用户习惯） */
    private fun freshnessFactor(end: Instant, now: Instant): Double {
        val daysAgo = Duration.between(end, now).toDays().coerceAtLeast(0)
        return (1 - exp(-daysAgo / 30.0)).coerceIn(0.0, 1.0)
    }

    /** 构建推荐理由文本 */
    private fun buildReason(avgQuality: Double, favoriteCount: Int, size: Int): String {
        val parts = mutableListOf("语义聚合·${size}张")
        if (avgQuality > 0.6) parts += "高质量"
        if (favoriteCount > 0) parts += "含${favoriteCount}张收藏"
        return parts.joinToString("·")
    }
}
