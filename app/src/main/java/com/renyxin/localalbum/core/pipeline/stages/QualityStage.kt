package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * 质量评分阶段（Phase 2 重构）。
 *
 * 通过注入 [QualityProvider] 替代硬编码的 [com.renyxin.localalbum.core.analysis.QualityAnalyzer]，
 * 使该阶段可切换不同的质量评估模型（启发式 / NIMA 等）。
 * 结果写入 [MediaDao]。
 */
class QualityStage(
    private val qualityProvider: QualityProvider,
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "core:quality"
    override val stageType = StageType.BUILTIN
    override val displayName = "质量评分"
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
        // 文件级并行质量评估
        val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
            val result = qualityProvider.assess(File(path))
            mediaDao.setQualityScore(path, result.overall)
        }
        val success = results.count { it.success }
        val failed = results.count { !it.success }
        results.filter { !it.success }.forEach {
            Log.w("QualityStage", "质量分析失败: ${it.path}", it.error)
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = mapOf("providerId" to qualityProvider.providerId),
        )
    }
}
