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
 * 基于 MobileNetV2 的真实 TFLite 场景分类 Provider（Phase 1 重构）。
 *
 * 注入 [ModelManager] 统一管理模型生命周期，不再直接创建/销毁 [org.tensorflow.lite.Interpreter]。
 *
 * ## 模型信息
 *
 * - 模型: MobileNetV2 (quantized), 224×224 输入, ImageNet 1001 类
 * - 大小: ~4.3MB
 * - 下载 URL: Google TF Hub 托管的公开模型
 */
class MobileNetSceneProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : SceneProvider {

    companion object {
        private const val TAG = "MobileNetScene"

        const val MODEL_ID = "model:mobilenet_v2"
        const val MODEL_FILE_NAME = "mobilenet_v2_1.0_224_quant.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite" +
                "/mobilenet_v2_1.0_224_quant.tflite"

        const val INPUT_SIZE = 224
        const val INPUT_CHANNELS = 3
        const val NUM_CLASSES = 1001
    }

    override val providerId = MODEL_ID
    override val displayName = "MobileNetV2 场景分类"
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
                fileUrl = MODEL_URL,
                inputShape = listOf(1, INPUT_SIZE, INPUT_SIZE, INPUT_CHANNELS),
                outputShape = intArrayOf(1, NUM_CLASSES),
                fileSizeBytes = 4_300_000,
                memoryFootprintBytes = 20_000_000,
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
            val outputArray = Array(1) { FloatArray(NUM_CLASSES) }
            // 通过 Interpreter 池获取线程安全实例，支持文件级并发推理
            modelManager.withInterpreter(MODEL_ID) { interpreter ->
                android.util.Log.i("CrashDebug", ">> MobileNet interpreter.run (线程=${Thread.currentThread().name})")
                interpreter.run(inputBuffer, outputArray)
                android.util.Log.i("CrashDebug", "<< MobileNet interpreter.run 完成")
            }

            val scores = outputArray[0]
            val topIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            val topConfidence = scores[topIndex]

            val top5 = scores.indices
                .sortedByDescending { scores[it] }
                .take(5)
                .associate { idx ->
                    val sceneLabel = ImageNetLabels.toSceneLabel(idx, scores[idx])
                    sceneLabel to scores[idx]
                }

            val sceneLabel = ImageNetLabels.toSceneLabel(topIndex, topConfidence)

            Log.d(TAG, "分类结果: $sceneLabel (${ImageNetLabels.LABELS[topIndex]}, conf=${"%.2f".format(topConfidence)})")

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
        Log.d(TAG, "MobileNetV2 消费者已注销")
    }

    // ---- 预处理 ----

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * INPUT_CHANNELS)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized != bitmap) resized.recycle()

        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
            buffer.put((pixel and 0xFF).toByte())           // B
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
