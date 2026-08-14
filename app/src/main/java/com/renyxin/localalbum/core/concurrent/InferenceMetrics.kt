package com.renyxin.localalbum.core.concurrent

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 推理与批处理的轻量指标收集器。
 *
 * 仅保存聚合数据，不记录文件路径、OCR 文本或模型输入，避免扫描大量媒体时产生
 * 高基数日志和隐私泄露。指标用于建立 CPU 基线，并为后续硬件后端灰度提供依据。
 */
object InferenceMetrics {

    private const val TAG = "InferenceMetrics"

    enum class Backend {
        CPU_XNNPACK,
        CPU_DEFAULT,
        TFLITE_NNAPI,
        ONNX_NNAPI,
    }

    data class Snapshot(
        val operation: String,
        val backend: Backend,
        val count: Long,
        val failures: Long,
        val totalMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val averageMs: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
    )

    private class Aggregate {
        val count = AtomicLong()
        val failures = AtomicLong()
        val totalMs = AtomicLong()
        val minMs = AtomicLong(Long.MAX_VALUE)
        val maxMs = AtomicLong()
        private val latencySamplesMs = ArrayDeque<Long>()

        fun record(elapsedMs: Long, success: Boolean) {
            count.incrementAndGet()
            if (!success) failures.incrementAndGet()
            totalMs.addAndGet(elapsedMs)
            minMs.accumulateAndGet(elapsedMs, ::minOf)
            maxMs.accumulateAndGet(elapsedMs, ::maxOf)
            synchronized(latencySamplesMs) {
                if (latencySamplesMs.size == MAX_PERCENTILE_SAMPLES) latencySamplesMs.removeFirst()
                latencySamplesMs.addLast(elapsedMs)
            }
        }

        fun snapshot(operation: String, backend: Backend): Snapshot {
            val samples = count.get()
            val total = totalMs.get()
            val sortedSamples = synchronized(latencySamplesMs) { latencySamplesMs.sorted() }
            return Snapshot(
                operation = operation,
                backend = backend,
                count = samples,
                failures = failures.get(),
                totalMs = total,
                minMs = if (samples == 0L) 0L else minMs.get(),
                maxMs = maxMs.get(),
                averageMs = if (samples == 0L) 0.0 else total.toDouble() / samples,
                p50Ms = percentile(sortedSamples, 50),
                p95Ms = percentile(sortedSamples, 95),
                p99Ms = percentile(sortedSamples, 99),
            )
        }
    }

    private val aggregates = ConcurrentHashMap<String, Aggregate>()

    suspend fun <T> measure(
        operation: String,
        backend: Backend,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        var success = false
        try {
            return block().also { success = true }
        } finally {
            record(operation, backend, elapsedMsSince(startedAt), success)
        }
    }

    fun record(operation: String, backend: Backend, elapsedMs: Long, success: Boolean) {
        require(operation.isNotBlank()) { "operation 不能为空" }
        val normalizedElapsedMs = elapsedMs.coerceAtLeast(0)
        val key = "$operation|${backend.name}"
        val aggregate = aggregates.computeIfAbsent(key) { Aggregate() }
        aggregate.record(normalizedElapsedMs, success)

        if (!success) {
            Log.w(TAG, "推理指标失败: operation=$operation backend=$backend elapsedMs=$normalizedElapsedMs")
        }
    }

    fun snapshot(): List<Snapshot> = aggregates
        .flatMap { (key, aggregate) ->
            val separator = key.lastIndexOf('|')
            val operation = key.substring(0, separator)
            val backend = Backend.valueOf(key.substring(separator + 1))
            listOf(aggregate.snapshot(operation, backend))
        }
        .sortedWith(compareBy<Snapshot> { it.operation }.thenBy { it.backend.name })

    fun reset() {
        aggregates.clear()
    }

    fun logSnapshot(reason: String) {
        snapshot().forEach { item ->
            Log.i(
                TAG,
                "[$reason] operation=${item.operation} backend=${item.backend} " +
                    "count=${item.count} failures=${item.failures} avgMs=${"%.2f".format(item.averageMs)} " +
                    "p50Ms=${item.p50Ms} p95Ms=${item.p95Ms} p99Ms=${item.p99Ms} " +
                    "minMs=${item.minMs} maxMs=${item.maxMs}",
            )
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND

    /** Nearest-rank percentile, shared with the scan benchmark report convention. */
    private fun percentile(sortedSamples: List<Long>, percentile: Int): Long {
        if (sortedSamples.isEmpty()) return 0L
        val rank = kotlin.math.ceil(percentile / 100.0 * sortedSamples.size)
            .toInt()
            .coerceAtLeast(1)
        return sortedSamples[rank - 1]
    }

    private const val MAX_PERCENTILE_SAMPLES = 512
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
