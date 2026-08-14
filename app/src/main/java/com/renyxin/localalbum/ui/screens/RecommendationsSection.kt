package com.renyxin.localalbum.ui.screens

/**
 * 精选推荐区块（自 LocalAlbumApp.kt 纯 cut-paste 提取，行为不变）。
 *
 * 内容：推荐 Tab 列表页（[RecommendationTab]）、推荐详情页
 * （[RecommendationDetailScreen]）、推荐卡片（[RecommendationCard]）
 * 及 2×2 缩略图预览网格（[RecommendationThumbnailGrid]）。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.recommendation.Recommendation
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.EmptyStateView
import com.renyxin.localalbum.ui.THUMB_REQUEST_SIZE_CARD
import com.renyxin.localalbum.ui.THUMB_REQUEST_SIZE_GRID
import com.renyxin.localalbum.ui.VIDEO_BADGE_PLAY_TEXT
import com.renyxin.localalbum.ui.formatInstant
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecommendationTab(
    viewModel: AlbumViewModel,
    onNavigateToSettings: () -> Unit,
    onRecommendationClick: (Recommendation) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    // 推荐列表由 ViewModel/Repository 持有。返回详情页再回来时只恢复原有批次，
    // 不把进入页面当成一次用户主动刷新。

    Scaffold(
        modifier = modifier,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {
                        Text(
                            text = "精选推荐",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refreshRecommendations() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新推荐",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        // 扫描中若已有候选则继续显示内容，仅在首批结果尚未写入时展示加载态。
        if (scanState is ScanState.Scanning && recommendations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "${(scanState as ScanState.Scanning).message}\n已扫描内容将自动显示",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            return@Scaffold
        }

        if (recommendations.isEmpty()) {
            EmptyStateView(
                icon = Icons.Outlined.AutoAwesome,
                title = "暂无推荐精选",
                description = if (scanState is ScanState.Idle) "尚未进行全量媒体扫描，请先在设置中添加扫描根目录。"
                else "当前选定的扫描目录下暂未匹配到符合推荐条件的相册。",
                buttonText = "前往添加扫描目录",
                onButtonClick = onNavigateToSettings,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recommendations, key = { "${it.albumId}-${it.windowStart}" }) { rec ->
                RecommendationCard(
                    rec = rec,
                    onClick = { onRecommendationClick(rec) },
                )
            }
        }
    }
}

/**
 * 精选推荐详情页（Phase C）。
 *
 * 点击推荐卡片后进入此页面，以缩略图网格展示该推荐中的所有媒体项，
 * 用户可从中选择要预览的具体项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecommendationDetailScreen(
    recommendation: Recommendation,
    onBack: () -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = recommendation.albumName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = recommendation.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (recommendation.mediaItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("该推荐没有媒体项", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val items = recommendation.mediaItems
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items.size, key = { index -> items[index].filePath }) { index ->
                val item = items[index]
                val context = androidx.compose.ui.platform.LocalContext.current
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onMediaClick(items, index) },
                ) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://${item.thumbnailPath ?: item.filePath}"))
                            .crossfade(true)
                            .size(THUMB_REQUEST_SIZE_GRID)
                            .build(),
                        contentDescription = item.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (item.type == com.renyxin.localalbum.core.model.MediaType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = VIDEO_BADGE_PLAY_TEXT,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    rec: Recommendation,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            // 2×2 缩略图预览
            RecommendationThumbnailGrid(
                thumbnailPaths = rec.thumbnailPaths,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = rec.albumName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // Phase 5: 推荐类别标签
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                ) {
                                    Text(
                                        text = rec.category.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                                Text(
                                    text = rec.directoryPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "${rec.mediaItems.size} 张",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        // Phase 5: 收藏数展示
                        if (rec.favoriteCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "${rec.favoriteCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Text(
                    text = rec.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "时间跨度: ${formatInstant(rec.windowStart)} ~ ${formatInstant(rec.windowEnd)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 推荐卡片 2×2 缩略图预览网格。
 * 展示推荐集合中前 4 张媒体文件的缩略图，让用户对推荐内容有直观了解。
 */
@Composable
private fun RecommendationThumbnailGrid(
    thumbnailPaths: List<String>,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (thumbnailPaths.isEmpty()) {
            // 无缩略图时显示图标占位
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        } else if (thumbnailPaths.size == 1) {
            // 仅 1 张，全幅显示
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(android.net.Uri.parse("file://${thumbnailPaths[0]}"))
                    .crossfade(true)
                    .size(THUMB_REQUEST_SIZE_GRID)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (thumbnailPaths.size == 2) {
            // 2 张并排显示
            Row(modifier = Modifier.fillMaxSize()) {
                thumbnailPaths.forEach { path ->
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://$path"))
                            .crossfade(true)
                            .size(THUMB_REQUEST_SIZE_CARD)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        } else {
            // 3+ 张：2×2 网格
            Column(modifier = Modifier.fillMaxSize()) {
                val rows = thumbnailPaths.take(4).chunked(2)
                rows.forEach { rowPaths ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        rowPaths.forEach { path ->
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(android.net.Uri.parse("file://$path"))
                                    .crossfade(true)
                                    .size(THUMB_REQUEST_SIZE_CARD)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // 不足 2 张时填充空位
                        repeat(2 - rowPaths.size) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                }
                // 不足 2 行时填充空行
                repeat(2 - rows.size) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}
