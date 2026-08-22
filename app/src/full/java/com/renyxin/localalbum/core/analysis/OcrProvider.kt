package com.renyxin.localalbum.core.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 文字识别提供器，封装 ML Kit Text Recognition。
 *
 * 同时支持拉丁语系 (TextRecognizerOptions.DEFAULT)
 * 与中文 (ChineseTextRecognizerOptions) 识别。
 */
class OcrProvider(private val context: Context) {

    /**
     * OCR 识别结果。
     */
    data class OcrResult(
        /** 识别的全部文字 */
        val fullText: String,
        /** 文本行列表 */
        val lines: List<String>,
        /** 文本块列表 */
        val blocks: List<TextBlock>,
        /** 置信度估算 (0 - 1) */
        val averageConfidence: Float,
    )

    data class TextBlock(
        val text: String,
        val lines: List<String>,
        val confidence: Float,
    )

    /**
     * 拉丁语系识别器 (懒加载)。
     */
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 中文识别器 (懒加载，开销较大)。
     */
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 对图像文件执行 OCR 识别。
     *
     * @param file 图像文件
     * @param preferChinese 是否优先使用中文识别器（默认 false 用拉丁）
     */
    suspend fun recognize(file: File, preferChinese: Boolean = false): OcrResult? =
        withContext(Dispatchers.IO) {
            val bitmap = decodeBitmap(file) ?: return@withContext null
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val recognizer = if (preferChinese) chineseRecognizer else latinRecognizer

                val visionText = suspendCancellableCoroutine { cont ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { result -> cont.resume(result) }
                        .addOnFailureListener { e ->
                            // 中文识别器失败时降级为拉丁
                            if (preferChinese && e.message?.contains("not downloaded") == true) {
                                latinRecognizer.process(inputImage)
                                    .addOnSuccessListener { result -> cont.resume(result) }
                                    .addOnFailureListener { cont.resumeWithException(it) }
                            } else {
                                cont.resumeWithException(e)
                            }
                        }
                }

                val lines = visionText.textBlocks.flatMap { block ->
                    block.lines.map { it.text }
                }
                val blocks = visionText.textBlocks.map { block ->
                    TextBlock(
                        text = block.text,
                        lines = block.lines.map { it.text },
                        confidence = block.lines.map { it.confidence }.average().toFloat(),
                    )
                }
                val avgConfidence = if (blocks.isNotEmpty()) {
                    blocks.map { it.confidence }.average().toFloat()
                } else 0f

                OcrResult(
                    fullText = visionText.text,
                    lines = lines,
                    blocks = blocks,
                    averageConfidence = avgConfidence,
                )
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 批量 OCR 识别。
     */
    suspend fun recognizeBatch(
        files: List<File>,
        preferChinese: Boolean = false,
    ): Map<String, OcrResult> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, OcrResult>()
        for (file in files) {
            val result = recognize(file, preferChinese)
            if (result != null) {
                results[file.absolutePath] = result
            }
        }
        results
    }

    private fun decodeBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var sampleSize = 1
        while (options.outWidth / sampleSize > MAX_RECOGNITION_DIMENSION
            || options.outHeight / sampleSize > MAX_RECOGNITION_DIMENSION
        ) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    companion object {
        /** 识别图片最大边长，控制内存和耗时 */
        const val MAX_RECOGNITION_DIMENSION = 1024
    }
}