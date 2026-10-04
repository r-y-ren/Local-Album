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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 删除全流程的即时反馈：确认→路径解析→（系统弹窗/SAF 授权/树下钻删除）→清库，
    // 中间每段都有可见指示，避免"点了没反应"的空窗（解析与 SAF 下钻均可能在后台耗时）
    var deleteFlowBusy by remember { mutableStateOf(false) }
    val anyDeleteBusy = operationInFlight || deleteFlowBusy
    var pendingSystemDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingClearTrash by remember { mutableStateOf(false) }
    var pendingUnresolvedCount by remember { mutableIntStateOf(0) }
    // MediaStore 解析不到、等待用户 SAF 目录授权后删除的路径（Android 13+ 唯一途径）
    var pendingSafPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var safClearTrash by remember { mutableStateOf(false) }
    var pendingUnresolvedPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSafOffer by remember { mutableStateOf(false) }
    // 处于删除流程中的文件：缩略图叠转圈、行加深、不可再操作；
    // 流程结束（成功清库/失败保留/用户取消）后清除
    val pendingDeletePaths = remember { mutableStateMapOf<String, Boolean>() }

    fun clearPendingDelete(paths: List<String>) {
        paths.forEach { pendingDeletePaths.remove(it) }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val paths = pendingSafPaths
        val clear = safClearTrash
        pendingSafPaths = emptyList()
        safClearTrash = false
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("已取消文件夹授权") }
            return@rememberLauncherForActivityResult
        }
        // 持久化授权：同一目录后续删除不再弹选择器
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        scope.launch {
            deleteFlowBusy = true
            val outcome = withContext(Dispatchers.IO) {
                com.renyxin.localalbum.core.saf.SafTreeDelete.deleteUnderTree(context, uri, paths)
            }
            if (outcome.deleted.isNotEmpty()) {
                // SAF 已物理删除；走仓库清库链路（文件不存在 → MISSING → 原子清理）
                if (clear) onClearTrash() else onPermanentlyDelete(outcome.deleted)
            }
            deleteFlowBusy = false
            clearPendingDelete(paths)
            val remaining = paths.size - outcome.deleted.size
            val message = when {
                outcome.deleted.isNotEmpty() && remaining > 0 ->
                    "已通过文件夹授权删除 ${outcome.deleted.size} 项；$remaining 项不在授权目录内，保留在回收站"
                outcome.deleted.isNotEmpty() -> "已通过文件夹授权删除 ${outcome.deleted.size} 项"
                else -> "授权目录未覆盖所选文件，未删除（可尝试授权其所在的具体文件夹）"
            }
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    /**
     * 先消费已持久化的目录授权：覆盖到的文件直接删除，不再弹任何选择器/说明框。
     * 返回仍未删除的路径（无覆盖授权或授权树下已找不到）。
     */
    suspend fun deleteViaPersistedTrees(paths: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            var remaining = paths.distinct()
            val granted = context.contentResolver.persistedUriPermissions
                .filter { it.isWritePermission }
            for (perm in granted) {
                if (remaining.isEmpty()) break
                val covered = remaining.filter {
                    com.renyxin.localalbum.core.saf.SafTreeDelete.covers(context, perm.uri, it)
                }
                if (covered.isEmpty()) continue
                val outcome = com.renyxin.localalbum.core.saf.SafTreeDelete.deleteUnderTree(
                    context, perm.uri, covered,
                )
                if (outcome.deleted.isNotEmpty()) {
                    if (safClearTrash) onClearTrash() else onPermanentlyDelete(outcome.deleted)
                }
                remaining = remaining - outcome.deleted.toSet()
            }
            remaining
        }

    fun offerSafFor(paths: List<String>, clear: Boolean) {
        scope.launch {
            deleteFlowBusy = true
            // 已授权目录能覆盖的直接删；剩下没有授权覆盖的才引导用户授权
            val remaining = deleteViaPersistedTrees(paths)
            deleteFlowBusy = false
            if (remaining.isEmpty()) {
                clearPendingDelete(paths)
                snackbarHostState.showSnackbar("已删除 ${paths.size} 项", duration = SnackbarDuration.Long)
            } else if (remaining.size < paths.size) {
                clearPendingDelete(paths - remaining.toSet())
                snackbarHostState.showSnackbar(
                    "已删除 ${paths.size - remaining.size} 项；${remaining.size} 项需要新的文件夹授权",
                )
                pendingSafPaths = remaining
                safClearTrash = clear
                showSafOffer = true
            } else {
                pendingSafPaths = remaining
                safClearTrash = clear
                showSafOffer = true
            }
        }
    }

    val systemDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val paths = pendingSystemDeletePaths
        val clear = pendingClearTrash
        val unresolved = pendingUnresolvedCount
        val unresolvedPaths = pendingUnresolvedPaths
        pendingSystemDeletePaths = emptyList()
        pendingClearTrash = false
        pendingUnresolvedCount = 0
        pendingUnresolvedPaths = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            // 系统已删除文件；Repository 将其识别为 MISSING 并原子清理关联数据。
            if (clear) onClearTrash() else onPermanentlyDelete(paths)
            if (unresolvedPaths.isNotEmpty()) {
                // 未解析项优先消费已有目录授权，无覆盖授权才引导新的 SAF 授权
                offerSafFor(unresolvedPaths, clear)
            }
            // 待删态由仓库操作完成回调清除（operationState 观察者）
        } else {
            clearPendingDelete(paths)
            if (unresolvedPaths.isNotEmpty()) {
                offerSafFor(unresolvedPaths, clear)
            } else {
                scope.launch { snackbarHostState.showSnackbar("已取消系统删除授权") }
            }
        }
    }

    fun requestPermanentDelete(paths: List<String>, clearTrash: Boolean = false) {
        val distinct = paths.distinct()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (clearTrash) onClearTrash() else onPermanentlyDelete(distinct)
            return
        }
        // 即将删除的文件立即进入待删态：缩略图转圈 + 行加深 + 行内操作禁用
        distinct.forEach { pendingDeletePaths[it] = true }
        // 路径解析与后续授权链路可能耗时：先亮指示条并给出文字反馈
        deleteFlowBusy = true
        scope.launch {
            snackbarHostState.showSnackbar("正在处理删除…", duration = SnackbarDuration.Short)
            // 逐路径反查 MediaStore 是每路径一次 query：清空回收站时可达数千条，
            // 必须离开主线程执行；启动系统授权弹窗仍回主线程。
            val request = withContext(Dispatchers.IO) {
                MediaStoreDeleteRequest.create(context, distinct)
            }
            if (request == null) {
                // 全部无法解析（.nomedia/未索引目录）：先消费已有授权，无覆盖再引导 SAF
                offerSafFor(distinct, clearTrash)
            } else {
                pendingSystemDeletePaths = distinct
                pendingClearTrash = clearTrash
                pendingUnresolvedCount = request.unresolvedPaths.size
                pendingUnresolvedPaths = request.unresolvedPaths
                // 系统弹窗即反馈，指示条让位；弹窗结束后由回调/仓库状态接管
                deleteFlowBusy = false
                systemDeleteLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build(),
                )
            }
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
            // 清库终态（成功或失败）：全部待删态结束——成功的行已被数据流移除，
            // 失败保留的行恢复可操作
            pendingDeletePaths.clear()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            onOperationMessageConsumed()
        }
    }

    // SAF 文件夹授权引导：MediaStore 解析不到的文件在 Android 13+ 上唯一的删除途径
    if (showSafOffer && pendingSafPaths.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSafOffer = false },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("需要文件夹授权") },
            text = {
                Text(
                    "有 ${pendingSafPaths.size} 个文件不在系统媒体库中（如 .nomedia 目录），" +
                        "Android 13+ 不允许应用直接删除这类文件。\n\n" +
                        "授权其所在的文件夹后即可删除；授权一次长期有效。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSafOffer = false
                    safTreeLauncher.launch(
                        com.renyxin.localalbum.core.saf.SafTreeDelete.initialUriHint(),
                    )
                }) { Text("选择文件夹") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSafOffer = false
                    // 用户拒绝授权：仍走仓库链路尽力删（多数会 MISSING/FAILED 落墓碑）
                    if (safClearTrash) onClearTrash() else onPermanentlyDelete(pendingSafPaths)
                    pendingSafPaths = emptyList()
                }) { Text("暂不授权") }
            },
        )
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
                TextButton(enabled = !anyDeleteBusy, onClick = {
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
                TextButton(enabled = !anyDeleteBusy, onClick = {
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
                        IconButton(enabled = !anyDeleteBusy, onClick = {
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
                        IconButton(enabled = !anyDeleteBusy, onClick = {
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
                            IconButton(enabled = !anyDeleteBusy, onClick = { showClearConfirm = true }) {
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
                        if (!anyDeleteBusy && selectedPaths.isNotEmpty()) {
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
            visible = anyDeleteBusy,
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
                    isPendingDelete = pendingDeletePaths[item.filePath] == true,
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
                        if (!anyDeleteBusy) onRestore(listOf(item.filePath))
                    },
                    onDelete = {
                        if (!anyDeleteBusy) {
                            // 单张永久删除：借用确认弹窗（读 selectedPaths），选中即此一项
                            selectedPaths.clear()
                            selectedPaths[item.filePath] = true
                            showDeleteConfirm = true
                        }
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
    isPendingDelete: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    now: Instant,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !isPendingDelete,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            // 待删行用更深的容器色；选择态只在非待删时生效
            containerColor = when {
                isPendingDelete -> MaterialTheme.colorScheme.surfaceContainerHighest
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
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
                    alpha = if (isPendingDelete) 0.5f else 1f,
                )
                if (isPendingDelete) {
                    // 待删态：半透明压暗 + 中央转圈，明确指示该文件正在被处理
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                    )
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                        strokeWidth = 2.dp,
                    )
                }
                if (item.type == MediaType.VIDEO && !isPendingDelete) {
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

            // 恢复/永久删除按钮（非选择模式下逐项可用；长按行进入多选批量操作）
            if (!isSelectionMode) {
                IconButton(onClick = onRestore, enabled = !isPendingDelete) {
                    Icon(
                        Icons.Default.RestoreFromTrash,
                        contentDescription = "恢复",
                        tint = if (isPendingDelete) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, enabled = !isPendingDelete) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = "永久删除",
                        tint = if (isPendingDelete) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.error,
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