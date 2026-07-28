package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import java.time.Instant

data class Recommendation(
    val albumId: String,
    val albumName: String,
    val directoryPath: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val mediaItems: List<MediaItem>,
    val reason: String,
    val score: Double,
) {
    /** 用于 2×2 缩略图预览的前 4 张媒体路径。
     *  优先使用已生成的缩略图（thumbnailPath），若为 null 才回退到原始文件路径。
     *  修复问题：视频文件无法直接作为静态图片加载，导致精选推荐卡片中视频无缩略图。 */
    val thumbnailPaths: List<String>
        get() = mediaItems.take(4).map { it.thumbnailPath ?: it.filePath }
}
