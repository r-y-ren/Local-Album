package com.renyxin.localalbum.core.pipeline.stages

import android.util.Log
import com.renyxin.localalbum.core.analysis.GeoClusterer
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.EnhancedProgressCallback
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.core.pipeline.StageType
import com.renyxin.localalbum.data.db.dao.MediaDao

/**
 * 内置地理聚类阶段适配器。
 *
 * 将 [GeoClusterer] 封装为 [AnalysisStage]，读取所有含 GPS 坐标的媒体，
 * 执行 Haversine 距离 DBSCAN 聚类，将 clusterId 回写 [MediaDao]。
 */
class BuiltinGeoStage(
    private val mediaDao: MediaDao,
) : AnalysisStage {

    override val stageId = "builtin:geo"
    override val stageType = StageType.BUILTIN
    override val displayName = "地理聚类"
    override val dependencies = emptyList<String>()
    override val isCacheable = false

    private val clusterer = GeoClusterer()

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
        try {
            enhancedCallback(0, 1, null, null)

            val geoItems = mediaDao.getWithLocation()
            if (geoItems.isEmpty()) {
                enhancedCallback(1, 1, null, FileProcessingStatus.COMPLETED)
                return StageResult(successCount = 0, failedCount = 0)
            }

            // 清除旧的 geoClusterId
            mediaDao.clearAllGeoClusterIds()

            val samples = geoItems.map { entity ->
                GeoClusterer.GeoSample(
                    id = entity.filePath,
                    latitude = entity.latitude!!,
                    longitude = entity.longitude!!,
                )
            }

            val result = clusterer.cluster(samples)

            // 逐文件报告聚类结果
            var processed = 0
            for (cluster in result.clusters) {
                mediaDao.updateGeoClusterId(cluster.sampleIds, cluster.clusterId)
                for (sampleId in cluster.sampleIds) {
                    enhancedCallback(++processed, samples.size, sampleId, FileProcessingStatus.COMPLETED)
                }
            }
            // 噪声点也标记为完成
            for (sampleId in result.noise) {
                enhancedCallback(++processed, samples.size, sampleId, FileProcessingStatus.COMPLETED)
            }

            enhancedCallback(1, 1, null, null)

            return StageResult(
                successCount = geoItems.size,
                failedCount = 0,
                extra = mapOf(
                    "totalGeoItems" to geoItems.size.toString(),
                    "clusterCount" to result.clusterCount.toString(),
                    "noiseCount" to result.noiseCount.toString(),
                ),
            )
        } catch (e: Exception) {
            Log.w("BuiltinGeo", "地理聚类失败", e)
            return StageResult(successCount = 0, failedCount = filePaths.size)
        }
    }
}