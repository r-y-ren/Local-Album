package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem

/**
 * 将推荐候选转换为文件级不重复的轮换池。
 *
 * 推荐引擎的不同策略可能包含同一文件。本类按候选顺序保留文件第一次出现的位置，
 * 从后续推荐中移除已出现文件，并为没有被任何策略覆盖的文件创建兜底推荐，确保
 * 一轮推荐池包含全部媒体且每个文件只出现一次。
 */
class RecommendationFileRotator(
    private val fallbackGroupSize: Int = 20,
) {
    init {
        require(fallbackGroupSize > 0) { "fallbackGroupSize must be positive" }
    }

    fun build(
        candidates: List<Recommendation>,
        allMedia: List<MediaItem>,
    ): List<Recommendation> {
        if (allMedia.isEmpty()) return emptyList()

        val mediaByPath = LinkedHashMap<String, MediaItem>(allMedia.size)
        allMedia.forEach { mediaByPath.putIfAbsent(it.filePath, it) }

        val usedPaths = HashSet<String>(mediaByPath.size)
        val result = mutableListOf<Recommendation>()

        candidates.forEach { recommendation ->
            val uniqueItems = recommendation.mediaItems.mapNotNull { item ->
                val current = mediaByPath[item.filePath] ?: return@mapNotNull null
                if (usedPaths.add(current.filePath)) current else null
            }
            if (uniqueItems.isNotEmpty()) {
                result += recommendation.copy(
                    mediaItems = uniqueItems,
                    favoriteCount = uniqueItems.count { it.isFavorite },
                    avgQualityScore = uniqueItems.map { it.qualityScore }.average().toFloat(),
                )
            }
        }

        mediaByPath.values
            .filterNot { it.filePath in usedPaths }
            .chunked(fallbackGroupSize)
            .forEachIndexed { index, items ->
                val ordered = items.sortedBy { it.capturedAt }
                result += Recommendation(
                    albumId = "all-media-${index}-${ordered.first().filePath.hashCode()}",
                    albumName = "图库轮换",
                    directoryPath = "全部媒体",
                    windowStart = ordered.first().capturedAt,
                    windowEnd = ordered.last().capturedAt,
                    mediaItems = ordered,
                    reason = "完整图库轮换·${ordered.size}项",
                    score = 0.0,
                    category = RecommendationCategory.WHOLE_ALBUM,
                )
                usedPaths += ordered.map { it.filePath }
            }

        return result
    }
}
