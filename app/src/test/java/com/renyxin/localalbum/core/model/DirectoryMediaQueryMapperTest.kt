package com.renyxin.localalbum.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryMediaQueryMapperTest {
    @Test
    fun `query identity includes path type and sort`() {
        val base = DirectoryMediaQuery("/camera")
        assertNotEquals(base, base.copy(mediaType = MediaType.IMAGE))
        assertNotEquals(base, base.copy(sort = DirectoryMediaSort.SIZE_DESC))
        assertNotEquals(base, base.copy(directoryPath = "/screenshots"))
    }

    @Test
    fun `paging mapping binds path and optional media type`() {
        val spec = DirectoryMediaQueryMapper.paging(
            DirectoryMediaQuery("/camera", MediaType.VIDEO, DirectoryMediaSort.DATE_ASC)
        )

        assertEquals(listOf("/camera", "VIDEO"), spec.args)
        assertTrue(spec.sql.contains("mediaType = ?"))
        assertTrue(spec.sql.endsWith("ORDER BY capturedAtMs ASC, filePath ASC"))
    }

    @Test
    fun `all supported sorts have deterministic file path tie breaker`() {
        DirectoryMediaSort.entries.forEach { sort ->
            val sql = DirectoryMediaQueryMapper.paging(DirectoryMediaQuery("/camera", sort = sort)).sql
            assertTrue("missing stable tie breaker for $sort", sql.substringAfter("ORDER BY").contains("filePath"))
        }
    }

    @Test
    fun `directory context preserves complete query for viewer`() {
        val query = DirectoryMediaQuery("/camera", MediaType.IMAGE, DirectoryMediaSort.NAME_DESC)
        val context = MediaQueryContext.Directory(query)

        assertEquals(query, context.query)
        assertEquals("/camera", context.path)
    }

    @Test
    fun `offset mapping uses same filter and selected sort column`() {
        val query = DirectoryMediaQuery("/camera", MediaType.IMAGE, DirectoryMediaSort.SIZE_DESC)
        val anchor = DirectoryMediaAnchor("/camera/a.jpg", "a.jpg", 10L, 20L, 30L)
        val spec = DirectoryMediaQueryMapper.offset(query, anchor)

        assertEquals(listOf("/camera", "IMAGE", 30L, 30L, "/camera/a.jpg"), spec.args)
        assertTrue(spec.sql.contains("fileSize > ?"))
    }
}
