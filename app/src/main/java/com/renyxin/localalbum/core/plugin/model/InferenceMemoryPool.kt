package com.renyxin.localalbum.core.plugin.model

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * 张量推理内存池（Phase 5c — 性能优化）。
 *
 * 预分配并复用推理过程中频繁创建的 FloatArray / ByteBuffer，
 * 避免频繁 GC 导致的推理延迟抖动。
 *
 * ## 使用方式
 *
 * ```kotlin
 * val pool = InferenceMemoryPool()
 * val buffer = pool.acquireFloatArray(listOf(1, 224, 224, 3))
 * // ... 推理 ...
 * pool.releaseFloatArray(listOf(1, 224, 224, 3), buffer)
 * ```
 *
 * @param maxPoolSize 每种 shape 最多缓存的数组数量
 */
class InferenceMemoryPool(
    private val maxPoolSize: Int = 4,
) {
    companion object {
        private const val TAG = "InferenceMemPool"
        private const val MAX_CACHED_BYTES = 50L * 1024 * 1024 // 50MB
    }

    private val floatArrayPool = mutableMapOf<List<Int>, ArrayDeque<FloatArray>>()
    private val byteBufferPool = mutableMapOf<Int, ArrayDeque<ByteBuffer>>()

    private var totalCachedBytes: Long = 0

    /**
     * 获取指定 shape 的 FloatArray。
     * 优先从池中获取，池空时分配新数组。
     */
    @Synchronized
    fun acquireFloatArray(shape: List<Int>): FloatArray {
        val queue = floatArrayPool.getOrPut(shape) { ArrayDeque() }
        return if (queue.isNotEmpty()) {
            queue.removeFirst()
        } else {
            val size = shape.fold(1) { acc, dim -> acc * dim }
            FloatArray(size)
        }
    }

    /**
     * 归还 FloatArray 到池中以便复用。
     */
    @Synchronized
    fun releaseFloatArray(shape: List<Int>, array: FloatArray) {
        val queue = floatArrayPool.getOrPut(shape) { ArrayDeque() }
        if (queue.size < maxPoolSize) {
            val arrayBytes = array.size * 4L // float = 4 bytes
            if (totalCachedBytes + arrayBytes <= MAX_CACHED_BYTES) {
                queue.addLast(array)
                totalCachedBytes += arrayBytes
            }
        }
    }

    /**
     * 获取指定容量（字节）的 ByteBuffer（direct, native order）。
     */
    @Synchronized
    fun acquireByteBuffer(capacity: Int): ByteBuffer {
        val queue = byteBufferPool.getOrPut(capacity) { ArrayDeque() }
        return if (queue.isNotEmpty()) {
            queue.removeFirst().apply { clear() }
        } else {
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        }
    }

    /**
     * 归还 ByteBuffer 到池中。
     */
    @Synchronized
    fun releaseByteBuffer(capacity: Int, buffer: ByteBuffer) {
        val queue = byteBufferPool.getOrPut(capacity) { ArrayDeque() }
        if (queue.size < maxPoolSize && totalCachedBytes + capacity <= MAX_CACHED_BYTES) {
            queue.addLast(buffer)
            totalCachedBytes += capacity
        }
    }

    /**
     * 获取 FloatBuffer 视图（从池中获取 ByteBuffer 并包装）。
     */
    fun acquireFloatBuffer(numFloats: Int): FloatBuffer {
        return acquireByteBuffer(numFloats * 4).asFloatBuffer()
    }

    /**
     * 归还 FloatBuffer（解包 ByteBuffer 并归还）。
     *
     * 注意：Android 上 DirectFloatBuffer 的内部类型可能不同，
     * 反射访问可能失败，此时静默跳过归还。
     */
    fun releaseFloatBuffer(floatBuffer: FloatBuffer) {
        try {
            // 尝试通过反射获取内部的 ByteBuffer
            val bb = try {
                val bbField = floatBuffer.javaClass.getDeclaredField("bb")
                bbField.isAccessible = true
                bbField.get(floatBuffer) as? ByteBuffer
            } catch (_: Exception) {
                null
            }
            if (bb != null) {
                releaseByteBuffer(bb.capacity(), bb)
            }
        } catch (_: Exception) {
            // 反射失败时静默跳过
        }
    }

    /**
     * 获取池中当前缓存的统计信息。
     */
    @Synchronized
    fun getStats(): String {
        val floatArrays = floatArrayPool.values.sumOf { it.size }
        val byteBuffers = byteBufferPool.values.sumOf { it.size }
        return "池统计: FloatArrays=$floatArrays, ByteBuffers=$byteBuffers, " +
            "缓存=${formatPoolBytes(totalCachedBytes)}"
    }

    /**
     * 清空池，释放所有缓存的内存。
     */
    @Synchronized
    fun clear() {
        floatArrayPool.clear()
        byteBufferPool.clear()
        totalCachedBytes = 0
        Log.d(TAG, "内存池已清空")
    }

    private fun formatPoolBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
