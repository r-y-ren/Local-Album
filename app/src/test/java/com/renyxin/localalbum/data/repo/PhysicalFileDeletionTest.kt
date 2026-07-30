package com.renyxin.localalbum.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhysicalFileDeletionTest {
    @Test
    fun `missing file is safe to purge`() {
        val missing = File.createTempFile("localalbum-missing", ".jpg")
        assertTrue(missing.delete())

        val result = PhysicalFileDeletion.delete(listOf(missing.absolutePath))

        assertEquals(listOf(missing.absolutePath), result.deletedOrMissing)
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `failed physical deletion is retained`() {
        val existing = File.createTempFile("localalbum-retained", ".jpg").apply { deleteOnExit() }

        val result = PhysicalFileDeletion.delete(listOf(existing.absolutePath)) { false }

        assertTrue(result.deletedOrMissing.isEmpty())
        assertEquals(listOf(existing.absolutePath), result.failed)
        assertTrue(existing.exists())
    }

    @Test
    fun `successful deletion and duplicate input produce one purge path`() {
        val existing = File.createTempFile("localalbum-deleted", ".jpg")

        val result = PhysicalFileDeletion.delete(
            listOf(existing.absolutePath, existing.absolutePath),
        ) { file -> file.delete() }

        assertEquals(listOf(existing.absolutePath), result.deletedOrMissing)
        assertTrue(result.failed.isEmpty())
        assertTrue(!existing.exists())
    }

    @Test
    fun `deletion exception is retained instead of purged`() {
        val existing = File.createTempFile("localalbum-exception", ".jpg").apply { deleteOnExit() }

        val result = PhysicalFileDeletion.delete(listOf(existing.absolutePath)) {
            error("permission denied")
        }

        assertTrue(result.deletedOrMissing.isEmpty())
        assertEquals(listOf(existing.absolutePath), result.failed)
    }
}
