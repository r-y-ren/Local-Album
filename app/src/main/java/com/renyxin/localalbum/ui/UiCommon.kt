package com.renyxin.localalbum.ui

/**
 * UI 层共享常量与工具（自 LocalAlbumApp.kt 纯 cut-paste 提取，行为不变）。
 *
 * 内容：主 Tab 索引、存储预设路径、缩略图请求尺寸等顶层常量，
 * 以及时间格式化、MediaEntity → MediaItem 转换等纯函数；
 * 另含各 Tab 共用的空状态组件 [EmptyStateView]。
 */
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.model.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 主 Tab 索引：与导航宿主的 navDestinations 顺序一一对应（0=照片，1=搜索，2=相册，3=设置）。 */
internal object MainTabIndex {
    const val PHOTOS = 0
    const val SEARCH = 1
    const val ALBUMS = 2
    const val SETTINGS = 3
}

/** 主外部存储根目录（默认主存储设备的约定路径；预设目录仅在该设备布局上成立）。 */
internal const val PRIMARY_EXTERNAL_STORAGE_ROOT = "/storage/emulated/0"

// 常用系统预设路径
internal val presetDirectories = listOf(
    "DCIM" to "$PRIMARY_EXTERNAL_STORAGE_ROOT/DCIM",
    "Pictures" to "$PRIMARY_EXTERNAL_STORAGE_ROOT/Pictures",
    "Download" to "$PRIMARY_EXTERNAL_STORAGE_ROOT/Download",
    "Movies" to "$PRIMARY_EXTERNAL_STORAGE_ROOT/Movies",
)

/** 手势引导浮层自动关闭延迟（毫秒）。 */
internal const val GESTURE_GUIDE_AUTO_DISMISS_MS = 4_000L

/** 备份导出文件名的时间戳格式（SimpleDateFormat pattern）。 */
internal const val BACKUP_TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

/** 视频网格缩略图角标的播放图标字符（与 MediaViewerScreen 的同类角标对应）。 */
internal const val VIDEO_BADGE_PLAY_TEXT = "▶"

/** Coil 缩略图请求像素尺寸：网格/全幅大图。 */
internal const val THUMB_REQUEST_SIZE_GRID = 256

/** Coil 缩略图请求像素尺寸：推荐卡片 2×2 网格半格。 */
internal const val THUMB_REQUEST_SIZE_CARD = 128

/** Coil 缩略图请求像素尺寸：相册封面小格。 */
internal const val THUMB_REQUEST_SIZE_COVER = 64

/** 空状态组件（扫描目录未配置/无媒体等场景的占位视图）。 */
@Composable
internal fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onButtonClick,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(buttonText)
            }
        }
    }
}

/** 将 [Instant] 格式化为中文日期（yyyy年M月d日），用于推荐卡片时间跨度展示。 */
internal fun formatInstant(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

/* ---- MediaEntity → MediaItem 转换扩展 ---- */

internal fun com.renyxin.localalbum.data.db.entity.MediaEntity.toMediaItem(): MediaItem {
    return MediaItem(
        id = java.util.UUID.nameUUIDFromBytes(filePath.toByteArray()).toString(),
        filePath = filePath,
        fileName = fileName,
        type = mediaType,
        capturedAt = java.time.Instant.ofEpochMilli(capturedAtMs),
        modifiedAt = java.time.Instant.ofEpochMilli(modifiedAtMs),
        fileSize = fileSize,
        isFavorite = isFavorite,
        width = width,
        height = height,
        mimeType = mimeType,
        durationMs = durationMs,
        isTrashed = isTrashed,
        latitude = latitude,
        longitude = longitude,
        make = make,
        model = model,
        aperture = aperture,
        focalLength = focalLength,
        iso = iso,
        exposureTime = exposureTime,
        orientation = orientation,
        sceneType = sceneType,
        thumbnailPath = thumbnailPath,
        faceClusterId = faceClusterId,
        ocrText = ocrText,
        qualityScore = qualityScore,
    )
}
