package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.search.EmbeddingCodec
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.search.SemanticVectorSpace
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
import com.renyxin.localalbum.data.db.dao.SemanticDao
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import java.io.File

/**
 * 语义嵌入阶段（Phase 2/5 重构）。
 *
 * 结果双写到 [EmbeddingDao] + [FeatureStoreDao]（Phase 5）。
 *
 * 若场景/OCR 已就绪则使用其结果补充 [SemanticEmbedProvider.ImageContext]；
 * 图像向量本身不依赖这些可选字段，因此允许与它们并行执行以支持扫描中搜索。
 */
class SemanticStage(
    private val embedProvider: SemanticEmbedProvider,
    private val mediaDao: MediaDao,
    private val embeddingDao: EmbeddingDao,
    private val featureStoreDao: FeatureStoreDao? = null, // Phase 5: 可选注入
    private val semanticDao: SemanticDao? = null,
) : AnalysisStage {

    override val stageId = "core:semantic"
    override val stageType = StageType.BUILTIN
    override val displayName = "语义索引"
    // scene/OCR 仅是可选增强上下文，不能让语义索引等到两个全量阶段结束，
    // 否则大相册在扫描绝大部分时间里始终没有可搜索向量。
    override val dependencies = emptyList<String>()

    companion object {
        /** 模型版本号，Provider 升级后递增触发增量重建。
         *  v2: 默认 Provider 切换为 CLIP-ViT-bigG（1280 维），与概念向量（32 维）不兼容，需全量重建。
         *  v3: 默认 Provider 切换为 EVA02-CLIP-L-14（768 维），与 bigG（1280 维）不兼容，需全量重建。 */
        const val MODEL_VERSION = 3
        /** 每批完成后立即落库，使语义搜索可在扫描尚未结束时查询已分析照片。 */
        private const val INDEX_WRITE_BATCH_SIZE = 32
    }

    // EVA02 视觉模型固定单 session，外层并发大于 1 只会增加解码和等待开销。
    override val fileConcurrency = 1

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
        val space = SemanticVectorSpace.from(embedProvider)
        var success = 0
        var failed = 0

        try {
            // 清除旧嵌入
            embeddingDao.deleteByFilePaths(filePaths)

            // 分批推理并立即写入：此前会将整个扫描的向量保留到最后才 insert，
            // 所以即使首批图片已完成，搜索侧仍只能看到“尚未建立语义索引”。
            var completedBeforeBatch = 0
            for (pathsInBatch in filePaths.chunked(INDEX_WRITE_BATCH_SIZE)) {
                val batchOffset = completedBeforeBatch
                val results = ParallelFileProcessor.mapParallel(
                    pathsInBatch,
                    { processed, _, path, status ->
                        // ParallelFileProcessor 的计数在每个批次内从零开始；转换为整个语义阶段的
                        // 累计进度，避免 UI 在批次切换时回退或停留在首批进度。
                        enhancedCallback(batchOffset + processed, total, path, status)
                    },
                    concurrency = com.renyxin.localalbum.core.concurrent.AnalysisSchedulingRuntime
                        .effectiveStageConcurrency(stageId, fileConcurrency),
                ) { path ->
                    val entity = mediaDao.getByFilePathLight(path)
                    val context = SemanticEmbedProvider.ImageContext(
                        sceneType = entity?.sceneType,
                        ocrText = entity?.ocrText,
                        qualityScore = entity?.qualityScore ?: 0f,
                    )
                    val vec = embedProvider.embedImage(File(path), context)
                    if (vec == null || vec.all { it == 0f }) null else MediaEmbedding(
                        filePath = path,
                        // 保留 CSV 以兼容旧备份与 FeatureStore；搜索读取端优先走 BLOB。
                        embedding = serializeEmbedding(vec),
                        embeddingBlob = EmbeddingCodec.encode(vec),
                        modelVersion = embedProvider.modelVersion,
                        generatedAtMs = System.currentTimeMillis(),
                        source = embedProvider.providerId.split(":").firstOrNull() ?: "plugin",
                        providerId = space.providerId,
                        modelId = space.modelId,
                        dimension = space.dimension,
                        spaceId = space.spaceId,
                        generation = 1,
                        codecId = space.codecId,
                        formatVersion = space.formatVersion,
                    )
                }
                val batchEmbeddings = results.mapNotNull { result ->
                    if (!result.success) Log.w("SemanticStage", "语义嵌入失败: ${result.path}", result.error)
                    result.value
                }
                success += batchEmbeddings.size
                failed += results.size - batchEmbeddings.size
                if (batchEmbeddings.isNotEmpty()) {
                    embeddingDao.insertEmbeddings(batchEmbeddings)
                    embeddings.addAll(batchEmbeddings)
                }
                completedBeforeBatch += pathsInBatch.size
            }
            if (success > 0 && semanticDao != null) {
                val now = System.currentTimeMillis()
                semanticDao.upsertIndexMeta(
                    com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity(
                        spaceId = space.spaceId,
                        providerId = space.providerId,
                        modelId = space.modelId,
                        modelVersion = space.modelVersion,
                        dimension = space.dimension,
                        codecId = space.codecId,
                        formatVersion = space.formatVersion,
                        activeGeneration = 1,
                        isActive = true,
                        status = com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity.STATUS_READY,
                        vectorCount = embeddingDao.getCountInSpace(space.spaceId).toLong(),
                        indexedCount = embeddingDao.getCountInSpace(space.spaceId).toLong(),
                        deletedCount = 0,
                        indexKind = com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity.INDEX_EXACT,
                        builtAt = now,
                        updatedAt = now,
                    ),
                )
                semanticDao.activate(space.spaceId, now)
            }

            // Phase 5: 双写 FeatureStore。主语义索引已按批写入，双写失败不影响扫描中搜索。
            if (featureStoreDao != null && embeddings.isNotEmpty()) {
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
        return vec.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
    }
}
