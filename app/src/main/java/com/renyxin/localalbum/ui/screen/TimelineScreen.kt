package com.renyxin.localalbum.ui.screen

import android.provider.MediaStore
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.core.timeline.TimelineGrouper
import com.renyxin.localalbum.core.timeline.TimelineSection
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 时间线主屏。
 *
 * 布局：
 * - TopAppBar: 标题 "照片" + 年份滑块快速跳转
 * - ScrollableYearStrip: 水平滑动年份条
 * - Sectioned LazyColumn: 年/月/日分组 + 自适应网格
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    items: List<MediaEntity>,
    onItemClick: (MediaEntity) -> Unit = {},
    onYearSelected: (Int) -> Unit = {},
    onBack: (() -> Unit)? = null,
    title: String = "照片",
    modifier: Modifier = Modifier,
) {
    val grouper = remember { TimelineGrouper() }
    val sections = remember(items) { grouper.groupFlat(items) }
    val availableYears = remember(items) { grouper.getAvailableYears(items) }

    var selectedYear by remember(items) { mutableIntStateOf(availableYears.lastOrNull() ?: 2024) }
    var showYearPicker by remember { mutableStateOf(false) }

    // 检测当前可视年份，用于滑块同步
    var currentVisibleYear by remember { mutableIntStateOf(availableYears.lastOrNull() ?: 2024) }

    Column(modifier = modifier.fillMaxSize()) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                }
            },
            actions = {
                // 年份信息
                if (availableYears.isNotEmpty()) {
                    Text(
                        text = "${availableYears.first()} - ${availableYears.last()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        // 年份滑动条 (Year Slider)
        if (availableYears.size > 1 && showYearPicker) {
            YearSlider(
                years = availableYears,
                selectedYear = selectedYear,
                onYearChange = { year ->
                    selectedYear = year
                    onYearSelected(year)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // 主时间线列表
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // 对每个 section 分组
            val yearSections = sections.groupBy { it.year }
            val sortedYears = yearSections.keys.sortedDescending()

            for (year in sortedYears) {
                val yearSectionList = yearSections[year] ?: continue
                val yearLabel = "${year}年"

                // 年标题 (粘性头部)
                stickyHeader(key = "year_$year") {
                    YearHeader(yearLabel, itemCount = yearSectionList.sumOf { it.itemCount })
                }

                val daySections = yearSectionList.filter { it.day != null }

                if (daySections.isNotEmpty()) {
                    items(
                        items = daySections,
                        key = { section -> "day_${section.year}_${section.month}_${section.day}" }
                    ) { section ->
                        // 日标题
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = section.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${section.itemCount} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 自适应网格
                        AdaptiveMediaGrid(
                            items = section.items,
                            onItemClick = onItemClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自适应列数媒体网格。
 * 列数根据屏幕宽度动态调整：
 * - < 360dp → 3列
 * - 360-600dp → 4列
 * - 600-840dp → 5列
 * - > 840dp → 6列
 */
@Composable
fun AdaptiveMediaGrid(
    items: List<MediaEntity>,
    onItemClick: (MediaEntity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = remember(screenWidthDp) {
        when {
            screenWidthDp < 360 -> 3
            screenWidthDp < 600 -> 4
            screenWidthDp < 840 -> 5
            else -> 6
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val rows = items.chunked(columns)
        for (row in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (item in row) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .clickable { onItemClick(item) },
                    ) {
                        ThumbnailCell(item)
                    }
                }
                // 填充空位，保持对齐
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 缩略图单元格。
 */
@Composable
fun ThumbnailCell(item: MediaEntity, modifier: Modifier = Modifier) {
    val uri = android.net.Uri.parse("file://${item.thumbnailPath ?: item.filePath}")

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(uri)
                .crossfade(true)
                .size(256)
                .build(),
            contentDescription = item.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // 视频标记
        if (item.mediaType == com.renyxin.localalbum.core.model.MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "视频",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // 收藏标记（右上角星标）
        if (item.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "已收藏",
                tint = Color(0xFFFFC107),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(14.dp),
            )
        }
    }
}

/**
 * 年份滑块组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearSlider(
    years: List<Int>,
    selectedYear: Int,
    onYearChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (years.isEmpty()) return

    val minYear = years.first()
    val maxYear = years.last()
    var sliderPosition by remember(selectedYear) {
        mutableFloatStateOf(years.indexOf(selectedYear).coerceIn(0, years.size - 1).toFloat())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$minYear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$selectedYear",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "$maxYear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Slider(
            value = sliderPosition,
            onValueChange = { value ->
                sliderPosition = value
                val index = value.roundToInt().coerceIn(0, years.size - 1)
                onYearChange(years[index])
            },
            valueRange = 0f..(years.size - 1).toFloat(),
            steps = (years.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * 年标题（粘性头部）。
 */
@Composable
fun YearHeader(yearLabel: String, itemCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = yearLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "$itemCount 张",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Paging 3 版本的时间线屏幕。
 *
 * 使用 [LazyVerticalGrid] + [collectAsLazyPagingItems] 实现分段无限滚动，
 * 替代旧版 [TimelineScreen] 的全量 [List] 加载方式，避免大型媒体库内存溢出。
 *
 * 布局简化为自适应列数网格（无年/月/日分组头部），适合分页场景。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagedTimelineScreen(
    pagedItems: androidx.paging.compose.LazyPagingItems<MediaItem>,
    onItemClick: (MediaItem) -> Unit = {},
    onThumbnailWindowChanged: (visible: List<MediaItem>, prefetch: List<MediaItem>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    headerContent: (@androidx.compose.runtime.Composable androidx.compose.foundation.lazy.grid.LazyGridItemScope.() -> Unit)? = null,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = remember(screenWidthDp) {
        when {
            screenWidthDp < 360 -> 3
            screenWidthDp < 600 -> 4
            screenWidthDp < 840 -> 5
            else -> 6
        }
    }

    val gridState = rememberLazyGridState()
    val headerOffset = if (headerContent == null) 0 else 1
    LaunchedEffect(gridState, pagedItems, headerOffset) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { gridIndices ->
                val mediaIndices = gridIndices.map { it - headerOffset }
                    .filter { it in 0 until pagedItems.itemCount }
                val visible = mediaIndices.mapNotNull { pagedItems.peek(it) }
                val after = (mediaIndices.maxOrNull() ?: -1) + 1
                val prefetch = (after until minOf(after + 20, pagedItems.itemCount))
                    .mapNotNull { pagedItems.peek(it) }
                onThumbnailWindowChanged(visible, prefetch)
            }
    }

    val refreshLoadState = pagedItems.loadState.refresh
    val appendLoadState = pagedItems.loadState.append

    Box(modifier = modifier.fillMaxSize()) {
        if (refreshLoadState is LoadState.Loading && pagedItems.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在加载时间线…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (refreshLoadState is LoadState.Error) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "加载失败: ${refreshLoadState.error.message}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (pagedItems.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无媒体文件", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 可选的头部内容（占满整行），用于注入快捷入口卡片等
                if (headerContent != null) {
                    item(
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) },
                        content = headerContent,
                    )
                }

                // 使用 paging-compose 的 items 扩展（LazyGridScope）
                items(
                    count = pagedItems.itemCount,
                    key = pagedItems.itemKey { it.filePath },
                ) { index ->
                    val mediaItem = pagedItems[index]
                    mediaItem?.let {
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .clickable { onItemClick(it) }
                                .animateItemPlacement(),
                        ) {
                            PagedThumbnailCell(it)
                        }
                    }
                }

                // 底部加载更多指示器
                if (appendLoadState is LoadState.Loading) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分页缩略图单元格（基于 [MediaItem]）。
 */
@Composable
private fun PagedThumbnailCell(item: MediaItem, modifier: Modifier = Modifier) {
    val uri = android.net.Uri.parse("file://${item.thumbnailPath ?: item.filePath}")

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(uri)
                .crossfade(true)
                .size(256)
                .build(),
            contentDescription = item.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // 视频标记
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "视频",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * 格式化时长 (ms → mm:ss 或 h:mm:ss)。
 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}