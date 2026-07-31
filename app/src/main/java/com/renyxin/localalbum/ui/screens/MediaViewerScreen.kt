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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
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
    var initialPathPositioned by remember(initialPath) { mutableStateOf(false) }
    LaunchedEffect(initialPath, snapshot.items, snapshot.placeholdersBefore) {
        if (!initialPathPositioned) {
            val loadedIndex = snapshot.items.indexOfFirst { it.filePath == initialPath }
            if (loadedIndex >= 0) {
                // snapshot.items 不包含占位符，Pager 页码则是完整数据集中的绝对索引。
                pagerState.scrollToPage(snapshot.placeholdersBefore + loadedIndex)
                initialPathPositioned = true
            }
        }
    }

    var showOverlay by remember { mutableStateOf(true) }
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
        val exifInfo = remember(currentItem.filePath, showExifDialog) {
            if (currentItem.type == MediaType.IMAGE) {
                ExifExtractor.extract(currentItem.filePath)
            } else {
                null
            }
        }
        val item = currentItem
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
                    if (exifInfo != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("相机参数", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        ExifExtractor.formatExifInfo(exifInfo).filterNot { (label, _) ->
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
                    filePath = if (page == pagerState.currentPage) resolvedPreviewPath ?: item.filePath else item.filePath,
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
                    // P1-4: 仅当前可见页播放视频
                    isCurrentPage = (page == pagerState.currentPage),
                    onTap = { showOverlay = !showOverlay },
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

        // --- Metadata bar at bottom ---
        AnimatedVisibility(
            visible = showOverlay,
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
@Composable
private fun ZoomableImageContent(
    filePath: String,
    dismissOffset: Float,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    onVerticalDragCancel: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 方向锁定状态：手势开始后根据初始方向锁定，避免中途抖动导致误触
    var dragDirection by remember { mutableStateOf(DragDirection.None) }

    // 记录竖直拖拽累计量（用于判断是否触发 dismiss）
    var verticalDragAccum by remember { mutableFloatStateOf(0f) }

    // 切页时重置所有状态
    LaunchedEffect(filePath) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        verticalDragAccum = 0f
        dragDirection = DragDirection.None
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 单击 / 双击
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.5f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2f
                        }
                    },
                )
            }
            // 统合手势：双指缩放 + 放大后平移 + 1x 时竖向拖拽关闭
            // 关键修复：使用方向锁定（direction lock），手势开始后根据初始方向
            // 确定是水平切图还是竖直关闭，锁定后不再改变，避免误触
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var previousZoom = 1f
                    var previousCentroid = firstDown.position
                    var isMultiTouchActive = false

                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val pressedChanges = changes.filter { it.pressed }

                        when {
                            // --- 多指：处理缩放 ---
                            pressedChanges.size >= 2 -> {
                                isMultiTouchActive = true
                                val centroid = event.calculateCentroid()
                                val zoomChange = if (previousZoom != 1f) {
                                    event.calculateZoom()
                                } else {
                                    1f
                                }
                                val pan = if (previousZoom != 1f) {
                                    centroid - previousCentroid
                                } else {
                                    androidx.compose.ui.geometry.Offset.Zero
                                }

                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                scale = newScale
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }

                                previousZoom = zoomChange
                                previousCentroid = centroid

                                // 消费多指事件，不传递给 pager
                                pressedChanges.forEach { if (it.pressed) it.consume() }
                            }

                            // --- 已缩放后的单指平移 ---
                            scale > 1f && pressedChanges.size == 1 -> {
                                val change = pressedChanges.first()
                                val pan = change.position - change.previousPosition
                                offsetX += pan.x
                                offsetY += pan.y
                                change.consume()
                            }

                            // --- 1x 未缩放的单指拖动：方向锁定 ---
                            pressedChanges.size == 1 -> {
                                val change = pressedChanges.first()
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y

                                // 方向尚未锁定时，根据初始位移判断并锁定方向
                                if (dragDirection == DragDirection.None) {
                                    val absDx = abs(dx)
                                    val absDy = abs(dy)
                                    if (absDx > 8f || absDy > 8f) {
                                        dragDirection = if (absDx > absDy) {
                                            DragDirection.Horizontal
                                        } else {
                                            DragDirection.Vertical
                                        }
                                    }
                                }

                                when (dragDirection) {
                                    DragDirection.Horizontal -> {
                                        // 水平滑动 → 不消费，透传给 HorizontalPager 处理切图
                                        if (verticalDragAccum != 0f) {
                                            onVerticalDragCancel()
                                            verticalDragAccum = 0f
                                        }
                                    }
                                    DragDirection.Vertical -> {
                                        // 竖直滑动 → 处理下滑关闭
                                        onVerticalDrag(dy)
                                        verticalDragAccum += dy
                                        change.consume()
                                    }
                                    DragDirection.None -> {
                                        // 方向尚未确定，不消费事件
                                    }
                                }
                            }
                        }
                    } while (pressedChanges.isNotEmpty())

                    // 手势结束：根据方向和竖直拖拽量决定 dismiss
                    if (!isMultiTouchActive && scale <= 1f) {
                        if (dragDirection == DragDirection.Vertical && abs(verticalDragAccum) > 10f) {
                            onVerticalDragEnd()
                        } else if (verticalDragAccum != 0f) {
                            onVerticalDragCancel()
                        }
                    }
                    dragDirection = DragDirection.None
                    verticalDragAccum = 0f
                }
            },
    ) {
        AsyncImage(
            model = filePath,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    // 1x 时应用 dismiss 偏移，使图片跟随手指上下移动（Google Photos 风格）
                    translationY = offsetY + (if (scale <= 1f) dismissOffset else 0f)
                },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun VideoContent(
    filePath: String,
    isCurrentPage: Boolean = true,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    // 标记用户是否正在拖拽进度条，避免轮询与手动拖拽冲突导致位置跳变
    var isUserSeeking by remember { mutableStateOf(false) }

    // P1-4: 以 filePath 为 remember key，确保切换视频时重建播放器
    val player = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(filePath))
            prepare()
            playWhenReady = false // 不自动播放，由当前页可见性控制
        }
    }

    // P1-4: 仅当前可见页播放，离屏页暂停，避免多个视频同时播放
    LaunchedEffect(isCurrentPage) {
        player.playWhenReady = isCurrentPage
    }

    // Track playback state
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    duration = player.duration
                }
                // 播放结束时重置进度条到起点
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    currentPosition = 0L
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 修复：周期性轮询播放器当前位置，驱动进度条实时移动。
    // 原实现缺少此机制，currentPosition 仅在用户手动拖拽时更新，
    // 导致播放时进度条纹丝不动。仅在播放中且非用户拖拽时更新，
    // 避免与手动 seek 操作冲突。
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            kotlinx.coroutines.delay(200)
            if (!isUserSeeking) {
                currentPosition = player.currentPosition
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(3000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Player view (hidden controller, we use our custom overlay)
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- Gesture overlay ----
        // 手势策略：仅处理点击（显示/隐藏控制栏），所有拖拽事件不消费，
        // 水平滑动交给 HorizontalPager 翻页（与图片一致），垂直滑动不处理。
        // 视频进度通过底部 SeekBar 调整。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                            onTap()
                        },
                    )
                },
        )

        // ---- Custom control overlay (bottom) ----
        AnimatedVisibility(
            visible = showControls,
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
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { fraction ->
                        // 用户拖拽时暂停轮询，避免位置跳变
                        isUserSeeking = true
                        currentPosition = (fraction * duration).toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(currentPosition)
                        // 松手后恢复轮询，并立即同步一次真实位置
                        isUserSeeking = false
                        currentPosition = player.currentPosition
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
                        if (isPlaying) player.pause() else player.play()
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
                                    player.setPlaybackSpeed(speed)
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
