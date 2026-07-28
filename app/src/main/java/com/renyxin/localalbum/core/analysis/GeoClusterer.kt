package com.renyxin.localalbum.core.analysis

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地理聚类器（Phase 3.4 地图视图 + 地理聚类）。
 *
 * 采用 DBSCAN（Density-Based Spatial Clustering of Applications with Noise）算法，
 * 基于 GPS 坐标的 Haversine 距离进行地理聚类。
 *
 * 选择 DBSCAN 的理由：
 * - 不需要预先指定聚类数量 K（地点数量未知）
 * - 能发现任意形状的地理簇（如沿公路分布的照片）
 * - 可将孤立照片标记为噪声（不归入任何聚类）
 * - 对异常值鲁棒（偶尔的 GPS 漂移不会影响整体聚类）
 *
 * 距离度量：Haversine 公式计算地球表面两点间的大圆距离（公里）
 *
 * @param epsKm 邻域半径（公里），默认 0.2km（200 米）
 * @param minPts 形成聚类所需的最少样本数，默认 2
 */
class GeoClusterer(
    private val epsKm: Double = DEFAULT_EPS_KM,
    private val minPts: Int = DEFAULT_MIN_PTS,
) {
    companion object {
        /** 默认邻域半径 200 米 */
        const val DEFAULT_EPS_KM = 0.2
        const val DEFAULT_MIN_PTS = 2
        /** 地球半径（公里） */
        const val EARTH_RADIUS_KM = 6371.0
    }

    /**
     * 单个地理样本点。
     *
     * @param id 样本唯一标识（对应 MediaEntity.filePath）
     * @param latitude 纬度
     * @param longitude 经度
     */
    data class GeoSample(
        val id: String,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * 聚类结果。
     *
     * @param clusters 聚类列表，每个聚类含样本 ID 列表与生成的 clusterId
     * @param noise 噪声样本 ID 列表（未归入任何聚类）
     */
    data class ClusterResult(
        val clusters: List<Cluster>,
        val noise: List<String>,
    ) {
        /** 聚类数量 */
        val clusterCount: Int get() = clusters.size
        /** 噪声数量 */
        val noiseCount: Int get() = noise.size
    }

    /**
     * 单个地理聚类。
     *
     * @param clusterId 聚类 ID（UUID）
     * @param sampleIds 该聚类包含的样本 ID 列表（文件路径）
     * @param centerLatitude 聚类中心纬度（成员坐标的算术平均）
     * @param centerLongitude 聚类中心经度
     */
    data class Cluster(
        val clusterId: String,
        val sampleIds: List<String>,
        val centerLatitude: Double,
        val centerLongitude: Double,
    )

    /**
     * 对地理样本集合执行 DBSCAN 聚类。
     *
     * @param samples 地理样本列表
     * @return 聚类结果
     */
    fun cluster(samples: List<GeoSample>): ClusterResult {
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
        val clustersMap = mutableMapOf<Int, MutableList<GeoSample>>()
        val noise = mutableListOf<String>()
        for (i in 0 until n) {
            val label = clusterLabels[i]
            if (label >= 0) {
                clustersMap.getOrPut(label) { mutableListOf() }.add(samples[i])
            } else if (label == -2) {
                noise.add(samples[i].id)
            }
        }

        val clusters = clustersMap.map { (_, clusterSamples) ->
            val centerLat = clusterSamples.map { it.latitude }.average()
            val centerLon = clusterSamples.map { it.longitude }.average()
            Cluster(
                clusterId = java.util.UUID.randomUUID().toString(),
                sampleIds = clusterSamples.map { it.id },
                centerLatitude = centerLat,
                centerLongitude = centerLon,
            )
        }

        return ClusterResult(clusters, noise)
    }

    /**
     * 计算样本 i 的 eps 邻域（Haversine 距离 ≤ epsKm 的所有样本索引）。
     */
    private fun regionQuery(i: Int, samples: List<GeoSample>): List<Int> {
        val result = mutableListOf<Int>()
        val latI = samples[i].latitude
        val lonI = samples[i].longitude
        for (j in samples.indices) {
            if (i == j) {
                result.add(j)
                continue
            }
            if (haversineKm(latI, lonI, samples[j].latitude, samples[j].longitude) <= epsKm) {
                result.add(j)
            }
        }
        return result
    }

    /**
     * Haversine 公式计算地球表面两点间的大圆距离（公里）。
     *
     * @param lat1 点1 纬度
     * @param lon1 点1 经度
     * @param lat2 点2 纬度
     * @param lon2 点2 经度
     * @return 两点间距离（公里）
     */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }
}
