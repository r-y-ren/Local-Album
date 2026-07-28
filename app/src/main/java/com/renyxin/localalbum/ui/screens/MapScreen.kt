package com.renyxin.localalbum.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.pipeline.StageFileProgress
import com.renyxin.localalbum.data.repo.GeoCluster
import com.renyxin.localalbum.data.repo.GeoStats
import com.renyxin.localalbum.ui.components.AnalysisProgressGrid
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

/**
 * 地图视图界面（Phase 3.4 地图视图 + 地理聚类）。
 *
 * 功能：
 * - 使用 osmdroid 开源地图 SDK 展示照片地理分布
 * - 每个地理聚类在地图上显示为带照片数量的标记
 * - 点击标记弹出聚类信息（地名 + 照片数 + 代表缩略图）
 * - 点击信息窗口进入该聚类的照片列表
 * - 底部统计栏显示聚类数量、照片总数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    geoClusters: List<GeoCluster>,
    geoStats: GeoStats,
    clusterPhotos: List<MediaItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    onMediaClick: (List<MediaItem>, Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    stageFileProgress: StageFileProgress = StageFileProgress.EMPTY,
    isPipelineRunning: Boolean = false,
) {
    val context = LocalContext.current

    // osmdroid 初始化配置
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("地图", fontWeight = FontWeight.Bold) },
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
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Phase 6.1: 管道运行中显示 per-file 进度网格（完成后自动隐藏，展示地图聚类）
            if (isPipelineRunning && stageFileProgress.files.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnalysisProgressGrid(stageProgress = stageFileProgress)
                }
            } else if (isLoading && geoClusters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在加载地图数据…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (geoClusters.isEmpty()) {
                EmptyMapState(modifier = Modifier.fillMaxSize())
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 统计信息栏
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatItem(label = "地点", value = "${geoStats.clusterCount}")
                            StatItem(label = "带定位照片", value = "${geoStats.totalGeoItems}")
                            StatItem(label = "未聚类", value = "${geoStats.noiseCount}")
                        }
                    }

                    // 地图视图
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        OsmMapView(
                            clusters = geoClusters,
                            context = context,
                            onClusterClick = onClusterClick,
                        )
                    }

                    // 聚类照片列表（点击标记后展示）
                    if (clusterPhotos.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                            ) {
                                Text(
                                    text = "该地点 ${clusterPhotos.size} 张照片",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(clusterPhotos, key = { it.filePath }) { photo ->
                                        ClusterPhotoThumb(
                                            photo = photo,
                                            onClick = {
                                                val idx = clusterPhotos.indexOf(photo)
                                                if (idx >= 0) onMediaClick(clusterPhotos, idx)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * osmdroid MapView 的 Compose 封装。
 * 在地图上为每个地理聚类添加标记。
 */
@Composable
private fun OsmMapView(
    clusters: List<GeoCluster>,
    context: Context,
    onClusterClick: (String) -> Unit,
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val imageLoader = remember { coil.Coil.imageLoader(context) }
    val scope = rememberCoroutineScope()
    val markerJobs = remember { mutableListOf<Job>() }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setBuiltInZoomControls(false)
                controller.setZoom(3.0)
                controller.setCenter(GeoPoint(35.0, 105.0)) // 默认中国中心
            }
        },
        update = { map ->
            mapView = map
            // 取消上一轮缩略图加载任务，避免对已移除的标记做无效刷新
            markerJobs.forEach { it.cancel() }
            markerJobs.clear()
            // 清除旧标记
            map.overlays.clear()

            // 为每个聚类添加标记
            for (cluster in clusters) {
                val point = GeoPoint(cluster.centerLatitude, cluster.centerLongitude)
                val marker = ClusterMarker(
                    mapView = map,
                    cluster = cluster,
                    context = context,
                    imageLoader = imageLoader,
                    scope = scope,
                    jobs = markerJobs,
                    onClick = { onClusterClick(cluster.clusterId) },
                )
                marker.position = point
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(marker)
            }

            // 自动缩放到所有标记
            if (clusters.isNotEmpty()) {
                val latitudes = clusters.map { it.centerLatitude }
                val longitudes = clusters.map { it.centerLongitude }
                val minLat = latitudes.min()
                val maxLat = latitudes.max()
                val minLon = longitudes.min()
                val maxLon = longitudes.max()
                val boundingBox = org.osmdroid.util.BoundingBox(
                    maxLat + 0.5, maxLon + 0.5, minLat - 0.5, minLon - 0.5
                )
                if (clusters.size == 1) {
                    map.controller.setZoom(15.0)
                    map.controller.setCenter(GeoPoint(clusters[0].centerLatitude, clusters[0].centerLongitude))
                } else {
                    map.zoomToBoundingBox(boundingBox, false, 40)
                }
            }

            map.invalidate()
        },
        modifier = Modifier.fillMaxSize(),
    )

    // 生命周期管理
    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

