package com.renyxin.localalbum.core.plugin.runtime

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PyTorch Mobile 模型运行时（Phase 2.3）。
 *
 * 封装 PyTorch Mobile [Module]，提供统一的模型加载、推理能力。
 * 需新增依赖 `pytorch-android-lite`（约 40MB）。
 *
 * ## 推理流程
 * 1. 将输入 ByteBuffer 转换为 PyTorch [Tensor]
 * 2. 调用 `module.forward(IValue)`
 * 3. 将输出 [Tensor] 提取为 ByteBuffer 返回
 *
 * PyTorch Mobile 的输入/输出张量名通过 manifest 中的顺序匹配，
 * 因为 PyTorch 模型不直接暴露张量名（与 TFLite/ONNX 不同）。
 */
class PyTorchModelRuntime : ModelRuntime {

    companion object {
        private const val TAG = "PyTorchModelRuntime"
    }

    private var module: Module? = null
    private var inputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var outputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var ready = false

    override suspend fun initialize(context: Context, modelFile: File, manifest: PluginManifest) {
        inputTensors = manifest.inputTensors
        outputTensors = manifest.outputTensors

        // PyTorch Mobile Lite 模型文件后缀为 .ptl
        NativeAiRuntime.ensureNativePrerequisite()
        module = Module.load(modelFile.absolutePath)
        ready = true
        Log.i(TAG, "PyTorch 模型加载完成: ${modelFile.name}")
    }

    override fun isReady(): Boolean = ready && module != null

    override suspend fun invoke(inputs: Map<String, ByteBuffer>): Map<String, ByteBuffer> {
        val mod = module ?: throw IllegalStateException("PyTorch 运行时未初始化")

        // PyTorch 模型按顺序接收输入张量
        // 将所有输入按 inputTensors 顺序打包为 IValue 数组
        val inputIValues = Array<IValue>(inputTensors.size) { i ->
            val spec = inputTensors[i]
            val buffer = inputs[spec.name] ?: allocateBuffer(spec)
            buffer.rewind()
            IValue.from(bufferToTensor(buffer, spec))
        }

        // 执行推理
        val output = when (inputIValues.size) {
            1 -> mod.forward(inputIValues[0])
            else -> {
                // 多输入：使用 forward(IValue, IValue, ...) 变长参数
                mod.forward(*inputIValues)
            }
        }

        // 提取输出
        val result = mutableMapOf<String, ByteBuffer>()
        val outputTensor = output.toTensor()
        val outputBuffer = tensorToBuffer(outputTensor, outputTensors.firstOrNull())
        if (outputTensors.isNotEmpty()) {
            result[outputTensors[0].name] = outputBuffer
        }

        return result
    }

    override fun getInputTensors(): List<PluginManifest.TensorSpec> = inputTensors

    override fun getOutputTensors(): List<PluginManifest.TensorSpec> = outputTensors

    override fun close() {
        // PyTorch Module 没有 close 方法，GC 会回收 native 资源
        module = null
        ready = false
    }

    // ---- 辅助方法 ----

    /**
     * 将 ByteBuffer 转换为 PyTorch [Tensor]。
     * 目前支持 FLOAT32 数据类型。
     */
    private fun bufferToTensor(buffer: ByteBuffer, spec: PluginManifest.TensorSpec): Tensor {
        buffer.rewind()
        val shape = spec.shape.map { it.toLong() }.toLongArray()

        return when (spec.dataType) {
            PluginManifest.TensorDataType.FLOAT32,
            PluginManifest.TensorDataType.FLOAT16 -> {
                val floatArray = FloatArray(spec.elementCount)
                buffer.asFloatBuffer().get(floatArray)
                Tensor.fromBlob(floatArray, shape)
            }
            PluginManifest.TensorDataType.INT32,
            PluginManifest.TensorDataType.INT64 -> {
                val longArray = LongArray(spec.elementCount)
                buffer.asLongBuffer().get(longArray)
                Tensor.fromBlob(longArray, shape)
            }
            else -> throw IllegalArgumentException("PyTorch 不支持数据类型: ${spec.dataType}")
        }
    }

    /**
     * 将 PyTorch [Tensor] 转换为 ByteBuffer。
     */
    private fun tensorToBuffer(tensor: Tensor, spec: PluginManifest.TensorSpec?): ByteBuffer {
        val dataType = spec?.dataType ?: PluginManifest.TensorDataType.FLOAT32
        // 从 spec 计算元素数，或从 tensor 的 dataAsFloatArray 长度推断
        val elementCount = spec?.elementCount ?: tensor.dataAsFloatArray.size

        return when (dataType) {
            PluginManifest.TensorDataType.FLOAT32,
            PluginManifest.TensorDataType.FLOAT16 -> {
                val floatArray = tensor.dataAsFloatArray
                val buffer = ByteBuffer.allocateDirect(elementCount * 4)
                    .order(ByteOrder.nativeOrder())
                buffer.asFloatBuffer().put(floatArray, 0, minOf(elementCount, floatArray.size))
                buffer
            }
            PluginManifest.TensorDataType.INT32,
            PluginManifest.TensorDataType.INT64 -> {
                val longArray = tensor.dataAsLongArray
                val buffer = ByteBuffer.allocateDirect(elementCount * 8)
                    .order(ByteOrder.nativeOrder())
                buffer.asLongBuffer().put(longArray, 0, minOf(elementCount, longArray.size))
                buffer
            }
            else -> ByteBuffer.allocateDirect(0)
        }
    }

    /**
     * 分配空缓冲区（当输入缺失时使用）。
     */
    private fun allocateBuffer(spec: PluginManifest.TensorSpec): ByteBuffer {
        val bytesPerElement = when (spec.dataType) {
            PluginManifest.TensorDataType.FLOAT32 -> 4
            PluginManifest.TensorDataType.FLOAT16 -> 2
            PluginManifest.TensorDataType.INT32 -> 4
            PluginManifest.TensorDataType.INT64 -> 8
            else -> 1
        }
        return ByteBuffer.allocateDirect(spec.elementCount * bytesPerElement)
            .order(ByteOrder.nativeOrder())
    }
}
