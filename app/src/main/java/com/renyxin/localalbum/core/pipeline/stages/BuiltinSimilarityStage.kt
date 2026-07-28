package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.SimilarityAnalyzer
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * 内置相似度分析阶段适配器。
 *
 * 将 [SimilarityAnalyzer] 封装为 [AnalysisStage]，使用感知哈希计算图片相似度并分组。
 * 依赖 `builtin:quality`，因为在全量分析中需等待质量评分写入后再执行相似度运算。
 */
class BuiltinSimilarityStage(
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "builtin:similarity"
    override val stageType = StageType.BUILTIN
    override val displayName = "相似度分析"
    // 依赖 QualityStage 的 stageId（"core:quality"）。原值 "builtin:quality" 是旧版
    // BuiltinQualityStage 的 id，Phase 2 重构后已不存在，导致依赖无法解析、相似度阶段
    // 被错误地与质量阶段同层并行，读到未写入的（零）质量分数。
    override val dependencies = listOf(AnalysisStage.STAGE_QUALITY)
    override val isCacheable = false

    private val analyzer = SimilarityAnalyzer()

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
        var success = 0
        var failed = 0

        try {
            val files = filePaths.map { File(it) }.filter { it.exists() }
            if (files.isEmpty()) {
                return StageResult(successCount = 0, failedCount = filePaths.size)
            }
            enhancedCallback(0, total, null, null)

            val hashes = analyzer.computeHashes(files)
            val groups = analyzer.groupSimilar(hashes)

            for (group in groups) {
                for (path in group.filePaths) {
                    try {
                        enhancedCallback(success + failed, total, path, FileProcessingStatus.PROCESSING)
                        mediaDao.setSimilarGroupId(path, group.groupId)
                        success++
                        enhancedCallback(success + failed, total, path, FileProcessingStatus.COMPLETED)
                    } catch (e: Exception) {
                        failed++
                        enhancedCallback(success + failed, total, path, FileProcessingStatus.FAILED)
                    }
                }
            }
            enhancedCallback(total, total, null, null)
        } catch (e: Exception) {
            Log.w("BuiltinSimilarity", "相似度分析失败", e)
            failed = total
        }

        return StageResult(successCount = success, failedCount = failed)
    }
}