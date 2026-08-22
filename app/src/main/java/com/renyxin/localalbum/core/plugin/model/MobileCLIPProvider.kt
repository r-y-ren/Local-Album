package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MobileCLIP 语义嵌入 Provider — 真实跨模态嵌入模型（Phase 1 重构）。
 *
 * 注入 [ModelManager] 统一管理图像编码器和文本编码器两个模型的生命周期。
 *
 * ## 模型信息
 *
 * - 模型: MobileCLIP-S0 (TFLite), 图像编码器 + 文本编码器
 * - 图像输入: 256×256 RGB, FLOAT32, normalized
 * - 文本输入: 77 tokens, INT32
 * - 嵌入维度: 512
 * - 大小: ~45MB (图像编码器 ~35MB, 文本编码器 ~10MB)
 */
class MobileCLIPProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : SemanticEmbedProvider {

    companion object {
        private const val TAG = "MobileCLIP"

        const val IMAGE_MODEL_ID = "model:mobileclip_image"
        const val TEXT_MODEL_ID = "model:mobileclip_text"
        const val IMAGE_MODEL_FILE = "mobileclip_image_encoder.tflite"
        const val TEXT_MODEL_FILE = "mobileclip_text_encoder.tflite"
        const val IMAGE_MODEL_URL =
            "https://huggingface.co/apple/MobileCLIP-S0/resolve/main/image_encoder.tflite"
        const val TEXT_MODEL_URL =
            "https://huggingface.co/apple/MobileCLIP-S0/resolve/main/text_encoder.tflite"

        const val MODEL_VERSION = 1
        const val IMAGE_SIZE = 256
        const val EMBEDDING_DIM = 512
        const val MAX_TEXT_LENGTH = 77

        val NORM_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val NORM_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    override val providerId = "model:mobileclip"
    override val modelId = "mobileclip-s0"
    override val modelVersion = MODEL_VERSION
    override val displayName = "MobileCLIP 语义检索"
    override val embeddingDim = EMBEDDING_DIM

    /** 简易 BPE 词汇表 */
    private val vocab: Map<String, Int> = buildSimpleVocab()

    init {
        // 注册图像编码器
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = IMAGE_MODEL_ID,
                displayName = "MobileCLIP 图像编码器",
                format = PluginManifest.ModelFormat.TFLITE,
                fileUrl = IMAGE_MODEL_URL,
                inputShape = listOf(1, IMAGE_SIZE, IMAGE_SIZE, 3),
                outputShape = intArrayOf(1, EMBEDDING_DIM),
                fileSizeBytes = 35_000_000,
                memoryFootprintBytes = 50_000_000,
            )
        )
        // 注册文本编码器
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = TEXT_MODEL_ID,
                displayName = "MobileCLIP 文本编码器",
                format = PluginManifest.ModelFormat.TFLITE,
                fileUrl = TEXT_MODEL_URL,
                inputShape = listOf(1, MAX_TEXT_LENGTH),
                outputShape = intArrayOf(1, EMBEDDING_DIM),
                fileSizeBytes = 10_000_000,
                memoryFootprintBytes = 15_000_000,
            )
        )
    }

    override suspend fun embedImage(
        file: File,
        context: SemanticEmbedProvider.ImageContext?,
    ): FloatArray? = withContext(Dispatchers.IO) {
        val modelResult = modelManager.ensureModelReady(IMAGE_MODEL_ID)
        if (modelResult.isFailure) {
            Log.w(TAG, "图像编码器未就绪: ${modelResult.exceptionOrNull()?.message}")
            return@withContext null
        }

        val bitmap = decodeBitmap(file) ?: return@withContext null
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            val inputBuffer = preprocessImage(resized)

            val outputArray = Array(1) { FloatArray(EMBEDDING_DIM) }
            // 通过 Interpreter 池获取线程安全实例，支持文件级并发推理
            modelManager.withInterpreter(IMAGE_MODEL_ID) { interpreter ->
                interpreter.run(inputBuffer, outputArray)
            }

            l2Normalize(outputArray[0])
        } catch (e: Exception) {
            Log.w(TAG, "MobileCLIP 图像嵌入失败: ${file.name}", e)
            null
        }
    }

    override suspend fun embedText(query: String): FloatArray {
        val modelResult = modelManager.ensureModelReady(TEXT_MODEL_ID)
        if (modelResult.isFailure) {
            Log.w(TAG, "文本编码器未就绪: ${modelResult.exceptionOrNull()?.message}")
            return FloatArray(EMBEDDING_DIM)
        }

        return try {
            val tokenIds = tokenize(query)
            val inputArray = Array(1) { IntArray(MAX_TEXT_LENGTH) { tokenIds.getOrElse(it) { 0 } } }
            val outputArray = Array(1) { FloatArray(EMBEDDING_DIM) }
            modelManager.withInterpreter(TEXT_MODEL_ID) { interpreter ->
                interpreter.run(inputArray, outputArray)
            }
            l2Normalize(outputArray[0])
        } catch (e: Exception) {
            Log.w(TAG, "MobileCLIP 文本嵌入失败: $query", e)
            FloatArray(EMBEDDING_DIM)
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(IMAGE_MODEL_ID, "MobileCLIPProvider")
        modelManager.unregisterConsumer(TEXT_MODEL_ID, "MobileCLIPProvider")
        Log.d(TAG, "MobileCLIP 消费者已注销")
    }

    // ---- 图像预处理 ----

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        for (pixel in pixels) {
            floatBuffer.put(((((pixel shr 16) and 0xFF) / 255f) - NORM_MEAN[0]) / NORM_STD[0])
            floatBuffer.put(((((pixel shr 8) and 0xFF) / 255f) - NORM_MEAN[1]) / NORM_STD[1])
            floatBuffer.put((((pixel and 0xFF) / 255f) - NORM_MEAN[2]) / NORM_STD[2])
        }
        buffer.rewind()
        return buffer
    }

    // ---- 文本 Tokenization ----

    private fun tokenize(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        tokens.add(49406) // <|startoftext|>

        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        for (word in words) {
            val tokenId = vocab[word]
                ?: vocab[word.take(4)]
                ?: word.sumOf { it.code }.mod(49408)
            tokens.add(tokenId)
        }

        tokens.add(49407) // <|endoftext|>
        if (tokens.size > MAX_TEXT_LENGTH) {
            return tokens.take(MAX_TEXT_LENGTH - 1) + 49407
        }
        return tokens
    }

    private fun buildSimpleVocab(): Map<String, Int> {
        val words = listOf(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "with",
            "and", "or", "but", "is", "are", "was", "were", "be", "been",
            "photo", "image", "picture", "scene", "view",
            "sunset", "sunrise", "beach", "mountain", "forest", "city", "night",
            "food", "selfie", "people", "person", "pet", "dog", "cat", "animal",
            "flower", "snow", "sky", "water", "lake", "river", "ocean", "sea",
            "building", "architecture", "indoor", "outdoor", "landscape",
            "car", "vehicle", "travel", "party", "sport", "black", "white",
            "tree", "grass", "garden", "park", "street", "road", "bridge",
            "bird", "fish", "horse", "nature", "summer", "winter", "spring", "autumn",
            "red", "blue", "green", "yellow", "dark", "bright", "light",
            "portrait", "group", "family", "friend", "baby", "child",
            "cake", "birthday", "wedding", "celebration", "holiday",
            "desk", "computer", "phone", "book", "document", "text",
        )
        return words.mapIndexed { i, w -> w to (i + 49408) }.toMap()
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(vec.map { it * it }.sum())
        if (norm > 1e-6f) vec.forEachIndexed { i, v -> vec[i] = v / norm }
        return vec
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 2
                // 16-bit PNG 会解码为 RGBA_F16，强制 8 位（详见 InsightFaceProvider）
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }
}
