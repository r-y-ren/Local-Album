package com.renyxin.localalbum.ui.screens

/**
 * 照片 Tab 区块（自 LocalAlbumApp.kt 纯 cut-paste 提取，行为不变）。
 *
 * 内容：时间线 + 顶部快捷入口卡片的照片 Tab（[PhotosTab]、[QuickAccessRow]、
 * [QuickAccessCard]）、手势引导浮层（[GestureGuideOverlay]）
 * 与冷启动恢复副作用（[RememberRestoreEffect]）。
 */
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.edition.EditionSearchContribution
import com.renyxin.localalbum.edition.EditionUiContribution
import com.renyxin.localalbum.ui.EmptyStateView
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotosTab(
    viewModel: AlbumViewModel,
    onMediaClick: (MediaItem) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicate: () -> Unit,
    onNavigateToEditionDestination: (String) -> Unit,
    onNavigateToRecommendations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    // Paging 3 是时间线唯一的媒体数据源，禁止维护全库 List 镜像。
    val pagedItems = viewModel.pagedMedia.collectAsLazyPagingItems()

    if (scanState is ScanState.Scanning && pagedItems.itemCount == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在加载时间线…", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    if (pagedItems.itemCount == 0) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "暂无媒体文件",
            description = "请在设置中添加扫描目录并开始扫描。",
            buttonText = "前往设置",
            onButtonClick = { /* handled by tab switch */ },
            modifier = modifier,
        )
        return
    }

    // 顶部快捷入口卡片 + 分页时间线网格
    // 注意：不能将 LazyVerticalGrid 嵌套进 LazyColumn 的 item 中（会导致运行时崩溃），
    // 因此通过 headerContent 将快捷入口卡片注入到 PagedTimelineScreen 内部的
    // LazyVerticalGrid 中，作为占满整行的 header item。
    com.renyxin.localalbum.ui.screen.PagedTimelineScreen(
        pagedItems = pagedItems,
        onItemClick = onMediaClick,
        onThumbnailWindowChanged = viewModel::requestGridThumbnails,
        modifier = modifier.fillMaxSize(),
        headerContent = {
            QuickAccessRow(
                favoritesCount = favoriteCount,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToFavorites = onNavigateToFavorites,
                onNavigateToDuplicate = onNavigateToDuplicate,
                onNavigateToEditionDestination = onNavigateToEditionDestination,
                onNavigateToRecommendations = onNavigateToRecommendations,
            )
        },
    )
}

/**
 * 顶部快捷入口横向卡片行，对齐谷歌相册的"回忆/收藏/实用工具"入口区。
 */
@Composable
private fun QuickAccessRow(
    favoritesCount: Int,
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicate: () -> Unit,
    onNavigateToEditionDestination: (String) -> Unit,
    onNavigateToRecommendations: () -> Unit,
) {
    val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.foundation.lazy.LazyRow(
        state = scrollState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "favorites") {
            QuickAccessCard(
                icon = Icons.Filled.Favorite,
                title = "收藏",
                subtitle = if (favoritesCount > 0) "$favoritesCount 张" else "查看收藏",
                onClick = onNavigateToFavorites,
            )
        }
        item(key = "recommendations") {
            QuickAccessCard(
                icon = Icons.Filled.AutoAwesome,
                title = "精选",
                subtitle = "智能推荐",
                onClick = onNavigateToRecommendations,
            )
        }
        item(key = "duplicate") {
            QuickAccessCard(
                icon = Icons.Filled.CleaningServices,
                title = "重复照片",
                subtitle = "清理重复",
                onClick = onNavigateToDuplicate,
            )
        }
        item(key = "edition-primary") {
            EditionUiContribution.primaryQuickAccessEntry(
                onNavigate = onNavigateToEditionDestination,
            )
        }
        item(key = "search") {
            QuickAccessCard(
                icon = Icons.Filled.Search,
                title = "搜索",
                subtitle = EditionSearchContribution.quickAccessSubtitle,
                onClick = onNavigateToSearch,
            )
        }
    }
}

/**
 * 单个快捷入口卡片。
 */
@Composable
internal fun QuickAccessCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 112.dp, height = 132.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ---- 手势引导浮层 ---- */

@Composable
internal fun GestureGuideOverlay(
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左滑图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SwipeLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 中间说明文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "左右滑动切换图片",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "双指捏合可缩放·双击快速切换缩放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---- 冷启动恢复 ---- */

@Composable
internal fun RememberRestoreEffect(viewModel: AlbumViewModel) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val tree by viewModel.albumTree.collectAsStateWithLifecycle()

    var didRestore by remember { mutableStateOf(false) }

    LaunchedEffect(scanState, tree) {
        if (!didRestore && scanState == ScanState.Idle && tree.isEmpty()) {
            viewModel.restoreFromDbIfNeeded()
            didRestore = true
        }
    }
}
