package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.SemanticEmbedder
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import java.io.File

/**
 * 内置语义嵌入生成阶段适配器。
 *
 * 将 [SemanticEmbedder] 封装为 [AnalysisStage]，基于图像特征生成概念向量并写入 [EmbeddingDao]。
 */
class BuiltinSemanticStage(
    private val mediaDao: MediaDao,
    private val embeddingDao: EmbeddingDao,
) : AnalysisStage {

    override val stageId = "builtin:semantic"
    override val stageType = StageType.BUILTIN
    override val displayName = "语义索引"
    // P0-C: 语义嵌入依赖场景分类(sceneType)和文字识别(ocrText)的结果，
    // 必须在它们之后执行以获取高质量的概念向量
    override val dependencies = listOf("builtin:scene", "builtin:ocr")

    private val embedder = SemanticEmbedder()

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
        val embeddings = mutableListOf<MediaEmbedding>()
        var success = 0
        var failed = 0

        try {
            // 清除旧嵌入
            embeddingDao.deleteByFilePaths(filePaths)

            // 文件级并行语义嵌入
            val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
                val entity = mediaDao.getByFilePathLight(path)
                val vec = embedder.embedImage(File(path), entity)
                if (vec == null || vec.all { it == 0f }) {
                    null
                } else {
                    MediaEmbedding(
                        filePath = path,
                        embedding = embedder.serialize(vec),
                        modelVersion = SemanticEmbedder.MODEL_VERSION,
                        generatedAtMs = System.currentTimeMillis(),
                        source = "concept",
                    )
                }
            }
            for (r in results) {
                if (r.success && r.value != null) {
                    embeddings.add(r.value)
                    success++
                } else {
                    if (!r.success) Log.w("BuiltinSemantic", "语义嵌入失败: ${r.path}", r.error)
                    failed++
                }
            }

            if (embeddings.isNotEmpty()) {
                embeddingDao.insertEmbeddings(embeddings)
            }
        } catch (e: Exception) {
            Log.w("BuiltinSemantic", "语义嵌入阶段失败", e)
            return StageResult(successCount = 0, failedCount = total)
        }

        return StageResult(successCount = success, failedCount = failed)
    }
}