/**
 * 自定义聚类标记。
 *
 * 标记图标优先使用聚类代表缩略图（[GeoCluster.representativeThumb]）做圆形裁剪，
 * 并在右下角叠加照片数量角标；缩略图尚未加载完成或缺失时回退为带数字的蓝色圆圈，
 * 加载完成后异步刷新图标。
 */
private class ClusterMarker(
    mapView: MapView,
    private val cluster: GeoCluster,
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
    private val jobs: MutableList<Job>,
    private val onClick: () -> Unit,
) : Marker(mapView) {

    private val mapRef = mapView
    private val iconSize = 96

    @Volatile
    private var thumbBitmap: Bitmap? = null

    init {
        title = cluster.placeName ?: "位置 ${cluster.clusterId.take(6)}"
        snippet = "${cluster.photoCount} 张照片"
        // 初始图标：缩略图未加载时的降级样式（纯数字圆圈）
        icon = createClusterIcon(cluster.photoCount, null)
        setOnMarkerClickListener { _, _ ->
            onClick()
            // 显示默认信息窗口
            showInfoWindow()
            true
        }
        // 异步加载代表缩略图，完成后刷新标记图标
        val thumbPath = cluster.representativeThumb
        if (thumbPath != null) {
            val job = scope.launch {
                val request = ImageRequest.Builder(context)
                    .data(android.net.Uri.parse("file://$thumbPath"))
                    .size(iconSize)
                    .build()
                val result = imageLoader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable ?: return@launch
                val bmp = withContext(Dispatchers.IO) { drawableToBitmap(drawable, iconSize) }
                withContext(Dispatchers.Main) {
                    thumbBitmap = bmp
                    icon = createClusterIcon(cluster.photoCount, bmp)
                    mapRef.post { mapRef.invalidate() }
                }
            }
            jobs.add(job)
        }
    }

    /**
     * 将 Drawable 转换为指定尺寸的 Bitmap（用于 BitmapShader 圆形裁剪）。
     */
    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /**
     * 生成聚类标记图标。
     * - 有缩略图时：圆形裁剪的代表缩略图 + 右下角数字角标。
     * - 无缩略图时：回退为带数字的蓝色圆圈。
     */
    private fun createClusterIcon(count: Int, thumb: Bitmap?): Drawable {
        val size = iconSize
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f - 4f

        // 外圈阴影
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(60, 0, 0, 0)
        }
        canvas.drawCircle(cx, cy + 2f, radius, shadowPaint)

        if (thumb != null) {
            // 圆形裁剪缩略图
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val shader = BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val srcSize = minOf(thumb.width, thumb.height).toFloat()
            val scale = (radius * 2f) / srcSize
            val matrix = Matrix()
            matrix.setTranslate((thumb.width - srcSize) / -2f, (thumb.height - srcSize) / -2f)
            matrix.postScale(scale, scale)
            matrix.postTranslate(cx - radius, cy - radius)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(cx, cy, radius, paint)
        } else {
            // 回退：蓝色主圆
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(66, 133, 244) // Google Blue
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, radius, circlePaint)
        }

        // 白色边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 有缩略图时数字以角标形式叠加在右下角；无缩略图时数字居中显示
        if (thumb != null) {
            drawCountBadge(canvas, count, cx, cy, radius, density)
        } else {
            drawCenterText(canvas, count, cx, cy, density)
        }

        return BitmapDrawable(context.resources, bitmap)
    }

    /** 右下角数字角标（白底蓝圆 + 数字）。 */
    private fun drawCountBadge(
        canvas: Canvas,
        count: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
    ) {
        val badgeRadius = 14f * density
        val bx = cx + radius * 0.62f
        val by = cy + radius * 0.62f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(bx, by, badgeRadius + 2f, bgPaint)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(66, 133, 244)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(bx, by, badgeRadius, badgePaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = badgeRadius * 1.1f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val text = if (count > 99) "99+" else count.toString()
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, bx, by + bounds.height() / 2f, textPaint)
    }

    /** 居中数字文本（无缩略图降级样式）。 */
    private fun drawCenterText(
        canvas: Canvas,
        count: Int,
        cx: Float,
        cy: Float,
        density: Float,
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 28f * density / 2
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val text = if (count > 99) "99+" else count.toString()
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, cx, cy + bounds.height() / 2f, textPaint)
    }
}

@Composable
private fun ClusterPhotoThumb(
    photo: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbPath = photo.thumbnailPath ?: photo.filePath

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(android.net.Uri.parse("file://$thumbPath"))
                .crossfade(true)
                .size(128)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
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
private fun EmptyMapState(modifier: Modifier = Modifier) {
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
                        Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "暂无带定位的照片",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "扫描完成后，含 GPS 信息的照片将在此地图上展示分布。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
