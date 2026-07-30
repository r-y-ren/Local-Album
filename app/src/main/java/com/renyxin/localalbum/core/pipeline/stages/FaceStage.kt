package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.FaceClusterer
import com.renyxin.localalbum.core.analysis.IncrementalFaceClusterAssigner
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import java.io.File

/**
 * 人脸检测与聚类阶段（Phase 2/5 重构）。
 *
 * 工作流：清除旧记录 → 逐图检测人脸 + 提取嵌入 → 双写 faces 表 + feature_store 表 → DBSCAN 聚类。
 *
 * Phase 5: 双写到 [FeatureStoreDao]（featureType="face"），
 * 为后续统一特征存储迁移做准备。
 */
class FaceStage(
    private val faceProvider: FaceProvider,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    faceClusterDao: FaceClusterDao,
    private val featureStoreDao: FeatureStoreDao? = null, // Phase 5: 可选注入
) : AnalysisStage {

    override val stageId = "core:face"
    override val stageType = StageType.BUILTIN
    override val displayName = "人脸识别"
    override val dependencies = emptyList<String>()

    private val faceClusterer = FaceClusterer()
    private val clusterAssigner = IncrementalFaceClusterAssigner(
        mediaDao = mediaDao,
        faceDao = faceDao,
        faceClusterDao = faceClusterDao,
        providerScope = faceProvider.providerId,
        modelScope = "dim:${faceProvider.embeddingDim}",
        faceClusterer = faceClusterer,
    )

    companion object {
        /** SQL IN 参数安全上限。 */
        private const val DATABASE_BATCH_SIZE = 500
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
        var faceCount = 0
        var processed = 0
        var failed = 0

        try {
            enhancedCallback(0, total, null, null)

            // 1. 清除旧的人脸记录，并同步清空这些照片的 media_items.faceClusterId，
            //    避免不再含人脸的照片残留旧聚类 ID（faces 表与 media_items 表不一致）
            filePaths.chunked(DATABASE_BATCH_SIZE).forEach { chunk ->
                faceDao.deleteByFilePaths(chunk)
                mediaDao.clearFaceClusterId(chunk)
            }

            // 2. 并发检测人脸（文件级并行，充分利用多核）
            val allFaceEntities = mutableListOf<FaceEntity>()
            val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
                val faces = faceProvider.detectFaces(File(path))
                faces.map { face ->
                    FaceEntity(
                        filePath = path,
                        embedding = faceClusterer.serializeEmbedding(face.embedding),
                        boxLeft = face.box.left,
                        boxTop = face.box.top,
                        boxRight = face.box.right,
                        boxBottom = face.box.bottom,
                        detectedAtMs = System.currentTimeMillis(),
                        thumbnailPath = path,
                    )
                }
            }
            for (r in results) {
                if (r.success) {
                    processed++
                    r.value?.let { allFaceEntities.addAll(it) }
                } else {
                    failed++
                    processed++
                    Log.w("FaceStage", "人脸检测失败: ${r.path}", r.error)
                }
            }
            enhancedCallback(processed, total, null, null)

            // 3. 写入人脸记录
            if (allFaceEntities.isNotEmpty()) {
                faceDao.insertFaces(allFaceEntities)
                faceCount = allFaceEntities.size

                // Phase 5: 双写到 FeatureStoreDao
                if (featureStoreDao != null) {
                    try {
                        featureStoreDao.deleteByPluginAndFilePaths("core:face", filePaths)
                        val schemaJson = PluginJsonCodec.schemaToJsonObj(
                            FeatureSchema(
                                pluginId = "core:face",
                                featureType = "face",
                                fields = listOf(
                                    FeatureSchema.FieldSpec("embedding", FeatureSchema.DataType.FLOAT_ARRAY, listOf(faceProvider.embeddingDim)),
                                    FeatureSchema.FieldSpec("box_left", FeatureSchema.DataType.FLOAT),
                                    FeatureSchema.FieldSpec("box_top", FeatureSchema.DataType.FLOAT),
                                    FeatureSchema.FieldSpec("box_right", FeatureSchema.DataType.FLOAT),
                                    FeatureSchema.FieldSpec("box_bottom", FeatureSchema.DataType.FLOAT),
                                ),
                                modelVersion = 1,
                            ),
                        ).toString()
                        val now = System.currentTimeMillis()
                        val featureEntities = allFaceEntities.map { face ->
                            val dataJson = """{"embedding":"${face.embedding}","box_left":${face.boxLeft},"box_top":${face.boxTop},"box_right":${face.boxRight},"box_bottom":${face.boxBottom}}"""
                            FeatureStoreEntity(
                                filePath = face.filePath,
                                pluginId = "core:face",
                                featureType = "face",
                                dataJson = dataJson,
                                schemaJson = schemaJson,
                                modelVersion = 1,
                                generatedAtMs = now,
                            )
                        }
                        featureStoreDao.insertAll(featureEntities)
                        Log.d("FaceStage", "双写到 feature_store: ${featureEntities.size} 条人脸记录")
                    } catch (e: Exception) {
                        Log.w("FaceStage", "FeatureStore 双写失败（不影响主流程）", e)
                    }
                }
            }

            // 4. 与内置回退路径复用同一个有界增量归类服务。常规扫描不运行 DBSCAN；
            // 未命中代表向量的人脸进入 pending 临时簇，等待显式人物维护任务处理。
            val assignment = if (allFaceEntities.isNotEmpty()) {
                clusterAssigner.assign(filePaths)
            } else {
                IncrementalFaceClusterAssigner.AssignmentResult(0, 0)
            }
            val clusterCount = assignment.matchedClusterCount
            val noiseCount = assignment.pendingFaceCount

            enhancedCallback(total, total, null, null)

            return StageResult(
                successCount = total - failed,
                failedCount = failed,
                extra = mapOf(
                    "faceCount" to faceCount.toString(),
                    "clusterCount" to clusterCount.toString(),
                    "noiseCount" to noiseCount.toString(),
                    "providerId" to faceProvider.providerId,
                ),
            )
        } catch (e: Exception) {
            Log.w("FaceStage", "人脸检测阶段失败", e)
            return StageResult(successCount = 0, failedCount = total)
        }
    }

}
