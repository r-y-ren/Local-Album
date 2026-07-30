package com.renyxin.localalbum.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionFailurePolicyTest {
    @Test fun `backoff grows and is capped`() {
        val now = 1_000L
        assertEquals(61_000L, DeletionFailurePolicy.nextRetryAt(now, 1))
        assertEquals(121_000L, DeletionFailurePolicy.nextRetryAt(now, 2))
        val capped = DeletionFailurePolicy.nextRetryAt(now, 100) - now
        assertEquals(7L * 24 * 60 * 60 * 1_000, capped)
    }

    @Test fun `path key is stable hash without source path`() {
        val path = "/storage/emulated/0/private/family.jpg"
        val first = DeletionFailurePolicy.pathKey(path)
        assertEquals(first, DeletionFailurePolicy.pathKey(path))
        assertEquals(64, first.length)
        assertFalse(first.contains("family"))
    }

    @Test fun `message removes absolute path and uri`() {
        val path = "/storage/emulated/0/private/family.jpg"
        val safe = DeletionFailurePolicy.sanitizeMessage("denied $path content://media/42", path)
        assertFalse(safe.contains(path))
        assertFalse(safe.contains("content://"))
        assertTrue(safe.length <= 160)
    }
}
