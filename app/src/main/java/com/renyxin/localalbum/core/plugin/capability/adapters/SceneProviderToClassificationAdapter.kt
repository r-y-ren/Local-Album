package com.renyxin.localalbum.core.plugin.capability.adapters

import android.content.Context
import com.renyxin.localalbum.core.plugin.ClassificationPlugin
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.SceneProvider

/**
 * 将核心 [SceneProvider] 包装为 [ClassificationPlugin]，使其可在扩展插件列表中使用（Phase 3）。
 *
 * 核心场景分类器（如 MobileNetV2 Provider）通过此适配器
 * 可以作为分类插件在扩展插件 UI 中展示和调用。
 *
 * @param sceneProvider 核心场景分类 Provider
 */
class SceneProviderToClassificationAdapter(
    private val sceneProvider: SceneProvider,
) : ClassificationPlugin {

    private val pluginId = "core:${sceneProvider.providerId}"

    override fun getId(): String = pluginId

    override fun getManifest(): PluginManifest {
        return PluginManifest(
            pluginId = pluginId,
            name = sceneProvider.displayName,
            version = "1.0.0",
            author = "LocalAlbum Core",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "",
            entryClass = "",
            inputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "image",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 224, 224, 3),
                ),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "output",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, sceneProvider.labels.size),
                ),
            ),
            description = "核心场景分类器适配（${sceneProvider.providerId}）",
        )
    }

    override fun getInputSchema(): List<FeatureSchema.FieldSpec> = listOf(
        FeatureSchema.FieldSpec(name = "image", dataType = FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(224, 224, 3)),
    )

    override fun getOutputSchema(): FeatureSchema = FeatureSchema(
        pluginId = pluginId,
        featureType = "classification",
        fields = listOf(
            FeatureSchema.FieldSpec(name = "label", dataType = FeatureSchema.DataType.STRING),
            FeatureSchema.FieldSpec(name = "confidence", dataType = FeatureSchema.DataType.FLOAT),
        ),
    )

    override suspend fun initialize(context: Context, pluginContext: PluginContext) {
        // 核心 Provider 无需额外初始化
    }

    override fun isReady(): Boolean = true

    override suspend fun execute(input: PluginInput): PluginOutput.ClassificationOutput {
        val imageInput = input as PluginInput.ImageInput
        val result = sceneProvider.classify(imageInput.file)
        return PluginOutput.ClassificationOutput(
            labels = listOf(
                PluginOutput.LabelResult(label = result.topLabel, confidence = result.confidence),
            ),
        )
    }

    override suspend fun release() {
        sceneProvider.release()
    }
}
