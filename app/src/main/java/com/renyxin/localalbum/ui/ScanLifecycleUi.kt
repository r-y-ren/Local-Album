package com.renyxin.localalbum.ui

import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.repo.ScanState

/** Human-readable status mapping for the durable core/enhancement lifecycle. */
internal fun coreScanStatusText(
    availability: IndexAvailability,
    state: CoreScanState,
): String = when (state) {
    CoreScanState.IDLE -> when (availability) {
        IndexAvailability.EMPTY -> "核心索引未开始"
        IndexAvailability.PARTIAL -> "索引部分可用"
        IndexAvailability.PUBLISHED -> "核心索引已发布"
    }
    CoreScanState.DISCOVERING -> "正在发现媒体"
    CoreScanState.APPLYING_CHANGES -> if (availability == IndexAvailability.PARTIAL) {
        "索引已部分可用，正在应用变更"
    } else {
        "正在应用媒体变更"
    }
    CoreScanState.RECONCILING_DELETES -> "正在同步删除"
    CoreScanState.PUBLISHING -> "正在发布相册快照"
    CoreScanState.COMPLETED -> "核心扫描完成"
    CoreScanState.PAUSED -> "核心扫描已暂停，保留上次快照"
    CoreScanState.CANCELLED -> "核心扫描已取消，保留上次快照"
    CoreScanState.FAILED -> "核心扫描失败，保留上次快照"
}

internal fun enhancementStatusText(state: EnhancementState): String = when (state) {
    EnhancementState.NOT_SCHEDULED -> "后台增强未调度"
    EnhancementState.WAITING_FOR_CORE -> "等待核心对账后启动后台增强"
    EnhancementState.QUEUED -> "后台增强排队中"
    EnhancementState.RUNNING -> "后台增强进行中"
    EnhancementState.PREEMPTED -> "核心扫描优先，后台增强稍后恢复"
    EnhancementState.PAUSED,
    EnhancementState.WAITING_FOR_CORE_PAUSED -> "后台增强已暂停"
    EnhancementState.COMPLETED -> "后台增强完成"
    EnhancementState.COMPLETED_WITH_FAILURES -> "后台增强完成，部分任务失败"
    EnhancementState.CANCELLED -> "后台增强已取消"
}

internal fun isCoreScanActive(
    legacyState: ScanState,
    durableState: CoreScanState,
): Boolean = legacyState is ScanState.Scanning || durableState in setOf(
    CoreScanState.DISCOVERING,
    CoreScanState.APPLYING_CHANGES,
    CoreScanState.RECONCILING_DELETES,
    CoreScanState.PUBLISHING,
)
