package com.renyxin.localalbum.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.DirectoryMediaQuery
import com.renyxin.localalbum.core.model.DirectoryMediaSort
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow

enum class SortMode(val label: String) {
    NAME_ASC("名称 A → Z"),
    NAME_DESC("名称 Z → A"),
    DATE_NEWEST("拍摄日期 新 → 旧"),
    DATE_OLDEST("拍摄日期 旧 → 新"),
    MODIFIED_NEWEST("修改日期 新 → 旧"),
    MODIFIED_OLDEST("修改日期 旧 → 新"),
    SIZE_DESC("文件大小 大 → 小"),
    SIZE_ASC("文件大小 小 → 大"),
}

enum class MediaFilter(val label: String) {
    ALL("全部"),
    IMAGES_ONLY("仅图片"),
    VIDEOS_ONLY("仅视频"),
}

enum class ViewMode(val label: String) {
    GRID("网格视图"),
    DATE_GROUP("按日期分组"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    mediaPaging: (DirectoryMediaQuery) -> Flow<PagingData<MediaItem>>,
    onBack: () -> Unit,
    onMediaClick: (MediaItem, DirectoryMediaQuery) -> Unit,
    sortMode: SortMode = SortMode.DATE_NEWEST,
    onSortModeChange: (SortMode) -> Unit = {},
    filter: MediaFilter = MediaFilter.ALL,
    onFilterChange: (MediaFilter) -> Unit = {},
    viewMode: ViewMode = ViewMode.GRID,
    onViewModeChange: (ViewMode) -> Unit = {},
    onDeleteMediaItems: (List<String>) -> Unit = {},
    onBatchSetFavorite: (List<String>, Boolean) -> Unit = { _, _ -> },
    onSetCover: (String) -> Unit = {},
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    // 多选模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val directoryQuery = remember(album.directoryPath, sortMode, filter) {
        DirectoryMediaQuery(
            directoryPath = album.directoryPath,
            mediaType = when (filter) {
                MediaFilter.ALL -> null
                MediaFilter.IMAGES_ONLY -> MediaType.IMAGE
                MediaFilter.VIDEOS_ONLY -> MediaType.VIDEO
            },
            sort = when (sortMode) {
                SortMode.NAME_ASC -> DirectoryMediaSort.NAME_ASC
                SortMode.NAME_DESC -> DirectoryMediaSort.NAME_DESC
                SortMode.DATE_NEWEST -> DirectoryMediaSort.DATE_DESC
                SortMode.DATE_OLDEST -> DirectoryMediaSort.DATE_ASC
                SortMode.MODIFIED_NEWEST -> DirectoryMediaSort.MODIFIED_DESC
                SortMode.MODIFIED_OLDEST -> DirectoryMediaSort.MODIFIED_ASC
                SortMode.SIZE_DESC -> DirectoryMediaSort.SIZE_DESC
                SortMode.SIZE_ASC -> DirectoryMediaSort.SIZE_ASC
            },
        )
    }
    // 查询身份变化时创建新 Pager；UI 不再对当前 Paging 窗口做全局筛选或排序。
    val pagingFlow = remember(directoryQuery) { mediaPaging(directoryQuery) }
    val pagedItems = pagingFlow.collectAsLazyPagingItems()
    val loadedItems = pagedItems.itemSnapshotList.items

