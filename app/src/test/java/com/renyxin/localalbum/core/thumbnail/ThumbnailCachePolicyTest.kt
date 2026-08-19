package com.renyxin.localalbum.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCachePolicyTest {
    private fun record(
        path: String,
        bytes: Long,
        access: Long,
        version: String = "1:10",
        sizeClass: String = ThumbnailSpec.SIZE_GRID,
        state: String = "READY",
        leaseUntil: Long = 0,
    ) = ThumbnailCacheRecord(path, sizeClass, version, bytes, access, state, leaseUntil)

    @Test
    fun `budget uses strict LRU until within limit`() {
        val records = listOf(record("a", 40, 1), record("b", 40, 2), record("c", 40, 3))
        assertEquals(listOf("a", "b"), ThumbnailCachePolicy.selectEvictions(records, 50, 10_000, 0).map { it.filePath })
    }

    @Test
    fun `recent touch running lease and generating entries are protected`() {
        val now = 10_000L
        val records = listOf(
            record("old", 40, 1),
            record("recent", 40, 9_500),
            record("leased", 40, 2, leaseUntil = 20_000),
            record("running", 40, 3, state = "GENERATING"),
        )
        assertEquals(listOf("old"), ThumbnailCachePolicy.selectEvictions(records, 0, now, 1_000).map { it.filePath })
    }

    @Test
    fun `source version and size class are part of hit identity`() {
        val gridV1 = record("a", 10, 1)
        assertTrue(ThumbnailCachePolicy.isHit(gridV1, "a", ThumbnailSpec.SIZE_GRID, "1:10"))
        assertFalse(ThumbnailCachePolicy.isHit(gridV1, "a", ThumbnailSpec.SIZE_PREVIEW, "1:10"))
        assertFalse(ThumbnailCachePolicy.isHit(gridV1, "a", ThumbnailSpec.SIZE_GRID, "2:10"))
    }

    @Test
    fun `size specs and priorities are ordered`() {
        assertEquals(256, ThumbnailSpec.targetPx(ThumbnailSpec.SIZE_GRID))
        assertEquals(1280, ThumbnailSpec.targetPx(ThumbnailSpec.SIZE_PREVIEW))
        assertTrue(ThumbnailSpec.PRIORITY_VISIBLE > ThumbnailSpec.PRIORITY_PREFETCH)
        assertTrue(ThumbnailSpec.PRIORITY_PREFETCH > ThumbnailSpec.PRIORITY_BACKGROUND)
    }

    @Test
    fun `grid encoding remains square cropped`() {
        assertEquals(256 to 256, ThumbnailSpec.encodedSize(4000, 3000, ThumbnailSpec.SIZE_GRID))
    }

    @Test
    fun `preview encoding preserves landscape and portrait aspect ratios`() {
        assertEquals(1280 to 960, ThumbnailSpec.encodedSize(4000, 3000, ThumbnailSpec.SIZE_PREVIEW))
        assertEquals(960 to 1280, ThumbnailSpec.encodedSize(3000, 4000, ThumbnailSpec.SIZE_PREVIEW))
    }

    @Test
    fun `preview encoding does not upscale small images`() {
        assertEquals(640 to 480, ThumbnailSpec.encodedSize(640, 480, ThumbnailSpec.SIZE_PREVIEW))
    }

    @Test
    fun `legacy format is rejected for both grid and preview`() {
        assertFalse(ThumbnailSpec.isCurrentCachePath(ThumbnailSpec.SIZE_PREVIEW, "/cache/photo_preview_v4.webp"))
        assertTrue(ThumbnailSpec.isCurrentCachePath(ThumbnailSpec.SIZE_PREVIEW, "/cache/photo_preview_v5.webp"))
        assertFalse(ThumbnailSpec.isCurrentCachePath(ThumbnailSpec.SIZE_GRID, "/cache/legacy-grid.webp"))
        assertTrue(ThumbnailSpec.isCurrentCachePath(ThumbnailSpec.SIZE_GRID, "/cache/photo_grid_v5.webp"))
    }
}
