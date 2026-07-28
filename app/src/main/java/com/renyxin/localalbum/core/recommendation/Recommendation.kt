package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import java.time.Instant

/**
 * 推荐数据模型（Phase 5 增强）。
 *
 * 新增字段：
 * - [category]：推荐类别标签，用于多样性控制
 * - [favoriteCount]：推荐内收藏图片数，用于 UI 展示和评分加权
 * - [avgQualityScore]：平均质量分，用于封面优选和展示
 *
 * 封面优选改进：[thumbnailPaths] 不再取时间最早的前 4 张，
 * 而是按质量评分降序取前 4 张，展示最佳代表图。
 */
data class Recommendation(
    val albumId: String,
    val albumName: String,
    val directoryPath: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val mediaItems: List<MediaItem>,
    val reason: String,
    val score: Double,
    /** 推荐类别标签（默认 WHOLE_ALBUM，向后兼容） */
    val category: RecommendationCategory = RecommendationCategory.WHOLE_ALBUM,
    /** 推荐内收藏图片数量 */
    val favoriteCount: Int = mediaItems.count { it.isFavorite },
    /** 推荐内平均质量评分 (0~1) */
    val avgQualityScore: Float = if (mediaItems.isEmpty()) 0f
        else mediaItems.map { it.qualityScore }.average().toFloat(),
) {
    /** 用于 2×2 缩略图预览的前 4 张媒体路径。
     *  封面优选：按质量评分降序取前 4 张，质量相同时按拍摄时间降序（优先更新的）。
     *  优先使用已生成的缩略图（thumbnailPath），若为 null 才回退到原始文件路径。
     *  修复问题：视频文件无法直接作为静态图片加载，导致精选推荐卡片中视频无缩略图。 */
    val thumbnailPaths: List<String>
        get() = mediaItems
            .sortedWith(compareByDescending<MediaItem> { it.qualityScore }.thenByDescending { it.capturedAt })
            .take(4)
            .map { it.thumbnailPath ?: it.filePath }
}