    // 确认删除对话框
    if (showDeleteConfirm) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 $count 个媒体文件吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val paths = selectedPaths.keys.toList()
                    onDeleteMediaItems(paths)
                    selectedPaths.clear()
                    isSelectionMode = false
                    showDeleteConfirm = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // 多选模式顶栏
                TopAppBar(
                    title = {
                        Text("已选择 ${selectedPaths.size} 项")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedPaths.clear()
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, "退出多选")
                        }
                    },
                    actions = {
                        // 全选/取消全选
                        IconButton(onClick = {
                            if (selectedPaths.size == loadedItems.size) {
                                selectedPaths.clear()
                            } else {
                                loadedItems.forEach { selectedPaths[it.filePath] = true }
                            }
                        }) {
                            Icon(
                                imageVector = if (selectedPaths.size == loadedItems.size) Icons.Default.CheckCircle
                                    else Icons.Default.SelectAll,
                                contentDescription = "全选",
                            )
                        }
                        // 设为封面按钮 (单选)
                        IconButton(onClick = {
                            if (selectedPaths.size == 1) {
                                onSetCover(selectedPaths.keys.first())
                                selectedPaths.clear()
                                isSelectionMode = false
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Star,
                                contentDescription = "设为封面",
                                tint = if (selectedPaths.size != 1) MaterialTheme.colorScheme.outline
                                    else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // 收藏按钮
                        IconButton(onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                onBatchSetFavorite(selectedPaths.keys.toList(), true)
                                selectedPaths.clear()
                                isSelectionMode = false
                            }
                        }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "收藏",
                                tint = if (selectedPaths.isEmpty()) MaterialTheme.colorScheme.outline
                                    else MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 删除按钮
                        IconButton(onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                showDeleteConfirm = true
                            }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = if (selectedPaths.isEmpty()) MaterialTheme.colorScheme.outline
                                    else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            } else {
                // 普通模式顶栏
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${album.mediaCount} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        @Suppress("DEPRECATION")
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, "排序")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = mode.label,
                                                fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            onSortModeChange(mode)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(Icons.Default.FilterList, "筛选")
                            }
                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false },
                            ) {
                                MediaFilter.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = f.label,
                                                fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            onFilterChange(f)
                                            showFilterMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            onViewModeChange(
                                if (viewMode == ViewMode.GRID) ViewMode.DATE_GROUP else ViewMode.GRID,
                            )
                        }) {
                            Icon(
                                imageVector = if (viewMode == ViewMode.GRID) Icons.Default.CalendarMonth else Icons.Default.GridView,
                                contentDescription = "切换视图",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        val clickItem: (MediaItem) -> Unit = { item ->
            if (isSelectionMode) {
                if (selectedPaths.containsKey(item.filePath)) selectedPaths.remove(item.filePath)
                else selectedPaths[item.filePath] = true
            } else {
                onMediaClick(item, directoryQuery)
            }
        }
        val longClickItem: (MediaItem) -> Unit = { item ->
            if (!isSelectionMode) {
                isSelectionMode = true
                selectedPaths[item.filePath] = true
            }
        }
        when (viewMode) {
            ViewMode.GRID -> MediaGrid(
                items = pagedItems,
                isSelectionMode = isSelectionMode,
                selectedPaths = selectedPaths,
                onMediaClick = clickItem,
                onLongClick = longClickItem,
                modifier = Modifier.padding(padding),
            )
            ViewMode.DATE_GROUP -> DateGroupedView(
                items = pagedItems,
                isSelectionMode = isSelectionMode,
                selectedPaths = selectedPaths,
                onMediaClick = clickItem,
                onLongClick = longClickItem,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: LazyPagingItems<MediaItem>,
    isSelectionMode: Boolean,
    selectedPaths: Map<String, Boolean>,
    onMediaClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (items.itemCount == 0) {
        EmptyAlbumContent(modifier)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.filePath },
        ) { index ->
            val item = items[index] ?: return@items
            SelectableMediaThumbnail(
                item = item,
                isSelected = selectedPaths.containsKey(item.filePath),
                isSelectionMode = isSelectionMode,
                onClick = { onMediaClick(item) },
                onLongClick = { onLongClick(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateGroupedView(
    items: LazyPagingItems<MediaItem>,
    isSelectionMode: Boolean,
    selectedPaths: Map<String, Boolean>,
    onMediaClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (items.itemCount == 0) {
        EmptyAlbumContent(modifier)
        return
    }

    // 仅做展示分组，不改变数据库返回的全局顺序；跨页同日允许出现续接标题。
    val loaded = items.itemSnapshotList.items
    val grouped = remember(loaded) {
        loaded.groupBy {
            LocalDate.ofInstant(it.capturedAt, ZoneId.systemDefault())
        }.toList()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        grouped.forEach { (date, dateItems) ->
            item(key = "header-$date") {
                Text(
                    text = formatDateHeader(date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            items(
                count = dateItems.size,
                key = { index -> dateItems[index].filePath },
            ) { index ->
                val item = dateItems[index]
                SelectableDateGroupMediaRow(
                    item = item,
                    isSelected = selectedPaths.containsKey(item.filePath),
                    isSelectionMode = isSelectionMode,
                    onClick = { onMediaClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableDateGroupMediaRow(
    item: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelectionMode) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "已选择" else "未选择",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 8.dp),
            )
        }
        Box(modifier = Modifier.size(56.dp)) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.thumbnailPath ?: item.filePath)
                    .crossfade(true)
                    .size(120, 120)
                    .build(),
                contentDescription = item.fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            if (item.type == MediaType.VIDEO) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "视频",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDateTime(item.capturedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isSelectionMode) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            ),
        contentAlignment = Alignment.BottomEnd,
    ) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.thumbnailPath ?: item.filePath)
                .crossfade(true)
                .size(300, 300)
                .build(),
            contentDescription = item.fileName,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        // 多选模式下的选择指示器
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "已选择" else "未选择",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableMediaThumbnail(
    item: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    MediaThumbnail(
        item = item,
        onClick = onClick,
        isSelected = isSelected,
        isSelectionMode = isSelectionMode,
        onLongClick = onLongClick,
    )
}

@Composable
private fun EmptyAlbumContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "此相册中没有符合筛选条件的媒体",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun formatDateHeader(date: LocalDate): String {
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

private fun formatDateTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}