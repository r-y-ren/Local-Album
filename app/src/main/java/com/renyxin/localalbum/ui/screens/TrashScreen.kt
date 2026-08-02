package com.renyxin.localalbum.ui.screens

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.saf.MediaStoreDeleteRequest
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TRASH_RETENTION_DAYS = 30L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    trashedItems: LazyPagingItems<MediaItem>,
    totalCount: Int,
    operationState: AlbumViewModel.TrashOperationState,
    onOperationMessageConsumed: () -> Unit,
    onBack: () -> Unit,
    onRestore: (List<String>) -> Unit = {},
    onPermanentlyDelete: (List<String>) -> Unit = {},
    onClearTrash: () -> Unit = {},
    loadAllTrashedPaths: suspend () -> List<String> = { emptyList() },
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val operationInFlight = operationState is AlbumViewModel.TrashOperationState.Running
    var pendingSystemDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingClearTrash by remember { mutableStateOf(false) }

    val systemDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val paths = pendingSystemDeletePaths
        val clear = pendingClearTrash
        pendingSystemDeletePaths = emptyList()
        pendingClearTrash = false
        if (result.resultCode == Activity.RESULT_OK) {
            // 系统已删除文件；Repository 将其识别为 MISSING 并原子清理关联数据。
            if (clear) onClearTrash() else onPermanentlyDelete(paths)
        } else {
            scope.launch { snackbarHostState.showSnackbar("已取消系统删除授权") }
        }
    }

    fun requestPermanentDelete(paths: List<String>, clearTrash: Boolean = false) {
        val distinct = paths.distinct()
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStoreDeleteRequest.create(context, distinct)
        } else null
        if (request != null) {
            pendingSystemDeletePaths = distinct
            pendingClearTrash = clearTrash
            systemDeleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else if (clearTrash) {
            onClearTrash()
        } else {
            onPermanentlyDelete(distinct)
        }
    }

    // 退出选择模式时清空选择
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) selectedPaths.clear()
    }

    // 仅在 Repository 的异步操作真正结束后反馈结果，不再提前宣称成功。
    LaunchedEffect(operationState) {
        val message = when (operationState) {
            is AlbumViewModel.TrashOperationState.Completed -> operationState.message
            is AlbumViewModel.TrashOperationState.Failed -> operationState.message
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            onOperationMessageConsumed()
        }
    }

    // 永久删除确认对话框
    if (showDeleteConfirm) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("永久删除") },
            text = {
                Text("确定要永久删除选中的 $count 个文件吗？此操作不可撤销，文件将无法恢复！")
            },
            confirmButton = {
                TextButton(enabled = !operationInFlight, onClick = {
                    requestPermanentDelete(selectedPaths.keys.toList())
                    selectedPaths.clear()
                    isSelectionMode = false
                    showDeleteConfirm = false
                }) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 清空回收站确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("清空回收站") },
            text = {
                Text("确定要清空回收站中的所有 $totalCount 个文件吗？此操作不可撤销！")
            },
            confirmButton = {
                TextButton(enabled = !operationInFlight, onClick = {
                    scope.launch {
                        requestPermanentDelete(loadAllTrashedPaths(), clearTrash = true)
                    }
                    showClearConfirm = false
                    isSelectionMode = false
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                // 多选模式顶栏
                TopAppBar(
                    title = { Text("已选择 ${selectedPaths.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedPaths.clear()
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, "退出多选")
                        }
                    },
                    actions = {
                        // 恢复按钮
                        IconButton(enabled = !operationInFlight, onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                onRestore(selectedPaths.keys.toList())
                                selectedPaths.clear()
                                isSelectionMode = false
                            }
                        }) {
                            Icon(
                                Icons.Default.RestoreFromTrash,
                                contentDescription = "恢复",
                                tint = if (selectedPaths.isEmpty())
                                    MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 永久删除按钮
                        IconButton(enabled = !operationInFlight, onClick = {
                            if (selectedPaths.isNotEmpty()) showDeleteConfirm = true
                        }) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = "永久删除",
                                tint = if (selectedPaths.isEmpty())
                                    MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("回收站 ($totalCount)") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        if (totalCount > 0) {
                            IconButton(enabled = !operationInFlight, onClick = { showClearConfirm = true }) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = "清空回收站",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (isSelectionMode) {
                // 快捷恢复按钮
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!operationInFlight && selectedPaths.isNotEmpty()) {
                            onRestore(selectedPaths.keys.toList())
                            selectedPaths.clear()
                            isSelectionMode = false
                        }
                    },
                    icon = { Icon(Icons.Default.RestoreFromTrash, "恢复") },
                    text = { Text("恢复 (${selectedPaths.size})") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }
        },
    ) { padding ->
        AnimatedVisibility(
            visible = operationInFlight,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (totalCount == 0 && trashedItems.loadState.refresh !is androidx.paging.LoadState.Loading) {
            // 空回收站 —— 增强空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "回收站为空",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "被移到回收站的媒体文件将在这里显示",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "文件将在 $TRASH_RETENTION_DAYS 天后自动永久删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            return@Scaffold
        }

        // 天数提示条仅依据当前分页快照，不触发完整回收站加载。
        val now = Instant.now()
        val oldestDeletedAt = trashedItems.itemSnapshotList.items
            .filter { it.deletedAtMs > 0 }
            .minOfOrNull { it.deletedAtMs }
        val retentionBannerDays = if (oldestDeletedAt != null) {
            val deletedInstant = Instant.ofEpochMilli(oldestDeletedAt)
            val remaining = TRASH_RETENTION_DAYS - Duration.between(deletedInstant, now).toDays()
            remaining.coerceAtLeast(0)
        } else null

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 提示横幅
            if (retentionBannerDays != null && !isSelectionMode) {
                item(key = "banner") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (retentionBannerDays > 0) {
                                        "最早删除的项目将在 $retentionBannerDays 天后被自动清理"
                                    } else {
                                        "部分项目即将被自动清理"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "回收站中的文件保留 $TRASH_RETENTION_DAYS 天后将自动永久删除",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }

            items(
                count = trashedItems.itemCount,
                key = trashedItems.itemKey { it.filePath },
            ) { index ->
                val item = trashedItems[index] ?: return@items
                TrashItemRow(
                    item = item,
                    isSelected = selectedPaths[item.filePath] == true,
                    isSelectionMode = isSelectionMode,
                    onClick = {
                        if (isSelectionMode) {
                            if (selectedPaths[item.filePath] == true) {
                                selectedPaths.remove(item.filePath)
                            } else {
                                selectedPaths[item.filePath] = true
                            }
                        }
                    },
                    onLongClick = {
                        isSelectionMode = true
                        selectedPaths[item.filePath] = true
                    },
                    onRestore = {
                        if (!operationInFlight) onRestore(listOf(item.filePath))
                    },
                    now = now,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashItemRow(
    item: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    now: Instant,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选择框
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected)
                        Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "已选中" else "未选中",
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                )
            }

            // 缩略图
            Box(modifier = Modifier.size(56.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.thumbnailPath ?: item.filePath)
                        .crossfade(true)
                        .size(112)
                        .build(),
                    contentDescription = item.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                if (item.type == MediaType.VIDEO) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "视频",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 文件信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatFileSize(item.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (item.deletedAtMs > 0) {
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = formatRemainingDays(item.deletedAtMs, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.deletedAtMs > 0) {
                                val remaining = TRASH_RETENTION_DAYS -
                                    Duration.between(Instant.ofEpochMilli(item.deletedAtMs), now).toDays()
                                if (remaining <= 3) MaterialTheme.colorScheme.error
                                else if (remaining <= 7) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline
                            } else MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Text(
                    text = item.filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 恢复按钮（非选择模式下显示）
            if (!isSelectionMode) {
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Default.RestoreFromTrash,
                        contentDescription = "恢复",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun formatRemainingDays(deletedAtMs: Long, now: Instant): String {
    if (deletedAtMs <= 0) return ""
    val deletedInstant = Instant.ofEpochMilli(deletedAtMs)
    val remainingDays = TRASH_RETENTION_DAYS - Duration.between(deletedInstant, now).toDays()
    return when {
        remainingDays <= 0 -> "即将清理"
        remainingDays == 1L -> "剩余 1 天"
        else -> "剩余 $remainingDays 天"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Suppress("unused")
private fun formatDateTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}