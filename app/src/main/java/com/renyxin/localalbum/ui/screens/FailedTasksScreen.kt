package com.renyxin.localalbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.model.FailedTaskItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 失败任务页：列出分析/缩略图持续失败（大概率是损坏文件）的媒体，
 * 由用户逐项或批量裁决——忽略（保留文件、标记损坏）或移入回收站（可恢复）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FailedTasksScreen(
    items: List<FailedTaskItem>,
    operationState: AlbumViewModel.FailedTaskOperationState,
    onOperationMessageConsumed: () -> Unit,
    onIgnore: (List<String>) -> Unit,
    onDelete: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var showIgnoreConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 单行动作打开的弹窗：取消需清掉临时选中并退出多选；
    // 多选工具栏打开的弹窗：取消保留原选择，回到弹窗前状态。
    var singleActionDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val operationInFlight = operationState is AlbumViewModel.FailedTaskOperationState.Running

    fun requestIgnore(paths: List<String>) {
        if (paths.isNotEmpty()) onIgnore(paths)
        selectedPaths.clear()
        isSelectionMode = false
    }

    fun requestDelete(paths: List<String>) {
        if (paths.isNotEmpty()) onDelete(paths)
        selectedPaths.clear()
        isSelectionMode = false
    }

    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) selectedPaths.clear()
    }

    LaunchedEffect(operationState) {
        val message = when (operationState) {
            is AlbumViewModel.FailedTaskOperationState.Completed -> operationState.message
            is AlbumViewModel.FailedTaskOperationState.Failed -> operationState.message
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            onOperationMessageConsumed()
        }
    }

    fun dismissDialog() {
        showIgnoreConfirm = false
        showDeleteConfirm = false
        if (singleActionDialog) {
            selectedPaths.clear()
            isSelectionMode = false
        }
    }

    if (showIgnoreConfirm) {
        FailedTaskActionDialog(
            title = "忽略失败任务",
            body = "将忽略 ${selectedPaths.size} 个文件的失败任务：文件保留在图库中，" +
                "标记为损坏并不再自动重试分析/缩略图。",
            confirmText = "忽略",
            confirmTint = MaterialTheme.colorScheme.tertiary,
            icon = Icons.Outlined.Report,
            iconTint = MaterialTheme.colorScheme.tertiary,
            enabled = !operationInFlight,
            onConfirm = {
                showIgnoreConfirm = false
                requestIgnore(selectedPaths.keys.toList())
            },
            onDismiss = ::dismissDialog,
        )
    }

    if (showDeleteConfirm) {
        FailedTaskActionDialog(
            title = "移入回收站",
            body = "将把 ${selectedPaths.size} 个文件移入回收站（30 天内可恢复），并终止其失败任务。",
            confirmText = "移入回收站",
            confirmTint = MaterialTheme.colorScheme.error,
            icon = Icons.Outlined.DeleteForever,
            iconTint = MaterialTheme.colorScheme.error,
            enabled = !operationInFlight,
            onConfirm = {
                showDeleteConfirm = false
                requestDelete(selectedPaths.keys.toList())
            },
            onDismiss = ::dismissDialog,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
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
                        IconButton(enabled = !operationInFlight, onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                singleActionDialog = false
                                showIgnoreConfirm = true
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Report,
                                contentDescription = "忽略",
                                tint = if (selectedPaths.isEmpty()) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        IconButton(enabled = !operationInFlight, onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                singleActionDialog = false
                                showDeleteConfirm = true
                            }
                        }) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = "移入回收站",
                                tint = if (selectedPaths.isEmpty()) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("失败任务 (${items.size})") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        if (items.isNotEmpty()) {
                            TextButton(
                                enabled = !operationInFlight,
                                onClick = {
                                    selectedPaths.putAll(
                                        items.map { it.filePath to true },
                                    )
                                    isSelectionMode = true
                                },
                            ) { Text("全选") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        AnimatedVisibility(
            visible = operationInFlight,
            enter = androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.shrinkVertically(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "没有失败任务",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "分析或缩略图持续失败的文件会出现在这里，由你决定忽略或删除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hint") {
                Text(
                    text = "这些文件的分析/缩略图已重试多次仍失败。" +
                        "可选择忽略（保留文件、不再重试）或移入回收站。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(items.size, key = { "${items[it].kind.name}:${items[it].filePath}" }) { index ->
                val item = items[index]
                FailedTaskRow(
                    item = item,
                    isSelected = selectedPaths[item.filePath] == true,
                    isSelectionMode = isSelectionMode,
                    operationInFlight = operationInFlight,
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
                    onIgnore = {
                        if (!operationInFlight) {
                            selectedPaths.putAll(listOf(item.filePath to true))
                            singleActionDialog = true
                            showIgnoreConfirm = true
                        }
                    },
                    onDelete = {
                        if (!operationInFlight) {
                            selectedPaths.putAll(listOf(item.filePath to true))
                            singleActionDialog = true
                            showDeleteConfirm = true
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedTaskRow(
    item: FailedTaskItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    operationInFlight: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIgnore: () -> Unit,
    onDelete: () -> Unit,
) {
    // 缩略图加载失败（Coil 解码报错）即"文件无法显示"——损坏或编码不受支持。
    var imageFailed by remember(item.filePath) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "已选中" else "未选中",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "无法显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "文件损坏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(item.filePath)
                            .size(128)
                            .build(),
                        contentDescription = item.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is coil.compose.AsyncImagePainter.State.Error) {
                                imageFailed = true
                            }
                        },
                    )
                    if (item.mediaType == MediaType.VIDEO.name) {
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
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = item.kind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "已重试 ${item.attemptCount} 次 · ${formatTimestamp(item.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = describeFailure(item.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!isSelectionMode) {
                IconButton(onClick = onIgnore, enabled = !operationInFlight) {
                    Icon(
                        Icons.Outlined.Report,
                        contentDescription = "忽略",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                IconButton(onClick = onDelete, enabled = !operationInFlight) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = "移入回收站",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 忽略/移入回收站共用确认弹窗；两个动作仅在文案与配色上不同。 */
@Composable
private fun FailedTaskActionDialog(
    title: String,
    body: String,
    confirmText: String,
    confirmTint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = iconTint) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(enabled = enabled, onClick = onConfirm) {
                Text(confirmText, color = confirmTint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把任务表里的原始错误串翻译成用户可读的失败原因。
 * 批级摘要（如 core:semantic:19 或多阶段串 core:a:1,core:b:2）展开为阶段与批次规模；
 * 已知的单点错误码给出具体含义。
 */
internal fun describeFailure(rawError: String?): String {
    if (rawError.isNullOrBlank()) return "未知错误"
    val parts = rawError.split(',').map { part -> describeFailureToken(part.trim()) }
    return parts.distinct().joinToString("；")
}

private fun describeFailureToken(token: String): String {
    Regex("^(core:[a-z]+):(\\d+)$").find(token)?.let { match ->
        val stage = when (match.groupValues[1]) {
            "core:semantic" -> "语义分析"
            "core:ocr" -> "文字识别"
            "core:face" -> "人脸识别"
            else -> match.groupValues[1]
        }
        return "$stage 失败（该批共 ${match.groupValues[2]} 个文件）"
    }
    return when (token) {
        "semantic_empty_vector" -> "图像解码失败或模型未产出结果"
        "decode_failed" -> "图像解码失败"
        else -> token
    }
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMs))
}
