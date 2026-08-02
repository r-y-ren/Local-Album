package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecommendationFileRotatorTest {
    private val baseTime: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `each file appears once and uncovered files are included`() {
        val media = (1..5).map(::media)
        val candidates = listOf(
            recommendation("scene", listOf(media[0], media[1], media[2])),
            recommendation("event", listOf(media[1], media[2], media[3])),
        )

        val pool = RecommendationFileRotator(fallbackGroupSize = 2).build(candidates, media)
        val paths = pool.flatMap { it.mediaItems }.map { it.filePath }

        assertEquals(media.map { it.filePath }.toSet(), paths.toSet())
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(pool.any { it.albumName == "图库轮换" && it.mediaItems == listOf(media[4]) })
    }

    @Test
    fun `duplicate database rows by path are represented once`() {
        val first = media(1)
        val duplicate = first.copy(qualityScore = 1f)

        val pool = RecommendationFileRotator().build(emptyList(), listOf(first, duplicate, media(2)))
        val paths = pool.flatMap { it.mediaItems }.map { it.filePath }

        assertEquals(listOf(first.filePath, media(2).filePath), paths)
    }

    private fun media(index: Int): MediaItem = MediaItem(
        id = index.toString(),
        filePath = "/library/$index.jpg",
        fileName = "$index.jpg",
        type = MediaType.IMAGE,
        capturedAt = baseTime.plusSeconds(index.toLong()),
        modifiedAt = baseTime.plusSeconds(index.toLong()),
        qualityScore = index / 10f,
    )

    private fun recommendation(id: String, items: List<MediaItem>): Recommendation = Recommendation(
        albumId = id,
        albumName = id,
        directoryPath = "/library",
        windowStart = items.minOf { it.capturedAt },
        windowEnd = items.maxOf { it.capturedAt },
        mediaItems = items,
        reason = "test",
        score = 1.0,
    )
}
