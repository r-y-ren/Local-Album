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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.data.repo.DuplicateGroup
import com.renyxin.localalbum.data.repo.DuplicateLevel
import com.renyxin.localalbum.data.repo.SimilarGroup
import kotlinx.coroutines.launch

/**
 * 相似照片管理界面（Phase 3.1）。
 *
 * 功能：
 * - 按 [SimilarGroup] 分组展示相似照片
 * - 每组显示质量评分（qualityScore），高亮推荐保留项（首项）
 * - 支持「选择保留最佳」一键删除组内低分项
 * - 支持点击进入媒体查看器浏览组内照片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarPhotosScreen(
    similarGroups: List<SimilarGroup>,
    groupPhotos: List<MediaItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onKeepBest: (String) -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // DUP-1: 新增重复照片相关参数
    duplicateGroups: List<DuplicateGroup> = emptyList(),
    isDuplicateMode: Boolean = false,
    onDuplicateRefresh: () -> Unit = {},
    onKeepOneDeleteRest: (String, String?) -> Unit = { _, _ -> },
    duplicateLoading: Boolean = false,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmGroupId by remember { mutableStateOf<String?>(null) }
    var confirmDuplicateGroupId by remember { mutableStateOf<String?>(null) }

    // DUP-1: 在重复模式下使用重复数据
    val displayGroups = if (isDuplicateMode) duplicateGroups else similarGroups
    val displayLoading = if (isDuplicateMode) duplicateLoading else isLoading

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("相似照片", fontWeight = FontWeight.Bold) },
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
            if (displayLoading && displayGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在加载相似组…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (displayGroups.isEmpty()) {
                if (isDuplicateMode) {
                    EmptyDuplicateState(modifier = Modifier.fillMaxSize(), onRefresh = onDuplicateRefresh)
                } else {
                    EmptySimilarState(modifier = Modifier.fillMaxSize())
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isDuplicateMode) {
                        items(duplicateGroups, key = { it.groupId }) { group ->
                            DuplicateGroupCard(
                                group = group,
                                onClick = { photos, index -> onMediaClick(photos, index) },
                                onKeepOne = { confirmDuplicateGroupId = group.groupId },
                            )
                        }
                    } else {
                        items(similarGroups, key = { it.groupId }) { group ->
                            SimilarGroupCard(
                                group = group,
                                groupPhotos = groupPhotos.takeIf { groupPhotos.isNotEmpty() },
                                onClick = { photos, index -> onMediaClick(photos, index) },
                                onKeepBest = { confirmGroupId = group.groupId },
                            )
                        }
                    }
                }
            }
        }
    }

    // "保留最佳"确认对话框
    // "保留最佳"确认对话框（相似模式）
    confirmGroupId?.let { gid ->
        val group = similarGroups.find { it.groupId == gid }
        val removeCount = (group?.filePaths?.size ?: 0) - 1
        AlertDialog(
            onDismissRequest = { confirmGroupId = null },
            icon = {
                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = { Text("保留最佳") },
            text = {
                Text("将保留质量评分最高的 1 张，其余 $removeCount 张移入回收站。可随时从回收站恢复。是否继续？")
            },
            confirmButton = {
                TextButton(onClick = {
                    onKeepBest(gid)
                    scope.launch { snackbarHostState.showSnackbar("已保留最佳，$removeCount 张移入回收站") }
                    confirmGroupId = null
                }) { Text("保留最佳", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { confirmGroupId = null }) { Text("取消") } },
        )
    }

    // DUP-1: "保留一项删除其余"确认对话框（重复模式）
    confirmDuplicateGroupId?.let { gid ->
        val group = duplicateGroups.find { it.groupId == gid }
        val keepPath = group?.recommendedKeepPath
        val removeCount = (group?.filePaths?.size ?: 0) - 1
        val wastedBytes = group?.let { g -> g.filePaths.filter { it != keepPath }.sumOf { g.fileSizes[it] ?: 0L } } ?: 0L
        AlertDialog(
            onDismissRequest = { confirmDuplicateGroupId = null },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
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
                    confirmDuplicateGroupId = null
                }) { Text("删除重复项", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDuplicateGroupId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SimilarGroupCard(
    group: SimilarGroup,
    groupPhotos: List<MediaItem>?,
    onClick: (List<MediaItem>, Int) -> Unit,
    onKeepBest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bestPath = group.bestPath

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
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "相似组 ${group.groupId.take(8)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${group.filePaths.size} 张相似照片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onKeepBest,
                    enabled = group.filePaths.size > 1,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保留最佳")
                }
            }

            // 横向滚动展示组内照片
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(group.filePaths, key = { it }) { path ->
                    val score = group.qualityScores[path] ?: 0f
                    val isBest = path == bestPath
                    SimilarPhotoThumb(
                        path = path,
                        qualityScore = score,
                        isBest = isBest,
                        onClick = {
                            // 仅当该组照片已加载时才允许进入查看器
                            val photos = groupPhotos ?: return@SimilarPhotoThumb
                            val idx = photos.indexOfFirst { it.filePath == path }
                            if (idx >= 0) onClick(photos, idx)
                        },
                        context = context,
                    )
                }
            }

            // 质量评分进度条（最佳项）
            if (bestPath != null) {
                val bestScore = group.qualityScores[bestPath] ?: 0f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "最佳质量分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { bestScore.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "%.2f".format(bestScore),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarPhotoThumb(
    path: String,
    qualityScore: Float,
    isBest: Boolean,
    onClick: () -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(android.net.Uri.parse("file://$path"))
                .crossfade(true)
                .size(256)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )

        // "最佳"角标
        AnimatedVisibility(
            visible = isBest,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "推荐保留",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "最佳",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 质量分角标
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Text(
                text = "%.2f".format(qualityScore),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptySimilarState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
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
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "暂无相似照片",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "扫描完成后，系统会自动检测相似/重复照片并在此分组展示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---- DUP-1: 重复照片专用组件 ----

/**
 * 重复组卡片，按 [DuplicateLevel] 显示彩色标签。
 * 红色=完全重复，橙色=感知重复，黄色=高度相似。
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onClick: (List<MediaItem>, Int) -> Unit,
    onKeepOne: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val levelColor = Color(group.level.color)
    val levelLabel = when (group.level) {
        DuplicateLevel.EXACT -> "完全重复"
        DuplicateLevel.PERCEPTUAL -> "感知重复"
        DuplicateLevel.NEAR_DUPLICATE -> "高度相似"
    }

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
                        color = levelColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = levelLabel.take(2),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = levelColor,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "$levelLabel · ${group.filePaths.size} 项",
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
                        onClick = { /* 点击进入查看器 */ },
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
    onClick: () -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
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
            Text("扫描完成后，系统会自动检测完全重复和高度相似的照片。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
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
