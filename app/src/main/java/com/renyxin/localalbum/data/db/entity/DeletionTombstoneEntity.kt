package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户物理删除意图及其可恢复执行状态。
 *
 * [filePath] 仅用于设备本地执行，不得写入日志、遥测或错误 UI；对外诊断仅使用 [pathKey]。
 * 成功/文件缺失后记录进入 [STATE_COMPLETED] 而非删除，以阻止同一路径被扫描静默复活。
 * 只有用户显式恢复才删除 tombstone。
 */
@Entity(
    tableName = "deletion_tombstones",
    indices = [
        Index(value = ["state", "nextRetryAt", "leaseUntil"]),
        Index(value = ["leaseToken"]),
        Index(value = ["filePath"], unique = true),
    ],
)
data class DeletionTombstoneEntity(
    @PrimaryKey val pathKey: String,
    val filePath: String,
    val operation: String,
    val state: String = STATE_PENDING,
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0,
    val leaseUntil: Long = 0,
    val leaseToken: String? = null,
    val lastErrorType: String? = null,
    val lastErrorCode: String? = null,
    /** 已脱敏、无绝对路径的有限错误摘要。 */
    val lastErrorMessage: String? = null,
    val sourceVersion: String,
    val deletedAtMs: Long,
    val firstAttemptAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val OP_USER_PERMANENT = "USER_PERMANENT"
        const val OP_CLEAR_TRASH = "CLEAR_TRASH"
        const val OP_EXPIRED_TRASH = "EXPIRED_TRASH"

        const val STATE_PENDING = "PENDING"
        const val STATE_LEASED = "LEASED"
        const val STATE_EXHAUSTED = "EXHAUSTED"
        const val STATE_COMPLETED = "COMPLETED"
    }
}
