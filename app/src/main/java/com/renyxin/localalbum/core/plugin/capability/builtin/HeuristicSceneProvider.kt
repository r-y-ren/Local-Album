package com.renyxin.localalbum.core.plugin.capability.builtin

import com.renyxin.localalbum.core.analysis.SceneClassifier
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import java.io.File

/**
 * 基于像素启发式的内置默认场景分类 Provider。
 *
 * 包裹项目现有的 [SceneClassifier]，将其适配为 [SceneProvider] 接口。
 * 零模型依赖，通过颜色直方图/亮度/饱和度/宽高比等特征分类。
 *
 * 可被 EfficientNet/MobileNet/CLIP 等更高精度的模型替换。
 */
class HeuristicSceneProvider : SceneProvider {

    override val providerId = "builtin:heuristic"
    override val displayName = "启发式场景分类 (默认)"
    override val labels: List<String> = SceneClassifier.SceneLabel.values()
        .filter { it != SceneClassifier.SceneLabel.UNKNOWN }
        .map { it.displayName }

    private val classifier = SceneClassifier()

    override suspend fun classify(file: File): SceneProvider.SceneResult {
        val result = classifier.classify(file)
        val allScores = result.allScores.mapKeys { it.key.displayName }
        return SceneProvider.SceneResult(
            topLabel = result.label.displayName,
            confidence = result.confidence,
            allScores = allScores,
        )
    }

    override suspend fun release() {
        // 启发式分类器无资源需释放
    }
}
