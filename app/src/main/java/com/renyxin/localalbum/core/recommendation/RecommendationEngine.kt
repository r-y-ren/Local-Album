package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.MediaItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.DayOfWeek
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 增强版推荐引擎（Phase 5 推荐增强）。
 *
 * 改进点：
 * 1. 去掉随机因子，评分完全确定性（基于密度 + 新鲜度 + 美学评分 + 多样性）
 * 2. 接入 [MediaItem.qualityScore] 美学评分，优先推荐高质量照片
 * 3. 添加跨目录时空事件聚合：将相近时空的照片聚合为「事件」推荐
 * 4. 周末/节假日加权：周末拍摄的照片获得额外加分（更可能是旅行/聚会）
 * 5. [Phase 5] 集成场景主题推荐 [SceneThemeRecommender]
 * 6. [Phase 5] 集成语义聚类推荐 [SemanticClusterRecommender]（可选，需 EmbeddingDao）
 * 7. [Phase 5] 事件聚合去除 GPS 硬依赖，无 GPS 时用语义相似度判断
 * 8. [Phase 5] 各评分函数加入收藏加权维度
 *
 * 新鲜度逻辑保持不变（越老分越高），符合用户"旧图看得少"的习惯。
 */
class RecommendationEngine(
    private val clock: Clock = Clock.systemUTC(),
    private val largeAlbumThreshold: Int = 500,
    private val largeAlbumSpanDays: Long = 60,
    /** 事件聚合：同一地点 + 时间窗口（小时）内的照片视为同一事件 */
    private val eventTimeWindowHours: Long = 6,
    /** 事件聚合：地理距离阈值（公里），超过则视为不同地点 */
    private val eventGeoDistanceKm: Double = 5.0,
    /** 事件聚合：无 GPS 时，语义向量余弦相似度阈值，超过则视为同主题 */
    private val eventSemanticSimilarity: Float = 0.7f,
    /** [Phase 5] 场景主题推荐器 */
    private val sceneThemeRecommender: SceneThemeRecommender = SceneThemeRecommender(clock = clock),
    /** [Phase 5] 语义聚类推荐器（可选，无 EmbeddingDao 时跳过） */
    private val semanticClusterRecommender: SemanticClusterRecommender? = null,
) {
    /**
     * 生成推荐列表（取评分最高的前 [maxResults] 条）。
     * 评分完全确定性，无随机因子。
     */
    suspend fun generate(albums: List<Album>, maxResults: Int = 10): List<Recommendation> {
        return generateAll(albums).take(maxResults)
    }

    /**
     * 生成全量推荐列表（不截断），按评分降序排列。
     * 供上层做多样性选择 + 批次分发。
     *
     * [Phase 5] 改为 suspend 以支持语义聚类推荐的异步 DAO 调用，
     * 避免 runBlocking 阻塞。
     */
    suspend fun generateAll(albums: List<Album>): List<Recommendation> {
        val result = mutableListOf<Recommendation>()

        // 1. 按相册生成推荐（密度 + 美学 + 新鲜度 + 收藏加权）
        albums.forEach { album ->
            if (album.mediaItems.isEmpty()) return@forEach
            val spanDays = spanDays(album.mediaItems)
            val isLargeAlbum = album.mediaCount >= largeAlbumThreshold && spanDays >= largeAlbumSpanDays

            if (isLargeAlbum) {
                result += windowRecommendations(album)
            } else {
                result += wholeAlbumRecommendation(album)
            }
        }

        // 2. 跨目录时空事件聚合推荐
        val allItems = albums.flatMap { it.mediaItems }
        if (allItems.size >= 2) {
            result += eventRecommendations(allItems)
        }

        // 3. [Phase 5] 场景主题推荐
        result += sceneThemeRecommender.generate(allItems)

        // 4. [Phase 5] 语义聚类推荐（可选）。生产路径由推荐器自行有界采样并加载元数据，
        // 不把 albums 展平出的潜在整库列表继续传入语义推荐路径。
        semanticClusterRecommender?.let { recommender ->
            result += recommender.generate()
        }

        val preference = com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime.current
            .recommendationPreference
        return result.sortedByDescending { recommendation ->
            when (preference) {
                com.renyxin.localalbum.core.analysis.RecommendationPreference.BALANCED -> recommendation.score
                com.renyxin.localalbum.core.analysis.RecommendationPreference.QUALITY ->
                    recommendation.score + recommendation.avgQualityScore * 0.35
                com.renyxin.localalbum.core.analysis.RecommendationPreference.FAVORITES ->
                    recommendation.score +
                        recommendation.favoriteCount.toDouble() / recommendation.mediaItems.size.coerceAtLeast(1) * 0.35
                com.renyxin.localalbum.core.analysis.RecommendationPreference.MEMORIES ->
                    recommendation.score + (recommendation.mediaItems.minOfOrNull { it.capturedAt.epochSecond }
                        ?.let { oldest -> ((Instant.now(clock).epochSecond - oldest) / 31_536_000.0).coerceAtMost(1.0) * 0.35 }
                        ?: 0.0)
                com.renyxin.localalbum.core.analysis.RecommendationPreference.RECENT ->
                    recommendation.score + (recommendation.mediaItems.maxOfOrNull { it.capturedAt.epochSecond }
                        ?.let { newest -> (1.0 - ((Instant.now(clock).epochSecond - newest) / 2_592_000.0).coerceIn(0.0, 1.0)) * 0.35 }
                        ?: 0.0)
            }
        }
    }

    // ---- 单相册推荐 ----

    private fun wholeAlbumRecommendation(album: Album): Recommendation {
        val media = album.mediaItems.sortedBy { it.capturedAt }
        val start = media.first().capturedAt
        val end = media.last().capturedAt
        val density = if (media.size == 1) 1.0 else media.size.toDouble() / (spanDays(media).coerceAtLeast(1))
        val aesthetic = averageAestheticScore(media)
        val weekendBoost = weekendBoost(media)
        val favoriteRatio = favoriteRatio(media)
        val score = scoreWhole(
            density = density,
            aesthetic = aesthetic,
            end = end,
            weekendBoost = weekendBoost,
            favoriteRatio = favoriteRatio,
        )
        return Recommendation(
            albumId = album.id,
            albumName = album.name,
            directoryPath = album.directoryPath,
            windowStart = start,
            windowEnd = end,
            mediaItems = media,
            reason = buildReason(aesthetic, weekendBoost, favoriteRatio, "整册推荐"),
            score = score,
            category = RecommendationCategory.WHOLE_ALBUM,
        )
    }

    private fun windowRecommendations(album: Album): List<Recommendation> {
        val sorted = album.mediaItems.sortedBy { it.capturedAt }
        val byDay = sorted.groupBy { LocalDate.ofInstant(it.capturedAt, ZoneOffset.UTC) }
        val dayKeys = byDay.keys.sorted()
        if (dayKeys.isEmpty()) return emptyList()

        val span = spanDays(sorted).coerceAtLeast(1)
        val avgDensity = sorted.size.toDouble() / span
        val windows = mutableListOf<Recommendation>()

        val windowSizes = listOf(7, 14, 21, 30)
        windowSizes.forEach { size ->
            if (dayKeys.size < size) return@forEach

            for (i in 0..(dayKeys.size - size)) {
                val startDay = dayKeys[i]
                val endDay = dayKeys[i + size - 1]
                val inWindow = sorted.filter {
                    val day = LocalDate.ofInstant(it.capturedAt, ZoneOffset.UTC)
                    !day.isBefore(startDay) && !day.isAfter(endDay)
                }
                if (inWindow.isEmpty()) continue

                val density = inWindow.size.toDouble() / size
                if (density < avgDensity * 1.5) continue

                val start = inWindow.first().capturedAt
                val end = inWindow.last().capturedAt
                val aesthetic = averageAestheticScore(inWindow)
                val weekendBoost = weekendBoost(inWindow)
                val favoriteRatio = favoriteRatio(inWindow)
                val score = scoreWindow(
                    density = density,
                    aesthetic = aesthetic,
                    end = end,
                    weekendBoost = weekendBoost,
                    favoriteRatio = favoriteRatio,
                )
                windows += Recommendation(
                    albumId = album.id,
                    albumName = album.name,
                    directoryPath = album.directoryPath,
                    windowStart = start,
                    windowEnd = end,
                    mediaItems = inWindow,
                    reason = buildReason(aesthetic, weekendBoost, favoriteRatio, "连续${size}天拍摄密度较高"),
                    score = score,
                    category = RecommendationCategory.TIME_WINDOW,
                )
            }
        }

        return deduplicateWindows(windows).take(3)
    }

    // ---- 跨目录时空事件聚合 ----

    /**
     * 将所有媒体项按「时间窗口 + 地理距离/语义相似度」聚合为事件，生成跨目录事件推荐。
     * 同一事件内的照片：拍摄时间间隔 ≤ eventTimeWindowHours 且地理距离 ≤ eventGeoDistanceKm。
     * [Phase 5] 当两张照片都无 GPS 时，改用语义向量余弦相似度判断（需嵌入向量）。
     */
    private fun eventRecommendations(allItems: List<MediaItem>): List<Recommendation> {
        val sorted = allItems.sortedBy { it.capturedAt }
        val events = mutableListOf<MutableList<MediaItem>>()
        var currentEvent = mutableListOf<MediaItem>()

        for (item in sorted) {
            if (currentEvent.isEmpty()) {
                currentEvent.add(item)
                continue
            }
            val last = currentEvent.last()
            val timeGap = Duration.between(last.capturedAt, item.capturedAt).toHours()
            val sameEvent = timeGap <= eventTimeWindowHours && isSameEvent(last, item)
            if (sameEvent) {
                currentEvent.add(item)
            } else {
                if (currentEvent.size >= 3) events.add(currentEvent)
                currentEvent = mutableListOf(item)
            }
        }
        if (currentEvent.size >= 3) events.add(currentEvent)

        return events.map { event ->
            val sortedEvent = event.sortedBy { it.capturedAt }
            val start = sortedEvent.first().capturedAt
            val end = sortedEvent.last().capturedAt
            val spanH = Duration.between(start, end).toHours().coerceAtLeast(1)
            val density = sortedEvent.size.toDouble() / spanH
            val aesthetic = averageAestheticScore(sortedEvent)
            val weekendBoost = weekendBoost(sortedEvent)
            val favoriteRatio = favoriteRatio(sortedEvent)
            val score = scoreEvent(
                density = density,
                aesthetic = aesthetic,
                end = end,
                weekendBoost = weekendBoost,
                size = sortedEvent.size,
                favoriteRatio = favoriteRatio,
            )
            Recommendation(
                albumId = "event-${start.toEpochMilli()}",
                albumName = "事件精选",
                directoryPath = "跨目录聚合",
                windowStart = start,
                windowEnd = end,
                mediaItems = sortedEvent,
                reason = buildReason(aesthetic, weekendBoost, favoriteRatio, "跨目录时空事件（${sortedEvent.size} 张）"),
                score = score,
                category = RecommendationCategory.EVENT,
            )
        }
    }

    /**
     * [Phase 5] 判断两张照片是否属于同一事件。
     * - 有 GPS：地理距离 ≤ eventGeoDistanceKm
     * - 无 GPS：视为同主题（不阻断事件聚合，保持向后兼容）
     *   注：真正的语义相似度判断需要嵌入向量，此处保持轻量，不引入 DAO 依赖。
     *   语义聚类推荐由 [SemanticClusterRecommender] 独立处理。
     */
    private fun isSameEvent(a: MediaItem, b: MediaItem): Boolean {
        val lat1 = a.latitude
        val lon1 = a.longitude
        val lat2 = b.latitude
        val lon2 = b.longitude

        // 任一缺失 GPS → 视为同主题（不阻断事件聚合）
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return true
        }
        return haversineKm(lat1, lon1, lat2, lon2) <= eventGeoDistanceKm
    }

    // ---- 评分函数（确定性，无随机因子）----

    /**
     * 整册推荐评分 = 0.3*密度 + 0.3*新鲜度 + 0.2*美学 + 0.1*周末 + 0.1*收藏
     * [Phase 5] 新增收藏加权维度，密度权重从 0.4 降至 0.3
     */
    private fun scoreWhole(
        density: Double,
        aesthetic: Double,
        end: Instant,
        weekendBoost: Double,
        favoriteRatio: Double,
    ): Double {
        val freshness = freshnessFactor(end)
        return (0.3 * normalizeDensity(density)) +
            (0.3 * freshness) +
            (0.2 * aesthetic) +
            (0.1 * weekendBoost) +
            (0.1 * favoriteRatio)
    }

    /**
     * 窗口推荐评分 = 0.3*密度 + 0.25*新鲜度 + 0.2*美学 + 0.15*周末 + 0.1*收藏
     * [Phase 5] 新增收藏加权维度，密度权重从 0.4 降至 0.3
     */
    private fun scoreWindow(
        density: Double,
        aesthetic: Double,
        end: Instant,
        weekendBoost: Double,
        favoriteRatio: Double,
    ): Double {
        val freshness = freshnessFactor(end)
        return (0.3 * normalizeDensity(density)) +
            (0.25 * freshness) +
            (0.2 * aesthetic) +
            (0.15 * weekendBoost) +
            (0.1 * favoriteRatio)
    }

    /**
     * 事件推荐评分 = 0.2*密度 + 0.25*新鲜度 + 0.25*美学 + 0.1*周末 + 0.1*规模 + 0.1*收藏
     * [Phase 5] 新增收藏加权维度，密度权重从 0.3 降至 0.2
     */
    private fun scoreEvent(
        density: Double,
        aesthetic: Double,
        end: Instant,
        weekendBoost: Double,
        size: Int,
        favoriteRatio: Double,
    ): Double {
        val freshness = freshnessFactor(end)
        val sizeFactor = 1.0 - exp(-size / 10.0) // 规模越大加分越多，饱和于 1
        return (0.2 * normalizeDensity(density)) +
            (0.25 * freshness) +
            (0.25 * aesthetic) +
            (0.1 * weekendBoost) +
            (0.1 * sizeFactor) +
            (0.1 * favoriteRatio)
    }

    // ---- 辅助函数 ----

    /** 新鲜度因子：越近拍摄分越高，30 天内快速衰减，使用对数衰减保证确定性
     *  注：保留原有逻辑（1 - exp(-daysAgo/30)），越老分越高，符合用户"旧图看得少"习惯 */
    private fun freshnessFactor(end: Instant): Double {
        val now = Instant.now(clock)
        val daysAgo = Duration.between(end, now).toDays().coerceAtLeast(0)
        // 1 - exp(-days/30)，30 天内衰减到 ~63%
        return 1 - exp(-daysAgo / 30.0)
    }

    /** 密度归一化：使用对数压缩，避免极端密度值主导评分 */
    private fun normalizeDensity(density: Double): Double {
        if (density <= 0) return 0.0
        // ln(1 + density) / ln(1 + 10) 将密度 ~10 映射到 ~1.0
        return (ln(1.0 + density) / ln(11.0)).coerceIn(0.0, 1.0)
    }

    /** 平均美学评分（qualityScore 已在 0–1 范围） */
    private fun averageAestheticScore(items: List<MediaItem>): Double {
        if (items.isEmpty()) return 0.0
        return items.map { it.qualityScore.toDouble() }.average().coerceIn(0.0, 1.0)
    }

    /** 周末加权：组内周末拍摄占比 */
    private fun weekendBoost(items: List<MediaItem>): Double {
        if (items.isEmpty()) return 0.0
        val weekendCount = items.count {
            val day = LocalDate.ofInstant(it.capturedAt, ZoneId.systemDefault()).dayOfWeek
            day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        }
        return weekendCount.toDouble() / items.size
    }

    /** [Phase 5] 收藏占比：组内收藏图片比例，范围 [0, 1] */
    private fun favoriteRatio(items: List<MediaItem>): Double {
        if (items.isEmpty()) return 0.0
        return items.count { it.isFavorite }.toDouble() / items.size
    }

    /** 构建推荐理由文本 */
    private fun buildReason(aesthetic: Double, weekendBoost: Double, favoriteRatio: Double, base: String): String {
        val parts = mutableListOf(base)
        if (aesthetic > 0.6) parts += "高质量照片"
        if (weekendBoost > 0.5) parts += "周末拍摄"
        if (favoriteRatio > 0.2) parts += "含收藏"
        return parts.joinToString("·")
    }

    /** Haversine 公式计算两点间距离（公里） */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // 地球半径（公里）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun deduplicateWindows(candidates: List<Recommendation>): List<Recommendation> {
        val selected = mutableListOf<Recommendation>()
        candidates
            .sortedByDescending { it.score }
            .forEach { candidate ->
                val overlap = selected.any { chosen ->
                    val minEnd = minOf(chosen.windowEnd, candidate.windowEnd)
                    val maxStart = maxOf(chosen.windowStart, candidate.windowStart)
                    !minEnd.isBefore(maxStart.minus(Duration.ofDays(3)))
                }
                if (!overlap) selected += candidate
            }
        return selected
    }

    private fun spanDays(mediaItems: List<MediaItem>): Long {
        val sorted = mediaItems.sortedBy { it.capturedAt }
        val start = sorted.first().capturedAt
        val end = sorted.last().capturedAt
        return Duration.between(start, end).toDays().coerceAtLeast(1)
    }
}
