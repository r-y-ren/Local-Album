package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** 可恢复离线维护运行；cursorPath 是按 filePath keyset 扫描的稳定游标。 */
@Entity(
    tableName = "maintenance_runs",
    primaryKeys = ["taskType", "generation"],
    indices = [
        Index(value = ["taskType", "status", "startedAt"]),
        Index(value = ["taskType", "isActive"]),
    ],
)
data class MaintenanceRunEntity(
    val taskType: String,
    val generation: Long,
    val status: String = STATUS_RUNNING,
    val cursorPath: String? = null,
    val scannedCount: Long = 0,
    val eligibleCount: Long = 0,
    val failedCount: Long = 0,
    val groupCount: Long = 0,
    val memberCount: Long = 0,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val isActive: Boolean = false,
) {
    companion object {
        const val TASK_DUPLICATE_EXACT = "DUPLICATE_EXACT"
        const val TASK_FACE_PROTOTYPES = "FACE_PROTOTYPES"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_FINALIZING = "FINALIZING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

/** 扫描阶段的有界落盘结果；完整 SHA-256 仅在相同 fileSize 候选中计算。 */
@Entity(
    tableName = "duplicate_hash_staging",
    primaryKeys = ["generation", "filePath"],
    indices = [
        Index(value = ["generation", "fileSize", "exactHash"]),
        Index(value = ["generation", "filePath"]),
    ],
)
data class DuplicateHashStagingEntity(
    val generation: Long,
    val filePath: String,
    val fileSize: Long,
    val exactHash: String,
    val qualityScore: Float,
    val modifiedAtMs: Long,
)

/** 已发布 generation 的重复组摘要；groupId 为 generation 与 exactHash 的稳定组合。 */
@Entity(
    tableName = "duplicate_groups",
    primaryKeys = ["generation", "groupId"],
    indices = [
        Index(value = ["generation", "invalidated", "wastedBytes"]),
        Index(value = ["generation", "exactHash"]),
    ],
)
data class DuplicateGroupEntity(
    val generation: Long,
    val groupId: String,
    val exactHash: String,
    val memberCount: Int,
    val totalBytes: Long,
    val wastedBytes: Long,
    val recommendedKeepPath: String,
    val invalidated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 重复组成员快照；删除操作只允许读取本表，不得重新检测。 */
@Entity(
    tableName = "duplicate_members",
    primaryKeys = ["generation", "groupId", "filePath"],
    indices = [
        Index(value = ["generation", "groupId", "filePath"]),
        Index(value = ["filePath"]),
    ],
)
data class DuplicateMemberEntity(
    val generation: Long,
    val groupId: String,
    val filePath: String,
    val fileSize: Long,
    val qualityScore: Float,
    val modifiedAtMs: Long,
    val isRecommendedKeep: Boolean,
)
