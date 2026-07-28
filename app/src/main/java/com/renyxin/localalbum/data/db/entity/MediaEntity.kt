package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.renyxin.localalbum.core.model.MediaType

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["capturedAtMs"]),
        Index(value = ["parentPath"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isTrashed"]),
        Index(value = ["mediaType"]),
        Index(value = ["sceneType"]),
        Index(value = ["geoClusterId"]),
        Index(value = ["fileSize"]),
        Index(value = ["modifiedAtMs"]),
        Index(value = ["isCorrupted"]),
        Index(value = ["faceClusterId"]),
    ],
)
data class MediaEntity(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val mediaType: MediaType,
    val capturedAtMs: Long,
    val modifiedAtMs: Long,
    val indexedAtMs: Long = System.currentTimeMillis(),
    val parentPath: String,
    val fileSize: Long = 0L,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val durationMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val make: String? = null,
    val model: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val iso: String? = null,
    val exposureTime: String? = null,
    val orientation: Int = 0,
    val sceneType: String? = null,
    val thumbnailPath: String? = null,
    // ---- v2 新增: 混合增量索引 ----
    val fingerprintHead: String? = null,   // 文件头哈希 (用于增量异常检测)
    @androidx.room.ColumnInfo(defaultValue = "0") val perceptualHash: Long = 0L, // 感知哈希
    // ---- v2 新增: OCR 文本 ----
    val ocrText: String? = null,
    // ---- v2 新增: 地理聚类 ID ----
    val geoClusterId: String? = null,
    // ---- v2 新增: 质量评分 ----
    @androidx.room.ColumnInfo(defaultValue = "0.0") val qualityScore: Float = 0f,
    // ---- v2 新增: 回收站时间戳 ----
    val deletedAtMs: Long = 0L,
    // ---- v3 新增: 损坏文件标记 ----
    @androidx.room.ColumnInfo(defaultValue = "0") val isCorrupted: Boolean = false,
    // ---- v4 新增: 人脸聚类 ID（代表该照片中出现的主要人物聚类） ----
    val faceClusterId: String? = null,
)
