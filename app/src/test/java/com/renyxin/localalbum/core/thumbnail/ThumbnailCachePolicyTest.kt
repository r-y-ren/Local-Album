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
}
