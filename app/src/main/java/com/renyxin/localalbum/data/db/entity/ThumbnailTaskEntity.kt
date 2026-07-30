package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 持久化缩略图任务；唯一缓存键由 filePath、sizeClass 和 sourceVersion 组成。 */
@Entity(
    tableName = "thumbnail_tasks",
    indices = [
        Index(value = ["filePath", "sizeClass", "sourceVersion"], unique = true),
        Index(value = ["status", "nextRetryAt", "priority"]),
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
    val priority: Int = PRIORITY_BACKGROUND,
    val status: String = STATUS_PENDING,
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val leaseUntil: Long = 0L,
    val leaseToken: String? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SIZE_GRID = "grid"
        const val SIZE_PREVIEW = "preview"
        const val PRIORITY_BACKGROUND = 0
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
