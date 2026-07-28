package com.renyxin.localalbum.core.album

import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
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
}
