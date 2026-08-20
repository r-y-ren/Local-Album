package com.renyxin.localalbum.core.thumbnail

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Persisted `fingerprintHead` protocol shared by indexing and thumbnail validation.
 *
 * Compatibility contract: SHA-256 over at most the first 4096 bytes of the source. The window must
 * not change without an explicit persisted-protocol migration.
 */
internal object HeadFingerprint {
    const val WINDOW_BYTES = 4096

    fun compute(file: File): String = FileInputStream(file).use(::compute)

    fun compute(source: RandomAccessFile): String {
        val position = source.filePointer
        return try {
            source.seek(0L)
            compute(object : InputStream() {
                override fun read(): Int = source.read()

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    source.read(buffer, offset, length)
            })
        } finally {
            source.seek(position)
        }
    }

    fun compute(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(WINDOW_BYTES)
        var remaining = WINDOW_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            when {
                read < 0 -> break
                read == 0 -> {
                    val next = input.read()
                    if (next < 0) break
                    digest.update(next.toByte())
                    remaining--
                }
                else -> {
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
