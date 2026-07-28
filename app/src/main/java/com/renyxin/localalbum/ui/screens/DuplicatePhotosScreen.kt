package com.renyxin.localalbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.data.repo.DuplicateGroup
import kotlinx.coroutines.launch

/**
 * 重复照片管理界面。
 *
 * 功能：
 * - 按 [DuplicateGroup] 分组展示完全重复的照片/视频（SHA-256 一致）
 * - 每组高亮推荐保留项（文件最大者）
 * - 支持「保留一项删除其余」一键清理组内重复项
 * - 支持点击进入媒体查看器浏览组内文件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatePhotosScreen(
    duplicateGroups: List<DuplicateGroup>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onKeepOneDeleteRest: (String, String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmGroupId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("重复照片", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading && duplicateGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在检测重复项…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (duplicateGroups.isEmpty()) {
                EmptyDuplicateState(modifier = Modifier.fillMaxSize(), onRefresh = onRefresh)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(duplicateGroups, key = { it.groupId }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            onKeepOne = { confirmGroupId = group.groupId },
                        )
                    }
                }
            }
        }
    }

    // "保留一项删除其余"确认对话框
    confirmGroupId?.let { gid ->
        val group = duplicateGroups.find { it.groupId == gid }
        val keepPath = group?.recommendedKeepPath
        val removeCount = (group?.filePaths?.size ?: 0) - 1
        val wastedBytes = group?.let { g ->
            g.filePaths.filter { it != keepPath }.sumOf { g.fileSizes[it] ?: 0L }
        } ?: 0L
        AlertDialog(
            onDismissRequest = { confirmGroupId = null },
            icon = {
                Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("删除重复项") },
            text = {
                Text(
                    "将保留 1 项（推荐项），其余 $removeCount 项移入回收站。" +
                        "可释放约 ${formatFileSize(wastedBytes)} 存储空间。是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onKeepOneDeleteRest(gid, keepPath)
                    scope.launch { snackbarHostState.showSnackbar("已保留 1 项，$removeCount 项移入回收站") }
                    confirmGroupId = null
                }) { Text("删除重复项", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmGroupId = null }) { Text("取消") } },
        )
    }
}

/**
 * 重复组卡片，展示组内完全重复的文件。
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onKeepOne: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 组标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "完全重复 · ${group.filePaths.size} 项",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val wasted = group.filePaths.drop(1).sumOf { group.fileSizes[it] ?: 0L }
                        Text(
                            text = "可释放 ${formatFileSize(wasted)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onKeepOne,
                    enabled = group.filePaths.size > 1,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清理重复")
                }
            }

            // 横向滚动展示组内文件
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(group.filePaths, key = { it }) { path ->
                    val isKeep = path == group.recommendedKeepPath
                    DuplicatePhotoThumb(
                        path = path,
                        isRecommendedKeep = isKeep,
                        fileSize = group.fileSizes[path] ?: 0L,
                        context = context,
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicatePhotoThumb(
    path: String,
    isRecommendedKeep: Boolean,
    fileSize: Long,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(android.net.Uri.parse("file://$path"))
                .crossfade(true)
                .size(256)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )

        // "保留"角标
        AnimatedVisibility(
            visible = isRecommendedKeep,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                color = Color(0xFF4CAF50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "推荐保留", tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("保留", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 文件大小角标
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Text(
                text = formatFileSize(fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptyDuplicateState(modifier: Modifier = Modifier, onRefresh: () -> Unit = {}) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("暂无重复照片", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("扫描完成后，系统会自动检测完全重复的照片和视频。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("立即扫描")
            }
        }
    }
}

/**
 * 格式化文件大小为人类可读字符串。
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}
