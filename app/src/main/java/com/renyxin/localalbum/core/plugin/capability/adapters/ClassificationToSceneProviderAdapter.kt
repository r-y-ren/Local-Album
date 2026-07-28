package com.renyxin.localalbum.core.plugin.capability.adapters

import com.renyxin.localalbum.core.plugin.ClassificationPlugin
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import java.io.File

/**
 * 将扩展 [ClassificationPlugin] 适配为 [SceneProvider]，使其可用于 Pipeline 场景分类槽位（Phase 3）。
 *
 * 第三方分类器插件（实现了 [ClassificationPlugin] 接口）通过此适配器
 * 可以无缝接入核心分析管道的场景分类阶段。
 *
 * @param classificationPlugin 第三方分类插件实例
 */
class ClassificationToSceneProviderAdapter(
    private val classificationPlugin: ClassificationPlugin,
) : SceneProvider {

    override val providerId: String = "plugin:${classificationPlugin.getId()}"
    override val displayName: String = classificationPlugin.getManifest().name
    override val labels: List<String> = classificationPlugin.getOutputSchema().fields.map { it.name }

    override suspend fun classify(file: File): SceneProvider.SceneResult {
        val input = PluginInput.ImageInput(file)
        val output = classificationPlugin.execute(input)
        return SceneProvider.SceneResult(
            topLabel = output.topLabel ?: "unknown",
            confidence = output.topConfidence,
            allScores = output.labels.associate { it.label to it.confidence },
        )
    }

    override suspend fun release() {
        classificationPlugin.release()
    }
}
