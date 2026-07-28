package com.renyxin.localalbum.core.model

import java.time.Instant

/**
 * 增强版媒体项，包含完整 EXIF 元数据和智能标签信息。
 */
data class MediaItem(
    val id: String,
    val filePath: String,
    val fileName: String,
    val type: MediaType,
    val capturedAt: Instant,
    val modifiedAt: Instant,
    val fileSize: Long = 0L,
    val isFavorite: Boolean = false,
    // ---- EXIF 元数据 ----
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val durationMs: Long = 0L, // 视频时长（毫秒）
    val isTrashed: Boolean = false,
    val deletedAtMs: Long = 0L, // 移入回收站的时间戳
    val latitude: Double? = null,
    val longitude: Double? = null,
    val make: String? = null,           // 相机品牌
    val model: String? = null,          // 相机型号
    val aperture: String? = null,       // 光圈
    val focalLength: String? = null,    // 焦距
    val iso: String? = null,            // ISO
    val exposureTime: String? = null,   // 曝光时间
    val orientation: Int = 0,           // EXIF 方向
    // ---- AI 标签 (后续由 TFLite 填充) ----
    val aiLabels: List<String> = emptyList(),
    val sceneType: String? = null,      // 场景分类: landscape/food/document/pet/screenshot/selfie
    val qualityScore: Float = 0f,       // 质量评分 0–1
    val ocrText: String? = null,        // OCR 文本
    // ---- 缩略图路径 ----
    val thumbnailPath: String? = null,
    // ---- 损坏标记 ----
    val isCorrupted: Boolean = false,   // 文件损坏标记
    // ---- 人脸聚类 ID（Phase 3.3，代表该照片中出现的主要人物聚类） ----
    val faceClusterId: String? = null,
) {
    /** 是否为视频 */
    val isVideo: Boolean get() = type == MediaType.VIDEO

    /** 是否为图片 */
    val isImage: Boolean get() = type == MediaType.IMAGE

    /** 格式化文件大小 */
    val formattedSize: String get() {
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.0f KB".format(kb)
            else -> "$fileSize B"
        }
    }
}