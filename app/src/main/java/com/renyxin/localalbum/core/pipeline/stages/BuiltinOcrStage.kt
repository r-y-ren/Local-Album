package com.renyxin.localalbum.core.pipeline.stages

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.analysis.OcrProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * 内置 OCR 文字识别阶段适配器。
 *
 * 将 [OcrProvider] 封装为 [AnalysisStage]，从图片中提取文字信息
 * （截取前 500 字），结果写入 [MediaDao]。
 * OCR 开销较大，默认仅在用户明确启用时执行。
 */
class BuiltinOcrStage(
    private val context: Context,
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "builtin:ocr"
    override val stageType = StageType.BUILTIN
    override val displayName = "文字识别"
    override val dependencies = emptyList<String>()

    private val ocrProvider = OcrProvider(context)

    override suspend fun execute(
        filePaths: List<String>,
        progressCallback: suspend (Int, Int) -> Unit,
    ): StageResult {
        return executeEnhanced(filePaths) { processed, total, _, _ ->
            progressCallback(processed, total)
        }
    }

    override suspend fun executeEnhanced(
        filePaths: List<String>,
        enhancedCallback: EnhancedProgressCallback,
    ): StageResult {
        val total = filePaths.size

        // 文件级并行 OCR 识别
        val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
            val result = ocrProvider.recognize(File(path), preferChinese = true)
            val ocrText = if (result != null && result.fullText.isNotBlank()) result.fullText.take(500) else null
            mediaDao.setAnalysisFields(
                path = path,
                0.0f,
                sceneType = "",
                ocrText = ocrText,
            )
            ocrText != null
        }
        val success = results.count { it.success }
        val failed = results.count { !it.success }
        val textCount = results.count { it.success && it.value == true }
        results.filter { !it.success }.forEach {
            Log.w("BuiltinOcr", "OCR 识别失败: ${it.path}", it.error)
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = mapOf("textFound" to textCount.toString()),
        )
    }
}