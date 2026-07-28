package com.renyxin.localalbum.core.recommendation

/**
 * 推荐类别标签（Phase 5 推荐增强）。
 *
 * 用于多样性控制：避免推荐列表中连续出现多条同类别推荐，
 * 保证视觉与内容的丰富度。
 *
 * @param displayName 用于 UI 展示的中文名称
 * @param priority 多样性控制中的优先级权重（数值越大越优先保留）
 */
enum class RecommendationCategory(
    val displayName: String,
    val priority: Int,
) {
    /** 场景主题推荐：基于 sceneType 分组的主题精选 */
    SCENE_THEME("场景精选", 30),

    /** 语义聚类推荐：基于语义嵌入向量聚类的主题精选 */
    SEMANTIC_CLUSTER("主题精选", 25),

    /** 时间窗口推荐：大相册内连续 N 天高密度拍摄 */
    TIME_WINDOW("时段精选", 20),

    /** 整册推荐：小相册整体推荐 */
    WHOLE_ALBUM("相册精选", 15),

    /** 跨目录事件聚合推荐 */
    EVENT("事件精选", 10),
}
