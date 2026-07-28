package com.renyxin.localalbum.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MediaItemTest {

    @Test
    fun `MediaItem with default values has zero fileSize and not favorite`() {
        val item = MediaItem(
            id = "test-id",
            filePath = "/test/path/img.jpg",
            fileName = "img.jpg",
            type = MediaType.IMAGE,
            capturedAt = Instant.ofEpochMilli(1000),
            modifiedAt = Instant.ofEpochMilli(2000),
        )
        assertEquals(0L, item.fileSize)
        assertFalse(item.isFavorite)
    }

    @Test
    fun `MediaItem copy with favorite preserves other fields`() {
        val item = MediaItem(
            id = "test-id",
            filePath = "/test/path/img.jpg",
            fileName = "img.jpg",
            type = MediaType.IMAGE,
            capturedAt = Instant.ofEpochMilli(1000),
            modifiedAt = Instant.ofEpochMilli(2000),
            fileSize = 1024L,
            isFavorite = false,
        )
        val favorited = item.copy(isFavorite = true)
        assertEquals("test-id", favorited.id)
        assertEquals("/test/path/img.jpg", favorited.filePath)
        assertEquals("img.jpg", favorited.fileName)
        assertEquals(MediaType.IMAGE, favorited.type)
        assertEquals(1024L, favorited.fileSize)
        assertTrue(favorited.isFavorite)
    }

    @Test
    fun `MediaItem with video type`() {
        val item = MediaItem(
            id = "vid-1",
            filePath = "/test/vid.mp4",
            fileName = "vid.mp4",
            type = MediaType.VIDEO,
            capturedAt = Instant.ofEpochMilli(3000),
            modifiedAt = Instant.ofEpochMilli(4000),
            fileSize = 50000L,
        )
        assertEquals(MediaType.VIDEO, item.type)
        assertEquals(50000L, item.fileSize)
    }
}
