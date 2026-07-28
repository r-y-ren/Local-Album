package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import java.io.File

/**
 * 语义嵌入阶段（Phase 2/5 重构）。
 *
 * 结果双写到 [EmbeddingDao] + [FeatureStoreDao]（Phase 5）。
 *
 * 依赖 [SceneStage] 和 [OcrStage] 的输出作为 [SemanticEmbedProvider.ImageContext]。
 */
class SemanticStage(
    private val embedProvider: SemanticEmbedProvider,
    private val mediaDao: MediaDao,
    private val embeddingDao: EmbeddingDao,
    private val featureStoreDao: FeatureStoreDao? = null, // Phase 5: 可选注入
) : AnalysisStage {

    override val stageId = "core:semantic"
    override val stageType = StageType.BUILTIN
    override val displayName = "语义索引"
    override val dependencies = listOf("core:scene", "core:ocr")

    companion object {
        /** 模型版本号，Provider 升级后递增触发增量重建。
         *  v2: 默认 Provider 切换为 CLIP-ViT-bigG（1280 维），与概念向量（32 维）不兼容，需全量重建。
         *  v3: 默认 Provider 切换为 EVA02-CLIP-L-14（768 维），与 bigG（1280 维）不兼容，需全量重建。 */
        const val MODEL_VERSION = 3
    }

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
                // 从数据库获取已分析的上下文信息辅助嵌入
                val entity = mediaDao.getByFilePathLight(path)
                val context = SemanticEmbedProvider.ImageContext(
                    sceneType = entity?.sceneType,
                    ocrText = entity?.ocrText,
                    qualityScore = entity?.qualityScore ?: 0f,
                )
                val vec = embedProvider.embedImage(File(path), context)
                if (vec == null || vec.all { it == 0f }) {
                    null
                } else {
                    MediaEmbedding(
                        filePath = path,
                        embedding = serializeEmbedding(vec),
                        modelVersion = MODEL_VERSION,
                        generatedAtMs = System.currentTimeMillis(),
                        source = embedProvider.providerId.split(":").firstOrNull() ?: "plugin",
                    )
                }
            }
            for (r in results) {
                if (r.success && r.value != null) {
                    embeddings.add(r.value)
                    success++
                } else {
                    if (!r.success) Log.w("SemanticStage", "语义嵌入失败: ${r.path}", r.error)
                    failed++
                }
            }

            if (embeddings.isNotEmpty()) {
                embeddingDao.insertEmbeddings(embeddings)

                // Phase 5: 双写到 FeatureStoreDao
                if (featureStoreDao != null) {
                    try {
                        featureStoreDao.deleteByPluginAndFilePaths("core:semantic", filePaths)
                        val schemaJson = PluginJsonCodec.schemaToJsonObj(
                            FeatureSchema(
                                pluginId = "core:semantic",
                                featureType = "semantic",
                                fields = listOf(
                                    FeatureSchema.FieldSpec("embedding", FeatureSchema.DataType.FLOAT_ARRAY, listOf(embedProvider.embeddingDim)),
                                ),
                                modelVersion = MODEL_VERSION,
                            ),
                        ).toString()
                        val now = System.currentTimeMillis()
                        val featureEntities = embeddings.map { emb ->
                            FeatureStoreEntity(
                                filePath = emb.filePath,
                                pluginId = "core:semantic",
                                featureType = "semantic",
                                dataJson = """{"embedding":"${emb.embedding}","dim":${embedProvider.embeddingDim}}""",
                                schemaJson = schemaJson,
                                modelVersion = MODEL_VERSION,
                                generatedAtMs = now,
                            )
                        }
                        featureStoreDao.insertAll(featureEntities)
                        Log.d("SemanticStage", "双写到 feature_store: ${featureEntities.size} 条语义嵌入")
                    } catch (e: Exception) {
                        Log.w("SemanticStage", "FeatureStore 双写失败（不影响主流程）", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SemanticStage", "语义嵌入阶段失败", e)
            return StageResult(successCount = 0, failedCount = total)
        }

        return StageResult(
            successCount = success,
            failedCount = failed,
            extra = mapOf("providerId" to embedProvider.providerId),
        )
    }

    /**
     * 将嵌入向量序列化为逗号分隔的字符串（用于数据库存储）。
     */
    private fun serializeEmbedding(vec: FloatArray): String {
        return vec.joinToString(",") { "%.6f".format(it) }
    }
}
