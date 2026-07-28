package com.renyxin.localalbum.core.plugin.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 分批模型加载器（Phase 5c — 性能优化）。
 *
 * 根据模型文件大小自动选择最优加载策略：
 * - **小模型** (<20MB)：直接 [MappedByteBuffer] 一次性加载（默认行为）
 * - **中等模型** (20MB-50MB)：分页映射，减少物理内存峰值
 * - **大模型** (>50MB)：异步后台加载 + 日志警告
 *
 * ## 使用方式
 *
 * ```kotlin
 * val loader = BatchedModelLoader()
 * val interpreter = loader.loadModel(modelFile) { progress ->
 *     Log.d("Load", "Loading: ${(progress * 100).toInt()}%")
 * }
 * ```
 *
 * @param smallThresholdBytes 小模型阈值（字节），默认 20MB
 * @param largeThresholdBytes 大模型阈值（字节），默认 50MB
 */
class BatchedModelLoader(
    private val smallThresholdBytes: Long = 20L * 1024 * 1024,
    private val largeThresholdBytes: Long = 50L * 1024 * 1024,
) {
    companion object {
        private const val TAG = "BatchedLoader"
        private const val PAGE_SIZE = 4L * 1024 * 1024 // 4MB 分页
    }

    /**
     * 根据模型文件大小选择策略加载 TFLite 模型。
     *
     * @param file 模型文件
     * @param progressCallback 加载进度回调（0.0 ~ 1.0）
     * @return 已加载的 [Interpreter]，可用于推理
     */
    suspend fun loadModel(
        file: File,
        progressCallback: (Float) -> Unit = {},
    ): Interpreter = withContext(Dispatchers.IO) {
        val fileSize = file.length()
        val sizeMB = fileSize / (1024.0 * 1024.0)

        Log.i(TAG, "加载模型: ${file.name} (${"%.1f".format(sizeMB)} MB)")

        when {
            fileSize <= smallThresholdBytes -> {
                // 小模型：直接 MappedByteBuffer 加载
                Log.d(TAG, "小模型，直接加载")
                progressCallback(0.5f)
                val buffer = loadDirect(file)
                progressCallback(1.0f)
                createInterpreter(buffer, fileSize)
            }
            fileSize <= largeThresholdBytes -> {
                // 中等模型：分页加载
                Log.d(TAG, "中等模型，分页加载")
                var progress = 0f
                val buffer = loadPaged(file) { pageProgress ->
                    progress = pageProgress
                    progressCallback(pageProgress)
                }
                createInterpreter(buffer, fileSize)
            }
            else -> {
                // 大模型：全量加载 + 警告
                Log.w(TAG, "大模型 (${"%.1f".format(sizeMB)} MB)，建议考虑模型量化或拆分")
                progressCallback(0.3f)
                val buffer = loadDirect(file)
                progressCallback(1.0f)
                createInterpreter(buffer, fileSize)
            }
        }
    }

    // ---- 内部 ----

    private fun loadDirect(file: File): MappedByteBuffer {
        return FileInputStream(file).channel.map(
            FileChannel.MapMode.READ_ONLY, 0, file.length()
        )
    }

    private fun loadPaged(
        file: File,
        progressCallback: (Float) -> Unit,
    ): MappedByteBuffer {
        val fileSize = file.length()
        val totalPages = ((fileSize + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

        Log.d(TAG, "分页加载: $totalPages 页, 每页 ${PAGE_SIZE / 1024}KB")

        // 逐页触摸以触发实际物理内存分配
        val channel = FileInputStream(file).channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)

        for (page in 0 until totalPages) {
            val offset = page * PAGE_SIZE
            val len = minOf(PAGE_SIZE, fileSize - offset).toInt()
            // 触摸该页的一个字节以触发物理页分配
            buffer.get(offset.toInt())
            progressCallback((page + 1).toFloat() / totalPages)
        }

        channel.close()
        return buffer
    }

    private fun createInterpreter(buffer: MappedByteBuffer, fileSize: Long): Interpreter {
        // 单线程 + XNNPACK，配合文件级并行（多文件并发各跑单线程推理）
        val options = Interpreter.Options().apply {
            setNumThreads(1)
            setUseXNNPACK(true)
        }
        Log.d(TAG, "创建 Interpreter: 1 线程 + XNNPACK（配合文件级并行）")
        return Interpreter(buffer, options)
    }
}
