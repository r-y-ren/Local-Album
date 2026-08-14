package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 核心媒体提交与后台增强之间的持久交接记录。
 *
 * 核心事务只写这张紧凑表；只有所属 scan run 的快照已经发布并进入
 * [CoreScanState.COMPLETED] 后，增强 handoff Worker 才能领取并创建实际任务。
 */
@Entity(
    tableName = "enhancement_outbox",
    indices = [
        Index(value = ["scanId", "status", "createdAt"]),
        Index(value = ["status", "nextRetryAt", "createdAt"]),
        Index(value = ["leaseUntil"]),
        Index(value = ["leaseToken"]),
        Index(value = ["profileId", "filePath", "sourceVersion", "pipelineScope"], unique = true),
    ],
)
data class EnhancementOutboxEntity(
    @PrimaryKey(autoGenerate = true) val outboxId: Long = 0L,
    val scanId: String,
    val profileId: String,
    val filePath: String,
    val sourceVersion: String,
    val mediaType: String,
    val pipelineScope: String,
    val enqueueAnalysis: Boolean,
    val enqueueThumbnail: Boolean,
    val parentPath: String,
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
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
