package com.renyxin.localalbum.core.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanRunCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `fingerprint head is computed once and cached within the run`() {
        val file = tempFolder.newFile("photo.jpg").apply { writeText("first-content") }
        val cache = ScanRunCache()

        val first = cache.fingerprintHead(file.absolutePath)
        assertNotNull(first)

        // 同一扫描运行内文件内容变化：缓存必须返回首次值，而不是重读磁盘
        file.writeText("second-content")
        assertEquals(first, cache.fingerprintHead(file.absolutePath))
    }

    @Test
    fun `failed fingerprint reads are not cached`() {
        val missing = File(tempFolder.root, "missing.jpg")
        val cache = ScanRunCache()

        assertNull(cache.fingerprintHead(missing.absolutePath))

        // 文件出现后必须能算出真实值，证明 null 没有被缓存
        missing.writeText("now-present")
        assertNotNull(cache.fingerprintHead(missing.absolutePath))
    }

    @Test
    fun `fingerprint differs across files`() {
        val a = tempFolder.newFile("a.jpg").apply { writeText("aaa") }
        val b = tempFolder.newFile("b.jpg").apply { writeText("bbb") }
        val cache = ScanRunCache()

        assertNotEquals(cache.fingerprintHead(a.absolutePath), cache.fingerprintHead(b.absolutePath))
    }

    @Test
    fun `timed out video blacklist marks and queries paths`() {
        val cache = ScanRunCache()
        val path = "/storage/emulated/0/DCIM/broken.mp4"

        assertFalse(cache.isTimedOutVideo(path))
        cache.markTimedOutVideo(path)
        assertTrue(cache.isTimedOutVideo(path))
        assertFalse(cache.isTimedOutVideo("/storage/emulated/0/DCIM/normal.mp4"))
    }
}
