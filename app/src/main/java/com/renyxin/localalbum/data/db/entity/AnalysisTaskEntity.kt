package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 持久化图片分析任务。任务由扫描批次创建，由唯一 WorkManager Worker 使用租约领取。 */
@Entity(
    tableName = "analysis_tasks",
    indices = [
        Index(value = ["filePath", "sourceVersion", "pipelineScope"], unique = true),
        Index(value = ["status", "nextRetryAt", "priority"]),
        Index(value = ["leaseUntil"]),
        Index(value = ["leaseToken"]),
    ],
)
data class AnalysisTaskEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0L,
    val filePath: String,
    val sourceVersion: String,
    val pipelineScope: String = DEFAULT_PIPELINE_SCOPE,
    val priority: Int = PRIORITY_SCAN,
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
        const val PRIORITY_SCAN = 0
        const val PRIORITY_USER = 100
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
        const val DEFAULT_PIPELINE_SCOPE = "core:v1"
    }
}
