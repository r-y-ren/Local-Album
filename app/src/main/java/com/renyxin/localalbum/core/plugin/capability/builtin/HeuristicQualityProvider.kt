package com.renyxin.localalbum.core.plugin.capability.builtin

import com.renyxin.localalbum.core.analysis.QualityAnalyzer
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import java.io.File

/**
 * 基于像素分析的内置默认质量评估 Provider。
 *
 * 包裹项目现有的 [QualityAnalyzer]，将其适配为 [QualityProvider] 接口。
 * 零模型依赖：通过拉普拉斯方差（模糊度）、亮度直方图（曝光）、
 * RMS 对比度、饱和度等像素级特征评估。
 *
 * 可被 NIMA (Neural Image Assessment) 等深度学习模型替换。
 */
class HeuristicQualityProvider : QualityProvider {

    override val providerId = "builtin:heuristic"
    override val displayName = "启发式质量评估 (默认)"

    private val analyzer = QualityAnalyzer()

    override suspend fun assess(file: File): QualityProvider.QualityResult {
        val result = analyzer.analyze(file)
        return QualityProvider.QualityResult(
            overall = result.overall,
            blurScore = result.blurScore,
            exposureScore = result.exposureScore,
            contrastScore = result.contrastScore,
            colorScore = result.colorScore,
            isTooBlurry = result.isTooBlurry,
        )
    }

    override suspend fun release() {
        // 启发式分析器无资源需释放
    }
}
