package com.renyxin.localalbum.core.thumbnail

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadFingerprintTest {
    @Test
    fun `persisted head window remains 4096 bytes`() {
        assertEquals(4096, HeadFingerprint.WINDOW_BYTES)
    }

    @Test
    fun `short reads continue until the complete head window`() {
        val bytes = ByteArray(HeadFingerprint.WINDOW_BYTES + 257) { (it % 251).toByte() }
        val shortReadingInput = object : InputStream() {
            private val delegate = ByteArrayInputStream(bytes)

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, minOf(length, 7))
        }

        assertEquals(expectedFingerprint(bytes), HeadFingerprint.compute(shortReadingInput))
    }

    private fun expectedFingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes.copyOfRange(0, HeadFingerprint.WINDOW_BYTES))
        .joinToString("") { "%02x".format(it) }
}
