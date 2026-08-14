package com.renyxin.localalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
 * 浮层三态（消除「扫描期空白 / 识别批次间空窗」误号）：
 * 1. [ProgressOverlayMode.ANALYSIS]：分析管线运行中，展示原有可展开分析卡片；
 * 2. [ProgressOverlayMode.SCAN]：核心扫描运行中（管线尚未开始），展示扫描进度卡片；
 * 3. [ProgressOverlayMode.WAITING]：增强任务已入队但管线批次间空窗
 *    （AnalysisWorker 按批租约执行，每批结束 onPipelineComplete 会短暂将
 *    isCompleted 置 true），展示「等待下一批次」卡片避免浮层消失。
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
 * @param scanMessage 核心扫描阶段的人类可读状态文案；null 表示当前无核心扫描
 * @param scanProcessed 核心扫描已处理文件数（0 表示不确定进度）
 * @param scanTotal 核心扫描总文件数（0 表示不确定进度）
 * @param analysisPending 增强任务已入队/运行中（EnhancementState 为
 *   QUEUED/RUNNING/WAITING_FOR_CORE 时为 true），用于批次间空窗保持浮层
 */
@Composable
fun GlobalProgressIndicator(
    progressManager: ProgressManager,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    scanMessage: String? = null,
    scanProcessed: Int = 0,
    scanTotal: Int = 0,
    analysisPending: Boolean = false,
) {
    val progress by progressManager.progress.collectAsState()
    val analysisVisible = !progress.isCompleted && (progress.totalFiles > 0 || progress.hasError)
    val overlayMode = resolveProgressOverlayMode(
        analysisRunning = analysisVisible,
        scanActive = scanMessage != null,
        analysisPending = analysisPending,
    )
    val isVisible = overlayMode != ProgressOverlayMode.HIDDEN

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        var cardWidthPx by remember { mutableIntStateOf(0) }
        var cardHeightPx by remember { mutableIntStateOf(0) }
        var dragX by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }

        fun clampPosition() {
            val maxX = ((containerWidthPx - cardWidthPx) / 2f).coerceAtLeast(0f)
            val maxUp = (containerHeightPx - cardHeightPx).coerceAtLeast(0f)
            dragX = dragX.coerceIn(-maxX, maxX)
            dragY = dragY.coerceIn(-maxUp, 0f)
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 460.dp)
                .onSizeChanged {
                    cardWidthPx = it.width
                    cardHeightPx = it.height
                    clampPosition()
                }
                .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                .pointerInput(containerWidthPx, containerHeightPx, cardWidthPx, cardHeightPx) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                        clampPosition()
                    }
                },
        ) {
            when (overlayMode) {
                ProgressOverlayMode.ANALYSIS ->
                    ExpandableProgressCard(progress = progress, onCancel = onCancel)
                ProgressOverlayMode.SCAN ->
                    ScanProgressCard(
                        message = scanMessage.orEmpty(),
                        processed = scanProcessed,
                        total = scanTotal,
                    )
                ProgressOverlayMode.WAITING ->
                    WaitingBatchCard()
                ProgressOverlayMode.HIDDEN -> Unit
            }
        }
    }
}

/** 全局进度浮层的显示模式。 */
enum class ProgressOverlayMode {
    /** 分析管线运行中：展示可展开的分析进度卡片。 */
    ANALYSIS,

    /** 核心扫描运行中：展示扫描进度卡片。 */
    SCAN,

    /** 增强任务已入队但管线批次间空窗：展示等待卡片。 */
    WAITING,

    /** 无任何进度可展示：浮层隐藏。 */
    HIDDEN,
}

/**
 * 浮层模式判定（纯函数，供单元测试覆盖）。
 *
 * 优先级：分析运行 > 核心扫描 > 等待批次 > 隐藏。
 * 分析批次间空窗由 [analysisPending]（EnhancementState 为 QUEUED/RUNNING/WAITING_FOR_CORE）
 * 兜底，避免浮层在 Worker 退避重试间隙闪烁消失。
 */
