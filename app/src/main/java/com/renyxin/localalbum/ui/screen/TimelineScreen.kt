package com.renyxin.localalbum.ui.screen

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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 分页时间线（Paging 3）。
 *
 * 旧版全量列表时间线（TimelineScreen/AdaptiveMediaGrid/YearSlider 等）与 core/timeline
 * 的 TimelineGrouper 已于 2026-10 治理删除——时间线唯一数据源是 Paging，禁止维护全库 List 镜像。
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
        // 已加载项目始终优先于 refresh 状态，避免后台 refresh 暂时遮掉现有快照。
        if (pagedItems.itemCount > 0) {
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
                                .animateItem(),
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
        } else if (refreshLoadState is LoadState.Loading) {
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
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无媒体文件", style = MaterialTheme.typography.bodyLarge)
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
