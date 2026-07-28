package com.renyxin.localalbum.core.analysis

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.analysis.SceneClassifier.SceneLabel
import com.renyxin.localalbum.core.analysis.SimilarityAnalyzer.SimilarGroup
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 智能分析管道：编排 QualityAnalyzer, SimilarityAnalyzer,
 * SceneClassifier, OcrProvider, FaceDetector, SemanticEmbedder 对索引完的媒体进行批量分析。
 *
 * 工作流:
 * 1. 将待分析文件路径分成批量 (BATCH_SIZE)
 * 2. 并行运行: 质量评分 + 场景分类 + OCR
 * 3. 批量写入分析结果到数据库
 * 4. 完成所有批次后运行相似度分析（需要感知哈希聚合）
 * 5. 完成所有批次后运行人脸检测 + DBSCAN 聚类（Phase 3.3）
 * 6. 完成所有批次后运行语义嵌入生成（Phase 4.1）
 */
class AnalysisPipeline(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: EmbeddingDao? = null,
) {
    companion object {
        private const val TAG = "AnalysisPipeline"
        private const val BATCH_SIZE = 32
        /** 人脸检测批次大小（ML Kit 较重，减小批次） */
        private const val FACE_BATCH_SIZE = 16
        /** 语义嵌入批次大小（图像采样较重，减小批次） */
        private const val EMBEDDING_BATCH_SIZE = 16
    }

    private val qualityAnalyzer = QualityAnalyzer()
    private val sceneClassifier = SceneClassifier()
    private val ocrProvider = OcrProvider(context)
    private val similarityAnalyzer = SimilarityAnalyzer()
    private val faceDetector: FaceDetector? by lazy {
        if (faceDao != null) FaceDetector(context) else null
    }
    private val faceClusterer = FaceClusterer()
    private val geoClusterer = GeoClusterer()
    private val semanticEmbedder: SemanticEmbedder? by lazy {
        if (embeddingDao != null) SemanticEmbedder() else null
    }

    /**
     * 分析结果汇总。
     */
    data class AnalysisResult(
        val qualityScores: MutableMap<String, Float> = mutableMapOf(),
        val sceneLabels: MutableMap<String, SceneLabel> = mutableMapOf(),
        val ocrTexts: MutableMap<String, String> = mutableMapOf(),
        val analyzedCount: Int = 0,
        val failedCount: Int = 0,
    )

    /**
     * 对新扫描的文件路径列表执行完整的分析管道。
     *
     * @param filePaths 待分析的文件路径列表
     * @param enableOcr 是否启用 OCR (默认 false，OCR 开销大)
     * @return AnalysisResult 含各维度分析结果
     */
    suspend fun analyzeNewFiles(
        filePaths: List<String>,
        enableOcr: Boolean = false,
    ): AnalysisResult = withContext(Dispatchers.IO) {
        if (filePaths.isEmpty()) return@withContext AnalysisResult()

        Log.i(TAG, "开始分析管道: ${filePaths.size} 个文件")
        val startTime = System.currentTimeMillis()

        val result = AnalysisResult()
        val batches = filePaths.chunked(BATCH_SIZE)
        var failedCount = 0

        for ((batchIndex, batch) in batches.withIndex()) {
            Log.v(TAG, "处理批次 ${batchIndex + 1}/${batches.size} (${batch.size} 个文件)")
            try {
                coroutineScope {
                    val qualityDeferred = async { analyzeBatchQuality(batch) }
                    val sceneDeferred = async { analyzeBatchScene(batch) }
                    val ocrDeferred = async {
                        if (enableOcr) analyzeBatchOcr(batch) else emptyMap()
                    }

                    val quality = qualityDeferred.await()
                    val scene = sceneDeferred.await()
                    val ocr = ocrDeferred.await()

                    result.qualityScores.putAll(quality)
                    result.sceneLabels.putAll(scene)
                    result.ocrTexts.putAll(ocr)

                    // 批量写入数据库 (每批写完即持久化)
                    writeBatchResults(batch, quality, scene, ocr)
                }
            } catch (e: Exception) {
                Log.w(TAG, "批次 ${batchIndex + 1} 分析失败: ${e.message}", e)
                failedCount += batch.size
            }
        }

        // 所有批次完成后运行相似度分析
        if (result.qualityScores.isNotEmpty()) {
            try {
                val files = result.qualityScores.keys.map { File(it) }.filter { it.exists() }
                val hashes = similarityAnalyzer.computeHashes(files)
                val groups = similarityAnalyzer.groupSimilar(hashes)
                // 写入相似组 ID
                for (group in groups) {
                    for (path in group.filePaths) {
                        mediaDao.setSimilarGroupId(path, group.groupId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "相似度分析失败: ${e.message}", e)
            }
        }

        // 所有批次完成后运行人脸检测 + 聚类（Phase 3.3）
        if (faceDetector != null && faceDao != null) {
            try {
                analyzeFaces(filePaths)
            } catch (e: Exception) {
                Log.w(TAG, "人脸检测/聚类失败: ${e.message}", e)
            }
        }

        // 所有批次完成后运行地理聚类（Phase 3.4）
        try {
            analyzeGeoClusters()
        } catch (e: Exception) {
            Log.w(TAG, "地理聚类失败: ${e.message}", e)
        }

        // 所有批次完成后运行语义嵌入生成（Phase 4.1）
        if (semanticEmbedder != null && embeddingDao != null) {
            try {
                analyzeSemanticEmbeddings(filePaths)
            } catch (e: Exception) {
                Log.w(TAG, "语义嵌入生成失败: ${e.message}", e)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "分析管道完成: ${filePaths.size} 个文件, 失败 $failedCount, 耗时 ${elapsed}ms")

        result
    }

    /**
     * 人脸检测 + DBSCAN 聚类（Phase 3.3）。
     *
     * 工作流：
     * 1. 先清除这些文件旧的人脸记录（避免重复）
     * 2. 分批检测人脸，提取特征向量，写入 faces 表
     * 3. 读取全部人脸记录，执行 DBSCAN 聚类
     * 4. 将聚类 ID 回写 faces 表 + media_items.faceClusterId
     *
     * @param filePaths 待检测的图片路径列表
     */
    suspend fun analyzeFaces(filePaths: List<String>): FaceAnalysisResult = withContext(Dispatchers.IO) {
        val detector = faceDetector ?: run {
            Log.w(TAG, "FaceDetector 未初始化，跳过人脸分析")
            return@withContext FaceAnalysisResult()
        }
        val dao = faceDao!!

        Log.i(TAG, "开始人脸检测: ${filePaths.size} 个文件")
        val faceStartTime = System.currentTimeMillis()

        // 1. 清除旧的人脸记录（这些文件可能重新检测）
        dao.deleteByFilePaths(filePaths)

        // 2. 分批检测人脸
        val allFaceEntities = mutableListOf<FaceEntity>()
        val batches = filePaths.chunked(FACE_BATCH_SIZE)

        for ((batchIdx, batch) in batches.withIndex()) {
            Log.v(TAG, "人脸检测批次 ${batchIdx + 1}/${batches.size} (${batch.size} 个文件)")
            try {
                for (path in batch) {
                    val file = File(path)
                    if (!file.exists()) continue

                    val detection = detector.detect(file)
                    if (detection.faces.isEmpty()) continue

                    for (face in detection.faces) {
                        val entity = FaceEntity(
                            filePath = path,
                            embedding = faceClusterer.serializeEmbedding(face.embedding),
                            boxLeft = face.box.left,
                            boxTop = face.box.top,
                            boxRight = face.box.right,
                            boxBottom = face.box.bottom,
                            detectedAtMs = System.currentTimeMillis(),
                        )
                        allFaceEntities.add(entity)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "人脸检测批次 ${batchIdx + 1} 失败: ${e.message}", e)
            }
        }

        // 3. 写入人脸记录
        if (allFaceEntities.isNotEmpty()) {
            dao.insertFaces(allFaceEntities)
            Log.i(TAG, "写入 ${allFaceEntities.size} 条人脸记录")
        }

        // 4. 执行 DBSCAN 聚类
        val clusterResult = runClustering(dao)

        val elapsed = System.currentTimeMillis() - faceStartTime
        Log.i(TAG, "人脸检测完成: ${allFaceEntities.size} 张脸, ${clusterResult.clusterCount} 个聚类, " +
                "${clusterResult.noiseCount} 噪声, 耗时 ${elapsed}ms")

        FaceAnalysisResult(
            totalFaces = allFaceEntities.size,
            clusterCount = clusterResult.clusterCount,
            noiseCount = clusterResult.noiseCount,
        )
    }

    /**
     * 读取全部人脸记录，执行 DBSCAN 聚类，回写 clusterId。
     */
    private suspend fun runClustering(dao: FaceDao): FaceClusterer.ClusterResult {
        // 清除旧的聚类 ID
        dao.clearAllClusterIds()

        val allFaces = dao.getAll()
        if (allFaces.isEmpty()) return FaceClusterer.ClusterResult(emptyList(), emptyList())

        val samples = allFaces.map { face ->
            FaceClusterer.FaceSample(
                id = face.faceId,
                embedding = faceClusterer.parseEmbedding(face.embedding),
            )
        }

        val result = faceClusterer.cluster(samples)

        // 回写聚类 ID
        for (cluster in result.clusters) {
            dao.updateClusterIds(cluster.sampleIds, cluster.clusterId)
            // 同时更新 media_items.faceClusterId（取每张照片的主脸）
            for (sampleId in cluster.sampleIds) {
                val face = allFaces.find { it.faceId == sampleId } ?: continue
                mediaDao.setFaceClusterId(face.filePath, cluster.clusterId)
            }
        }

        return result
    }

    /**
     * 地理聚类（Phase 3.4）。
     *
     * 工作流：
     * 1. 读取所有含 GPS 坐标的媒体记录
     * 2. 清除旧的 geoClusterId
     * 3. 执行 DBSCAN 地理聚类（Haversine 距离）
     * 4. 将聚类 ID 回写 media_items.geoClusterId
     *
     * @return 聚类结果汇总
     */
    suspend fun analyzeGeoClusters(): GeoAnalysisResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始地理聚类…")
        val geoStartTime = System.currentTimeMillis()

        // 1. 读取所有含 GPS 坐标的媒体
        val geoItems = mediaDao.getWithLocation()
        if (geoItems.isEmpty()) {
            Log.i(TAG, "无 GPS 坐标的媒体，跳过地理聚类")
            return@withContext GeoAnalysisResult()
        }

        // 2. 清除旧的 geoClusterId
        mediaDao.clearAllGeoClusterIds()

        // 3. 构建 GeoSample 列表并执行 DBSCAN 聚类
        val samples = geoItems.map { entity ->
            GeoClusterer.GeoSample(
                id = entity.filePath,
                latitude = entity.latitude!!,
                longitude = entity.longitude!!,
            )
        }

        val result = geoClusterer.cluster(samples)

        // 4. 回写聚类 ID 到数据库
        for (cluster in result.clusters) {
            mediaDao.updateGeoClusterId(cluster.sampleIds, cluster.clusterId)
        }

        val elapsed = System.currentTimeMillis() - geoStartTime
        Log.i(TAG, "地理聚类完成: ${geoItems.size} 个坐标, ${result.clusterCount} 个聚类, " +
                "${result.noiseCount} 噪声, 耗时 ${elapsed}ms")

        GeoAnalysisResult(
            totalGeoItems = geoItems.size,
            clusterCount = result.clusterCount,
            noiseCount = result.noiseCount,
        )
    }

    /**
     * 语义嵌入生成（Phase 4.1）。
     *
     * 工作流：
     * 1. 清除这些文件旧的语义嵌入（避免过期向量残留）
     * 2. 分批读取图片 + 已存储的分析字段，生成概念向量
     * 3. 批量写入 media_embeddings 表
     *
     * @param filePaths 待生成语义嵌入的图片路径列表
     * @return 嵌入生成结果汇总
     */
    suspend fun analyzeSemanticEmbeddings(filePaths: List<String>): SemanticAnalysisResult =
        withContext(Dispatchers.IO) {
            val embedder = semanticEmbedder ?: run {
                Log.w(TAG, "SemanticEmbedder 未初始化，跳过语义嵌入")
                return@withContext SemanticAnalysisResult()
            }
            val dao = embeddingDao!!

            Log.i(TAG, "开始语义嵌入生成: ${filePaths.size} 个文件")
            val embStartTime = System.currentTimeMillis()

            // 1. 清除旧嵌入
            dao.deleteByFilePaths(filePaths)

            // 2. 分批生成嵌入
            val embeddings = mutableListOf<MediaEmbedding>()
            var successCount = 0
            var failedCount = 0
            val batches = filePaths.chunked(EMBEDDING_BATCH_SIZE)

            for ((batchIdx, batch) in batches.withIndex()) {
                Log.v(TAG, "语义嵌入批次 ${batchIdx + 1}/${batches.size} (${batch.size} 个文件)")
                try {
                    for (path in batch) {
                        val file = File(path)
                        if (!file.exists()) {
                            failedCount++
                            continue
                        }
                        // 读取已存储的媒体实体（含 sceneType/ocrText/qualityScore）
                        val entity = mediaDao.getByFilePathLight(path)
                        val vec = embedder.embedImage(file, entity)
                        if (vec == null || vec.all { it == 0f }) {
                            failedCount++
                            continue
                        }
                        embeddings.add(
                            MediaEmbedding(
                                filePath = path,
                                embedding = embedder.serialize(vec),
                                modelVersion = SemanticEmbedder.MODEL_VERSION,
                                generatedAtMs = System.currentTimeMillis(),
                                source = "concept",
                            ),
                        )
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "语义嵌入批次 ${batchIdx + 1} 失败: ${e.message}", e)
                }
            }

            // 3. 批量写入
            if (embeddings.isNotEmpty()) {
                dao.insertEmbeddings(embeddings)
                Log.i(TAG, "写入 ${embeddings.size} 条语义嵌入")
            }

            val elapsed = System.currentTimeMillis() - embStartTime
            Log.i(TAG, "语义嵌入完成: 成功 $successCount, 失败 $failedCount, 耗时 ${elapsed}ms")

            SemanticAnalysisResult(
                totalEmbeddings = successCount,
                failedCount = failedCount,
            )
        }

    /**
     * 对单个文件执行快速分析 (质量 + 场景)，用于增量扫描中的新文件。
     */
    suspend fun analyzeSingleFile(filePath: String): FileAnalysisResult =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext FileAnalysisResult(filePath, success = false)
            }

            try {
                coroutineScope {
                    val qualityDeferred = async { qualityAnalyzer.analyze(file) }
                    val sceneDeferred = async { sceneClassifier.classify(file) }

                    val quality = qualityDeferred.await()
                    val scene = sceneDeferred.await()

                    // 写入数据库
                    mediaDao.setQualityScore(filePath, quality.overall)
                    mediaDao.setSceneType(filePath, scene.label.name.lowercase())

                    FileAnalysisResult(
                        filePath = filePath,
                        success = true,
                        qualityScore = quality.overall,
                        sceneLabel = scene.label,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "单文件分析失败: $filePath", e)
                FileAnalysisResult(filePath, success = false)
            }
        }

    // ---- 批量分析 ----

    private suspend fun analyzeBatchQuality(files: List<String>): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()
        for (path in files) {
            try {
                val result = qualityAnalyzer.analyze(File(path))
                scores[path] = result.overall
            } catch (e: Exception) {
                scores[path] = 0f
            }
        }
        return scores
    }

    private suspend fun analyzeBatchScene(files: List<String>): Map<String, SceneLabel> {
        val labels = mutableMapOf<String, SceneLabel>()
        for (path in files) {
            try {
                val result = sceneClassifier.classify(File(path))
                labels[path] = result.label
            } catch (e: Exception) {
                labels[path] = SceneLabel.UNKNOWN
            }
        }
        return labels
    }

    private suspend fun analyzeBatchOcr(files: List<String>): Map<String, String> {
        val texts = mutableMapOf<String, String>()
        for (path in files) {
            try {
                val result = ocrProvider.recognize(File(path), preferChinese = true)
                if (result != null && result.fullText.isNotBlank()) {
                    val shortText = result.fullText.take(500)
                    texts[path] = shortText
                }
            } catch (_: Exception) { }
        }
        return texts
    }

    private suspend fun writeBatchResults(
        paths: List<String>,
        quality: Map<String, Float>,
        scene: Map<String, SceneLabel>,
        ocr: Map<String, String>,
    ) {
        for (path in paths) {
            try {
                val score = quality[path] ?: 0f
                val label = scene[path]?.name?.lowercase() ?: SceneLabel.UNKNOWN.name.lowercase()
                val ocrText = ocr[path]

                mediaDao.setAnalysisFields(path, score, label, ocrText)
            } catch (e: Exception) {
                Log.w(TAG, "写入分析结果失败: $path", e)
            }
        }
    }

    data class FileAnalysisResult(
        val filePath: String,
        val success: Boolean,
        val qualityScore: Float = 0f,
        val sceneLabel: SceneLabel = SceneLabel.UNKNOWN,
    )

    /**
     * 人脸分析结果汇总（Phase 3.3）。
     */
    data class FaceAnalysisResult(
        val totalFaces: Int = 0,
        val clusterCount: Int = 0,
        val noiseCount: Int = 0,
    )

    /**
     * 地理聚类结果汇总（Phase 3.4）。
     */
    data class GeoAnalysisResult(
        val totalGeoItems: Int = 0,
        val clusterCount: Int = 0,
        val noiseCount: Int = 0,
    )

    /**
     * 语义嵌入生成结果汇总（Phase 4.1）。
     */
    data class SemanticAnalysisResult(
        val totalEmbeddings: Int = 0,
        val failedCount: Int = 0,
    )
}