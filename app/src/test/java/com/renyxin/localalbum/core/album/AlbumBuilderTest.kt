package com.renyxin.localalbum.core.album

import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.dao.DirectorySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AlbumBuilderTest {
    @Test
    fun `only leaf directories become albums`() {
        val media = listOf(
            MediaItem(
                id = "1",
                filePath = "/root/a/b/1.jpg",
                fileName = "1.jpg",
                type = MediaType.IMAGE,
                capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
                modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )

        val root = DirectoryNode(
            path = "/root",
            name = "root",
            parentPath = null,
            children = listOf(
                DirectoryNode(
                    path = "/root/a",
                    name = "a",
                    parentPath = "/root",
                    children = listOf(
                        DirectoryNode(
                            path = "/root/a/b",
                            name = "b",
                            parentPath = "/root/a",
                            children = emptyList(),
                            mediaItems = media,
                        ),
                    ),
                    mediaItems = emptyList(),
                ),
            ),
            mediaItems = emptyList(),
        )

        val albums = AlbumBuilder().buildFromDirectoryTree(listOf(root))

        assertEquals(1, albums.size)
        assertEquals("b", albums.first().name)
        assertEquals("/root/a/b", albums.first().directoryPath)
    }
    @Test
    fun `directory summaries build lightweight hierarchy without media entities`() {
        val summaries = listOf(
            DirectorySummary(
                parentPath = "/storage/DCIM/Camera",
                mediaCount = 120_000,
                latestCapturedAtMs = 1_700_000_000_000L,
                coverThumbnailPath = "/cache/camera.webp",
                coverFilePath = "/storage/DCIM/Camera/latest.jpg",
            ),
            DirectorySummary(
                parentPath = "/storage/DCIM/Screenshots",
                mediaCount = 42,
                latestCapturedAtMs = 1_600_000_000_000L,
                coverThumbnailPath = null,
                coverFilePath = "/storage/DCIM/Screenshots/latest.png",
            ),
        )

        val (tree, leaves) = AlbumBuilder().buildFromDirectorySummaries(
            summaries = summaries,
            roots = listOf("/storage/DCIM"),
        )

        assertEquals(1, tree.size)
        assertEquals("/storage/DCIM", tree.single().directoryPath)
        assertEquals(120_042, tree.single().totalMediaCount)
        assertEquals(2, leaves.size)
        assertEquals(120_000, leaves.first { it.name == "Camera" }.mediaCount)
        assertEquals(emptyList<MediaItem>(), leaves.first().mediaItems)
        assertEquals("/cache/camera.webp", leaves.first { it.name == "Camera" }.coverPath)
    }
}
