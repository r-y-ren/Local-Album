package com.renyxin.localalbum.core.model

import com.renyxin.localalbum.data.repo.MediaSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQueryContextTest {
    @Test
    fun stablePaths_removesBlankAndDuplicatesWhileKeepingOrder() {
        val context = MediaQueryContext.StablePaths.of(listOf("/b.jpg", "", "/a.jpg", "/b.jpg"))

        assertEquals(listOf("/b.jpg", "/a.jpg"), context.paths)
    }

    @Test
    fun stablePaths_isBounded() {
        val context = MediaQueryContext.StablePaths.of(
            (0 until MediaQueryContext.StablePaths.MAX_PATHS + 20).map { "/$it.jpg" }
        )

        assertEquals(MediaQueryContext.StablePaths.MAX_PATHS, context.paths.size)
    }

    @Test
    fun searchContext_preservesCompleteDatabaseQueryIdentity() {
        val query = MediaSearchQuery(
            text = "camera",
            mediaType = MediaType.IMAGE,
            startMs = 10L,
            endMs = 20L,
            model = "Pixel",
        )

        val context = MediaQueryContext.Search(query)

        assertEquals(query, context.query)
        assertTrue(context.query.endMs!! >= context.query.startMs!!)
    }
}