internal fun resolveProgressOverlayMode(
    analysisRunning: Boolean,
    scanActive: Boolean,
    analysisPending: Boolean,
): ProgressOverlayMode = when {
    analysisRunning -> ProgressOverlayMode.ANALYSIS
    scanActive -> ProgressOverlayMode.SCAN
    analysisPending -> ProgressOverlayMode.WAITING
    else -> ProgressOverlayMode.HIDDEN
}

@Composable
private fun ExpandableProgressCard(
    progress: AnalysisProgress,
    onCancel: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(progress) { buildProgressSummary(progress) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(progress)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (progress.hasError) "智能整理遇到问题" else "正在整理媒体库",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = progress.currentStageName.ifBlank { "正在准备分析任务" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "${summary.percent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (progress.hasError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = { progress.percent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = if (progress.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = summary.compactText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DragIndicator, "拖动进度浮窗", Modifier.size(20.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        if (expanded) "收起" else "展开详情",
                        Modifier.size(20.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ExpandedDetail(progress, summary, onCancel)
            }
        }
    }
}

/** 核心扫描阶段的轻量进度卡片（管线尚未开始，仅有 ScanState 数据）。 */
@Composable
private fun ScanProgressCard(
    message: String,
    processed: Int,
    total: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "正在扫描媒体库",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = message.ifBlank { "正在建立媒体索引…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (total > 0 && processed > 0) {
                    Text(
                        text = "${processed}/${total}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (processed.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
            Text(
                text = "扫描完成后将自动开始 AI 识别",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 识别批次间空窗的等待卡片（增强任务已入队，Worker 批次间隙）。 */
@Composable
private fun WaitingBatchCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 识别进行中",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "正在准备下一批分析任务…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpandedDetail(
    progress: AnalysisProgress,
    summary: ProgressSummary,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailRow("总体进度", "${progress.processedFiles} / ${progress.totalFiles}（剩余 ${summary.remainingFiles}）")
        DetailRow("分析项目", "${progress.completedStages} / ${progress.totalStages} 已完成")
        DetailRow("预计完成", summary.etaText)

        progress.perStageFiles.values
            .filter { it.totalCount > 0 && it.completedCount < it.totalCount }
            .take(3)
            .forEach { stage ->
                DetailRow(stage.displayName, "${stage.completedCount} / ${stage.totalCount}")
            }

        summary.currentFileName?.let { fileName ->
            Text(
                text = "当前：$fileName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (progress.hasError) {
            Text(
                text = progress.extra["error"] ?: "部分分析任务执行失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = "按住卡片可拖到屏幕其他位置，点击标题可收起",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        if (onCancel != null && !progress.hasError) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Cancel, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("停止本次分析")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusIcon(progress: AnalysisProgress) {
    val (icon, tint) = when {
        progress.hasError -> Icons.Default.Error to MaterialTheme.colorScheme.error
        progress.isCompleted -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
    }
    Icon(icon, if (progress.hasError) "出错" else "进行中", tint = tint, modifier = Modifier.size(24.dp))
}

internal data class ProgressSummary(
    val percent: Int,
    val remainingFiles: Int,
    val etaText: String,
    val compactText: String,
    val currentFileName: String?,
)

internal fun buildProgressSummary(progress: AnalysisProgress): ProgressSummary {
    val remaining = (progress.totalFiles - progress.processedFiles).coerceAtLeast(0)
    val eta = when {
        progress.etaMs > 0 -> formatEta(progress.etaMs)
        progress.percent <= 0f -> "正在统计任务…"
        else -> "正在估算…"
    }
    val currentFile = progress.perStageFiles.values.asSequence()
        .flatMap { it.files.asSequence() }
        .firstOrNull { it.status == com.renyxin.localalbum.core.pipeline.FileProcessingStatus.PROCESSING }
        ?.filePath?.substringAfterLast('/')
    return ProgressSummary(
        percent = (progress.percent.coerceIn(0f, 1f) * 100).toInt(),
        remainingFiles = remaining,
        etaText = eta,
        compactText = "剩余 $remaining 个文件 · ${progress.completedStages}/${progress.totalStages} 项完成 · $eta",
        currentFileName = currentFile,
    )
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
