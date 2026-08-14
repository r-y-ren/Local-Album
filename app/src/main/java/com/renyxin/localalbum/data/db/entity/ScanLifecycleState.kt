package com.renyxin.localalbum.data.db.entity

/**
 * 本次扫描运行的索引可用性；旧快照可继续展示，但不替代当前运行的事件。
 */
enum class IndexAvailability {
    EMPTY,
    PARTIAL,
    PUBLISHED,
}

/** 核心媒体索引生命周期；不包含场景、质量、缩略图或其他增强工作。 */
enum class CoreScanState {
    IDLE,
    DISCOVERING,
    APPLYING_CHANGES,
    RECONCILING_DELETES,
    PUBLISHING,
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED,
}

/**
 * 扫描后增强生命周期；NOT_SCHEDULED 表示本次核心扫描没有交接增强任务。
 * WAITING_FOR_CORE 用于覆盖式恢复后的对账屏障；PREEMPTED 是核心扫描暂时抢占，
 * 可自动恢复；PAUSED 与 WAITING_FOR_CORE_PAUSED 都保留用户持久暂停意图。
 */
enum class EnhancementState {
    NOT_SCHEDULED,
    WAITING_FOR_CORE,
    QUEUED,
    RUNNING,
    PREEMPTED,
    PAUSED,
    WAITING_FOR_CORE_PAUSED,
    COMPLETED,
    COMPLETED_WITH_FAILURES,
    CANCELLED,
}
