package com.renyxin.localalbum.core.analysis

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 流式读取完整文件的 SHA-256；固定缓冲区保证单文件内存有界。 */
object ExactFileHasher {
    const val BUFFER_SIZE = 64 * 1024

    fun sha256(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** 纯 JVM 可测的批次执行器；任何时刻只保留当前输入批次与当前哈希结果。 */
object BoundedDuplicateBatchProcessor {
    data class Input(val path: String, val fileSize: Long)
    data class Output(val path: String, val fileSize: Long, val exactHash: String)

    fun process(
        input: List<Input>,
        maxBatchSize: Int,
        hash: (String) -> String?,
    ): List<Output> {
        require(maxBatchSize > 0)
        require(input.size <= maxBatchSize) { "输入批次超过上界：${input.size} > $maxBatchSize" }
        return input.mapNotNull { item ->
            hash(item.path)?.let { Output(item.path, item.fileSize, it) }
        }
    }
}
