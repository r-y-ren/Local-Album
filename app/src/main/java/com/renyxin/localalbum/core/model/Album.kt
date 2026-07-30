package com.renyxin.localalbum.core.model

import java.time.Instant

data class Album(
    val id: String,
    val name: String,
    val directoryPath: String,
    /**
     * 兼容推荐等尚未分页的离线调用。前台目录树节点必须保持为空，媒体由 directory Paging 查询。
     */
    val mediaItems: List<MediaItem> = emptyList(),
    val parentPath: String? = null,
    val depth: Int = 0,
    val children: List<Album> = emptyList(),
    val coverItem: MediaItem? = null,
    val directMediaCount: Int = mediaItems.size,
    val latestCapturedAtMs: Long = 0L,
    val summaryCoverPaths: List<String> = emptyList(),
) {
    val mediaCount: Int
        get() = directMediaCount

    val isLeaf: Boolean
        get() = children.isEmpty()

    /**
     * 该相册及其所有后代相册包含的媒体总数（含子目录）。
     * 用于树形视图中展示"共 N 项"。
     */
    val totalMediaCount: Int
        get() = directMediaCount + children.sumOf { it.totalMediaCount }

    /**
     * 该相册下（含后代）的叶子相册数量，便于在树视图中显示"含 N 个子相册"。
     */
    val leafCount: Int
        get() = if (isLeaf) {
            if (directMediaCount == 0) 0 else 1
        } else {
            children.sumOf { it.leafCount }
        }

    val dateRange: ClosedRange<Instant>?
        get() {
            if (mediaItems.isNotEmpty()) {
                val sorted = mediaItems.sortedBy { it.capturedAt }
                return sorted.first().capturedAt..sorted.last().capturedAt
            }
            return latestCapturedAtMs.takeIf { it > 0 }?.let {
                val instant = Instant.ofEpochMilli(it)
                instant..instant
            }
        }

    /** 获取封面图路径（优先使用 coverItem 的缩略图，否则用第一张媒体的缩略图，回退到原始文件路径），用于 UI 展示。
     *  修复问题：视频文件的 thumbnailPath 用于 AsyncImage 显示，原始 filePath 无法作为静态图片解码。 */
    val coverPath: String?
        get() = coverItem?.let { it.thumbnailPath ?: it.filePath }
            ?: summaryCoverPaths.firstOrNull()
            ?: mediaItems.firstOrNull()?.let { it.thumbnailPath ?: it.filePath }

    /**
     * 获取用于 2×2 合成封面的前 4 张媒体路径。
     * 优先使用 coverItem，不足 4 张时用已有项填充空位。
     * 每条路径优先取 thumbnailPath（缩略图），若为 null 才回退到原始 filePath。
     * 修复问题：视频文件无法直接作为静态图片加载，导致相册封面中视频无缩略图。
     */
    val coverPaths: List<String>
        get() {
            val inMemory = if (coverItem != null) {
                listOf(coverItem) + mediaItems.filter { it.filePath != coverItem.filePath }
            } else {
                mediaItems
            }.map { it.thumbnailPath ?: it.filePath }
            return (summaryCoverPaths + inMemory).distinct().take(4)
        }
}
