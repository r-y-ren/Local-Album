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
    // 修复 SIGSEGV：依赖 FaceStage 避免多个 ONNX Runtime session 并发推理。
    // 原 Layer 0 中 FaceStage[ONNX] + OcrStage[ONNX] 同时执行 session.run 时
    // 触发原生内存冲突（emutls/线程安全），导致段错误闪退。
    // 串行化 ONNX 阶段后：Layer 0 = Face+Scene[TFLite]+Quality+Geo，Layer 1 = Ocr。
    override val dependencies = listOf(AnalysisStage.STAGE_FACE)
    // 字典从 ppocr_keys_v1.txt (6623) 升级到 ppocrv5_dict.txt (18383)，bump 版本号强制重跑
    override val modelVersion: Int = 2

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
        // 修复：使用 setOcrText 仅写入 ocrText 字段，避免覆盖同层并行执行的
        // SceneStage/QualityStage 已写入的 sceneType/qualityScore（DAG Layer 0 竞态）。
        //
        // 并发度限制：concurrency=1，因为 PaddleOCR 检测+识别两个 ONNX 模型
        // 在多文件并发 session.run 时与 InsightFace 的 ONNX session 产生竞态，
        // 触发 libonnxruntime.so 内部 SIGSEGV（ARM MTE use-after-free）。
        // 串行化 OCR 推理避免此问题，OCR 阶段耗时原本就由模型推理主导。
        val results = ParallelFileProcessor.mapParallel(
            filePaths, enhancedCallback, concurrency = 1,
        ) { path ->
            val result = ocrProvider.recognize(File(path))
            val ocrText = if (result.fullText.isNotBlank()) result.fullText.take(500) else null
            mediaDao.setOcrText(path, ocrText)
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
