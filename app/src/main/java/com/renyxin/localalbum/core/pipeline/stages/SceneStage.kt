package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File

/**
 * 场景分类阶段（Phase 2 重构）。
 *
 * 通过注入 [SceneProvider] 替代硬编码的 [com.renyxin.localalbum.core.analysis.SceneClassifier]，
 * 使该阶段可切换不同的场景分类模型（启发式 / EfficientNet / CLIP 等）。
 * 结果写入 [MediaDao]。
 */
class SceneStage(
    private val sceneProvider: SceneProvider,
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "core:scene"
    override val stageType = StageType.BUILTIN
    override val displayName = "场景识别"
    override val dependencies = emptyList<String>()
    override val fileConcurrency = 2

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
        val labelCounts = mutableMapOf<String, Int>()

        // 与 TFLite Interpreter 池容量匹配，避免无收益的协程和 Bitmap 排队。
        val results = ParallelFileProcessor.mapParallel(
            filePaths,
            enhancedCallback,
            concurrency = com.renyxin.localalbum.core.concurrent.AnalysisSchedulingRuntime
                .effectiveStageConcurrency(stageId, fileConcurrency),
        ) { path ->
            val result = sceneProvider.classify(File(path))
            mediaDao.setSceneType(path, result.topLabel.lowercase())
            result.topLabel
        }
        val success = results.count { it.success }
        val failed = results.count { !it.success }
        for (r in results) {
            if (r.success && r.value != null) {
                labelCounts[r.value] = (labelCounts[r.value] ?: 0) + 1
            } else if (!r.success) {
                Log.w("SceneStage", "场景分类失败: ${r.path}", r.error)
            }
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = labelCounts.mapValues { it.value.toString() } + ("providerId" to sceneProvider.providerId),
        )
    }
}
