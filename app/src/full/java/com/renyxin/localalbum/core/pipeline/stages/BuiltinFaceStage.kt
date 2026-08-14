package com.renyxin.localalbum.core.pipeline.stages

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.analysis.FaceClusterer
import com.renyxin.localalbum.core.analysis.FaceDetector
import com.renyxin.localalbum.core.analysis.IncrementalFaceClusterAssigner
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.ParallelFileProcessor
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import java.io.File

/**
 * 内置人脸检测与增量归类阶段适配器。
 *
 * 将 [FaceDetector] 封装为 [AnalysisStage]，并复用 [IncrementalFaceClusterAssigner]。
 * 常规扫描只比较已有人物代表向量；未命中的人脸进入 pending 临时簇，绝不执行全库 DBSCAN。
 */
class BuiltinFaceStage(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    faceClusterDao: FaceClusterDao,
) : AnalysisStage {

    override val stageId = "builtin:face"
    override val stageType = StageType.BUILTIN
    override val displayName = "人脸识别"
    override val dependencies = emptyList<String>()

    private val faceDetector = FaceDetector(context)
    private val faceClusterer = FaceClusterer()
    private val clusterAssigner = IncrementalFaceClusterAssigner(
        mediaDao = mediaDao,
        faceDao = faceDao,
        faceClusterDao = faceClusterDao,
        providerScope = BUILTIN_PROVIDER_SCOPE,
        modelScope = BUILTIN_MODEL_SCOPE,
        faceClusterer = faceClusterer,
    )

    companion object {
        private const val DATABASE_BATCH_SIZE = 500
        const val BUILTIN_PROVIDER_SCOPE = "builtin:face-detector"
        const val BUILTIN_MODEL_SCOPE = "builtin:face-embedding-v1"
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
        // P0-5: 使用单一计数器，避免 failed + processedPaths.size 双重计算
        var processed = 0
        var failed = 0

        try {
            enhancedCallback(0, total, null, null)

            // 1. 只清除本批旧记录及照片级聚类引用，不影响历史人物簇。
            filePaths.chunked(DATABASE_BATCH_SIZE).forEach { chunk ->
                faceDao.deleteByFilePaths(chunk)
                mediaDao.clearFaceClusterId(chunk)
            }

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

            // 4. 有界增量归类：不读取历史人脸全集，不清空已有簇，不运行 DBSCAN。
            val assignment = if (faceCount > 0) {
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
                ),
                failedPaths = results.filterNot { it.success }.mapTo(linkedSetOf()) { it.path },
            )
        } catch (e: Exception) {
            Log.w("BuiltinFace", "人脸检测阶段失败", e)
            return StageResult(successCount = 0, failedCount = total, failedPaths = filePaths.toSet())
        }
    }
}