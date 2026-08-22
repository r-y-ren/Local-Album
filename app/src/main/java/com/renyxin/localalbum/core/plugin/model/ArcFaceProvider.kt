package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.FaceEmbedder
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ArcFace 人脸嵌入 Provider — 纯嵌入器实现（Phase 3 拆分）。
 *
 * 仅负责对裁剪后的人脸区域提取 512 维嵌入向量，不再做假检测框。
 * 人脸检测由 [FaceDetector] 负责，组合逻辑由 [ComposedFaceProvider] 处理。
 *
 * ## 模型信息
 *
 * - 模型: w600k_r50.onnx (ArcFace r100, InsightFace buffalo_l 识别模型)
 * - 输入: 112×112 RGB, NCHW, FLOAT32, [-1, 1] ((x/127.5)-1)
 * - 输出: 512 维 L2 归一化嵌入向量
 * - 来源: 内置 assets/models/buffalo_l.zip (与 InsightFace REC 共用同一模型文件)
 */
class ArcFaceProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : FaceEmbedder {

    companion object {
        private const val TAG = "ArcFace"

        const val MODEL_ID = "model:arcface"
        const val INPUT_SIZE = 112
        const val EMBEDDING_DIM = 512
    }

    override val embedderId = MODEL_ID
    override val displayName = "ArcFace 人脸识别 (512-dim)"
    override val embeddingDim = EMBEDDING_DIM

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = MODEL_ID,
                displayName = displayName,
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, INPUT_SIZE, INPUT_SIZE),
                outputShape = intArrayOf(1, EMBEDDING_DIM),
                fileSizeBytes = 174_400_000,
                memoryFootprintBytes = 80_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "w600k_r50.onnx",
            )
        )
    }

    override suspend fun embed(faceCrop: Bitmap): FloatArray? {
        val modelResult = modelManager.ensureModelReady(MODEL_ID)
        if (modelResult.isFailure) {
            Log.w(TAG, "模型未就绪: ${modelResult.exceptionOrNull()?.message}")
            return null
        }

        return try {
            val resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
            val buf = preprocess(resized)

            // 通过 session 池获取独立 session（独立 intra-op 线程池），实现多核并行
            modelManager.withOnnxSession(MODEL_ID) { session ->
                val env = NativeAiRuntime.getOrtEnvironment()
                val tensor = OnnxTensor.createTensor(
                    env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                )
                val result = session.run(mapOf(session.inputNames.iterator().next() to tensor))
                tensor.close()

                val value = result.iterator().next().value.value
                result.close()

                // 输出 [1, 512]；逐层校验运行时类型，避免对泛型数组做未检查转换。
                val arr = value as? Array<*>
                val emb = arr?.getOrNull(0) as? FloatArray
                if (emb != null && emb.size == EMBEDDING_DIM) l2Normalize(emb) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ArcFace 嵌入提取失败", e)
            null
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(MODEL_ID, "ArcFaceProvider")
        Log.d(TAG, "ArcFace 消费者已注销")
    }

    // ---- 预处理 ----

    /** NCHW，RGB，归一化到 [-1,1] ((x/127.5)-1) */
    private fun preprocess(bitmap: Bitmap): java.nio.FloatBuffer {
        val s = INPUT_SIZE
        val buf = ByteBuffer.allocateDirect(s * s * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pixels = IntArray(s * s)
        bitmap.getPixels(pixels, 0, s, 0, 0, s, s)
        for (c in 0 until 3) {
            for (p in pixels) {
                val raw = (p shr (16 - 8 * c) and 0xFF)
                buf.put(raw / 127.5f - 1f)
            }
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(vec.map { it * it }.sum())
        if (norm > 1e-6f) vec.forEachIndexed { i, v -> vec[i] = v / norm }
        return vec
    }
}
