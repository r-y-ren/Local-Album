package com.renyxin.localalbum.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.items
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.pipeline.StageFileProgress
import com.renyxin.localalbum.data.repo.FaceCluster
import com.renyxin.localalbum.data.repo.FaceStats
import com.renyxin.localalbum.ui.components.AnalysisProgressGrid
import kotlinx.coroutines.flow.Flow

/**
 * 人脸相册界面（Phase 3.3 人脸聚类）。
 *
 * 功能：
 * - 按 [FaceCluster] 分组展示检测到的人物
 * - 每组显示人脸数量与代表缩略图
 * - 点击进入该人物的照片列表
 * - 支持为人物命名
 * - 管道运行中时显示 per-file 进度网格（Phase 6.1）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesScreen(
    faceClusters: List<FaceCluster>,
    faceStats: FaceStats,
    pagedMediaForCluster: (String) -> Flow<PagingData<MediaItem>>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onMediaClick: (MediaItem, String) -> Unit,
    onSetName: (String, String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    stageFileProgress: StageFileProgress = StageFileProgress.EMPTY,
    isPipelineRunning: Boolean = false,
) {
    var namingClusterId by remember { mutableStateOf<String?>(null) }
    var selectedClusterId by remember { mutableStateOf<String?>(null) }

    // 详情视图返回：优先返回聚类列表，而非退出页面
    BackHandler(enabled = selectedClusterId != null) {
        selectedClusterId = null
    }

    val selectedCluster = selectedClusterId?.let { id ->
        faceClusters.find { it.clusterId == id }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (selectedClusterId != null) {
                            selectedCluster?.personName ?: "人物 ${selectedClusterId?.take(6)}"
                        } else {
                            "人物"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedClusterId != null) {
                            selectedClusterId = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新人物列表")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 聚类详情视图：按 selectedClusterId 创建并收集独立分页流。
            if (selectedClusterId != null) {
                val clusterId = selectedClusterId!!
                val clusterPaging = remember(clusterId) { pagedMediaForCluster(clusterId) }
                FaceClusterDetailContent(
                    clusterId = clusterId,
                    clusterPhotos = clusterPaging,
                    onMediaClick = onMediaClick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (isPipelineRunning && stageFileProgress.files.isNotEmpty()) {
                // 扫描期间同时展示已完成的聚类；进度网格仅作为增量结果的提示，
                // 不再覆盖已有结果，用户可随扫描推进浏览新加入的人物。
                Column(modifier = Modifier.fillMaxSize()) {
                    AnalysisProgressGrid(
                        stageProgress = stageFileProgress,
                        modifier = Modifier.weight(0.42f),
                    )
                    if (faceClusters.isNotEmpty()) {
                        FaceClusterGrid(
                            faceClusters = faceClusters,
                            faceStats = faceStats,
                            onClusterClick = { cluster -> selectedClusterId = cluster.clusterId },
                            modifier = Modifier.weight(0.58f),
                        )
                    }
                }
            } else if (isLoading && faceClusters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在加载人脸聚类…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (faceClusters.isEmpty()) {
                EmptyFacesState(modifier = Modifier.fillMaxSize())
            } else {
                FaceClusterGrid(
                    faceClusters = faceClusters,
                    faceStats = faceStats,
                    onClusterClick = { cluster -> selectedClusterId = cluster.clusterId },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 命名对话框
    namingClusterId?.let { clusterId ->
        val cluster = faceClusters.find { it.clusterId == clusterId }
        var nameInput by remember(clusterId) {
            mutableStateOf(cluster?.personName ?: "")
        }
        AlertDialog(
            onDismissRequest = { namingClusterId = null },
            icon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("为人物命名") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("人物名称") },
                    placeholder = { Text("输入名称（留空取消命名）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalName = nameInput.trim().ifBlank { null }
                    onSetName(clusterId, finalName)
                    namingClusterId = null
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { namingClusterId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FaceClusterGrid(
    faceClusters: List<FaceCluster>,
    faceStats: FaceStats,
    onClusterClick: (FaceCluster) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(label = "人物", value = "${faceStats.clusterCount}")
                StatItem(label = "人脸", value = "${faceStats.totalFaces}")
                StatItem(label = "已聚类", value = "${faceStats.clusteredFaces}")
            }
        }
        // 显式占用统计卡片之外的剩余高度，确保扫描进度下方的聚类区获得
        // 有界高度；LazyVerticalGrid 因而可独立处理纵向滑动，不会被上方进度网格吞掉。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(faceClusters, key = { it.clusterId }) { cluster ->
                FaceClusterCard(
                    cluster = cluster,
                    onClick = { onClusterClick(cluster) },
                )
            }
        }
    }
}

/**
 * 聚类详情视图：以 3 列网格展示该人物的所有照片。
 * 点击照片进入大图浏览。
 */
@Composable
private fun FaceClusterDetailContent(
    clusterId: String,
    clusterPhotos: Flow<PagingData<MediaItem>>,
    onMediaClick: (MediaItem, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pagedItems = clusterPhotos.collectAsLazyPagingItems()
    if (pagedItems.itemCount == 0 && pagedItems.loadState.refresh is androidx.paging.LoadState.Loading) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在加载照片…", style = MaterialTheme.typography.bodyLarge)
            }
        }
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
            count = pagedItems.itemCount,
            key = pagedItems.itemKey { it.filePath },
        ) { index ->
            val item = pagedItems[index] ?: return@items
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onMediaClick(item, clusterId) },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(android.net.Uri.parse("file://${item.thumbnailPath ?: item.filePath}"))
                        .crossfade(true)
                        .size(256)
                        .build(),
                    contentDescription = item.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun FaceClusterCard(
    cluster: FaceCluster,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayName = cluster.personName ?: "人物 ${cluster.clusterId.take(6)}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 人脸代表图（圆形）
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onClick),
            ) {
                val thumbPath = cluster.representativeThumb
                if (thumbPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://$thumbPath"))
                            .crossfade(true)
                            .size(256)
                            .build(),
                        contentDescription = displayName,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else if (cluster.filePaths.isNotEmpty()) {
                    // 回退到照片本身
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(android.net.Uri.parse("file://${cluster.filePaths.first()}"))
                            .crossfade(true)
                            .size(256)
                            .build(),
                        contentDescription = displayName,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp),
                    )
                }
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${cluster.faceCount} 张照片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EmptyFacesState(modifier: Modifier = Modifier) {
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
                        Icons.Default.Face,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "暂未检测到人脸",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "扫描完成后，系统会自动检测照片中的人脸并在此分组展示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
