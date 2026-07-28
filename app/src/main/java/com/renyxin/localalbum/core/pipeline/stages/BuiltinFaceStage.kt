package com.renyxin.localalbum.core.pipeline.stages

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.analysis.FaceClusterer
import com.renyxin.localalbum.core.analysis.FaceDetector
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import java.io.File

/**
 * 内置人脸检测与聚类阶段适配器。
 *
 * 将 [FaceDetector] + [FaceClusterer] 封装为 [AnalysisStage]。
 * 工作流：清除旧记录 → 分批检测人脸 → 写入 faces 表 → DBSCAN 聚类 → 回写 clusterId。
 */
class BuiltinFaceStage(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
) : AnalysisStage {

    override val stageId = "builtin:face"
    override val stageType = StageType.BUILTIN
    override val displayName = "人脸识别"
    override val dependencies = emptyList<String>()

    private val faceDetector = FaceDetector(context)
    private val faceClusterer = FaceClusterer()

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
        // P0-5: 使用单一计数器，避免 failed + processedPaths.size 双重计算
        var processed = 0
        var failed = 0

        try {
            enhancedCallback(0, total, null, null)

            // 1. 清除旧的人脸记录
            faceDao.deleteByFilePaths(filePaths)

            // 2. 并发检测人脸（文件级并行，充分利用多核）
            val allFaceEntities = mutableListOf<FaceEntity>()
            val results = ParallelFileProcessor.mapParallel(filePaths, enhancedCallback) { path ->
                val detection = faceDetector.detect(File(path))
                detection.faces.map { face ->
                    FaceEntity(
                        filePath = path,
                        embedding = faceClusterer.serializeEmbedding(face.embedding),
                        boxLeft = face.box.left,
                        boxTop = face.box.top,
                        boxRight = face.box.right,
                        boxBottom = face.box.bottom,
                        detectedAtMs = System.currentTimeMillis(),
                        // P1-A: 使用照片路径作为人脸缩略图引用
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
                    Log.w("BuiltinFace", "人脸检测失败: ${r.path}", r.error)
                }
            }
            enhancedCallback(processed, total, null, null)

            // 3. 写入人脸记录
            if (allFaceEntities.isNotEmpty()) {
                faceDao.insertFaces(allFaceEntities)
                faceCount = allFaceEntities.size
            }

            // 4. 执行 DBSCAN 聚类
            var clusterCount = 0
            var noiseCount = 0
            if (faceCount > 0) {
                faceDao.clearAllClusterIds()
                val allFaces = faceDao.getAll()

                // 修复：聚类前清空所有含人脸照片的 media_items.faceClusterId，
                // 使变为 noise 的照片不残留旧 clusterId，保证 faces 表与 media_items 表一致
                val allFacePaths = allFaces.map { it.filePath }.distinct()
                if (allFacePaths.isNotEmpty()) {
                    allFacePaths.chunked(500).forEach { chunk -> mediaDao.clearFaceClusterId(chunk) }
                }

                val samples = allFaces.map { face ->
                    FaceClusterer.FaceSample(
                        id = face.faceId,
                        embedding = faceClusterer.parseEmbedding(face.embedding),
                    )
                }

                val clusterResult = faceClusterer.cluster(samples)

                for (cluster in clusterResult.clusters) {
                    faceDao.updateClusterIds(cluster.sampleIds, cluster.clusterId)
                    for (sampleId in cluster.sampleIds) {
                        val face = allFaces.find { it.faceId == sampleId } ?: continue
                        mediaDao.setFaceClusterId(face.filePath, cluster.clusterId)
                    }
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
                ),
            )
        } catch (e: Exception) {
            Log.w("BuiltinFace", "人脸检测阶段失败", e)
            return StageResult(successCount = 0, failedCount = total)
        }
    }
}