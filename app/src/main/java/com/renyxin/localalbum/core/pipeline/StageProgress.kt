package com.renyxin.localalbum.core.pipeline

/**
 * 统一进度模型（Phase 3.3）。
 *
 * 每个阶段执行时通过 [PluginAnalysisPipeline.stageProgressFlow] 推送进度事件，
 * UI 层收集后更新进度 UI（阶段名称、百分比、错误信息等）。
 *
 * @property stageId 阶段唯一标识（如 "builtin:quality"）
 * @property displayName 阶段显示名称（如 "质量评分"）
 * @property stageIndex 在当前排序中的索引（从 0 开始）
 * @property totalStages 阶段总数
 * @property stageStatus 当前阶段状态（RUNNING / COMPLETED / ERROR）
 * @property processedFiles 已处理的文件数
 * @property totalFiles 该阶段需要处理的总文件数
 * @property error 若 status 为 ERROR，包含异常信息
 */
data class StageProgress(
    val stageId: String,
    val displayName: String,
    val stageIndex: Int,
    val totalStages: Int,
    val stageStatus: StageStatus,
    val processedFiles: Int,
    val totalFiles: Int,
    val error: String? = null,
) {
    /** 阶段执行状态 */
    enum class StageStatus {
        RUNNING,
        COMPLETED,
        ERROR,
    }
}