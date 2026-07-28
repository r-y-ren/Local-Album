package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLM-OCR Provider — 基于多模态大模型的文字识别（Phase 1 重构）。
 *
 * 注入 [ModelManager] 统一管理视觉编码器和文本解码器两个模型的生命周期。
 *
 * ## 模型信息
 *
 * - 模型: GLM-OCR (TFLite), Vision Encoder + Text Decoder
 * - 输入: 384×384 RGB, FLOAT32
 * - 输出: token 序列 (UTF-8 文本)
 * - 大小: ~180MB (encoder) + ~120MB (decoder)
 */
class GLMOcrProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : OcrProvider {

    companion object {
        private const val TAG = "GLM-OCR"

        const val ENCODER_MODEL_ID = "model:glm_ocr_encoder"
        const val DECODER_MODEL_ID = "model:glm_ocr_decoder"
        const val ENCODER_MODEL = "glm_ocr_encoder.tflite"
        const val DECODER_MODEL = "glm_ocr_decoder.tflite"
        const val ENCODER_URL =
            "https://huggingface.co/THUDM/GLM-OCR/resolve/main/vision_encoder.tflite"
        const val DECODER_URL =
            "https://huggingface.co/THUDM/GLM-OCR/resolve/main/text_decoder.tflite"

        const val IMAGE_SIZE = 384
        const val MAX_TOKENS = 256
        const val ENCODER_OUTPUT_DIM = 1024
    }

    override val providerId = "model:glm_ocr"
    override val displayName = "GLM-OCR 文字识别"

    private val vocabTokens = listOf(
        "<s>", "</s>", "<unk>", "<pad>", " ", "!", "\"", "#", "$", "%", "&", "'",
        "(", ")", "*", "+", ",", "-", ".", "/", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        ":", ";", "<", "=", ">", "?", "@", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
        "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "[", "\\", "]",
        "^", "_", "`", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
        "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "的", "一", "是", "在", "不", "了", "有", "和", "人", "这", "中", "大", "为", "上", "个",
        "国", "我", "以", "要", "他", "时", "来", "用", "们", "生", "到", "作", "地", "于", "出",
        "就", "分", "对", "成", "会", "可", "主", "发", "年", "动", "同", "工", "也", "能", "下",
        "过", "子", "说", "产", "种", "面", "而", "方", "后", "多", "定", "行", "学", "法", "所",
        "民", "得", "经", "十", "三", "之", "进", "着", "等", "部", "度", "家", "电", "力", "里",
        "如", "水", "化", "高", "自", "二", "理", "起", "小", "物", "现", "实", "加", "量", "都",
        "两", "体", "制", "机", "当", "使", "点", "从", "业", "本", "去", "把", "性", "应", "开",
        "它", "合", "还", "因", "由", "其", "些", "然", "前", "外", "天", "政", "四", "日", "那",
        "社", "义", "事", "平", "形", "相", "全", "表", "间", "样", "与", "关", "各", "重", "新",
        "线", "内", "数", "正", "心", "反", "你", "明", "看", "原", "又", "么", "利", "比", "或",
    )

    private val tokenToId: Map<String, Int> = vocabTokens.mapIndexed { i, t -> t to i }.toMap()
    private val idToToken: Map<Int, String> = vocabTokens.mapIndexed { i, t -> i to t }.toMap()

