package com.renyxin.localalbum.core.plugin.runtime

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ONNX 模型运行时（Phase 2.3）。
 *
 * 封装 ONNX Runtime [OrtSession]，提供统一的模型加载、推理能力。
 * 需新增依赖 `onnxruntime-android`（约 15MB）。
 *
 * ## 推理流程
 * 1. 将输入 ByteBuffer 包装为 [OnnxTensor]
 * 2. 调用 `session.run(inputs)`
 * 3. 将输出 [OnnxTensor] 提取为 ByteBuffer 返回
 */
class OnnxModelRuntime : ModelRuntime {

    companion object {
        private const val TAG = "OnnxModelRuntime"
    }

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var outputTensors: List<PluginManifest.TensorSpec> = emptyList()
    private var ready = false

    override suspend fun initialize(context: Context, modelFile: File, manifest: PluginManifest) {
        inputTensors = manifest.inputTensors
        outputTensors = manifest.outputTensors

        environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            // intra/inter op = 1，配合文件级并行避免 oversubscription
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            // XNNPACK：ARM NEON 优化，天玑大核受益明显
            runCatching { addXnnpack(emptyMap()) }
                .onFailure { Log.w(TAG, "XNNPACK 启用失败: ${it.message}") }
        }
        session = environment?.createSession(modelFile.absolutePath, options)
        ready = true
        Log.i(TAG, "ONNX 模型加载完成: ${modelFile.name}")
    }

    override fun isReady(): Boolean = ready && session != null

    override suspend fun invoke(inputs: Map<String, ByteBuffer>): Map<String, ByteBuffer> {
        val env = environment ?: throw IllegalStateException("ONNX 运行时未初始化")
        val sess = session ?: throw IllegalStateException("ONNX Session 未初始化")

        // 构建输入张量映射
        val inputMap = mutableMapOf<String, OnnxTensor>()
        for (spec in inputTensors) {
            val buffer = inputs[spec.name]
            if (buffer != null) {
                buffer.rewind()
                inputMap[spec.name] = OnnxTensor.createTensor(env, buffer, toOnnxShape(spec))
            }
        }

        // 执行推理
        val output = sess.run(inputMap)
        val result = mutableMapOf<String, ByteBuffer>()

        // 提取输出
        for (spec in outputTensors) {
            val optTensor = output.get(spec.name)
            if (optTensor.isPresent) {
                val tensor = optTensor.get() as? OnnxTensor
                if (tensor != null) {
                    // 将 OnnxTensor 的数据提取为 ByteBuffer
                    val buffer = extractByteBuffer(tensor, spec)
                    if (buffer != null) {
                        result[spec.name] = buffer
                    }
                }
            }
        }

        // 清理输入张量
        for (tensor in inputMap.values) {
            tensor.close()
        }
        output.close()

        return result
    }

    /**
     * 从 [OnnxTensor] 提取 ByteBuffer。
     */
    private fun extractByteBuffer(tensor: OnnxTensor, spec: PluginManifest.TensorSpec): ByteBuffer? {
        return try {
            when (spec.dataType) {
                PluginManifest.TensorDataType.FLOAT32,
                PluginManifest.TensorDataType.FLOAT16 -> {
                    val floatArray = tensor.value as? FloatArray
                    if (floatArray != null) {
                        val buffer = ByteBuffer.allocateDirect(floatArray.size * 4)
                            .order(ByteOrder.nativeOrder())
                        buffer.asFloatBuffer().put(floatArray)
                        buffer
                    } else null
                }
                PluginManifest.TensorDataType.INT32,
                PluginManifest.TensorDataType.INT64 -> {
                    val longArray = tensor.value as? LongArray
                    if (longArray != null) {
                        val buffer = ByteBuffer.allocateDirect(longArray.size * 8)
                            .order(ByteOrder.nativeOrder())
                        buffer.asLongBuffer().put(longArray)
                        buffer
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取 ONNX 输出张量失败: ${spec.name}", e)
            null
        }
    }

    override fun getInputTensors(): List<PluginManifest.TensorSpec> = inputTensors

    override fun getOutputTensors(): List<PluginManifest.TensorSpec> = outputTensors

    override fun close() {
        session?.close()
        session = null
        // OrtEnvironment 是单例，不在此关闭
        ready = false
    }

    /**
     * 将 [PluginManifest.TensorSpec.shape] 转换为 ONNX Runtime 的 long[] shape。
     */
    private fun toOnnxShape(spec: PluginManifest.TensorSpec): LongArray {
        return spec.shape.map { it.toLong() }.toLongArray()
    }
}
