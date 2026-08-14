package com.renyxin.localalbum.core.plugin.runtime

import android.content.Context
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TensorFlow Lite 模型运行时（Phase 2.3）。
 *
 * 封装 TFLite [Interpreter]，提供统一的模型加载、推理、张量元数据读取能力。
 * TFLite 依赖已在 build.gradle.kts 中声明（`tensorflow-lite:2.14.0`）。
 *
 * ## 推理流程
 * 1. 将输入 ByteBuffer 按张量名映射到 Interpreter 的 inputIndex
 * 2. 创建输出 ByteBuffer 映射（根据输出张量 shape 分配容量）
 * 3. 调用 `interpreter.runForMultipleInputsOutputs()`
 * 4. 返回输出 ByteBuffer 映射
 */
class TfliteModelRuntime : ModelRuntime {

    private var interpreter: Interpreter? = null
    private var inputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var outputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var ready = false

    override suspend fun initialize(context: Context, modelFile: File, manifest: PluginManifest) {
        inputTensors = manifest.inputTensors
        outputTensors = manifest.outputTensors

        val options = Interpreter.Options().apply {
            // 使用默认线程数，可根据设备 CPU 核心数优化
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }

        NativeAiRuntime.ensureNativePrerequisite()
        interpreter = Interpreter(modelFile, options)
        ready = true
    }

    override fun isReady(): Boolean = ready && interpreter != null

    override suspend fun invoke(inputs: Map<String, ByteBuffer>): Map<String, ByteBuffer> {
        val interp = interpreter ?: throw IllegalStateException("TFLite 运行时未初始化")

        // 构建输入数组（按 inputTensors 顺序）
        val inputArray = Array(inputTensors.size) { i ->
            val spec = inputTensors[i]
            val buffer = inputs[spec.name] ?: allocateInputBuffer(spec)
            buffer.rewind()
            buffer
        }

        // 构建输出映射
        val outputMap = mutableMapOf<Int, Any>()
        val outputBuffers = mutableMapOf<String, ByteBuffer>()
        for ((i, spec) in outputTensors.withIndex()) {
            val buffer = allocateOutputBuffer(spec)
            outputMap[i] = buffer
            outputBuffers[spec.name] = buffer
        }

        // 执行推理
        interp.runForMultipleInputsOutputs(inputArray, outputMap)

        return outputBuffers
    }

    override fun getInputTensors(): List<PluginManifest.TensorSpec> = inputTensors

    override fun getOutputTensors(): List<PluginManifest.TensorSpec> = outputTensors

    override fun close() {
        interpreter?.close()
        interpreter = null
        ready = false
    }

    // ---- 辅助方法 ----

    /**
     * 根据张量规格分配输入 ByteBuffer。
     */
    private fun allocateInputBuffer(spec: PluginManifest.TensorSpec): ByteBuffer {
        val bytesPerElement = bytesPerElement(spec.dataType)
        val capacity = spec.elementCount * bytesPerElement
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }

    /**
     * 根据张量规格分配输出 ByteBuffer。
     */
    private fun allocateOutputBuffer(spec: PluginManifest.TensorSpec): ByteBuffer {
        val bytesPerElement = bytesPerElement(spec.dataType)
        val capacity = spec.elementCount * bytesPerElement
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }

    /**
     * 根据数据类型返回每个元素占用的字节数。
     */
    private fun bytesPerElement(dataType: PluginManifest.TensorDataType): Int {
        return when (dataType) {
            PluginManifest.TensorDataType.FLOAT32 -> 4
            PluginManifest.TensorDataType.FLOAT16 -> 2
            PluginManifest.TensorDataType.INT32 -> 4
            PluginManifest.TensorDataType.INT64 -> 8
            PluginManifest.TensorDataType.INT8 -> 1
            PluginManifest.TensorDataType.UINT8 -> 1
            PluginManifest.TensorDataType.BOOL -> 1
            PluginManifest.TensorDataType.STRING -> 1 // 字符串张量特殊处理，此处占位
        }
    }
}
