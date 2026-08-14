package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.renyxin.localalbum.core.model.MediaType

/**
 * P2-A 覆盖式恢复的隔离区。generation 允许清理陈旧导入，并使不同导入的数据绝不混合。
 * staging 不声明生产表外键：解析和分批写入期间生产数据完全不受影响。
 */
@Entity(
    tableName = "import_media_staging",
    primaryKeys = ["generation", "filePath"],
    indices = [Index(value = ["generation"])],
)
data class ImportMediaStagingEntity(
    val generation: String,
    val filePath: String,
    val fileName: String,
    val mediaType: MediaType,
    val capturedAtMs: Long,
    val modifiedAtMs: Long,
    val indexedAtMs: Long,
    val parentPath: String,
    val fileSize: Long,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val durationMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val make: String?,
    val model: String?,
    val aperture: String?,
    val focalLength: String?,
    val iso: String?,
    val exposureTime: String?,
    val orientation: Int,
    val sceneType: String?,
    val thumbnailPath: String?,
    val fingerprintHead: String?,
    val perceptualHash: Long,
    val ocrText: String?,
    val qualityScore: Float,
    val deletedAtMs: Long,
    val isCorrupted: Boolean,
    val faceClusterId: String?,
    val scanGeneration: Long,
)

@Entity(
    tableName = "import_face_staging",
    indices = [Index(value = ["generation"])],
)
data class ImportFaceStagingEntity(
    @PrimaryKey(autoGenerate = true) val stagingId: Long = 0,
    val generation: String,
    val faceId: Long,
    val filePath: String,
    val clusterId: String?,
    val personName: String?,
    val embedding: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val thumbnailPath: String?,
    val detectedAtMs: Long,
)

@Entity(
    tableName = "import_embedding_staging",
    primaryKeys = ["generation", "filePath"],
    indices = [Index(value = ["generation"])],
)
data class ImportEmbeddingStagingEntity(
    val generation: String,
    val filePath: String,
    val embedding: String,
    val modelVersion: Int,
    val generatedAtMs: Long,
    val source: String,
    val embeddingBlob: ByteArray?,
    val providerId: String,
    val modelId: String,
    val dimension: Int,
    val spaceId: String,
    val vectorGeneration: Long,
    val codecId: String,
    val formatVersion: Int,
)

@Entity(
    tableName = "import_fts_staging",
    indices = [Index(value = ["generation"])],
)
data class ImportFtsStagingEntity(
    @PrimaryKey(autoGenerate = true) val stagingId: Long = 0,
    val generation: String,
    val filePath: String,
    val fileName: String,
    val parentPath: String,
    val ocrText: String?,
    val make: String?,
    val model: String?,
)

fun MediaEntity.toImportStaging(generation: String) = ImportMediaStagingEntity(
    generation, filePath, fileName, mediaType, capturedAtMs, modifiedAtMs, indexedAtMs,
    parentPath, fileSize, isFavorite, isTrashed, width, height, mimeType, durationMs,
    latitude, longitude, make, model, aperture, focalLength, iso, exposureTime, orientation,
    sceneType, thumbnailPath, fingerprintHead, perceptualHash, ocrText, qualityScore,
    deletedAtMs, isCorrupted, faceClusterId, scanGeneration,
)

fun FaceEntity.toImportStaging(generation: String) = ImportFaceStagingEntity(
    generation = generation, faceId = faceId, filePath = filePath, clusterId = clusterId,
    personName = personName, embedding = embedding, boxLeft = boxLeft, boxTop = boxTop,
    boxRight = boxRight, boxBottom = boxBottom, thumbnailPath = thumbnailPath,
    detectedAtMs = detectedAtMs,
)

fun MediaEmbedding.toImportStaging(generation: String) = ImportEmbeddingStagingEntity(
    generation, filePath, embedding, modelVersion, generatedAtMs, source, embeddingBlob,
    providerId, modelId, dimension, spaceId, this.generation, codecId, formatVersion,
)

fun MediaFts.toImportStaging(generation: String) = ImportFtsStagingEntity(
    generation = generation, filePath = filePath, fileName = fileName, parentPath = parentPath,
    ocrText = ocrText, make = make, model = model,
)
