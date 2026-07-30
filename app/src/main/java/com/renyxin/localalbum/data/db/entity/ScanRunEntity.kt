package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次媒体扫描的持久化生命周期。
 *
 * 只有 MediaStore 与 File API 两个来源都明确完成后，状态才能切换为 COMPLETED；
 * 孤儿删除必须以 COMPLETED 作为硬门禁，不能根据空列表或部分枚举推断文件已删除。
 */
@Entity(
    tableName = "scan_runs",
    indices = [
        Index(value = ["status", "startedAt"]),
        Index(value = ["generation"], unique = true),
    ],
)
data class ScanRunEntity(
    @PrimaryKey val scanId: String,
    val generation: Long,
    val scanType: String,
    val status: String = STATUS_RUNNING,
    val mediaStoreCompleted: Boolean = false,
    val fileSystemCompleted: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val errorType: String? = null,
) {
    companion object {
        const val TYPE_FULL = "FULL"
        const val TYPE_INCREMENTAL = "INCREMENTAL"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_ABORTED = "ABORTED"
    }
}
