package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 持久化缩略图任务；唯一键严格等于完整 [ThumbnailIdentity]。 */
@Entity(
    tableName = "thumbnail_tasks",
    indices = [
        Index(
            value = ["filePath", "mediaType", "sourceVersion", "sizeClass", "formatVersion"],
            unique = true,
        ),
        Index(value = ["status", "nextRetryAt", "priority"]),
        Index(value = ["scanId", "status"]),
        Index(value = ["leaseUntil"]),
        Index(value = ["leaseToken"]),
    ],
)
data class ThumbnailTaskEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0L,
    val filePath: String,
    val sizeClass: String = SIZE_GRID,
    val sourceVersion: String,
    val mediaType: String,
    val formatVersion: Int = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity.CURRENT_FORMAT_VERSION,
    val priority: Int = PRIORITY_BACKGROUND,
    val status: String = STATUS_PENDING,
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val leaseUntil: Long = 0L,
    val leaseToken: String? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Null denotes an interactive/legacy task that is not owned by an automatic scan. */
    val scanId: String? = null,
) {
    companion object {
        const val SIZE_GRID = "grid"
        const val SIZE_PREVIEW = "preview"
        const val PRIORITY_BACKGROUND = 0
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
    }
}
