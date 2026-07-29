package com.renyxin.localalbum.core.timeline

import com.renyxin.localalbum.data.db.entity.MediaEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 时间线分组模型。
 *
 * 将媒体列表按 年 → 月 → 日 三层分组，
 * 用于时间线主视图的 Sectioned LazyColumn 渲染。
 */
data class TimelineSection(
    val year: Int,
    val month: Int? = null,          // null = 年标题
    val day: Int? = null,            // null = 月标题 / 年标题
    val label: String,                // 显示标签，如 "2024", "12月", "25 星期四"
    val itemCount: Int,
    val items: List<MediaEntity>,
    val startTimestamp: Long,         // 该段最早时间戳
)

/**
 * 时间线分组器。
 *
 * 分组规则：
 * - 年标题：占满宽度，大号字体
 * - 月标题：左侧小标签
 * - 日标题：左侧日期 + 星期，右侧物品数量
 */
class TimelineGrouper {

    companion object {
        private val ZONE = ZoneId.systemDefault()
        private val YEAR_FMT = DateTimeFormatter.ofPattern("yyyy年")
        private val MONTH_FMT = DateTimeFormatter.ofPattern("M月")
        private const val MIN_ITEMS_PER_DAY_SECTION = 1
    }

    /**
     * 将媒体列表分组为时间线 Section 列表。
     */
    fun group(items: List<MediaEntity>): List<TimelineSection> {
        if (items.isEmpty()) return emptyList()

        val sections = mutableListOf<TimelineSection>()

        // 按日期降序排序（最新在前）
        val sorted = items.sortedByDescending { it.capturedAtMs }

        // 按年/月/日分组
        val byYear = sorted.groupBy { yearOf(it.capturedAtMs) }
        val years = byYear.keys.sortedDescending()

        for (year in years) {
            val yearItems = byYear[year] ?: continue
            sections.add(TimelineSection(
                year = year,
                label = "${year}年",
                itemCount = yearItems.size,
                items = emptyList(), // 年标题不含 items
                startTimestamp = yearItems.first().capturedAtMs,
            ))

            // 按月分组
            val byMonth = yearItems.groupBy { monthOf(it.capturedAtMs) }
            val months = byMonth.keys.sortedDescending()

            for (month in months) {
                val monthItems = byMonth[month] ?: continue
                sections.add(TimelineSection(
                    year = year,
                    month = month,
                    label = "${month + 1}月",
                    itemCount = monthItems.size,
                    items = emptyList(),
                    startTimestamp = monthItems.first().capturedAtMs,
                ))

                // 按日分组
                val byDay = monthItems.groupBy { dayOf(it.capturedAtMs) }
                val days = byDay.keys.sortedDescending()

                for (day in days) {
                    val dayItems = byDay[day] ?: continue
                    val dayLabel = buildDayLabel(month, day, dayItems.first().capturedAtMs)
                    sections.add(TimelineSection(
                        year = year,
                        month = month,
                        day = day,
                        label = dayLabel,
                        itemCount = dayItems.size,
                        items = dayItems,
                        startTimestamp = dayItems.first().capturedAtMs,
                    ))
                }
            }
        }

        return sections
    }

    /**
     * 扁平分组（适合简洁视图，无月标题）。
     */
    fun groupFlat(items: List<MediaEntity>): List<TimelineSection> {
        if (items.isEmpty()) return emptyList()

        val sorted = items.sortedByDescending { it.capturedAtMs }
        val sections = mutableListOf<TimelineSection>()
        val byYear = sorted.groupBy { yearOf(it.capturedAtMs) }
        val years = byYear.keys.sortedDescending()

        for (year in years) {
            val yearItems = byYear[year] ?: continue

            sections.add(TimelineSection(
                year = year,
                label = "${year}年",
                itemCount = yearItems.size,
                items = emptyList(),
                startTimestamp = yearItems.first().capturedAtMs,
            ))

            val byDay = yearItems.groupBy {
                val date = localDateOf(it.capturedAtMs)
                year * 10000 + date.monthValue * 100 + date.dayOfMonth
            }
            val days = byDay.keys.sortedDescending()

            for (dayKey in days) {
                val dayItems = byDay[dayKey] ?: continue
                val dayLabel = buildDayLabel(monthOf(dayItems.first().capturedAtMs),
                    dayOf(dayItems.first().capturedAtMs), dayItems.first().capturedAtMs)
                sections.add(TimelineSection(
                    year = year,
                    month = monthOf(dayItems.first().capturedAtMs),
                    day = dayOf(dayItems.first().capturedAtMs),
                    label = dayLabel,
                    itemCount = dayItems.size,
                    items = dayItems,
                    startTimestamp = dayItems.first().capturedAtMs,
                ))
            }
        }

        return sections
    }

    // ---- 辅助方法 ----

    private fun yearOf(epochMs: Long): Int =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE).year

    private fun monthOf(epochMs: Long): Int =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE).monthValue - 1

    private fun dayOf(epochMs: Long): Int =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE).dayOfMonth

    private fun localDateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate()

    private fun buildDayLabel(month: Int, day: Int, timestamp: Long): String {
        val date = localDateOf(timestamp)
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
        val today = LocalDate.now(ZONE)
        val yesterday = today.minusDays(1)

        val prefix = when (date) {
            today -> "今天"
            yesterday -> "昨天"
            else -> "${month + 1}月${day}日"
        }
        return "$prefix $dayOfWeek"
    }

    /**
     * 年份滑块位置计算。
     * 输入所有年份列表，返回可用于 Slider 的年份数组。
     */
    fun getAvailableYears(items: List<MediaEntity>): List<Int> {
        return items.map { yearOf(it.capturedAtMs) }.distinct().sorted()
    }
}