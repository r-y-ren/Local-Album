package com.renyxin.localalbum.core.search

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语义向量的紧凑二进制编解码。
 *
 * 数据库旧版本以 CSV 文本保存向量；该格式会在搜索时产生大量字符串拆分和临时对象。
 * 新写入同时保存小端 Float32 BLOB，读取端优先使用 BLOB，旧数据仍可回退到 CSV，
 * 因而迁移期间不影响已有语义索引的可用性。
 */
object EmbeddingCodec {
    const val CODEC_ID = "float32-le"
    const val FORMAT_VERSION = 1

    fun encode(vector: FloatArray): ByteArray {
        require(vector.all { it.isFinite() }) { "语义向量包含非有限数值" }
        return ByteBuffer
            .allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach(::putFloat) }
            .array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.isNotEmpty() && bytes.size % Float.SIZE_BYTES == 0) {
            "语义向量二进制长度无效: ${bytes.size}"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
            .also { vector -> require(vector.all { it.isFinite() }) { "语义向量包含非有限数值" } }
    }
}
