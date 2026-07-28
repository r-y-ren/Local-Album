package com.renyxin.localalbum.core.plugin.capability

import java.io.File

/**
 * 场景分类提供者接口。
 *
 * 封装图像场景分类模型，将图像归类为预定义场景标签（风景、美食、夜景、自拍等）。
 * 每种实现（启发式、EfficientNet、CLIP zero-shot 等）提供不同的精度/覆盖范围。
 *
 * ## 使用场景
 * - 管道批处理：[SceneStage] 逐图调用 [classify]
 * - 相册浏览：按场景类别筛选照片
 *
 * @see com.renyxin.localalbum.core.plugin.capability.builtin.HeuristicSceneProvider 内置默认实现
 */
interface SceneProvider {
    /** Provider 唯一标识，如 "builtin:heuristic"、"plugin:efficientnet_b0" */
    val providerId: String

    /** 人类可读显示名称 */
    val displayName: String

    /** 支持的标签列表 */
    val labels: List<String>

    /**
     * 对单张图片进行场景分类。
     *
     * @param file 图片文件
     * @return 分类结果，含最高置信度标签及所有标签得分
     */
    suspend fun classify(file: File): SceneResult

    /** 释放模型资源 */
    suspend fun release()

    /**
     * 场景分类结果。
     *
     * @property topLabel 最高置信度标签
     * @property confidence 最高置信度 (0~1)
     * @property allScores 所有标签的得分映射（标签名 → 得分）
     */
    data class SceneResult(
        val topLabel: String,
        val confidence: Float,
        val allScores: Map<String, Float> = emptyMap(),
    )
}
