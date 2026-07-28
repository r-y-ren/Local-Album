package com.renyxin.localalbum.core.plugin.capability

import java.io.File

/**
 * 图像质量评估提供者接口。
 *
 * 对照片进行多维度质量评分（清晰度、曝光、对比度、色彩），输出综合评分。
 * 每种实现（启发式、NIMA 等）提供不同的评估精度。
 *
 * ## 使用场景
 * - 管道批处理：[QualityStage] 逐图调用 [assess]
 * - 相似照片管理：按质量排序，保留最佳
 *
 * @see com.renyxin.localalbum.core.plugin.capability.builtin.HeuristicQualityProvider 内置默认实现
 */
interface QualityProvider {
    /** Provider 唯一标识 */
    val providerId: String

    /** 人类可读显示名称 */
    val displayName: String

    /**
     * 评估单张图片的质量。
     *
     * @param file 图片文件
     * @return 质量评估结果
     */
    suspend fun assess(file: File): QualityResult

    /** 释放模型资源 */
    suspend fun release()

    /**
     * 质量评估结果。
     *
     * @property overall 综合评分 (0~1)，越高越好
     * @property blurScore 清晰度评分 (0~1)
     * @property exposureScore 曝光评分 (0~1)
     * @property contrastScore 对比度评分 (0~1)
     * @property colorScore 色彩丰富度评分 (0~1)
     * @property isTooBlurry 是否过于模糊
     */
    data class QualityResult(
        val overall: Float,
        val blurScore: Float,
        val exposureScore: Float,
        val contrastScore: Float,
        val colorScore: Float,
        val isTooBlurry: Boolean,
    )
}
