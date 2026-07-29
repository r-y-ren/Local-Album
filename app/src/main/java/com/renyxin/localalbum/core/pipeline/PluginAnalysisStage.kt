package com.renyxin.localalbum.core.pipeline

import android.util.Log
import com.renyxin.localalbum.core.plugin.AiPlugin
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import java.io.File

/**
 * 动态插件分析阶段包裹器（Phase 3.2）。
 *
 * 将任意 [AiPlugin] 适配为统一 [AnalysisStage] 接口，使外部自定义模型插件
 * 能够无缝嵌入管道编排引擎。
 *
 * ## 特征类型映射
 * - `ClassificationPlugin` → featureType = "classification"
 * - `FeatureExtractionPlugin` → featureType = "embedding"
 * - `DetectionPlugin` → featureType = "detection_box"
 * - `GenerativePlugin` → featureType = "generated_image"
 *
 * ## 输出持久化
 * 插件输出通过 [FeatureStoreDao] 存储到 `feature_store` 表，使用 [PluginJsonCodec]
 * 将 [PluginOutput] 序列化为通用 JSON Blob 格式。
 */
class PluginAnalysisStage(
    private val plugin: AiPlugin,
    private val featureStoreDao: FeatureStoreDao,
) : AnalysisStage {

    override val stageId: String
        get() = plugin.getId()

    override val stageType: StageType = StageType.PLUGIN

    override val displayName: String
        get() = plugin.getManifest().name

    override val dependencies: List<String>
        get() = plugin.getManifest().dependsOn

    /**
     * 从插件清单的 [PluginManifest.pipelineStage] 推断特征类型。
     */
    private val featureType: String by lazy {
        when (plugin.getManifest().taskType) {
            com.renyxin.localalbum.core.plugin.PluginManifest.TaskType.CLASSIFICATION -> "classification"
            com.renyxin.localalbum.core.plugin.PluginManifest.TaskType.FEATURE_EXTRACTION -> "embedding"
            com.renyxin.localalbum.core.plugin.PluginManifest.TaskType.DETECTION -> "detection_box"
            com.renyxin.localalbum.core.plugin.PluginManifest.TaskType.GENERATIVE -> "generated_image"
        }
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
        if (!plugin.isReady()) {
            Log.w("PluginAnalysis", "插件 ${plugin.getId()} 未就绪，跳过")
            return StageResult(successCount = 0, failedCount = filePaths.size)
        }

        val total = filePaths.size
        var success = 0
        var failed = 0
        val outputSchema: FeatureSchema = plugin.getOutputSchema()
        val schemaJson: String = PluginJsonCodec.schemaToJson(outputSchema)

        // 1. 清除该插件的旧特征记录（基于 filePaths）
        featureStoreDao.deleteByPluginAndFilePaths(plugin.getId(), filePaths)

        // 2. 逐图推理
        val entities = mutableListOf<FeatureStoreEntity>()

        for (path in filePaths) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    failed++
                    enhancedCallback(success + failed, total, path, FileProcessingStatus.FAILED)
                    continue
                }
                enhancedCallback(success + failed, total, path, FileProcessingStatus.PROCESSING)
                val input = PluginInput.ImageInput(file = file)
                val output: PluginOutput = plugin.execute(input)

                val dataJson = PluginJsonCodec.pluginOutputToJson(output)

                val entity = FeatureStoreEntity(
                    filePath = path,
                    pluginId = plugin.getId(),
                    featureType = featureType,
                    dataJson = dataJson,
                    schemaJson = schemaJson,
                    modelVersion = plugin.getManifest().version.hashCode(),
                    generatedAtMs = System.currentTimeMillis(),
                )
                entities.add(entity)
                success++
                enhancedCallback(success + failed, total, path, FileProcessingStatus.COMPLETED)
            } catch (e: Exception) {
                Log.w("PluginAnalysis", "插件 ${plugin.getId()} 推理失败: $path", e)
                failed++
                enhancedCallback(success + failed, total, path, FileProcessingStatus.FAILED)
            }
        }

        // 3. 批量写入
        if (entities.isNotEmpty()) {
            featureStoreDao.insertAll(entities)
        }

        return StageResult(successCount = success, failedCount = failed)
    }
}