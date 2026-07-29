package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基于 MobileNetV3 Large 的 TFLite 场景分类 Provider。
 *
 * 注入 [ModelManager] 统一管理模型生命周期，不再直接创建/销毁 [org.tensorflow.lite.Interpreter]。
 *
 * ## 模型信息
 *
 * - 模型: MobileNetV3 Large (float32), 224×224 输入, ImageNet 1000 类
 * - 大小: ~21MB
 * - 来源: 随包打包在 assets/models/MobileNet-v3-Large.tflite，安装即用，无需网络下载
 *
 * ## 与旧版 MobileNetV2 的差异
 *
 * - V2 quantized 使用 uint8 输入（原始像素值 0-255），V3 Large float 使用 float32 输入（归一化到 [0,1]）
 * - V2 输出 1001 类（含 background），V3 输出 1000 类（标准 ImageNet，无 background）
 * - 标签映射通过运行时检测输出维度自动适配 1000/1001 类
 */
class MobileNetSceneProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : SceneProvider {

    companion object {
        private const val TAG = "MobileNetScene"

        const val MODEL_ID = "model:mobilenet_v2"
        const val MODEL_FILE_NAME = "MobileNet-v3-Large.tflite"

        const val INPUT_SIZE = 224
        const val INPUT_CHANNELS = 3
        const val NUM_CLASSES = 1001
    }

    override val providerId = MODEL_ID
    override val displayName = "MobileNetV3 场景分类"
    override val labels: List<String> = listOf(
        "nature", "beach", "mountain", "flower", "food", "animal",
        "architecture", "vehicle", "people", "night", "water", "document", "unknown",
    )

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = MODEL_ID,
                displayName = displayName,
                format = PluginManifest.ModelFormat.TFLITE,
                fileUrl = "", // 随包打包，无需下载
                inputShape = listOf(1, INPUT_SIZE, INPUT_SIZE, INPUT_CHANNELS),
                outputShape = intArrayOf(1, NUM_CLASSES),
                fileSizeBytes = 21_927_112,
                memoryFootprintBytes = 30_000_000,
                assetFileName = MODEL_FILE_NAME, // 从 assets/models/ 加载
            )
        )
    }

    override suspend fun classify(file: File): SceneProvider.SceneResult = withContext(Dispatchers.IO) {
        val modelResult = modelManager.ensureModelReady(MODEL_ID)
        if (modelResult.isFailure) {
            Log.w(TAG, "模型未就绪，回退到启发式分类")
            return@withContext fallbackClassify(file)
        }

        val bitmap = decodeBitmap(file) ?: return@withContext SceneProvider.SceneResult("unknown", 1f)
        // 注册消费者，防止推理过程中模型被 evictUnusedModels 淘汰
        modelManager.registerConsumer(MODEL_ID, "MobileNetSceneProvider")
        try {
            val inputBuffer = preprocess(bitmap)
            // 模型实际输出为 1000 类，而旧常量为兼容旧 V2 模型保留的 1001 类。
            // 必须依据 Interpreter 的真实输出 Tensor 分配，否则 TFLite 会因输出容器尺寸不匹配
            // 而在 CPU 和 NNAPI 两条路径上均失败，造成“后端失败”的假象。
            lateinit var scores: FloatArray
            modelManager.withInterpreter(MODEL_ID) { interpreter ->
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputCount = outputShape.fold(1) { acc, dimension -> acc * dimension }
                require(outputCount > 0) { "MobileNet 输出张量为空: ${outputShape.contentToString()}" }
                val outputArray = Array(1) { FloatArray(outputCount) }
                interpreter.run(inputBuffer, outputArray)
                scores = outputArray[0]
            }

            val topIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            val topConfidence = scores[topIndex]

            // 1000 类 V3 没有 background；1001 类旧模型保留 background，故仅前者偏移。
            val labelOffset = if (scores.size == NUM_CLASSES - 1) 1 else 0
            val labelIndex = topIndex + labelOffset

            val top5 = scores.indices
                .sortedByDescending { scores[it] }
                .take(5)
                .associate { idx ->
                    val sceneLabel = ImageNetLabels.toSceneLabel(idx + labelOffset, scores[idx])
                    sceneLabel to scores[idx]
                }

            val sceneLabel = ImageNetLabels.toSceneLabel(labelIndex, topConfidence)

            Log.d(TAG, "分类结果: $sceneLabel (${ImageNetLabels.LABELS.getOrElse(labelIndex) { "unknown" }}, conf=${"%.2f".format(topConfidence)})")

            SceneProvider.SceneResult(
                topLabel = sceneLabel,
                confidence = topConfidence,
                allScores = top5,
            )
        } catch (e: Exception) {
            Log.w(TAG, "MobileNet 推理失败: ${file.name}", e)
            fallbackClassify(file)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            modelManager.unregisterConsumer(MODEL_ID, "MobileNetSceneProvider")
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(MODEL_ID, "MobileNetSceneProvider")
        Log.d(TAG, "MobileNetV3 消费者已注销")
    }

    // ---- 预处理 ----

    /**
     * MobileNetV3 Large (float32) 预处理：NCHW float32，归一化到 [0, 1]。
     *
     * 与旧版 V2 quantized 的区别：
     * - V2: ByteBuffer 存 uint8 原始像素值 (0-255)
     * - V3: ByteBuffer 存 float32 归一化值 (pixel / 255.0f)
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        // float32: 每个值 4 字节
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * INPUT_CHANNELS * 4)
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized != bitmap) resized.recycle()

        for (pixel in pixels) {
            floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f) // R
            floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)  // G
            floatBuffer.put((pixel and 0xFF) / 255.0f)           // B
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(file)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }

    private fun calculateSampleSize(file: File): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        val maxDim = INPUT_SIZE * 2
        while (w / sample > maxDim || h / sample > maxDim) sample *= 2
        return sample
    }

    /** 模型不可用时回退到简单的像素分析 */
    private suspend fun fallbackClassify(file: File): SceneProvider.SceneResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext SceneProvider.SceneResult("unknown", 1f)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumBrightness = 0f
        for (p in pixels) {
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
            sumBrightness += (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
        }
        val n = pixels.size
        val avgR = sumR.toFloat() / n
        val avgG = sumG.toFloat() / n
        val avgB = sumB.toFloat() / n
        val avgBrightness = sumBrightness / n

        val label = when {
            avgBrightness < 50f -> "night"
            avgB > avgR + 15 && avgB > avgG + 10 -> "water"
            avgG > avgR && avgG > avgB -> "nature"
            avgR > avgG && avgR > avgB && avgBrightness > 100f -> "food"
            else -> "unknown"
        }
        SceneProvider.SceneResult(label, 0.5f)
    }
}
