package com.renyxin.localalbum.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.renyxin.localalbum.core.pipeline.FileProcessingStatus
import com.renyxin.localalbum.core.pipeline.StageFileProgress

/**
 * 按文件可视化进度网格组件（Phase 6.1）。
 *
 * 在分析管道运行期间，以缩略图网格形式展示每张图片的处理状态：
 * - ✓ 绿色勾号 = 已完成
 * - ⟳ 蓝色旋转圆环 = 处理中
 * - 灰色半透明蒙层 = 待处理
 * - ✗ 红色 X = 失败
 *
 * 顶部显示阶段名称、完成统计和进度条。
 *
 * @param stageProgress 某阶段的 per-file 进度数据
 * @param modifier 外部 Modifier
 * @param columns 网格列数，默认 4
 * @param maxVisibleFiles 最大展示缩略图数量，超出部分显示 "+N 张" 占位
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnalysisProgressGrid(
    stageProgress: StageFileProgress,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    maxVisibleFiles: Int = 200,
) {
    if (stageProgress.totalCount == 0 && stageProgress.files.isEmpty()) return

    val percent = stageProgress.percent
    val completedCount = stageProgress.completedCount
    val totalCount = stageProgress.totalCount
    val displayName = stageProgress.displayName.ifEmpty { "分析中" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ---- 统计头部 ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 旋转进度指示器（无明确进度时显示）
                        if (totalCount == 0) {
                            RotatingProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "${(percent * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { percent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "已完成 $completedCount / $totalCount 张照片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 缩略图网格 ----
        val visibleFiles = stageProgress.files.take(maxVisibleFiles)
        val remainingCount = (stageProgress.files.size - maxVisibleFiles).coerceAtLeast(0)

        if (visibleFiles.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // 填充父容器剩余空间，适配不同屏幕高度
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleFiles, key = { it.filePath }) { fileProgress ->
                    ProgressThumbnail(
                        filePath = fileProgress.filePath,
                        status = fileProgress.status,
                        modifier = Modifier.animateItemPlacement(),
                    )
                }

                if (remainingCount > 0) {
                    item {
                        RemainingCountPlaceholder(count = remainingCount)
                    }
                }
            }
        }
    }
}

/**
 * 单张缩略图 + 状态覆盖层。
 */
@Composable
private fun ProgressThumbnail(
    filePath: String,
    status: FileProcessingStatus,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        // 缩略图
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(android.net.Uri.parse("file://$filePath"))
                .crossfade(true)
                .size(128)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // 状态覆盖层
        StatusOverlay(status = status, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 状态覆盖层：根据 [FileProcessingStatus] 显示不同的视觉效果。
 */
@Composable
private fun StatusOverlay(
    status: FileProcessingStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        FileProcessingStatus.PENDING -> {
            // 半透明灰色蒙层
            Box(
                modifier = modifier
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "待",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        FileProcessingStatus.PROCESSING -> {
            // 蓝色旋转圆环 + 半透明蒙层
            Box(
                modifier = modifier
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                RotatingProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFF42A5F5),
                )
            }
        }

        FileProcessingStatus.COMPLETED -> {
            // 右下角绿色勾号
            Box(modifier = modifier) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(22.dp),
                    shape = CircleShape,
                    color = Color(0xFF4CAF50),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        FileProcessingStatus.FAILED -> {
            // 红色 X + 半透明蒙层
            Box(
                modifier = modifier
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "失败",
                    tint = Color(0xFFEF5350),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * 旋转进度指示器（无限循环动画）。
 */
@Composable
private fun RotatingProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation_angle",
    )

    Surface(
        modifier = modifier
            .rotate(rotation)
            .clip(CircleShape),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, color, CircleShape),
        )
    }
}

/**
 * 剩余数量占位符。
 */
@Composable
private fun RemainingCountPlaceholder(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "+$count",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
