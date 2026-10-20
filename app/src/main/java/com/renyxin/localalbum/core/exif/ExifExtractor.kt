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
                // EXIF 有理数（形如 "22/10"）在此换算为可读值，避免信息面板显示原始分数（审计 F-P2-5）。
                aperture = formatRationalField(exif.getAttribute(ExifInterface.TAG_F_NUMBER)),
                focalLength = formatRationalField(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)),
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                exposureTime = formatExposure(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                gpsLatitude = formatDms(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)),
                gpsLongitude = formatDms(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)),
                gpsAltitude = formatRationalField(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
                    ?.let { "$it m" },
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

    // ---- EXIF 有理数解析（审计 F-P2-5） ----

    /** "a/b" 或纯数字 → Double；分母为 0 或格式异常返回 null。 */
    private fun rationalToDouble(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return try {
            val slash = trimmed.indexOf('/')
            if (slash <= 0) {
                trimmed.toDouble()
            } else {
                val numerator = trimmed.substring(0, slash).trim().toDouble()
                val denominator = trimmed.substring(slash + 1).trim().toDouble()
                if (denominator == 0.0) null else numerator / denominator
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)

    private fun formatRationalField(raw: String?): String? =
        rationalToDouble(raw)?.let { formatNumber(it) } ?: raw?.takeIf { it.isNotBlank() }

    private fun formatExposure(raw: String?): String? {
        val seconds = rationalToDouble(raw) ?: return raw?.takeIf { it.isNotBlank() }
        return when {
            seconds > 0 && seconds < 1 -> "1/${Math.round(1.0 / seconds)} s"
            seconds >= 1 -> "${formatNumber(seconds)} s"
            else -> raw
        }
    }

    /** 度分秒有理数（"deg/1,min/1,sec/100"）→ 十进制度；解析失败原样返回。 */
    private fun formatDms(raw: String?): String? {
        val parts = raw?.split(',', ' ')?.mapNotNull { rationalToDouble(it) } ?: return null
        if (parts.size < 3) return raw
        val decimal = parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
        return String.format("%.5f", decimal)
    }

    fun formatExifInfo(info: ExifInfo): List<Pair<String, String>> {        val result = mutableListOf<Pair<String, String>>()
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
