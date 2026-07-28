package com.renyxin.localalbum.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GeoClusterer 单元测试（Phase 3.4 地图视图 + 地理聚类）。
 *
 * 验证 DBSCAN 聚类算法在 Haversine 距离下的正确性：
 * - 相近地点的照片应归入同一聚类
 * - 不同地点的照片应归入不同聚类
 * - 孤立照片应标记为噪声
 * - Haversine 距离计算正确
 */
class GeoClustererTest {

    // 北京天安门附近坐标
    private val tiananmen = GeoClusterer.GeoSample("p1", 39.9087, 116.3974)
    // 天安门广场东侧（约 100 米）
    private val tiananmenEast = GeoClusterer.GeoSample("p2", 39.9087, 116.3985)
    // 天安门广场北侧（约 80 米）
    private val tiananmenNorth = GeoClusterer.GeoSample("p3", 39.9095, 116.3974)

    // 上海外滩坐标（距北京约 1200 公里）
    private val shanghaiBund = GeoClusterer.GeoSample("p4", 31.2397, 121.4900)
    private val shanghaiBundNear = GeoClusterer.GeoSample("p5", 31.2398, 121.4901)

    // 默认 eps=0.2km，minPts=2
    private val clusterer = GeoClusterer(epsKm = 0.2, minPts = 2)

    @Test
    fun `空样本集返回空结果`() {
        val result = clusterer.cluster(emptyList())
        assertEquals(0, result.clusterCount)
        assertEquals(0, result.noiseCount)
    }

    @Test
    fun `相近地点的照片归入同一聚类`() {
        // 天安门附近 3 张照片，互相距离 < 200 米
        val samples = listOf(tiananmen, tiananmenEast, tiananmenNorth)

        val result = clusterer.cluster(samples)

        assertEquals(1, result.clusterCount)
        assertEquals(3, result.clusters[0].sampleIds.size)
        assertEquals(0, result.noiseCount)
    }

    @Test
    fun `不同地点的照片归入不同聚类`() {
        // 北京 2 张 + 上海 2 张
        val samples = listOf(
            tiananmen, tiananmenEast,
            shanghaiBund, shanghaiBundNear,
        )

        val result = clusterer.cluster(samples)

        assertEquals(2, result.clusterCount)
        assertEquals(0, result.noiseCount)
        // 每个聚类应包含 2 个样本
        assertTrue(result.clusters.all { it.sampleIds.size == 2 })
    }

    @Test
    fun `孤立照片标记为噪声`() {
        // 天安门附近 2 张 + 一张远处的孤立照片
        val isolated = GeoClusterer.GeoSample("p6", 40.0000, 117.0000) // 距天安门约 80 公里

        val samples = listOf(tiananmen, tiananmenEast, isolated)

        val result = clusterer.cluster(samples)

        // 应有 1 个聚类（2 个相近样本）+ 1 个噪声
        assertEquals(1, result.clusterCount)
        assertEquals(1, result.noiseCount)
        assertEquals("p6", result.noise[0])
    }

    @Test
    fun `Haversine 距离计算正确`() {
        // 北京天安门 → 上海外滩，直线距离约 1067 公里
        val dist = clusterer.haversineKm(39.9087, 116.3974, 31.2397, 121.4900)

        // 允许 5% 误差
        assertTrue("距离应在 1000-1130 公里之间，实际: $dist", dist in 1000.0..1130.0)
    }

    @Test
    fun `相同坐标距离为零`() {
        val dist = clusterer.haversineKm(39.9087, 116.3974, 39.9087, 116.3974)
        assertTrue(kotlin.math.abs(dist) < 0.001)
    }

    @Test
    fun `聚类中心坐标为成员平均值`() {
        val samples = listOf(
            GeoClusterer.GeoSample("a", 40.0, 116.0),
            GeoClusterer.GeoSample("b", 40.0, 116.0),
        )

        val result = clusterer.cluster(samples)

        assertEquals(1, result.clusterCount)
        val cluster = result.clusters[0]
        assertEquals(40.0, cluster.centerLatitude, 0.001)
        assertEquals(116.0, cluster.centerLongitude, 0.001)
    }

    @Test
    fun `minPts 为 2 时单个样本为噪声`() {
        val sample = listOf(
            GeoClusterer.GeoSample("solo", 39.9087, 116.3974),
        )
        val result = clusterer.cluster(sample)
        assertEquals(0, result.clusterCount)
        assertEquals(1, result.noiseCount)
    }

    @Test
    fun `边界点可被并入聚类`() {
        // p1 - p2 距离 < eps，p2 - p3 距离 < eps，但 p1 - p3 距离 > eps
        // p3 是边界点，应被并入 p1-p2 形成的聚类
        val p1 = GeoClusterer.GeoSample("p1", 40.0000, 116.0000)
        val p2 = GeoClusterer.GeoSample("p2", 40.0008, 116.0000) // 约 90 米
        val p3 = GeoClusterer.GeoSample("p3", 40.0016, 116.0000) // 距 p2 约 90 米，距 p1 约 180 米

        val result = clusterer.cluster(listOf(p1, p2, p3))

        // 三个点应归入同一聚类（p3 作为边界点被并入）
        assertEquals(1, result.clusterCount)
        assertEquals(3, result.clusters[0].sampleIds.size)
    }
}
