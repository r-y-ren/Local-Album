package com.renyxin.localalbum.core.pipeline

/**
 * 管道全局进度数据模型（Phase 3.4）。
 *
 * 供 [ProgressManager] 跨阶段汇总推送，UI 层通过 collect [ProgressManager.progress]
 * 获取实时进度以渲染全局进度条、通知等。
 *
 * @property currentStageName 当前正在执行的阶段显示名称，无运行时为空字符串
 * @property totalStages 管道包含的阶段总数
 * @property completedStages 已完成的阶段数
 * @property totalFiles 本次扫描的总文件数
 * @property processedFiles 跨阶段累计已处理文件数
 * @property percent 全局完成百分比 (0f ~ 1f)
 * @property isCompleted 管道是否已全部完成
 * @property hasError 管道中是否有阶段出错
 * @property etaMs 预估剩余毫秒数，-1 表示暂无法估算
 * @property extra 扩展键值对（如错误信息、出错阶段 ID）
 * @property perStageFiles 按阶段分组的每文件进度快照，key 为 stageId
 */
data class AnalysisProgress(
    val currentStageName: String = "",
    val totalStages: Int = 0,
    val completedStages: Int = 0,
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val percent: Float = 0f,
    val isCompleted: Boolean = false,
    val hasError: Boolean = false,
    val etaMs: Long = -1L,
    val extra: Map<String, String> = emptyMap(),
    val perStageFiles: Map<String, StageFileProgress> = emptyMap(),
)