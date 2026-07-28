package com.renyxin.localalbum.core.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局进度管理器。
 *
 * 维护多任务（多阶段管道）进度状态，暴露 [StateFlow] 供 UI 层观察。
 * 内置 ETA 估算：基于滑动窗口平均处理速率预估剩余时间。
 *
 * 使用方式：
 * - 管道编排器在每阶段开始时调用 [createTask]
 * - 每处理一批图片后调用 [reportProgress]
 * - 阶段完成/失败时调用 [completeTask] / [failTask]
 * - UI 层通过 [progressState] 观察所有活跃任务
 */
class ProgressManager {

    // ---- 内部状态 ----

    private val _progressState = MutableStateFlow<Map<String, TaskProgress>>(emptyMap())
    val progressState: StateFlow<Map<String, TaskProgress>> = _progressState.asStateFlow()

    /** ETA 滑动窗口大小 */
    private val etaWindowSize = 20

    /** 记录每个任务的最近处理速率样本 (index, timestampMs) */
    private val etaSamples = mutableMapOf<String, ArrayDeque<Pair<Int, Long>>>()

    // ---- 公共 API ----

    /**
     * 创建新的进度任务。
     *
     * @param taskId 任务唯一标识（通常为管道阶段 ID）
     * @param pluginId 执行的插件 ID
     * @param pluginName 插件显示名称
     * @param totalCount 待处理总数
     * @param weight 全局进度权重（用于加权聚合）
     */
    fun createTask(
        taskId: String,
        pluginId: String,
        pluginName: String,
        totalCount: Int,
        weight: Float = 1.0f,
    ) {
        val progress = TaskProgress(
            taskId = taskId,
            pluginId = pluginId,
            pluginName = pluginName,
            currentIndex = 0,
            totalCount = totalCount,
            percentage = 0f,
            globalPercentage = 0f,
            status = ProgressReporter.TaskStatus.STARTING,
            etaMs = null,
            startedAtMs = System.currentTimeMillis(),
            weight = weight,
            errorMessage = null,
        )
        _progressState.value = _progressState.value.toMutableMap().apply {
            put(taskId, progress)
        }
        etaSamples[taskId] = ArrayDeque(etaWindowSize)
    }

    /**
     * 上报进度。
     *
     * @param taskId 任务 ID
     * @param currentIndex 当前已处理数量
     * @param message 可选状态描述
     */
    fun reportProgress(taskId: String, currentIndex: Int, message: String? = null) {
        val current = _progressState.value[taskId] ?: return
        val percentage = if (current.totalCount > 0) {
            (currentIndex.toFloat() / current.totalCount * 100f).coerceIn(0f, 100f)
        } else 0f

        val now = System.currentTimeMillis()
        val samples = etaSamples[taskId] ?: return
        samples.addLast(Pair(currentIndex, now))
        // 保持窗口大小
        while (samples.size > etaWindowSize) {
            samples.removeFirst()
        }

        val etaMs = calculateEta(samples, current.totalCount)
        val globalPct = recalculateGlobalPercentage(taskId, percentage)

        val updated = current.copy(
            currentIndex = currentIndex,
            percentage = percentage,
            globalPercentage = globalPct,
            status = if (current.status == ProgressReporter.TaskStatus.STARTING) {
                ProgressReporter.TaskStatus.RUNNING
            } else current.status,
            etaMs = etaMs,
            message = message,
        )
        _progressState.value = _progressState.value.toMutableMap().apply {
            put(taskId, updated)
        }
    }

    /**
     * 标记任务完成。
     */
    fun completeTask(taskId: String) {
        val current = _progressState.value[taskId] ?: return
        val updated = current.copy(
            currentIndex = current.totalCount,
            percentage = 100f,
            globalPercentage = 100f,
            status = ProgressReporter.TaskStatus.COMPLETED,
            etaMs = 0L,
        )
        _progressState.value = _progressState.value.toMutableMap().apply {
            put(taskId, updated)
        }
    }

