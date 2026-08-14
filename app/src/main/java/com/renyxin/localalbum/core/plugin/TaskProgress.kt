package com.renyxin.localalbum.core.plugin

/**
 * 任务进度数据类。
 *
 * @param taskId 任务唯一标识
 * @param pluginId 执行的插件 ID
 * @param pluginName 插件显示名称
 * @param currentIndex 当前处理索引
 * @param totalCount 总处理数量
 * @param percentage 本任务百分比 (0~100)
 * @param globalPercentage 全局加权进度 (0~100)
 * @param status 任务状态
 * @param etaMs 预估剩余毫秒数，无法估算时为 null
 * @param startedAtMs 任务开始时间戳
 * @param weight 全局进度权重（用于多任务加权聚合）
 * @param message 可选状态描述
 * @param errorMessage 错误信息（失败时）
 */
data class TaskProgress(
    val taskId: String,
    val pluginId: String,
    val pluginName: String,
    val currentIndex: Int,
    val totalCount: Int,
    val percentage: Float,
    val globalPercentage: Float,
    val status: ProgressReporter.TaskStatus,
    val etaMs: Long?,
    val startedAtMs: Long,
    val weight: Float = 1.0f,
    val message: String? = null,
    val errorMessage: String? = null,
)
