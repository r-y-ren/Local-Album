package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次媒体扫描或持久增强修复的生命周期。
 *
 * 旧 [status] 与来源完成字段继续作为数据库升级和孤儿删除的兼容门禁；
 * [coreScanState]、[indexAvailability] 与 [enhancementState] 是新的正交完成语义。
 * 场景、质量、缩略图和其他后台任务不能通过 [status] 伪装成核心扫描完成。
 * [TYPE_THUMBNAIL_REPAIR] 复用同一控制面，但不代表重新枚举媒体或执行分析。
 */
@Entity(
    tableName = "scan_runs",
    indices = [
        Index(value = ["status", "startedAt"]),
        Index(value = ["coreScanState", "startedAt"]),
        Index(value = ["enhancementState", "startedAt"]),
        Index(value = ["generation"], unique = true),
    ],
)
data class ScanRunEntity(
    @PrimaryKey val scanId: String,
    val generation: Long,
    val scanType: String,
    /** Legacy terminal status retained for existing callers and orphan gates. */
    val status: String = STATUS_RUNNING,
    val mediaStoreCompleted: Boolean = false,
    val fileSystemCompleted: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val errorType: String? = null,
    val indexAvailability: String = IndexAvailability.EMPTY.name,
    val coreScanState: String = CoreScanState.IDLE.name,
    val enhancementState: String = EnhancementState.NOT_SCHEDULED.name,
    val firstBatchCommittedAt: Long = 0L,
    val indexAvailableAt: Long = 0L,
    val coreCompletedAt: Long = 0L,
    val enhancementStartedAt: Long = 0L,
    val enhancementCompletedAt: Long = 0L,
) {
    companion object {
        const val TYPE_FULL = "FULL"
        const val TYPE_INCREMENTAL = "INCREMENTAL"
        const val TYPE_RECONCILIATION = "RECONCILIATION"
        /** Durable app-level repair that owns only current Grid thumbnail identities. */
        const val TYPE_THUMBNAIL_REPAIR = "THUMBNAIL_REPAIR"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_ABORTED = "ABORTED"
    }
}

/** UI and telemetry-facing snapshot of a persisted scan run. */
data class ScanLifecycle(
    val scanId: String? = null,
    val scanType: String? = null,
    val indexAvailability: IndexAvailability = IndexAvailability.EMPTY,
    val coreScanState: CoreScanState = CoreScanState.IDLE,
    val enhancementState: EnhancementState = EnhancementState.NOT_SCHEDULED,
    val firstBatchCommittedAt: Long = 0L,
    val indexAvailableAt: Long = 0L,
    val coreCompletedAt: Long = 0L,
    val enhancementStartedAt: Long = 0L,
    val enhancementCompletedAt: Long = 0L,
    val errorType: String? = null,
) {
    companion object {
        fun from(run: ScanRunEntity?): ScanLifecycle = run?.let {
            ScanLifecycle(
                scanId = it.scanId,
                scanType = it.scanType,
                indexAvailability = enumOrDefault(it.indexAvailability, IndexAvailability.EMPTY),
                coreScanState = enumOrDefault(it.coreScanState, CoreScanState.IDLE),
                enhancementState = enumOrDefault(it.enhancementState, EnhancementState.NOT_SCHEDULED),
                firstBatchCommittedAt = it.firstBatchCommittedAt,
                indexAvailableAt = it.indexAvailableAt,
                coreCompletedAt = it.coreCompletedAt,
                enhancementStartedAt = it.enhancementStartedAt,
                enhancementCompletedAt = it.enhancementCompletedAt,
                errorType = it.errorType,
            )
        } ?: ScanLifecycle()

        private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
            runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    }
}
