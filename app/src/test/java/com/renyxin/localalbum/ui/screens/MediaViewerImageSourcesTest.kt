package com.renyxin.localalbum.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaViewerImageSourcesTest {
    @Test
    fun `original remains final source when preview is available`() {
        val sources = viewerImageSources(
            originalPath = "/photos/original.jpg",
            previewPath = "/cache/preview.webp",
        )

        assertEquals("/cache/preview.webp", sources.previewPlaceholderPath)
        assertEquals("/photos/original.jpg", sources.originalPath)
    }

    @Test
    fun `same path is not rendered twice as placeholder and original`() {
        val sources = viewerImageSources(
            originalPath = "/photos/original.jpg",
            previewPath = "/photos/original.jpg",
        )

        assertNull(sources.previewPlaceholderPath)
        assertEquals("/photos/original.jpg", sources.originalPath)
    }

    @Test
    fun `missing preview still loads original`() {
        val sources = viewerImageSources(
            originalPath = "/photos/original.jpg",
            previewPath = null,
        )

        assertNull(sources.previewPlaceholderPath)
        assertEquals("/photos/original.jpg", sources.originalPath)
    }
}
