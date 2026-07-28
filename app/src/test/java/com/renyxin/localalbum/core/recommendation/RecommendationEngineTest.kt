package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class RecommendationEngineTest {

    private val fixedNow = Instant.parse("2026-07-01T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Test
    fun `large album returns at least one recommendation`() {
        val media = (0 until 700).map { index ->
            MediaItem(
                id = "$index",
                filePath = "/dcim/camera/$index.jpg",
                fileName = "$index.jpg",
                type = if (index % 20 == 0) MediaType.VIDEO else MediaType.IMAGE,
                capturedAt = fixedNow.minus(index.toLong(), ChronoUnit.HOURS),
                modifiedAt = fixedNow.minus(index.toLong(), ChronoUnit.HOURS),
            )
        }

        val album = Album(
            id = "a1",
            name = "Camera",
            directoryPath = "/dcim/camera",
            mediaItems = media,
        )

        val output = RecommendationEngine(clock = fixedClock).generate(listOf(album), maxResults = 5)

        assertTrue(output.isNotEmpty())
        assertTrue(output.first().mediaItems.isNotEmpty())
    }

    @Test
    fun `recommendations are deterministic - no random factor`() {
        val media = (0 until 50).map { index ->
            MediaItem(
                id = "$index",
                filePath = "/dcim/camera/$index.jpg",
                fileName = "$index.jpg",
                type = MediaType.IMAGE,
                capturedAt = fixedNow.minus(index.toLong(), ChronoUnit.DAYS),
                modifiedAt = fixedNow.minus(index.toLong(), ChronoUnit.DAYS),
                qualityScore = 0.5f,
            )
        }
        val album = Album(
            id = "a1",
            name = "Camera",
            directoryPath = "/dcim/camera",
            mediaItems = media,
        )
        val engine = RecommendationEngine(clock = fixedClock)

        val firstRun = engine.generate(listOf(album), maxResults = 5)
        val secondRun = engine.generate(listOf(album), maxResults = 5)

        // 两次运行结果完全一致（确定性）
        assertEquals(firstRun.map { it.score }, secondRun.map { it.score })
        assertEquals(firstRun.map { it.albumId }, secondRun.map { it.albumId })
    }

    @Test
    fun `higher quality score yields higher recommendation score`() {
        val baseTime = fixedNow.minus(10, ChronoUnit.DAYS)
        val lowQualityMedia = (0 until 10).map { index ->
            MediaItem(
                id = "low-$index",
                filePath = "/dcim/low/$index.jpg",
                fileName = "$index.jpg",
                type = MediaType.IMAGE,
                capturedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                modifiedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                qualityScore = 0.1f,
            )
        }
        val highQualityMedia = (0 until 10).map { index ->
            MediaItem(
                id = "high-$index",
                filePath = "/dcim/high/$index.jpg",
                fileName = "$index.jpg",
                type = MediaType.IMAGE,
                capturedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                modifiedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                qualityScore = 0.9f,
            )
        }
        val lowAlbum = Album(
            id = "low",
            name = "LowQuality",
            directoryPath = "/dcim/low",
            mediaItems = lowQualityMedia,
        )
        val highAlbum = Album(
            id = "high",
            name = "HighQuality",
            directoryPath = "/dcim/high",
            mediaItems = highQualityMedia,
        )
        val engine = RecommendationEngine(clock = fixedClock)

        val output = engine.generate(listOf(lowAlbum, highAlbum), maxResults = 10)

        // 高质量相册的推荐评分应高于低质量相册
        val highScore = output.filter { it.albumId == "high" }.maxOfOrNull { it.score } ?: 0.0
        val lowScore = output.filter { it.albumId == "low" }.maxOfOrNull { it.score } ?: 0.0
        assertTrue("高质量相册评分 ($highScore) 应高于低质量相册 ($lowScore)", highScore > lowScore)
    }

    @Test
    fun `cross-directory event aggregation produces event recommendations`() {
        // 两个不同目录，但时间和地点相近的照片 → 应聚合为事件
        val baseTime = fixedNow.minus(5, ChronoUnit.DAYS)
        val album1Media = (0 until 5).map { index ->
            MediaItem(
                id = "a1-$index",
                filePath = "/dcim/camera1/$index.jpg",
                fileName = "$index.jpg",
                type = MediaType.IMAGE,
                capturedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                modifiedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                latitude = 39.9,
                longitude = 116.4,
            )
        }
        val album2Media = (0 until 5).map { index ->
            MediaItem(
                id = "a2-$index",
                filePath = "/dcim/camera2/$index.jpg",
                fileName = "$index.jpg",
                type = MediaType.IMAGE,
                capturedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                modifiedAt = baseTime.plus(index.toLong(), ChronoUnit.HOURS),
                latitude = 39.91,
                longitude = 116.41,
            )
        }
        val album1 = Album(id = "a1", name = "Camera1", directoryPath = "/dcim/camera1", mediaItems = album1Media)
        val album2 = Album(id = "a2", name = "Camera2", directoryPath = "/dcim/camera2", mediaItems = album2Media)

        val engine = RecommendationEngine(clock = fixedClock)
        val output = engine.generate(listOf(album1, album2), maxResults = 20)

        // 应存在跨目录事件推荐
        val eventRecs = output.filter { it.albumId.startsWith("event-") }
        assertTrue("应产生跨目录事件推荐", eventRecs.isNotEmpty())
        // 事件推荐应包含来自两个目录的照片
        val eventPaths = eventRecs.flatMap { it.mediaItems.map { item -> item.filePath } }
        assertTrue("事件应包含 camera1 的照片", eventPaths.any { it.contains("camera1") })
        assertTrue("事件应包含 camera2 的照片", eventPaths.any { it.contains("camera2") })
    }
}
