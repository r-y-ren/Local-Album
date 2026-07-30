package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.FaceClusterer
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
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
    private val featureStoreDao: FeatureStoreDao? = null, // Phase 5: 可选注入
) : AnalysisStage {

    override val stageId = "core:face"
    override val stageType = StageType.BUILTIN
    override val displayName = "人脸识别"
    override val dependencies = emptyList<String>()

    private val faceClusterer = FaceClusterer()

    companion object {
        /** 聚类时分页读取人脸，避免大型媒体库一次复制全部人脸实体。 */
        private const val CLUSTER_PAGE_SIZE = 500
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

            // 4. 执行 DBSCAN 聚类
            var clusterCount = 0
            var noiseCount = 0
            if (faceCount > 0) {
                faceDao.clearAllClusterIds()
                val allFaces = loadFacesForClustering()

                // 聚类前清空所有含人脸照片的 media_items.faceClusterId，使 noise 不残留旧聚类。
                allFaces.asSequence().map { it.filePath }.distinct()
                    .toList().chunked(DATABASE_BATCH_SIZE)
                    .forEach { chunk -> mediaDao.clearFaceClusterId(chunk) }

                val samples = allFaces.map { face ->
                    FaceClusterer.FaceSample(
                        id = face.faceId,
                        embedding = faceClusterer.parseEmbedding(face.embedding),
                    )
                }

                val clusterResult = faceClusterer.cluster(samples)
                val facesById = allFaces.associateBy { it.faceId }

                for (cluster in clusterResult.clusters) {
                    cluster.sampleIds.chunked(DATABASE_BATCH_SIZE).forEach { ids ->
                        faceDao.updateClusterIds(ids, cluster.clusterId)
                    }
                    // 同一照片可能出现多张同一人物的人脸；路径去重后一次更新，
                    // 既保证列表/详情一致，也移除原 O(聚类样本数 × 全部人脸数) 的线性查找。
                    cluster.sampleIds.asSequence().mapNotNull { facesById[it]?.filePath }.distinct()
                        .toList().chunked(DATABASE_BATCH_SIZE)
                        .forEach { paths -> mediaDao.updateFaceClusterId(paths, cluster.clusterId) }
                }
                clusterCount = clusterResult.clusterCount
                noiseCount = clusterResult.noiseCount
            }

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

    private suspend fun loadFacesForClustering(): List<FaceEntity> {
        val faces = mutableListOf<FaceEntity>()
        var offset = 0
        while (true) {
            val page = faceDao.getPaged(CLUSTER_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            faces.addAll(page)
            if (page.size < CLUSTER_PAGE_SIZE) break
            offset += page.size
        }
        return faces
    }
}
