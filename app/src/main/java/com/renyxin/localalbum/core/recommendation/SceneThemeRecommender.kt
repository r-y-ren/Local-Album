package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln

/**
 * 场景主题推荐策略（Phase 5 推荐增强）。
 *
 * 基于 [MediaItem.sceneType] 将媒体按场景分组，每组取质量评分 Top-N 组成一个推荐。
 * 适用于动漫/Cos 图库等无 GPS 场景，利用已分类的场景标签生成"风景精选""美食精选"等主题卡片。
 *
 * 评分公式：0.4 * 平均质量 + 0.3 * 新鲜度 + 0.2 * 组内规模 + 0.1 * 收藏占比
 *
 * @param clock 时钟，用于新鲜度计算
 * @param topNPerScene 每个场景取质量最高的前 N 张，默认 20
 * @param minGroupSize 形成推荐所需的最少图片数，默认 5
 */
class SceneThemeRecommender(
    private val clock: Clock = Clock.systemUTC(),
    private val topNPerScene: Int = 20,
    private val minGroupSize: Int = 5,
) {
    /**
     * 场景标签的中文显示名映射。
     * 与 [com.renyxin.localalbum.core.analysis.SceneClassifier.SceneLabel] 对应。
     */
    private val sceneDisplayNames = mapOf(
        "landscape" to "风景精选",
        "food" to "美食精选",
        "document" to "文档精选",
        "pet" to "宠物精选",
        "selfie" to "自拍精选",
        "night" to "夜景精选",
        "macro" to "微距精选",
        "screenshot" to "截图精选",
        "whiteboard" to "白板精选",
        "outdoor" to "户外精选",
        "indoor" to "室内精选",
    )

    /**
     * 基于场景类型生成主题推荐。
     *
     * @param allItems 全部媒体项（跨相册）
     * @return 场景主题推荐列表，按评分降序排列
     */
    fun generate(allItems: List<MediaItem>): List<Recommendation> {
        if (allItems.size < minGroupSize) return emptyList()

        // 1. 按 sceneType 分组，过滤 null 和 unknown
        val byScene = allItems
            .filter { !it.sceneType.isNullOrBlank() && it.sceneType != "unknown" }
            .groupBy { it.sceneType!! }

        val result = mutableListOf<Recommendation>()
        val now = Instant.now(clock)

        byScene.forEach { (sceneType, items) ->
            if (items.size < minGroupSize) return@forEach

            // 2. 每组取质量评分 Top-N
            val topItems = items
                .sortedWith(compareByDescending<MediaItem> { it.qualityScore }.thenByDescending { it.capturedAt })
                .take(topNPerScene)

            // 3. 计算评分维度
            val avgQuality = topItems.map { it.qualityScore.toDouble() }.average()
            val freshness = freshnessFactor(topItems.maxOf { it.capturedAt }, now)
            val sizeFactor = 1.0 - exp(-topItems.size / 10.0) // 规模饱和于 1
            val favoriteRatio = topItems.count { it.isFavorite }.toDouble() / topItems.size

            val score = (0.4 * avgQuality) +
                (0.3 * freshness) +
                (0.2 * sizeFactor) +
                (0.1 * favoriteRatio)

            // 4. 时间范围
            val sorted = topItems.sortedBy { it.capturedAt }
            val start = sorted.first().capturedAt
            val end = sorted.last().capturedAt

            // 5. 推荐名称和理由
            val displayName = sceneDisplayNames[sceneType] ?: "${sceneType}精选"
            val reason = buildReason(avgQuality, favoriteRatio, topItems.size)

            result += Recommendation(
                albumId = "scene-$sceneType",
                albumName = displayName,
                directoryPath = "场景聚合",
                windowStart = start,
                windowEnd = end,
                mediaItems = sorted,
                reason = reason,
                score = score,
                category = RecommendationCategory.SCENE_THEME,
            )
        }

        return result.sortedByDescending { it.score }
    }

    /** 新鲜度因子：越老分越高（保留用户习惯），30 天外快速饱和到 1 */
    private fun freshnessFactor(end: Instant, now: Instant): Double {
        val daysAgo = Duration.between(end, now).toDays().coerceAtLeast(0)
        return (1 - exp(-daysAgo / 30.0)).coerceIn(0.0, 1.0)
    }

    /** 构建推荐理由文本 */
    private fun buildReason(avgQuality: Double, favoriteRatio: Double, size: Int): String {
        val parts = mutableListOf("场景精选·${size}张")
        if (avgQuality > 0.6) parts += "高质量"
        if (favoriteRatio > 0.2) parts += "含收藏"
        return parts.joinToString("·")
    }
}