    /**
     * 标记任务失败。
     */
    fun failTask(taskId: String, errorMessage: String) {
        val current = _progressState.value[taskId] ?: return
        val updated = current.copy(
            status = ProgressReporter.TaskStatus.FAILED,
            errorMessage = errorMessage,
        )
        _progressState.value = _progressState.value.toMutableMap().apply {
            put(taskId, updated)
        }
    }

    /**
     * 移除已完成/失败的任务条目（清理 UI）。
     */
    fun removeTask(taskId: String) {
        _progressState.value = _progressState.value.toMutableMap().apply {
            remove(taskId)
        }
        etaSamples.remove(taskId)
    }

    /**
     * 清除所有任务。
     */
    fun clearAll() {
        _progressState.value = emptyMap()
        etaSamples.clear()
    }

    /**
     * 获取某个任务的 [ProgressReporter] 实现，供插件内部使用。
     */
    fun getReporter(taskId: String): ProgressReporter {
        return object : ProgressReporter {
            override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {
                // 插件内部上报时，可能更新 totalCount
                val current = _progressState.value[taskId] ?: return
                if (current.totalCount != totalCount && totalCount > 0) {
                    _progressState.value = _progressState.value.toMutableMap().apply {
                        put(taskId, current.copy(totalCount = totalCount))
                    }
                }
                this@ProgressManager.reportProgress(taskId, currentIndex, message)
            }

            override fun reportStatus(status: ProgressReporter.TaskStatus) {
                val current = _progressState.value[taskId] ?: return
                _progressState.value = _progressState.value.toMutableMap().apply {
                    put(taskId, current.copy(status = status))
                }
            }
        }
    }

    /**
     * 计算所有活跃任务的全局加权进度。
     * 若没有活跃任务则返回 0f。
     */
    fun getOverallProgress(): Float {
        val tasks = _progressState.value.values.filter {
            it.status == ProgressReporter.TaskStatus.RUNNING ||
                it.status == ProgressReporter.TaskStatus.STARTING
        }
        if (tasks.isEmpty()) return 100f
        val totalWeight = tasks.sumOf { it.weight.toDouble() }.toFloat()
        if (totalWeight == 0f) return 0f
        return tasks.sumOf { (it.weight * it.percentage).toDouble() }.toFloat() / totalWeight
    }

    // ---- 内部方法 ----

    /**
     * 计算 ETA：基于滑动窗口平均速率。
     *
     * @param samples 最近 N 个 (index, timestampMs) 样本
     * @param totalCount 总数
     * @return 预估剩余毫秒数，样本不足时返回 null
     */
    private fun calculateEta(samples: ArrayDeque<Pair<Int, Long>>, totalCount: Int): Long? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val itemsProcessed = last.first - first.first
        val timeElapsed = last.second - first.second
        if (itemsProcessed <= 0 || timeElapsed <= 0) return null

        val msPerItem = timeElapsed.toFloat() / itemsProcessed
        val remaining = totalCount - last.first
        if (remaining <= 0) return 0L
        return (msPerItem * remaining).toLong()
    }

    /**
     * 重新计算单个任务更新后的全局进度。
     */
    private fun recalculateGlobalPercentage(taskId: String, newPercentage: Float): Float {
        val allTasks = _progressState.value.toMutableMap().apply {
            val current = this[taskId] ?: return newPercentage
            put(taskId, current.copy(percentage = newPercentage))
        }
        val tasks = allTasks.values.filter {
            it.status == ProgressReporter.TaskStatus.RUNNING ||
                it.status == ProgressReporter.TaskStatus.STARTING ||
                it.status == ProgressReporter.TaskStatus.COMPLETED
        }
        if (tasks.isEmpty()) return 100f
        val totalWeight = tasks.sumOf { it.weight.toDouble() }.toFloat()
        if (totalWeight == 0f) return 0f
        return tasks.sumOf { (it.weight * it.percentage).toDouble() }.toFloat() / totalWeight
    }
}

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