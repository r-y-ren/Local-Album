package com.renyxin.localalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.pipeline.AnalysisProgress
import com.renyxin.localalbum.core.pipeline.ProgressManager

/**
 * 全局进度指示器（Phase 5.1-5.3）。
 *
 * 悬浮在屏幕底部的可展开卡片，实时展示跨阶段分析管道的总体进度：
 * - 当前阶段名称、已完成/总阶段数
 * - 已处理/总文件数、百分比进度条
 * - ETA 预估剩余时间
 * - 展开后展示：任务类型、插件信息、处理速率、错误信息
 * - 取消按钮（支持中断耗时任务）
 * - 管道完成后自动隐藏
 *
 * 使用方式：
 * ```kotlin
 * GlobalProgressIndicator(
 *     progressManager = appContainer.pluginAnalysisPipeline.progressManager,
 *     onCancel = { albumViewModel.cancelTask() },
 * )
 * ```
 *
 * @param progressManager 管道级 [ProgressManager]
 * @param onCancel 取消回调（可选），用户点击取消按钮时调用
 * @param modifier 外部 Modifier
 */
@Composable
fun GlobalProgressIndicator(
    progressManager: ProgressManager,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val progress by progressManager.progress.collectAsState()

    // 可见性判断：有进度且未完成，或有错误
    val isVisible = !progress.isCompleted && (progress.processedFiles > 0 || progress.hasError)

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        ExpandableProgressCard(progress = progress, onCancel = onCancel)
    }
}

// ---- Internal Components ----

/**
 * 可展开的进度卡片。
 */
@Composable
private fun ExpandableProgressCard(
    progress: AnalysisProgress,
    onCancel: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 折叠状态：总体进度条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态图标
                StatusIcon(progress = progress)

                Spacer(Modifier.width(12.dp))

                // 进度信息
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = progress.currentStageName.ifEmpty { "分析中..." },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(progress.percent * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    LinearProgressIndicator(
                        progress = { progress.percent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (progress.hasError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )

                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = buildMetaText(progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (expanded) "收起" else "展开详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 展开后的详情区域（显示任务类型、插件信息、处理速率、错误信息、取消按钮）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ExpandedDetail(progress = progress, onCancel = onCancel)
            }
        }
    }
}

/**
 * 展开区域的详情信息（Phase 5.3 增强版）。
 * 显示：阶段统计、文件统计、状态、处理速率、ETA、错误信息、取消按钮。
 */
@Composable
private fun ExpandedDetail(
    progress: AnalysisProgress,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 阶段统计
        DetailRow(
            label = "阶段",
            value = "${progress.completedStages} / ${progress.totalStages} 完成",
        )
        DetailRow(
            label = "文件",
            value = "${progress.processedFiles} / ${progress.totalFiles} 已处理",
        )

        // 处理速率
        if (progress.processedFiles > 0 && progress.etaMs >= 0) {
            DetailRow(
                label = "预估剩余",
                value = formatEta(progress.etaMs),
            )
        }

        DetailRow(
            label = "状态",
            value = when {
                progress.hasError -> "出错"
                progress.isCompleted -> "已完成"
                else -> "进行中"
            },
        )

        // 错误信息
        if (progress.hasError && progress.extra.isNotEmpty()) {
            val errorMsg = progress.extra["error"]
            val failedStage = progress.extra["failedStage"]
            if (errorMsg != null || failedStage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (failedStage != null) append("阶段 $failedStage 失败")
                        if (errorMsg != null && failedStage != null) append(": ")
                        if (errorMsg != null) append(errorMsg)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 取消按钮（仅在进行中且未出错时显示）
        if (onCancel != null && !progress.isCompleted && !progress.hasError) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("取消分析", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 详情行：标签-值对。
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 状态图标组件。
 */
@Composable
private fun StatusIcon(progress: AnalysisProgress) {
    val (icon, tint) = when {
        progress.hasError -> Icons.Default.Error to MaterialTheme.colorScheme.error
        progress.isCompleted -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
    }

    Icon(
        imageVector = icon,
        contentDescription = when {
            progress.hasError -> "出错"
            progress.isCompleted -> "完成"
            else -> "进行中"
        },
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * 构建底部元信息文本。
 */
private fun buildMetaText(progress: AnalysisProgress): String {
    val parts = mutableListOf<String>()
    parts.add("${progress.processedFiles} / ${progress.totalFiles} 文件")
    parts.add("${progress.completedStages} / ${progress.totalStages} 阶段")
    if (progress.etaMs > 0) {
        parts.add(formatEta(progress.etaMs))
    }
    return parts.joinToString(" · ")
}

/**
 * ETA 格式化：毫秒 → 人类可读。
 *
 * @VisibleForTesting — 供单元测试验证格式化逻辑。
 */
internal fun formatEta(etaMs: Long): String {
    if (etaMs <= 0) return ""
    val totalSeconds = etaMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 -> "约 ${minutes}m ${seconds}s"
        seconds > 0 -> "约 ${seconds}s"
        else -> "即将完成"
    }
}

// ---- Phase 5.5: Compose Previews ----

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewProgressRunning() {
    com.renyxin.localalbum.ui.theme.LocalAlbumTheme {
        val progressMgr = remember {
            val mgr = ProgressManager()
            mgr.beginPipeline(totalFiles = 120, stageIds = listOf("face_detect", "face_embed", "ocr", "scene_classify", "quality_score"))
            mgr.onStageStart("face_embed", "人脸特征提取", 30)
            mgr.onFileProgress(15, 30)
            mgr.onStageComplete("face_detect", 20, 0)
            mgr
        }
        GlobalProgressIndicator(progressManager = progressMgr)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewProgressError() {
    com.renyxin.localalbum.ui.theme.LocalAlbumTheme {
        val progressMgr = remember {
            val mgr = ProgressManager()
            mgr.beginPipeline(totalFiles = 50, stageIds = listOf("ocr", "scene_classify"))
            mgr.onStageStart("ocr", "文字识别", 25)
            mgr.onFileProgress(10, 25)
            mgr.onStageError("ocr", "Tesseract 初始化失败")
            mgr
        }
        GlobalProgressIndicator(progressManager = progressMgr)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewProgressNearComplete() {
    com.renyxin.localalbum.ui.theme.LocalAlbumTheme {
        val progressMgr = remember {
            val mgr = ProgressManager()
            mgr.beginPipeline(totalFiles = 120, stageIds = listOf("face_detect", "face_embed", "ocr", "scene_classify", "quality_score"))
            mgr.onStageStart("quality_score", "质量评分", 20)
            mgr.onStageComplete("face_detect", 20, 0)
            mgr.onStageComplete("face_embed", 30, 0)
            mgr.onStageComplete("ocr", 25, 0)
            mgr.onStageComplete("scene_classify", 25, 0)
            mgr.onFileProgress(18, 20)
            mgr
        }
        GlobalProgressIndicator(progressManager = progressMgr)
    }
}
