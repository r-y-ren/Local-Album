package com.renyxin.localalbum.core.exif

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * EXIF 元数据提取器：从图片文件中读取完整 EXIF 信息。
 */
object ExifExtractor {

    data class ExifInfo(
        val fileName: String,
        val fileSize: Long,
        val imageWidth: Int? = null,
        val imageHeight: Int? = null,
        val make: String? = null,
        val model: String? = null,
        val aperture: String? = null,
        val focalLength: String? = null,
        val iso: String? = null,
        val exposureTime: String? = null,
        val dateTimeOriginal: String? = null,
        val gpsLatitude: String? = null,
        val gpsLongitude: String? = null,
        val gpsAltitude: String? = null,
        val software: String? = null,
        val orientation: String? = null,
        val whiteBalance: String? = null,
        val colorSpace: String? = null,
        val sceneCaptureType: String? = null,
    )

    fun extract(filePath: String): ExifInfo? {
        val file = File(filePath)
        if (!file.exists()) return null

        val fileName = file.name
        val fileSize = file.length()

        return runCatching {
            val exif = ExifInterface(filePath)
            ExifInfo(
                fileName = fileName,
                fileSize = fileSize,
                imageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1)
                    .takeIf { it > 0 },
                imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, -1)
                    .takeIf { it > 0 },
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                gpsLatitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
                gpsLongitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE),
                gpsAltitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION),
                whiteBalance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE),
                colorSpace = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE),
                sceneCaptureType = exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE),
            )
        }.onFailure { e ->
            Log.w("ExifExtractor", "Failed to read EXIF from $filePath: ${e.message}")
        }.getOrNull()
    }

    fun formatExifInfo(info: ExifInfo): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        result += "文件名" to info.fileName
        result += "文件大小" to formatFileSize(info.fileSize)
        info.imageWidth?.let { w ->
            info.imageHeight?.let { h ->
                result += "分辨率" to "${w}x${h}"
            }
        }
        info.make?.let { result += "设备品牌" to it }
        info.model?.let { result += "设备型号" to it }
        info.aperture?.let { result += "光圈" to "f/$it" }
        info.focalLength?.let { result += "焦距" to "${it}mm" }
        info.iso?.let { result += "ISO" to it }
        info.exposureTime?.let { result += "曝光时间" to it }
        info.dateTimeOriginal?.let { result += "拍摄时间" to it }
        info.software?.let { result += "软件" to it }
        info.orientation?.let { result += "方向" to it }
        info.whiteBalance?.let { result += "白平衡" to it }
        info.colorSpace?.let { result += "色彩空间" to it }
        info.sceneCaptureType?.let { result += "场景类型" to it }
        info.gpsLatitude?.let { result += "GPS纬度" to it }
        info.gpsLongitude?.let { result += "GPS经度" to it }
        info.gpsAltitude?.let { result += "GPS海拔" to it }
        return result
    }

    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
