package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * OCR 文字识别阶段（Phase 2 重构）。
 *
 * 通过注入 [OcrProvider] 替代硬编码的旧版 OcrProvider，
 * 使该阶段可切换不同的 OCR 引擎（MLKit / PaddleOCR / Tesseract 等）。
 * 结果写入 [MediaDao]。
 */
class OcrStage(
    private val ocrProvider: OcrProvider,
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "core:ocr"
    override val stageType = StageType.BUILTIN
    override val displayName = "文字识别"
    override val dependencies = emptyList<String>()

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
            val result = ocrProvider.recognize(File(path))
            val ocrText = if (result.fullText.isNotBlank()) result.fullText.take(500) else null
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
            Log.w("OcrStage", "OCR 识别失败: ${it.path}", it.error)
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = mapOf(
                "textFound" to textCount.toString(),
                "providerId" to ocrProvider.providerId,
            ),
        )
    }
}
