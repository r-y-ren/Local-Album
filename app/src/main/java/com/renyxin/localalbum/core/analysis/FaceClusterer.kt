package com.renyxin.localalbum.core.analysis

import kotlin.math.sqrt

/**
 * 人脸特征聚类器（Phase 3.3 人脸聚类）。
 *
 * 采用 DBSCAN（Density-Based Spatial Clustering of Applications with Noise）算法，
 * 基于人脸特征向量的余弦距离进行聚类。
 *
 * 选择 DBSCAN 的理由：
 * - 不需要预先指定聚类数量 K（人物数量未知）
 * - 能发现任意形状的簇
 * - 可将孤立人脸标记为噪声（不归入任何聚类）
 * - 对异常值鲁棒
 *
 * 距离度量：余弦距离 = 1 - cos(θ)，范围 [0, 2]
 * - 同一人不同照片：通常 < 0.3
 * - 不同人：通常 > 0.5
 *
 * @param eps 邻域半径（余弦距离），默认 0.4
 * @param minPts 形成聚类所需的最少样本数，默认 2
 */
class FaceClusterer(
    private val eps: Float = DEFAULT_EPS,
    private val minPts: Int = DEFAULT_MIN_PTS,
) {
    companion object {
        const val DEFAULT_EPS = 0.4f
        const val DEFAULT_MIN_PTS = 2
    }

    /**
     * 单个人脸样本。
     *
     * @param id 样本唯一标识（对应 FaceEntity.faceId）
     * @param embedding 特征向量（L2 归一化）
     */
    data class FaceSample(
        val id: Long,
        val embedding: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceSample) return false
            return id == other.id
        }
        override fun hashCode(): Int = id.hashCode()
    }

    /**
     * 聚类结果。
     *
     * @param clusters 聚类列表，每个聚类含样本 ID 列表与生成的 clusterId
     * @param noise 噪声样本 ID 列表（未归入任何聚类）
     */
    data class ClusterResult(
        val clusters: List<Cluster>,
        val noise: List<Long>,
    ) {
        /** 聚类数量 */
        val clusterCount: Int get() = clusters.size
        /** 噪声数量 */
        val noiseCount: Int get() = noise.size
    }

    /**
     * 单个聚类。
     *
     * @param clusterId 聚类 ID（UUID）
     * @param sampleIds 该聚类包含的样本 ID 列表
     */
    data class Cluster(
        val clusterId: String,
        val sampleIds: List<Long>,
    )

    /**
     * 对人脸样本集合执行 DBSCAN 聚类。
     *
     * @param samples 人脸样本列表
     * @return 聚类结果
     */
    fun cluster(samples: List<FaceSample>): ClusterResult {
        if (samples.isEmpty()) return ClusterResult(emptyList(), emptyList())

        val n = samples.size
        val visited = BooleanArray(n)
        val clusterLabels = IntArray(n) { -1 } // -1 = 未分类, -2 = 噪声, >=0 = 聚类索引
        var clusterIdx = 0

        // 预计算邻域（避免重复距离计算）
        val neighborsCache = Array(n) { i -> regionQuery(i, samples) }

        for (i in 0 until n) {
            if (visited[i]) continue
            visited[i] = true

            val neighbors = neighborsCache[i]
            if (neighbors.size < minPts) {
                clusterLabels[i] = -2 // 标记为噪声（后续可能被并入其他簇）
                continue
            }

            // 开始新聚类
            val currentCluster = clusterIdx
            clusterIdx++
            clusterLabels[i] = currentCluster

            // 使用队列扩展聚类（BFS）
            val queue = ArrayDeque<Int>()
            queue.addAll(neighbors)

            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                if (clusterLabels[q] == -2) {
                    // 之前标记为噪声，现在归入当前簇（边界点）
                    clusterLabels[q] = currentCluster
                }
                if (visited[q]) continue
                visited[q] = true
                clusterLabels[q] = currentCluster

                val qNeighbors = neighborsCache[q]
                if (qNeighbors.size >= minPts) {
                    // 核心点：扩展邻域
                    for (nb in qNeighbors) {
                        if (!visited[nb] && clusterLabels[nb] == -1) {
                            queue.add(nb)
                        }
                    }
                }
            }
        }

        // 收集结果
        val clustersMap = mutableMapOf<Int, MutableList<Long>>()
        val noise = mutableListOf<Long>()
        for (i in 0 until n) {
            val label = clusterLabels[i]
            if (label >= 0) {
                clustersMap.getOrPut(label) { mutableListOf() }.add(samples[i].id)
            } else if (label == -2) {
                noise.add(samples[i].id)
            }
        }

        val clusters = clustersMap.map { (_, ids) ->
            Cluster(
                clusterId = java.util.UUID.randomUUID().toString(),
                sampleIds = ids,
            )
        }

        return ClusterResult(clusters, noise)
    }

    /**
     * 计算样本 i 的 eps 邻域（余弦距离 ≤ eps 的所有样本索引）。
     */
    private fun regionQuery(i: Int, samples: List<FaceSample>): List<Int> {
        val result = mutableListOf<Int>()
        val embI = samples[i].embedding
        for (j in samples.indices) {
            if (i == j) {
                result.add(j)
                continue
            }
            if (cosineDistance(embI, samples[j].embedding) <= eps) {
                result.add(j)
            }
        }
        return result
    }

    /**
     * 计算两个 L2 归一化向量的余弦距离 = 1 - cos(θ)。
     * 范围 [0, 2]，0 表示完全相同，2 表示完全相反。
     */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
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
        val cosine = dot / denom
        return (1f - cosine).coerceIn(0f, 2f)
    }

    /**
     * 将特征向量从逗号分隔字符串解析为 FloatArray。
     */
    fun parseEmbedding(embeddingStr: String): FloatArray {
        return embeddingStr.split(",")
            .filter { it.isNotBlank() }
            .map { it.trim().toFloat() }
            .toFloatArray()
    }

    /**
     * 将 FloatArray 序列化为逗号分隔字符串（用于数据库存储）。
     */
    fun serializeEmbedding(embedding: FloatArray): String {
        return embedding.joinToString(",") { "%.6f".format(it) }
    }
}
