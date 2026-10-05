package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class RecommendationSnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): RecommendationSnapshotStore =
        RecommendationSnapshotStore(File(tmp.root, "recommendations_snapshot.json"))

    private fun item(path: String, favorite: Boolean = false) = MediaItem(
        id = path,
        filePath = path,
        fileName = path.substringAfterLast('/'),
        type = MediaType.IMAGE,
        capturedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        modifiedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        isFavorite = favorite,
        qualityScore = 0.7f,
    )

    private fun recommendation(paths: List<String>) = Recommendation(
        albumId = "album-1",
        albumName = "测试相册",
        directoryPath = "/storage/emulated/0/DCIM",
        windowStart = Instant.ofEpochMilli(1_700_000_000_000L),
        windowEnd = Instant.ofEpochMilli(1_700_000_100_000L),
        mediaItems = paths.map { item(it) },
        reason = "整册推荐·高质量照片",
        score = 0.83,
        category = RecommendationCategory.TIME_WINDOW,
    )

    @Test
    fun `save then load round-trips cursor and entry metadata`() {
        val store = store()
        store.save(cursor = 20, batch = listOf(recommendation(listOf("/a/1.jpg", "/a/2.jpg"))))

        val snapshot = store.load()!!
        assertEquals(20, snapshot.cursor)
        assertEquals(1, snapshot.entries.size)
        val entry = snapshot.entries.single()
        assertEquals("album-1", entry.albumId)
        assertEquals("测试相册", entry.albumName)
        assertEquals(RecommendationCategory.TIME_WINDOW, entry.category)
        assertEquals(0.83, entry.score, 1e-9)
        assertEquals(listOf("/a/1.jpg", "/a/2.jpg"), entry.paths)
        assertEquals(1_700_000_000_000L, entry.windowStartMs)
        assertTrue(snapshot.savedAtMs > 0)
    }

    @Test
    fun `missing file loads as null`() {
        assertNull(store().load())
    }

    @Test
    fun `corrupt file is treated as missing and cleaned up`() {
        val file = File(tmp.root, "recommendations_snapshot.json")
        file.writeText("{ not valid json", Charsets.UTF_8)
        assertNull(store().load())
        assertTrue("corrupt snapshot must be deleted", !file.exists())
    }

    @Test
    fun `unknown schema version is rejected`() {
        val file = File(tmp.root, "recommendations_snapshot.json")
        file.writeText("""{"version": 999, "cursor": 3, "batch": []}""", Charsets.UTF_8)
        assertNull(store().load())
    }

    @Test
    fun `unknown category name falls back to whole album`() {
        val file = File(tmp.root, "recommendations_snapshot.json")
        file.writeText(
            """{"version": 1, "savedAtMs": 1, "cursor": 5, "batch": [
                {"albumId":"a","albumName":"n","directoryPath":"d","windowStartMs":1,"windowEndMs":2,
                 "reason":"r","score":0.5,"category":"NO_SUCH_CATEGORY","paths":["/p/1.jpg"]}
            ]}""",
            Charsets.UTF_8,
        )
        val snapshot = store().load()!!
        assertEquals(RecommendationCategory.WHOLE_ALBUM, snapshot.entries.single().category)
    }

    @Test
    fun `save overwrites previous snapshot`() {
        val store = store()
        store.save(cursor = 10, batch = listOf(recommendation(listOf("/a/1.jpg"))))
        store.save(cursor = 20, batch = emptyList())
        val snapshot = store.load()!!
        assertEquals(20, snapshot.cursor)
        assertTrue(snapshot.entries.isEmpty())
    }
}
