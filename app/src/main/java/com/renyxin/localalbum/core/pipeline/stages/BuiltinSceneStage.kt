package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.SceneClassifier
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.SceneTypeUpdate
import java.io.File

/**
 * 内置场景分类阶段适配器。
 *
 * 将 [SceneClassifier] 封装为 [AnalysisStage]，基于直方图、宽高比、饱和度等特征
 * 将图片分类为风景/食物/文档/宠物/自拍/夜景等类型，结果写入 [MediaDao]。
 */
class BuiltinSceneStage(
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "builtin:scene"
    override val stageType = StageType.BUILTIN
    override val displayName = "场景识别"
    override val dependencies = emptyList<String>()

    private val classifier = SceneClassifier()

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

        // Legacy adapter remains batch-safe even though the policy-aware factory no longer uses it.
        val results = ParallelFileProcessor.mapParallel(
            filePaths,
            enhancedCallback,
            metricOperation = "pipeline:file:$stageId",
        ) { path ->
            classifier.classify(File(path)).label.name.lowercase()
        }
        val updates = results.mapNotNull { result ->
            result.value?.takeIf { result.success }?.let { label ->
                SceneTypeUpdate(result.path, label)
            }
        }
        updates.chunked(MediaDao.ENHANCEMENT_WRITE_BATCH_SIZE).forEach { batch ->
            mediaDao.setSceneTypes(batch)
        }
        val success = results.count { it.success }
        val failed = results.count { !it.success }
        for (r in results) {
            if (r.success && r.value != null) {
                labelCounts[r.value] = (labelCounts[r.value] ?: 0) + 1
            } else if (!r.success) {
                Log.w("BuiltinScene", "场景分类失败: ${r.path}", r.error)
            }
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = labelCounts.mapValues { it.value.toString() } +
                ("databaseTransactions" to
                    updates.chunked(MediaDao.ENHANCEMENT_WRITE_BATCH_SIZE).size.toString()),
            failedPaths = results.filterNot { it.success }.mapTo(linkedSetOf()) { it.path },
        )
    }
}