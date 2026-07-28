package com.renyxin.localalbum.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FaceClusterer 单元测试（Phase 3.3 人脸聚类）。
 *
 * 验证 DBSCAN 聚类算法在余弦距离下的正确性：
 * - 相同人的多张人脸应归入同一聚类
 * - 不同人的人脸应归入不同聚类
 * - 孤立人脸应标记为噪声
 */
class FaceClustererTest {

    private val clusterer = FaceClusterer(eps = 0.4f, minPts = 2)

    @Test
    fun `空样本集返回空结果`() {
        val result = clusterer.cluster(emptyList())
        assertEquals(0, result.clusterCount)
        assertEquals(0, result.noiseCount)
    }

    @Test
    fun `相同人的多张人脸归入同一聚类`() {
        // 模拟同一人的 3 张人脸特征向量（高度相似）
        val base = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f,
            0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 0.5f)
        val samples = listOf(
            FaceClusterer.FaceSample(id = 1, embedding = base.copyOf()),
            FaceClusterer.FaceSample(id = 2, embedding = base.copyOf().also { it[0] += 0.01f }),
            FaceClusterer.FaceSample(id = 3, embedding = base.copyOf().also { it[1] -= 0.01f }),
        )

        val result = clusterer.cluster(samples)

        assertEquals(1, result.clusterCount)
        assertEquals(3, result.clusters[0].sampleIds.size)
        assertEquals(0, result.noiseCount)
    }

    @Test
    fun `不同人的人脸归入不同聚类`() {
        // 两个差异很大的特征向量组
        val personA = floatArrayOf(0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f,
            0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f)
        val personB = floatArrayOf(0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f,
            0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.1f)

        val samples = listOf(
            FaceClusterer.FaceSample(id = 1, embedding = personA.copyOf()),
            FaceClusterer.FaceSample(id = 2, embedding = personA.copyOf().also { it[0] += 0.01f }),
            FaceClusterer.FaceSample(id = 3, embedding = personB.copyOf()),
            FaceClusterer.FaceSample(id = 4, embedding = personB.copyOf().also { it[1] += 0.01f }),
        )

        val result = clusterer.cluster(samples)

        assertEquals(2, result.clusterCount)
        assertEquals(0, result.noiseCount)
        // 每个聚类应包含 2 个样本
        assertTrue(result.clusters.all { it.sampleIds.size == 2 })
    }

    @Test
    fun `孤立人脸标记为噪声`() {
        // 使用正交方向的特征向量确保余弦距离足够大
        val base = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val similar = floatArrayOf(1f, 0.01f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        // outlier 与 base 正交，余弦距离 = 1.0（远大于 eps=0.4）
        val outlier = floatArrayOf(0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f,
            0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f)

        val samples = listOf(
            FaceClusterer.FaceSample(id = 1, embedding = base),
            FaceClusterer.FaceSample(id = 2, embedding = similar),
            FaceClusterer.FaceSample(id = 3, embedding = outlier),
        )

        val result = clusterer.cluster(samples)

        // 应有 1 个聚类（2 个相似样本）+ 1 个噪声
        assertEquals(1, result.clusterCount)
        assertEquals(1, result.noiseCount)
        assertEquals(3L, result.noise[0])
    }

    @Test
    fun `余弦距离计算正确`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val c = floatArrayOf(1f, 0f, 0f)

        // 正交向量余弦距离 = 1
        val distAB = clusterer.cosineDistance(a, b)
        assertTrue(kotlin.math.abs(distAB - 1f) < 0.01f)

        // 相同向量余弦距离 = 0
        val distAC = clusterer.cosineDistance(a, c)
        assertTrue(kotlin.math.abs(distAC - 0f) < 0.01f)
    }

    @Test
    fun `序列化与反序列化保持一致`() {
        val original = floatArrayOf(0.123456f, 0.789012f, 0.345678f, -0.111111f)
        val serialized = clusterer.serializeEmbedding(original)
        val parsed = clusterer.parseEmbedding(serialized)

        assertEquals(original.size, parsed.size)
        for (i in original.indices) {
            assertTrue(kotlin.math.abs(original[i] - parsed[i]) < 0.001f)
        }
    }

    @Test
    fun `minPts 为 2 时单个样本为噪声`() {
        val sample = listOf(
            FaceClusterer.FaceSample(id = 1, embedding = FloatArray(21) { 0.5f }),
        )
        val result = clusterer.cluster(sample)
        assertEquals(0, result.clusterCount)
        assertEquals(1, result.noiseCount)
    }
}
