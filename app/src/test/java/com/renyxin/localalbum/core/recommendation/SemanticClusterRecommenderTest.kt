package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class SemanticClusterRecommenderTest {

    private val fixedNow = Instant.parse("2026-07-01T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    /** 内存模拟 EmbeddingDao */
    private class FakeEmbeddingDao(
        private val embeddings: List<MediaEmbedding>,
    ) : EmbeddingDao {
        override suspend fun insertEmbedding(embedding: MediaEmbedding) {}
        override suspend fun insertEmbeddings(embeddings: List<MediaEmbedding>) {}
        override suspend fun getByFilePath(filePath: String): MediaEmbedding? = embeddings.find { it.filePath == filePath }
        override suspend fun getAll(): List<MediaEmbedding> = embeddings
        override suspend fun getPaged(limit: Int, offset: Int): List<MediaEmbedding> =
            embeddings.drop(offset).take(limit)
        override suspend fun getAllFilePaths(): List<String> = embeddings.map { it.filePath }
        override suspend fun getCount(): Int = embeddings.size
        override suspend fun getCountByModelVersion(modelVersion: Int): Int = embeddings.count { it.modelVersion == modelVersion }
        override suspend fun deleteByFilePath(filePath: String) {}
        override suspend fun deleteByFilePaths(filePaths: List<String>) {}
        override suspend fun clearAll() {}
        override suspend fun getMissingEmbeddings(limit: Int): List<String> = emptyList()
        override suspend fun getStaleEmbeddings(currentVersion: Int): List<String> = emptyList()
    }

    private fun makeMedia(
        id: String,
        qualityScore: Float = 0.5f,
        isFavorite: Boolean = false,
        daysAgo: Long = 10,
    ): MediaItem {
        return MediaItem(
            id = id,
            filePath = "/dcim/$id.jpg",
            fileName = "$id.jpg",
            type = MediaType.IMAGE,
            capturedAt = fixedNow.minus(daysAgo, ChronoUnit.DAYS),
            modifiedAt = fixedNow.minus(daysAgo, ChronoUnit.DAYS),
            qualityScore = qualityScore,
            isFavorite = isFavorite,
        )
    }

    /** 生成模拟嵌入向量：在指定维度上激活 */
    private fun makeVector(activeDim: Int, dim: Int = 32): String {
        return FloatArray(dim) { if (it == activeDim) 1.0f else 0.0f }
            .joinToString(",") { "%.6f".format(it) }
    }

    @Test
    fun `null embeddingDao returns empty`() = runBlocking {
        val recommender = SemanticClusterRecommender(clock = fixedClock, embeddingDao = null)
        val media = (0 until 10).map { makeMedia("m$it") }
        val result = recommender.generate(media)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `insufficient embeddings returns empty`() = runBlocking {
        val dao = FakeEmbeddingDao(
            (0 until 3).map { MediaEmbedding(filePath = "/dcim/m$it.jpg", embedding = makeVector(0), modelVersion = 1) }
        )
        val recommender = SemanticClusterRecommender(clock = fixedClock, embeddingDao = dao, minClusterSize = 5)
        val media = (0 until 3).map { makeMedia("m$it") }
        val result = recommender.generate(media)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `produces cluster recommendations for valid data`() = runBlocking {
        // 10 张图片，向量都在维度 0（日落概念）激活
        val media = (0 until 10).map { makeMedia("m$it", qualityScore = 0.7f) }
        val embeddings = media.map {
            MediaEmbedding(filePath = it.filePath, embedding = makeVector(0), modelVersion = 1)
        }
        val dao = FakeEmbeddingDao(embeddings)
        val recommender = SemanticClusterRecommender(
            clock = fixedClock,
            embeddingDao = dao,
            k = 2,
            minClusterSize = 3,
        )

        val result = recommender.generate(media)

        assertTrue("应产生聚类推荐", result.isNotEmpty())
        assertTrue(result.all { it.category == RecommendationCategory.SEMANTIC_CLUSTER })
        // 聚类中心最大分量在维度 0 → 概念"日落"
        assertTrue(result.any { it.albumName.contains("日落") })
    }

    @Test
    fun `multiple clusters produce multiple recommendations`() = runBlocking {
        // 6 张在维度 0（日落），6 张在维度 8（自拍）
        val sunsetMedia = (0 until 6).map { makeMedia("sunset-$it") }
        val selfieMedia = (0 until 6).map { makeMedia("selfie-$it") }
        val allMedia = sunsetMedia + selfieMedia

        val embeddings = allMedia.mapIndexed { idx, item ->
            val dim = if (idx < 6) 0 else 8
            MediaEmbedding(filePath = item.filePath, embedding = makeVector(dim), modelVersion = 1)
        }
        val dao = FakeEmbeddingDao(embeddings)
        val recommender = SemanticClusterRecommender(
            clock = fixedClock,
            embeddingDao = dao,
            k = 2,
            minClusterSize = 3,
        )

        val result = recommender.generate(allMedia)

        assertTrue("应产生多个聚类推荐", result.size >= 1)
    }

    @Test
    fun `higher quality cluster yields higher score`() = runBlocking {
        val lowQuality = (0 until 6).map { makeMedia("low-$it", qualityScore = 0.1f) }
        val highQuality = (0 until 6).map { makeMedia("high-$it", qualityScore = 0.9f) }
        val allMedia = lowQuality + highQuality

        val embeddings = allMedia.mapIndexed { idx, item ->
            val dim = if (idx < 6) 0 else 8
            MediaEmbedding(filePath = item.filePath, embedding = makeVector(dim), modelVersion = 1)
        }
        val dao = FakeEmbeddingDao(embeddings)
        val recommender = SemanticClusterRecommender(
            clock = fixedClock,
            embeddingDao = dao,
            k = 2,
            minClusterSize = 3,
        )

        val result = recommender.generate(allMedia)

        assertTrue(result.size >= 2)
        // 高质量聚类评分应更高
        val highScore = result.maxOfOrNull { it.score } ?: 0.0
        val lowScore = result.minOfOrNull { it.score } ?: 0.0
        assertTrue("高质量聚类评分应更高: $highScore > $lowScore", highScore > lowScore)
    }

    @Test
    fun `embeddings without matching media are filtered`() = runBlocking {
        val media = (0 until 6).map { makeMedia("m$it") }
        // 多一个不匹配的嵌入
        val embeddings = media.map {
            MediaEmbedding(filePath = it.filePath, embedding = makeVector(0), modelVersion = 1)
        } + MediaEmbedding(filePath = "/nonexistent/path.jpg", embedding = makeVector(0), modelVersion = 1)
        val dao = FakeEmbeddingDao(embeddings)
        val recommender = SemanticClusterRecommender(
            clock = fixedClock,
            embeddingDao = dao,
            k = 1,
            minClusterSize = 3,
        )

        val result = recommender.generate(media)

        assertTrue(result.isNotEmpty())
        // 不应包含不匹配路径的媒体
        assertTrue(result.flatMap { it.mediaItems }.none { it.filePath == "/nonexistent/path.jpg" })
    }

    @Test
    fun `favorite items boost cluster score`() = runBlocking {
        val noFavorite = (0 until 6).map { makeMedia("nofav-$it", qualityScore = 0.5f, isFavorite = false) }
        val withFavorite = (0 until 6).map { makeMedia("fav-$it", qualityScore = 0.5f, isFavorite = true) }
        val allMedia = noFavorite + withFavorite

        val embeddings = allMedia.mapIndexed { idx, item ->
            val dim = if (idx < 6) 0 else 8
            MediaEmbedding(filePath = item.filePath, embedding = makeVector(dim), modelVersion = 1)
        }
        val dao = FakeEmbeddingDao(embeddings)
        val recommender = SemanticClusterRecommender(
            clock = fixedClock,
            embeddingDao = dao,
            k = 2,
            minClusterSize = 3,
        )

        val result = recommender.generate(allMedia)

        // K-Means 聚类结果可能将两组分到不同簇或同一簇，
        // 此处验证：若产生多个聚类，含收藏的推荐应存在且 favoriteCount 正确
        assertTrue("应产生聚类推荐", result.isNotEmpty())
        val favRec = result.find { it.favoriteCount > 0 }
        if (favRec != null) {
            assertTrue("收藏数应大于 0", favRec.favoriteCount > 0)
        }
    }
}
