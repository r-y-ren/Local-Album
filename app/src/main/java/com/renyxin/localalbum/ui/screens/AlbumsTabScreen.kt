package com.renyxin.localalbum.ui.screens

/**
 * 相册 Tab 区块（自 LocalAlbumApp.kt 纯 cut-paste 提取，行为不变）。
 *
 * 内容：树形展开 + 平铺网格双视图的相册 Tab（[AlbumsTab]）、树形视图
 * （[AlbumTreeView]/[AlbumTreeRow]）、网格卡片（[AlbumGridCard]）、
 * 2×2 封面（[AlbumCover]）及扁平化工具（[flattenAlbums]/[FlatAlbumRow]）。
 */
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.EmptyStateView
import com.renyxin.localalbum.ui.THUMB_REQUEST_SIZE_COVER
import com.renyxin.localalbum.ui.components.AlbumSyncStatusBanner
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumsTab(
    viewModel: AlbumViewModel,
    onNavigateToSettings: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree by viewModel.albumTree.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val syncState by viewModel.albumSyncState.collectAsStateWithLifecycle()

    // 视图模式：树形展开 / 平铺网格
    var isTreeView by remember { mutableStateOf(true) }

    // 将树形结构扁平化为相册列表（用于平铺网格视图）
    val flatAlbums = remember(tree) { flattenAlbums(tree) }

    // 只有确实没有任何已提交快照时才使用阻塞式首次构建页；
    // 有缓存的冷启动和后台校准因 tree 非空，始终保留旧相册供用户操作。
    if (tree.isEmpty() && scanState is ScanState.Scanning) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在首次建立相册…", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    if (flatAlbums.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "暂无本地相册",
            description = if (scanState is ScanState.Idle) "尚未进行目录扫描，点击下方按钮开始配置与扫描。"
            else "未在选定的扫描根目录下找到包含媒体文件的文件夹。",
            buttonText = "前往选择扫描目录",
            onButtonClick = onNavigateToSettings,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        AlbumSyncStatusBanner(
            state = syncState,
            onRetry = viewModel::rescan,
        )
        // 视图切换栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = isTreeView,
                onClick = { isTreeView = true },
                label = { Text("树形") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !isTreeView,
                onClick = { isTreeView = false },
                label = { Text("网格") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        if (isTreeView) {
            AlbumTreeView(
                tree = tree,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(flatAlbums, key = { it.id }) { album ->
                    AlbumGridCard(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * 树形展开视图：以可折叠/展开的目录树形式展示相册层级。
 *
 * - 根节点默认展开，子节点默认折叠。
 * - 每行显示展开箭头、封面缩略图、相册名、子相册数与媒体总数。
 * - 点击有子相册的行可展开/折叠；点击行主体打开相册详情。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTreeView(
    tree: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开状态映射：albumId -> 是否展开
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

    // 首次加载时默认展开根节点
    LaunchedEffect(tree) {
        if (expandedIds.keys.isEmpty() && tree.isNotEmpty()) {
            tree.forEach { expandedIds[it.id] = true }
        }
    }

    // 根据展开状态将树扁平化为可见行列表。
    // expandedIds 是 SnapshotStateMap，在 Composable 主体内读取会被快照系统追踪，
    // 任意展开状态变化都会触发重组并重新计算 visibleRows。
    val visibleRows = buildList {
        fun collect(albums: List<Album>, depth: Int) {
            albums.forEach { album ->
                val isExpanded = expandedIds[album.id] ?: false
                add(FlatAlbumRow(album, depth, isExpanded))
                if (album.children.isNotEmpty() && isExpanded) {
                    collect(album.children, depth + 1)
                }
            }
        }
        collect(tree, 0)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(visibleRows, key = { it.album.id }) { row ->
            AlbumTreeRow(
                album = row.album,
                depth = row.depth,
                isExpanded = row.isExpanded,
                onToggleExpand = {
                    expandedIds[row.album.id] = !(expandedIds[row.album.id] ?: false)
                },
                onClick = {
                    // 有直接媒体或是叶子节点 → 打开详情；否则切换展开状态
                    if (row.album.mediaCount > 0 || row.album.isLeaf) {
                        onAlbumClick(row.album)
                    } else {
                        expandedIds[row.album.id] = !(expandedIds[row.album.id] ?: false)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * 树形视图中的单行相册：展开箭头 + 封面 + 名称 + 统计 + 右箭头。
 */
@Composable
private fun AlbumTreeRow(
    album: Album,
    depth: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasChildren = album.children.isNotEmpty()
    val indent = (depth * 16).dp

    // 展开箭头旋转动画
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        label = "arrow_rotation",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent, end = 8.dp, top = 2.dp, bottom = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 展开/折叠箭头（仅有子相册时可点击，叶子节点显示占位）
            if (hasChildren) {
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        modifier = Modifier.rotate(arrowRotation),
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }

            // 封面缩略图
            AlbumCover(
                album = album,
                size = 48.dp,
                modifier = Modifier,
            )

            Spacer(Modifier.width(12.dp))

            // 名称 + 统计
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasChildren) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val subText = if (hasChildren) {
                    "含 ${album.leafCount} 个子相册 · 共 ${album.totalMediaCount} 项"
                } else {
                    "${album.mediaCount} 项"
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右箭头指示可点击进入
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 扁平化相册树为列表（用于网格视图），仅保留含媒体的相册并按媒体数倒序。
 */
private fun flattenAlbums(tree: List<Album>): List<Album> {
    val result = mutableListOf<Album>()
    fun collect(albums: List<Album>) {
        albums.forEach { album ->
            result.add(album)
            if (album.children.isNotEmpty()) collect(album.children)
        }
    }
    collect(tree)
    return result.filter { it.mediaCount > 0 }.sortedByDescending { it.mediaCount }
}

/**
 * 树形视图扁平化后的行数据。
 */
private data class FlatAlbumRow(
    val album: Album,
    val depth: Int,
    val isExpanded: Boolean,
)

/**
 * 相册网格卡片：2×2 封面 + 名称 + 媒体数，对齐谷歌相册的相册卡片样式。
 */
@Composable
private fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumCover(
                album = album,
                size = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${album.mediaCount} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 相册封面组件：自动合成 2×2 缩略图网格。
 * - 有媒体时取前 4 张路径组成 2×2 网格
 * - 不足 4 张时用已有项填充空位
 * - 无媒体时显示文件夹图标占位
 */
@Composable
private fun AlbumCover(
    album: com.renyxin.localalbum.core.model.Album,
    size: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val coverPaths = album.coverPaths
    val context = androidx.compose.ui.platform.LocalContext.current

    val baseModifier = if (size != null) {
        modifier.size(size)
    } else {
        modifier
    }

    Box(
        modifier = baseModifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (coverPaths.isEmpty()) {
            // 无媒体，显示文件夹图标
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size((size ?: 96.dp) * 0.6f),
            )
        } else if (coverPaths.size == 1) {
            // 仅 1 张，全幅显示
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(android.net.Uri.parse("file://${coverPaths[0]}"))
                    .crossfade(true)
                    .size(128)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 2×2 网格
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                val rows = coverPaths.chunked(2)
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
                                    .size(THUMB_REQUEST_SIZE_COVER)
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
