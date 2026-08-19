package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.renyxin.localalbum.core.model.MediaType

/**
 * Immutable-at-runtime projection used by the home timeline.
 *
 * Scan, thumbnail, and AI workers write their canonical tables while this table remains untouched.
 * The pipeline replaces it in one transaction only after every grid thumbnail in the candidate
 * generation reached READY or permanent-placeholder state. This isolates Paging from enhancement
 * invalidations and keeps the previous complete home view visible during incremental work.
 */
@Entity(
    tableName = "home_media_snapshot",
    indices = [
        Index(value = ["publishedGeneration"]),
        Index(value = ["capturedAtMs", "filePath"]),
        Index(value = ["parentPath", "capturedAtMs", "filePath"]),
    ],
)
data class HomeMediaSnapshotEntity(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val mediaType: MediaType,
    val capturedAtMs: Long,
    val modifiedAtMs: Long,
    val fileSize: Long,
    val isFavorite: Boolean,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val durationMs: Long,
    val parentPath: String,
    val latitude: Double?,
    val longitude: Double?,
    val make: String?,
    val model: String?,
    val aperture: String?,
    val focalLength: String?,
    val iso: String?,
    val exposureTime: String?,
    val orientation: Int,
    val thumbnailPath: String?,
    val thumbnailState: String,
    val isCorrupted: Boolean,
    val publishedGeneration: Long,
) {
    companion object {
        const val THUMBNAIL_READY = "READY"
        const val THUMBNAIL_PLACEHOLDER = "PLACEHOLDER"
    }
}
