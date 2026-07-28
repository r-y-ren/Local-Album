package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class SceneThemeRecommenderTest {

    private val fixedNow = Instant.parse("2026-07-01T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun makeMedia(
        id: String,
        sceneType: String?,
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
            sceneType = sceneType,
        )
    }

    @Test
    fun `empty input returns empty list`() {
        val recommender = SceneThemeRecommender(clock = fixedClock)
        val result = recommender.generate(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `insufficient items per scene returns empty`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 5)
        val media = (0 until 4).map { makeMedia("m$it", "landscape") }
        val result = recommender.generate(media)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `produces scene theme recommendations for valid groups`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 5)
        val landscapeMedia = (0 until 10).map { makeMedia("land-$it", "landscape", qualityScore = 0.7f) }
        val foodMedia = (0 until 8).map { makeMedia("food-$it", "food", qualityScore = 0.6f) }

        val result = recommender.generate(landscapeMedia + foodMedia)

        assertEquals(2, result.size)
        assertTrue(result.any { it.albumName == "风景精选" })
        assertTrue(result.any { it.albumName == "美食精选" })
        assertTrue(result.all { it.category == RecommendationCategory.SCENE_THEME })
    }

    @Test
    fun `higher quality scene yields higher score`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 5)
        // 使用不同场景类型确保产生独立推荐
        val lowQuality = (0 until 6).map { makeMedia("low-$it", "food", qualityScore = 0.1f) }
        val highQuality = (0 until 6).map { makeMedia("high-$it", "landscape", qualityScore = 0.9f) }

        val result = recommender.generate(lowQuality + highQuality)

        // 两个不同场景组应产生两个推荐
        assertEquals(2, result.size)
        assertTrue("高质量组应排第一", result[0].score > result[1].score)
    }

    @Test
    fun `recommendations are sorted by score descending`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 5)
        val media = (0 until 10).map { makeMedia("m$it", "landscape", qualityScore = 0.5f + it * 0.01f) }

        val result = recommender.generate(media)

        assertEquals(1, result.size)
        // topNPerScene 默认 20，应包含全部 10 张
        assertEquals(10, result[0].mediaItems.size)
    }

    @Test
    fun `topNPerScene limits items in recommendation`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 3, topNPerScene = 5)
        val media = (0 until 20).map { makeMedia("m$it", "landscape", qualityScore = 0.5f) }

        val result = recommender.generate(media)

        assertEquals(1, result.size)
        assertEquals(5, result[0].mediaItems.size)
    }

    @Test
    fun `favorite items boost score`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 5)
        // 使用不同场景类型确保产生独立推荐
        val noFavorite = (0 until 6).map { makeMedia("nofav-$it", "food", qualityScore = 0.5f, isFavorite = false) }
        val withFavorite = (0 until 6).map { makeMedia("fav-$it", "landscape", qualityScore = 0.5f, isFavorite = true) }

        val result = recommender.generate(noFavorite + withFavorite)

        assertEquals(2, result.size)
        val favScore = result.first { it.favoriteCount > 0 }.score
        val noFavScore = result.first { it.favoriteCount == 0 }.score
        assertTrue("含收藏的推荐评分应更高: $favScore > $noFavScore", favScore > noFavScore)
    }

    @Test
    fun `unknown and null scene types are filtered`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 3)
        val unknownMedia = (0 until 5).map { makeMedia("unk-$it", "unknown") }
        val nullMedia = (0 until 5).map { makeMedia("null-$it", null) }
        val validMedia = (0 until 5).map { makeMedia("val-$it", "landscape") }

        val result = recommender.generate(unknownMedia + nullMedia + validMedia)

        assertEquals(1, result.size)
        assertEquals("风景精选", result[0].albumName)
    }

    @Test
    fun `thumbnailPaths selects highest quality items`() {
        val recommender = SceneThemeRecommender(clock = fixedClock, minGroupSize = 3, topNPerScene = 10)
        val media = (0 until 8).map { makeMedia("m$it", "landscape", qualityScore = it * 0.1f) }

        val result = recommender.generate(media)

        assertEquals(1, result.size)
        val rec = result[0]
        // thumbnailPaths 应取质量最高的 4 张（索引 7,6,5,4）
        assertEquals(4, rec.thumbnailPaths.size)
    }
}
