package com.renyxin.localalbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.ui.layout.ContentScale
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.exif.ExifExtractor
import com.renyxin.localalbum.core.model.MediaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun MediaViewerScreen(
    mediaPaging: Flow<PagingData<MediaItem>>,
    initialPath: String,
    onBack: () -> Unit,
    onDeleteItem: (String) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    resolvePreview: suspend (MediaItem) -> String? = { null },
) {
    val mediaItems = mediaPaging.collectAsLazyPagingItems()
    if (mediaItems.itemCount == 0) return
    val snapshot = mediaItems.itemSnapshotList
    val initialIndex = snapshot.items.indexOfFirst { it.filePath == initialPath }
        .takeIf { it >= 0 }
        ?.let { snapshot.placeholdersBefore + it }
        ?: 0
    val pagerState = rememberPagerState(initialPage = initialIndex) { mediaItems.itemCount }
    // 定位闭锁只在"首个多元素快照"上落位：快速首帧的单条快照（无占位符、
    // 绝对索引未知）临时显示在第 0 页，分页窗口/完整列表到达时才是真实定位。
    // 旧实现在单条快照上就闭锁，分页数据到达后不再定位——错停在第一张。
    var positionedOnFullSnapshot by remember(initialPath) { mutableStateOf(false) }
    LaunchedEffect(initialPath, snapshot.items, snapshot.placeholdersBefore) {
        val loadedIndex = snapshot.items.indexOfFirst { it.filePath == initialPath }
        if (loadedIndex >= 0) {
            val absolute = snapshot.placeholdersBefore + loadedIndex
            if (snapshot.items.size == 1 && snapshot.placeholdersBefore == 0) {
                if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
            } else if (!positionedOnFullSnapshot) {
                pagerState.scrollToPage(absolute)
                positionedOnFullSnapshot = true
            }
        }
    }

    var showOverlay by remember { mutableStateOf(true) }
    // 当前页视频是否处于播放态（POSTER/ENDED 均为 false）。底部元数据栏与视频控制栏
    // 同锚 BottomCenter，必须互斥：播放时元数据栏让位（Google Photos 范式），否则重叠。
    var videoPlaybackActive by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExifDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 乐观收藏状态覆盖表：点击收藏时即时翻转本地状态，
    // 无需等待 DB 写入完成即可变色，提供即时视觉反馈。
    // key = filePath，value = 用户最新点击后的收藏状态。
    val favoriteOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    // --- Swipe-to-dismiss state（Google Photos 风格）---
    // 使用 Animatable 支持拖拽时即时跟随 + 释放时弹性回弹/滑出动画
    val dismissOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val screenHeight = with(LocalDensity.current) { LocalContext.current.resources.displayMetrics.heightPixels.toFloat() }
    val dismissThreshold = screenHeight * 0.2f

    // 背景透明度随拖拽距离即时变化（无需动画，跟随手指）
    val bgAlpha = (1f - (abs(dismissOffset.value) / dismissThreshold)).coerceIn(0f, 1f)

    val currentItem = mediaItems[pagerState.currentPage]
    var resolvedPreviewPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentItem?.filePath, currentItem?.modifiedAt, currentItem?.fileSize) {
        // 切页后先清除上一页预览，避免异步解析期间短暂显示错误照片。
        resolvedPreviewPath = null
        resolvedPreviewPath = currentItem?.let { resolvePreview(it) }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm && currentItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除 \"${currentItem.fileName}\" 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onDeleteItem(currentItem.filePath)
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

    // EXIF info dialog
    if (showExifDialog && currentItem != null) {
        val item = currentItem
        // 磁盘 I/O 不得在组合期主线程执行（审计 F-P2-5）：移入 LaunchedEffect + IO 调度器。
        var exifInfo by remember(item.filePath) { mutableStateOf<ExifExtractor.ExifInfo?>(null) }
        LaunchedEffect(item.filePath) {
            exifInfo = if (item.type == MediaType.IMAGE) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ExifExtractor.extract(item.filePath)
                }
            } else {
                null
            }
        }
        AlertDialog(
            onDismissRequest = { showExifDialog = false },
            title = { Text("媒体信息") },
            text = {
                Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                ) {
                    // ---- 基本文件信息 ----
                    Text("基本信息", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("文件名: ${item.fileName}", style = MaterialTheme.typography.bodySmall)
                    Text("类型: ${if (item.type == MediaType.IMAGE) "图片" else "视频"}", style = MaterialTheme.typography.bodySmall)
                    Text("文件大小: ${item.formattedSize}", style = MaterialTheme.typography.bodySmall)
                    if (item.width > 0 && item.height > 0) {
                        Text("分辨率: ${item.width}x${item.height}", style = MaterialTheme.typography.bodySmall)
                    }

                    // ---- EXIF 相机参数 ----
                    val parsedExif = exifInfo
                    if (parsedExif != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("相机参数", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        ExifExtractor.formatExifInfo(parsedExif).filterNot { (label, _) ->
                            label in listOf("文件名", "文件大小", "分辨率")
                        }.forEach { (label, value) ->
                            Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // ---- 智能分析结果 ----
                    Spacer(Modifier.height(4.dp))
                    Text("智能分析", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (item.sceneType != null) {
                        Text(
                            "场景类型: ${formatSceneType(item.sceneType)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text("场景类型: 未识别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (item.qualityScore > 0f) {
                        val scorePercent = (item.qualityScore * 100).toInt()
                        Text(
                            "质量评分: $scorePercent/100",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                item.qualityScore >= 0.7f -> Color(0xFF4CAF50)
                                item.qualityScore >= 0.4f -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            },
                        )
                    }
                    if (item.aiLabels.isNotEmpty()) {
                        Text("标签: ${item.aiLabels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    // ---- OCR 文字识别 ----
                    Spacer(Modifier.height(4.dp))
                    Text("识别文字 (OCR)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    val ocrText = item.ocrText
                    if (!ocrText.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                ocrText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        Text(
                            if (item.type == MediaType.IMAGE) "未识别到文字（可能图片中无文字，或尚未完成 OCR 分析）"
                            else "视频不支持 OCR 文字识别",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    // ---- 路径信息 ----
                    Spacer(Modifier.height(4.dp))
                    Text("路径: ${item.filePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                TextButton(onClick = { showExifDialog = false }) {
                    Text("关闭")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha)),
    ) {
        // 切页复位：新页若是视频会在组合后上报真实播放态
        LaunchedEffect(pagerState.currentPage) {
            videoPlaybackActive = false
        }

        // Pager — each page clips to visible area
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .clipToBounds(),
        ) { page ->
            val item = mediaItems[page] ?: return@HorizontalPager
            when (item.type) {
                MediaType.IMAGE -> ZoomableImageContent(
                    sources = viewerImageSources(
                        originalPath = item.filePath,
                        previewPath = resolvedPreviewPath.takeIf { page == pagerState.currentPage },
                    ),
                    dismissOffset = dismissOffset.value,
                    onTap = { showOverlay = !showOverlay },
                    onVerticalDrag = { delta ->
                        coroutineScope.launch {
                            dismissOffset.snapTo(dismissOffset.value + delta)
                        }
                    },
                    onVerticalDragEnd = {
                        coroutineScope.launch {
                            if (abs(dismissOffset.value) > dismissThreshold) {
                                // 超过阈值 → 滑出并关闭
                                val direction = if (dismissOffset.value > 0) 1f else -1f
                                dismissOffset.animateTo(
                                    direction * screenHeight,
                                    spring(),
                                )
                                onBack()
                            } else {
                                // 未超过阈值 → 弹性回弹
                                dismissOffset.animateTo(0f, spring())
                            }
                        }
                    },
                    onVerticalDragCancel = {
                        coroutineScope.launch {
                            dismissOffset.animateTo(0f, spring())
                        }
                    },
                )
                MediaType.VIDEO -> VideoContent(
                    filePath = item.filePath,
                    durationMs = item.durationMs,
                    // P1-4: 仅当前可见页播放视频
                    isCurrentPage = (page == pagerState.currentPage),
                    // chrome 统一由查看器级 showOverlay 驱动（顶栏与视频控制栏同显同隐），
                    // 消除此前两套独立状态 3 秒后分叉的问题
                    chromeVisible = showOverlay,
                    onToggleChrome = { showOverlay = !showOverlay },
                    onChromeAutoHide = { showOverlay = false },
                    onPlaybackActiveChanged = { videoPlaybackActive = it },
                )
            }
        }

        // --- Animated share-style button row (top, above pager) ---
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Share button
                if (currentItem != null) {
                    IconButton(onClick = {
                        val shareUri = runCatching {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                File(currentItem.filePath),
                            )
                        }.getOrNull()
                        if (shareUri == null) {
                            Toast.makeText(context, "无法生成分享链接", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (currentItem.type == MediaType.IMAGE) "image/*" else "video/*"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享媒体"))
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "分享",
                            tint = Color.White,
                        )
                    }
                    // Favorite button
                    // 乐观更新：点击后即时翻转本地状态，星标立即变色，
                    // 无需等待 DB 写入完成。DB 写入在后台异步进行。
                    val isFavorite = favoriteOverrides[currentItem.filePath] ?: currentItem.isFavorite
                    IconButton(onClick = {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        // 即时翻转本地覆盖状态，触发重组使星标立即变色
                        favoriteOverrides[currentItem.filePath] = !isFavorite
                        // 后台异步写入 DB（不阻塞 UI）
                        onToggleFavorite(currentItem.filePath)
                    }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${mediaItems.itemCount}",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }

        // --- Metadata bar at bottom（视频播放态让位给控制栏，避免同锚重叠） ---
        AnimatedVisibility(
            visible = showOverlay && !(currentItem?.type == MediaType.VIDEO && videoPlaybackActive),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (currentItem != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            text = currentItem.fileName,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatMediaDateTime(currentItem.capturedAt),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentItem.filePath,
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { showExifDialog = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "详细信息",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 手势方向锁定枚举。
 * 手势开始后根据初始方向锁定为水平或竖直，锁定后整个手势期间不再改变，
 * 避免中途抖动导致方向反复切换而误触 dismiss 或切图。
 */
private enum class DragDirection { None, Horizontal, Vertical }

/**
 * 支持完整手势的图片内容组件（对齐 Google Photos 交互）：
 * - 单击：切换信息栏显示/隐藏
 * - 双击：1x ↔ 2x 缩放切换
 * - 双指缩放：1x–5x，放大后可平移
 * - 下滑关闭（Swipe-to-dismiss）：1x 时向下拖拽关闭查看器，图片跟随手指移动
 * - 关键修复：使用方向锁定（direction lock），1x 时水平滑动不消费，
 *   让 HorizontalPager 处理左右切图；竖直滑动才触发 dismiss
 */
internal data class ViewerImageSources(
    val previewPlaceholderPath: String?,
    val originalPath: String,
)

/** 预览图只作为原图解码期间的底层占位；最终显示源始终是原图。 */
internal fun viewerImageSources(originalPath: String, previewPath: String?): ViewerImageSources =
    ViewerImageSources(
        previewPlaceholderPath = previewPath?.takeUnless { it == originalPath },
        originalPath = originalPath,
    )

@Composable
private fun ZoomableImageContent(
    sources: ViewerImageSources,
    dismissOffset: Float,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    onVerticalDragCancel: () -> Unit,
) {
    // telephoto（me.saket.telephoto:zoomable）接管全部缩放交互：质心锚定的捏合缩放、
    // 双击按点击位置定点缩放、放大后平移（边界约束+回弹+惯性）、双击后单指缩放、
    // 到达缩放上限的触感反馈。此前自研实现绕中心缩放导致双击/捏合都不在手指位置，
    // 且平移无边界（审计后的手势治理，移植成熟方案替代）。
    val zoomableState = rememberZoomableState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 仅处理"未放大时的单指手势"：方向锁定——水平透传给 HorizontalPager 切图，
            // 竖直驱动下滑关闭。放大后（zoomFraction > 0）整段让位，telephoto 独占手势。
            .pointerInput(zoomableState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lockedDirection = DragDirection.None
                    var verticalAccum = 0f
                    var verticalEngaged = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        val zoomed = (zoomableState.zoomFraction ?: 0f) > 0f
                        if (!zoomed && pressedChanges.size == 1) {
                            val change = pressedChanges.first()
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y

                            // 方向尚未锁定时，根据初始位移判断并锁定，避免中途抖动误触
                            if (lockedDirection == DragDirection.None) {
                                val absDx = abs(dx)
                                val absDy = abs(dy)
                                if (absDx > 8f || absDy > 8f) {
                                    lockedDirection =
                                        if (absDx > absDy) DragDirection.Horizontal else DragDirection.Vertical
                                }
                            }

                            if (lockedDirection == DragDirection.Vertical) {
                                // 竖直滑动 → 下滑关闭（Google Photos 风格）
                                verticalAccum += dy
                                verticalEngaged = true
                                onVerticalDrag(dy)
                                change.consume()
                            }
                            // Horizontal → 不消费，透传给 Pager 切图
                        }
                    } while (pressedChanges.isNotEmpty())

                    // 手势结束：按竖直累计量决定是否 dismiss
                    if (verticalEngaged) {
                        if (abs(verticalAccum) > 10f) onVerticalDragEnd() else onVerticalDragCancel()
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 未放大时应用 dismiss 偏移使图片跟随手指；放大后由 telephoto 变换接管
                    val zoomed = (zoomableState.zoomFraction ?: 0f) > 0f
                    translationY = if (!zoomed) dismissOffset else 0f
                }
                .zoomable(
                    state = zoomableState,
                    // 单击切换界面 chrome。telephoto 内部做双击消歧（单击等待超时后才触发），
                    // 双击由其按点击位置定点缩放，二者互不抢占。
                    onClick = { onTap() },
                ),
        ) {
            // 已有预览缓存时先显示为底层占位。原图解码成功后，上层图片自然覆盖它；
            // 原图加载失败时占位仍保留，避免黑屏。
            sources.previewPlaceholderPath?.let { previewPath ->
                AsyncImage(
                    model = previewPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            AsyncImage(
                model = sources.originalPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun VideoContent(
    filePath: String,
    durationMs: Long,
    isCurrentPage: Boolean = true,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onChromeAutoHide: () -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // ---- 三态模型（Google Photos 范式）----
    // POSTER：详情态——Coil 视频帧海报 + 播放按钮 + 时长徽标，零播放器实例；
    // PLAYING：播放态——首次点播才创建 ExoPlayer + 自绘控制栏（跟随查看器 chrome）；
    // ENDED：重播态——画面停在结尾，进度条停在末尾，居中重播按钮。
    var playbackState by remember(filePath) { mutableStateOf(VideoPlaybackState.POSTER) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(durationMs) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    // 标记用户是否正在拖拽进度条，避免轮询与手动拖拽冲突导致位置跳变
    var isUserSeeking by remember { mutableStateOf(false) }

    // 播放态上报父级：仅 PLAYING 为 true（海报/重播均为"详情语义"，元数据栏回归）
    LaunchedEffect(playbackState) {
        onPlaybackActiveChanged(playbackState == VideoPlaybackState.PLAYING)
    }

    // 播放器懒创建：海报态零解码器；进入播放态才 build（remember 键含 playbackState 的"已开始"位）
    val hasStarted = playbackState != VideoPlaybackState.POSTER
    val player = remember(filePath, hasStarted) {
        if (!hasStarted) null else ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            // 拔出耳机自动暂停（Media3 官方推荐）
            setHandleAudioBecomingNoisy(true)
            setMediaItem(Media3Item.fromUri(filePath))
            prepare()
            playWhenReady = true
        }
    }

    // 仅当前可见页播放；离屏页暂停并保留进度（翻页释放由 DisposableEffect 完成）
    LaunchedEffect(isCurrentPage, hasStarted) {
        val p = player ?: return@LaunchedEffect
        if (isCurrentPage && playbackState == VideoPlaybackState.PLAYING) {
            p.playWhenReady = true
        } else if (!isCurrentPage) {
            p.playWhenReady = false
        }
    }

    if (player != null) {
        // 播放状态跟踪 + 生命周期（切后台暂停）
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(player, lifecycleOwner) {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        duration = player.duration.takeIf { it > 0 } ?: durationMs
                    }
                    // 播放结束进入重播态：画面与进度条都停在结尾（原实现进度归零与画面不一致）
                    if (state == androidx.media3.common.Player.STATE_ENDED) {
                        playbackState = VideoPlaybackState.ENDED
                        currentPosition = duration
                    }
                }
            }
            player.addListener(listener)
            val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    player.pause()
                }
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                player.removeListener(listener)
                player.release()
            }
        }

        // 进度轮询：播放中且非拖拽时 200ms 同步（松手后交还轮询，不再立即读 getPosition 避免 seek 异步竞态）
        LaunchedEffect(isPlaying) {
            while (isPlaying) {
                kotlinx.coroutines.delay(200)
                if (!isUserSeeking) {
                    currentPosition = player.currentPosition.coerceIn(0L, duration)
                }
            }
        }
    }

    // 自动隐藏 chrome：仅播放中的视频页启动 3s 计时（暂停/海报/重播态常驻；图片页不受影响）
    LaunchedEffect(chromeVisible, isPlaying, isCurrentPage) {
        if (chromeVisible && isPlaying && isCurrentPage) {
            kotlinx.coroutines.delay(3000)
            onChromeAutoHide()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---- 海报（详情态 / 重播前的底图） ----
        if (playbackState == VideoPlaybackState.POSTER) {
            AsyncImage(
                model = filePath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            // 时长徽标
            if (durationMs > 0) {
                Text(
                    text = formatVideoTime(durationMs),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // ---- 播放器 surface（播放态/结束态） ----
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- 手势层：单击行为按态分流 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(playbackState) {
                    detectTapGestures(
                        onTap = {
                            when (playbackState) {
                                // 详情态：点击海报开始播放并显示 chrome
                                VideoPlaybackState.POSTER -> {
                                    playbackState = VideoPlaybackState.PLAYING
                                    onToggleChrome()
                                }
                                // 播放态：切换整套 chrome（顶栏+控制栏同显同隐）
                                VideoPlaybackState.PLAYING -> onToggleChrome()
                                // 重播态：从头播放
                                VideoPlaybackState.ENDED -> {
                                    player?.seekTo(0L)
                                    currentPosition = 0L
                                    playbackState = VideoPlaybackState.PLAYING
                                    player?.play()
                                }
                            }
                        },
                    )
                },
        )

        // ---- 居中播放/重播按钮（非播放中显示） ----
        if (playbackState != VideoPlaybackState.PLAYING) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                    .clickable {
                        when (playbackState) {
                            VideoPlaybackState.POSTER -> {
                                playbackState = VideoPlaybackState.PLAYING
                                onToggleChrome()
                            }
                            VideoPlaybackState.ENDED -> {
                                player?.seekTo(0L)
                                currentPosition = 0L
                                playbackState = VideoPlaybackState.PLAYING
                                player?.play()
                            }
                            VideoPlaybackState.PLAYING -> Unit
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playbackState == VideoPlaybackState.ENDED) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                    contentDescription = if (playbackState == VideoPlaybackState.ENDED) "重播" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // ---- 自绘控制栏：可见性 = chromeVisible && 播放态（与顶栏同源同步） ----
        AnimatedVisibility(
            visible = chromeVisible && playbackState == VideoPlaybackState.PLAYING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // Seek bar
                androidx.compose.material3.Slider(
                    value = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        isUserSeeking = true
                        currentPosition = (fraction * duration).toLong()
                    },
                    onValueChangeFinished = {
                        player?.seekTo(currentPosition)
                        isUserSeeking = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.4f),
                    ),
                )
                // Time labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    Text(formatVideoTime(currentPosition), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    Text(formatVideoTime(duration), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }

                Spacer(Modifier.size(4.dp))

                // Playback controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    // Play/Pause
                    IconButton(onClick = {
                        if (isPlaying) player?.pause() else player?.play()
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                        )
                    }

                    // Speed selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.5f, 0.75f, 1f, 1.5f, 2f).forEach { speed ->
                            TextButton(
                                onClick = {
                                    playbackSpeed = speed
                                    player?.setPlaybackSpeed(speed)
                                },
                                modifier = Modifier.size(36.dp, 32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = "${speed}x",
                                    color = if (abs(playbackSpeed - speed) < 0.01f) Color.White else Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp,
                                    fontWeight = if (abs(playbackSpeed - speed) < 0.01f) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 视频三态：详情海报 → 播放 → 结束（重播）。 */
private enum class VideoPlaybackState { POSTER, PLAYING, ENDED }

private fun formatVideoTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatMediaDateTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatSceneType(sceneType: String): String {
    return when (sceneType) {
        "landscape" -> "风景"
        "food" -> "美食"
        "document" -> "文档"
        "pet" -> "宠物"
        "screenshot" -> "截图"
        "selfie" -> "自拍"
        "portrait" -> "人像"
        "night" -> "夜景"
        "sports" -> "运动"
        "macro" -> "微距"
        else -> sceneType
    }
}
