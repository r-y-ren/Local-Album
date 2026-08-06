package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.QualityAnalyzer
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * 内置质量评分阶段适配器。
 *
 * 将 [QualityAnalyzer] 封装为 [AnalysisStage]，对每张图片进行多维度质量评估
 * （模糊度、曝光、对比度、色彩），结果写入 [MediaDao]。
 */
class BuiltinQualityStage(
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "builtin:quality"
    override val stageType = StageType.BUILTIN
    override val displayName = "质量评分"
    override val dependencies = emptyList<String>()

    private val analyzer = QualityAnalyzer()

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
        // 文件级并行质量评估
        val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
            val result = analyzer.analyze(File(path))
            mediaDao.setQualityScore(path, result.overall)
        }
        val success = results.count { it.success }
        val failed = results.count { !it.success }
        results.filter { !it.success }.forEach {
            Log.w("BuiltinQuality", "质量分析失败: ${it.path}", it.error)
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            failedPaths = results.filterNot { it.success }.mapTo(linkedSetOf()) { it.path },
        )
    }
}