package com.renyxin.localalbum.core.pipeline

import com.renyxin.localalbum.core.concurrent.InferenceDispatchers
import com.renyxin.localalbum.core.concurrent.InferenceMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文件级并行处理工具（多核优化）。
 *
 * 将原 Stage 内串行的 `for (path in filePaths)` 循环改造为受控并发执行，
 * 每个 [process] 闭包在 [InferenceDispatchers.cpuBound] 上运行，
 * 通过 [Semaphore] 限制并发度，避免内存峰值过高。
 *
 * ## 进度回调
 *
 * 使用 [AtomicInteger] 维护已处理计数，保证并发安全；
 * 每个文件开始时上报 PROCESSING，结束时上报 COMPLETED/FAILED。
 *
 * ## 用法
 *
 * ```kotlin
 * val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
 *     // 单文件推理，返回结果
 * }
 * ```
 */
object ParallelFileProcessor {

    /**
     * 单文件处理结果。
     *
     * @param path 文件路径
     * @param success 是否成功
     * @param value 处理产物（如人脸实体列表、标签等），失败时为 null
     * @param error 失败异常（仅 success=false 时有值）
     */
    data class FileResult<T>(
        val path: String,
        val success: Boolean,
        val value: T? = null,
        val error: Throwable? = null,
    )

    /**
     * 并发处理文件列表，返回每个文件的结果。
     *
     * @param filePaths 待处理文件路径
     * @param enhancedCallback 进度回调（processed, total, path, status）
     * @param concurrency 并发度，默认 [InferenceDispatchers.inferenceConcurrency]
     * @param dispatcher 调度器，默认 [InferenceDispatchers.cpuBound]
     * @param process 单文件处理闭包，抛异常视为该文件失败
     * @return 每个文件的 [FileResult] 列表，顺序与输入一致
     */
    suspend fun <T> mapParallel(
        filePaths: List<String>,
        enhancedCallback: EnhancedProgressCallback,
        concurrency: Int = InferenceDispatchers.inferenceConcurrency,
        dispatcher: CoroutineDispatcher = InferenceDispatchers.cpuBound,
        process: suspend (path: String) -> T,
    ): List<FileResult<T>> {
        val total = filePaths.size
        if (total == 0) return emptyList()
        val processed = AtomicInteger(0)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        return coroutineScope {
            filePaths.map { path ->
                async(dispatcher) {
                    semaphore.withPermit {
                        // 文件存在性预检（IO 轻操作，不占推理配额意义不大但避免无谓推理）
                        if (!File(path).exists()) {
                            val done = processed.incrementAndGet()
                            enhancedCallback(done, total, path, FileProcessingStatus.FAILED)
                            FileResult<T>(path, success = false)
                        } else {
                            enhancedCallback(processed.get(), total, path, FileProcessingStatus.PROCESSING)
                            try {
                                // D7 日志治理：移除每文件 2 条 CrashDebug 日志（5 万文件 = 10 万条 logcat）。
                                // 仅保留失败路径的错误日志，成功路径通过 enhancedCallback 上报进度。
                                // 指标仅按并行批处理聚合，不记录文件路径，避免高基数和隐私泄露。
                                val value = InferenceMetrics.measure(
                                    operation = "pipeline:file",
                                    backend = InferenceMetrics.Backend.CPU_DEFAULT,
                                ) {
                                    process(path)
                                }
                                val done = processed.incrementAndGet()
                                enhancedCallback(done, total, path, FileProcessingStatus.COMPLETED)
                                FileResult(path, success = true, value = value)
                            } catch (e: Throwable) {
                                // 修复：catch Throwable 而非 Exception，捕获 OOM/Error 等，
                                // 避免 native 层抛出 Error 时冒泡导致进程闪退。
                                if (e is CancellationException) throw e
                                android.util.Log.e("CrashDebug", "!! 文件处理异常: $path -> ${e.javaClass.name}: ${e.message}", e)
                                val done = processed.incrementAndGet()
                                enhancedCallback(done, total, path, FileProcessingStatus.FAILED)
                                FileResult(path, success = false, error = e)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 仅收集成功结果的便捷方法。
     */
    suspend fun <T> mapSuccess(
        filePaths: List<String>,
        enhancedCallback: EnhancedProgressCallback,
        concurrency: Int = InferenceDispatchers.inferenceConcurrency,
        dispatcher: CoroutineDispatcher = InferenceDispatchers.cpuBound,
        process: suspend (path: String) -> T,
    ): Pair<List<T>, Int> {
        val results = mapParallel(filePaths, enhancedCallback, concurrency, dispatcher, process)
        val success = results.mapNotNull { it.value }
        val failed = results.count { !it.success }
        return success to failed
    }
}