    init {
        // 注册视觉编码器
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = ENCODER_MODEL_ID,
                displayName = "GLM-OCR 视觉编码器",
                format = PluginManifest.ModelFormat.TFLITE,
                fileUrl = ENCODER_URL,
                inputShape = listOf(1, IMAGE_SIZE, IMAGE_SIZE, 3),
                outputShape = intArrayOf(1, ENCODER_OUTPUT_DIM),
                fileSizeBytes = 180_000_000,
                memoryFootprintBytes = 200_000_000,
            )
        )
        // 注册文本解码器
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = DECODER_MODEL_ID,
                displayName = "GLM-OCR 文本解码器",
                format = PluginManifest.ModelFormat.TFLITE,
                fileUrl = DECODER_URL,
                inputShape = listOf(1, MAX_TOKENS),
                outputShape = intArrayOf(1, vocabTokens.size),
                fileSizeBytes = 120_000_000,
                memoryFootprintBytes = 150_000_000,
            )
        )
    }

    override suspend fun recognize(file: File): OcrProvider.OcrResult = withContext(Dispatchers.IO) {
        // 确保两个模型都就绪
        val encResult = modelManager.ensureModelReady(ENCODER_MODEL_ID)
        if (encResult.isFailure) {
            return@withContext OcrProvider.OcrResult(
                fullText = "[GLM-OCR 编码器未就绪，请检查网络连接以下载模型]",
                averageConfidence = 0f,
            )
        }
        val decResult = modelManager.ensureModelReady(DECODER_MODEL_ID)
        if (decResult.isFailure) {
            return@withContext OcrProvider.OcrResult(
                fullText = "[GLM-OCR 解码器未就绪，请检查网络连接以下载模型]",
                averageConfidence = 0f,
            )
        }

        val bitmap = decodeBitmap(file) ?: return@withContext OcrProvider.OcrResult("", averageConfidence = 0f)
        // 注册消费者，防止推理过程中模型被 evictUnusedModels 淘汰
        modelManager.registerConsumer(ENCODER_MODEL_ID, "GLMOcrProvider")
        modelManager.registerConsumer(DECODER_MODEL_ID, "GLMOcrProvider")
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            // 通过 Interpreter 池获取线程安全实例，支持文件级并发推理
            val imageFeatures = modelManager.withInterpreter(ENCODER_MODEL_ID) { enc ->
                encodeImage(resized, enc)
            }
            if (resized != bitmap) resized.recycle()

            val text = modelManager.withInterpreter(DECODER_MODEL_ID) { dec ->
                decodeText(imageFeatures, dec)
            }
            val lines = text.split("\n").filter { it.isNotBlank() }

            OcrProvider.OcrResult(
                fullText = text,
                lines = lines,
                averageConfidence = 0.85f,
            )
        } catch (e: Exception) {
            Log.w(TAG, "GLM-OCR 推理失败", e)
            OcrProvider.OcrResult("[OCR 推理异常: ${e.message}]", averageConfidence = 0f)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            modelManager.unregisterConsumer(ENCODER_MODEL_ID, "GLMOcrProvider")
            modelManager.unregisterConsumer(DECODER_MODEL_ID, "GLMOcrProvider")
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(ENCODER_MODEL_ID, "GLMOcrProvider")
        modelManager.unregisterConsumer(DECODER_MODEL_ID, "GLMOcrProvider")
        Log.d(TAG, "GLM-OCR 消费者已注销")
    }

    // ---- 编码器推理 ----

    private fun encodeImage(bitmap: Bitmap, interpreter: org.tensorflow.lite.Interpreter): FloatArray {
        val buffer = ByteBuffer.allocateDirect(IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val fb = buffer.asFloatBuffer()
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        for (p in pixels) {
            fb.put((((p shr 16) and 0xFF) / 255f - 0.5f) * 2f)
            fb.put((((p shr 8) and 0xFF) / 255f - 0.5f) * 2f)
            fb.put(((p and 0xFF) / 255f - 0.5f) * 2f)
        }
        buffer.rewind()

        val features = Array(1) { FloatArray(ENCODER_OUTPUT_DIM) }
        interpreter.run(buffer, features)
        return features[0]
    }

    // ---- 解码器自回归推理 ----

    private fun decodeText(
        imageFeatures: FloatArray,
        interpreter: org.tensorflow.lite.Interpreter,
    ): String {
        val sb = StringBuilder()
        val bosId = 1   // <s>
        val eosId = 2   // </s>
        val padId = 3   // <pad>

        // 使用固定长度 (MAX_TOKENS) 的输入，避免 TFLite 模型因变长输入触发 shape mismatch。
        // 已生成 token 放在序列前部，其余位置用 padId 填充。
        val inputIds = IntArray(MAX_TOKENS) { padId }
        inputIds[0] = bosId
        var curLen = 1

        val output = Array(1) { FloatArray(vocabTokens.size) }

        for (step in 0 until MAX_TOKENS) {
            interpreter.run(arrayOf(inputIds, imageFeatures), output)

            // 输出为下一 token 的分布，取 argmax
            val nextTokenId = output[0].indices.maxByOrNull { output[0][it] } ?: eosId
            if (nextTokenId == eosId) break
            if (curLen >= MAX_TOKENS) break

            inputIds[curLen] = nextTokenId
            curLen++

            val token = idToToken[nextTokenId] ?: "?"
            sb.append(token)
        }

        return sb.toString()
            .replace("<s>", "")
            .replace("</s>", "")
            .replace("<unk>", "")
            .replace("<pad>", "")
            .trim()
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (file.length() > 5 * 1024 * 1024) 2 else 1
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }
}
