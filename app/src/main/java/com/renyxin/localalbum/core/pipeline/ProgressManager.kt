package com.renyxin.localalbum.core.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨阶段进度管理器（Phase 3.4）。
 *
 * 将管道中多阶段的进度汇总为全局视图，供 UI 层（如顶部全局进度条、通知栏）使用。
 * 与 [AnalysisProgress] 数据模型配合，提供：
 * - 当前阶段名称、剩余阶段数
 * - 总体已完成文件数 / 总文件数
 * - 百分比进度、ETA
 * - 每文件级别的完成状态追踪（通过 [onFileStatusChange]）
 *
 * ## 使用模式
 *
 * 1. 管道调用 [beginPipeline] 通知开始 N 个阶段
 * 2. 每阶段开始调用 [onStageStart]，每文件完成调用 [onFileProgress]
 * 3. 每个文件状态变更时调用 [onFileStatusChange] 追踪 per-file 进度
 * 4. 管道完成后调用 [onPipelineComplete]
 *
 * ## 线程安全
 *
 * [ParallelFileProcessor.mapParallel] 会在多线程上并发调用 [onFileProgress] /
 * [onFileStatusChange]，本类所有状态访问方法均通过 [lock] 串行化，避免
 * `ConcurrentModificationException`（遍历 `perFileStatus` 时被并发修改）。
 *
 * @param stageWeights 各阶段的权重占比（stageId → weight），用于计算总体百分比；
 *                     若不提供则各阶段均分权重
 */
class ProgressManager(
    private val stageWeights: Map<String, Float> = emptyMap(),
) {
    // ---- State ----

    private val _progress = MutableStateFlow(AnalysisProgress())
    val progress: StateFlow<AnalysisProgress> = _progress.asStateFlow()

    /** 管道是否已完成 */
    val isCompleted: Boolean get() = _progress.value.isCompleted

    /** 管道是否出错 */
    val hasError: Boolean get() = _progress.value.hasError

    // ---- Internal State ----

    /**
     * 保护所有可变状态的锁。所有公开/私有状态访问方法均在此锁内执行，
     * 因为 [ParallelFileProcessor.mapParallel] 会从多线程并发回调进度。
     * synchronized 是可重入的，方法间嵌套调用不会死锁。
     */
    private val lock = Any()

    private var ownedTotalFiles: Int = 0
    private var ownedStageCount: Int = 0
    private var ownedCompletedStages: Int = 0
    private var ownedStageFilesCompleted: MutableMap<String, Int> = mutableMapOf()
    private var ownedStageFilesTotal: MutableMap<String, Int> = mutableMapOf()
    /** 当前正在执行的阶段 ID，用于 onFileProgress 上下文 */
    private var currentStageId: String = ""

    /**
     * 每文件状态追踪：stageId → (filePath → FileProcessingStatus)
     */
    private val perFileStatus: MutableMap<String, MutableMap<String, FileProcessingStatus>> = mutableMapOf()

    /**
     * 每阶段的 displayName 缓存（stageId → displayName），用于构建 StageFileProgress
     */
    private val stageDisplayNames: MutableMap<String, String> = mutableMapOf()

    /** ETA 估算所需的时间戳追踪 */
    private var pipelineStartTimeMs: Long = 0L
    private var lastProgressTimeMs: Long = 0L
    private var lastProcessedFiles: Int = 0

    // ---- Public API ----

    /**
     * 管道开始：重置所有累计值。
     *
     * @param totalFiles 本次扫描的总文件数
     * @param stageIds 本次管道的所有阶段 ID（按执行顺序）
     */
    fun beginPipeline(totalFiles: Int, stageIds: List<String>) = synchronized(lock) {
        ownedTotalFiles = totalFiles
        ownedStageCount = stageIds.size
        ownedCompletedStages = 0
        ownedStageFilesCompleted = mutableMapOf()
        ownedStageFilesTotal = mutableMapOf()
        perFileStatus.clear()
        stageDisplayNames.clear()

        // 初始化 ETA 追踪
        val now = System.currentTimeMillis()
        pipelineStartTimeMs = now
        lastProgressTimeMs = now
        lastProcessedFiles = 0

        _progress.value = AnalysisProgress(
            currentStageName = "",
            totalStages = ownedStageCount,
            completedStages = 0,
            totalFiles = ownedTotalFiles,
            processedFiles = 0,
            percent = 0f,
            isCompleted = false,
            hasError = false,
            etaMs = -1L,
        )
    }

    /**
     * 阶段开始回执。
     */
    fun onStageStart(stageId: String, displayName: String, totalFilesInStage: Int) = synchronized(lock) {
        currentStageId = stageId
        ownedStageFilesCompleted.putIfAbsent(stageId, 0)
        ownedStageFilesTotal[stageId] = totalFilesInStage
        stageDisplayNames[stageId] = displayName

        // 初始化该阶段的 per-file 追踪
        perFileStatus.putIfAbsent(stageId, mutableMapOf())

        // P2-BUGFIX: 每阶段重置 ETA 计时基准，避免跨阶段累计导致估算失真
        pipelineStartTimeMs = System.currentTimeMillis()
        lastProcessedFiles = 0
        lastProgressTimeMs = pipelineStartTimeMs

        // 同一 DAG 层的多个阶段可以并行启动。不能把全局 UI 的已处理数重置为 0，
        // 否则另一个仍在执行的阶段会使数字在两个局部计数之间来回跳动。
        publishProgress(currentStageName = displayName)
    }

    /**
     * 单文件进度回执。
     *
     * 必须携带阶段 ID：同一 DAG 层可并发执行多个阶段，不能再依赖可被其他协程覆盖的
     * [currentStageId]。对 UI 显示的文件数使用所有阶段的加权完成度折算到本次扫描总文件数，
     * 因而跨阶段与并发阶段均保持单调递增。
     */
    fun onFileProgress(stageId: String, processed: Int, total: Int) = synchronized(lock) {
        val stageTotal = ownedStageFilesTotal[stageId] ?: total
        val stableProcessed = maxOf(
            ownedStageFilesCompleted[stageId] ?: 0,
            processed.coerceIn(0, stageTotal.coerceAtLeast(0)),
        )
        ownedStageFilesCompleted[stageId] = stableProcessed
        publishProgress()
    }

    /** 兼容仅串行阶段的既有调用；新管道调用应使用带 [stageId] 的重载。 */
    fun onFileProgress(processed: Int, total: Int) = onFileProgress(currentStageId, processed, total)

    /**
     * 单文件状态变更回执（Phase 6.1 新增）。
     *
     * 管道路在每文件状态变更时调用此方法，追踪 per-file 的完成状态。
     * 在 [updateProgress] 中自动构建 [StageFileProgress] 并合并到 [AnalysisProgress.perStageFiles]。
     *
     * @param stageId 阶段 ID
     * @param filePath 文件路径
     * @param status 新的处理状态
     * @param errorMessage 若 status 为 FAILED 时的错误信息（可选）
     */
    fun onFileStatusChange(
        stageId: String,
        filePath: String,
        status: FileProcessingStatus,
        @Suppress("UNUSED_PARAMETER") errorMessage: String? = null,
    ) = synchronized(lock) {
        val stageMap = perFileStatus.getOrPut(stageId) { mutableMapOf() }
        stageMap[filePath] = status

        // P2-BUGFIX: 仅更新当前阶段的完成计数，不跨阶段累加。
        // 状态回调同样可能晚于进度回调到达，不能用较小的状态统计覆盖已上报进度。
        if (status == FileProcessingStatus.COMPLETED || status == FileProcessingStatus.FAILED) {
            val currentCount = stageMap.count {
                it.value == FileProcessingStatus.COMPLETED || it.value == FileProcessingStatus.FAILED
            }
            ownedStageFilesCompleted[stageId] = maxOf(
                ownedStageFilesCompleted[stageId] ?: 0,
                currentCount,
            )
        }

        publishProgress()
    }

    /**
     * 阶段完成回执。
     *
     * @param failed 失败文件数（可计入 processed，也可以直接用于额外统计）
     */
    fun onStageComplete(stageId: String, successCount: Int, failedCount: Int) = synchronized(lock) {
        val totalInStage = ownedStageFilesTotal[stageId] ?: (successCount + failedCount)
        ownedStageFilesCompleted[stageId] = totalInStage
        ownedCompletedStages = ownedStageFilesCompleted.count {
            val stgId = it.key
            val done = it.value
            val expected = ownedStageFilesTotal[stgId] ?: ownedTotalFiles
            done >= expected
        }

        publishProgress()
    }

    /**
     * 阶段错误回执。
     */
    fun onStageError(stageId: String, error: String) = synchronized(lock) {
        val totalInStage = ownedStageFilesTotal[stageId] ?: ownedTotalFiles
        ownedStageFilesCompleted[stageId] = totalInStage
        ownedCompletedStages = ownedStageFilesCompleted.count {
            val stgId = it.key
            val done = it.value
            val expected = ownedStageFilesTotal[stgId] ?: ownedTotalFiles
            done >= expected
        }

        publishProgress(
            hasError = true,
            extra = mapOf("error" to error, "failedStage" to stageId),
        )
    }

    /**
     * 管道全部完成。
     */
    fun onPipelineComplete() = synchronized(lock) {
        _progress.value = AnalysisProgress(
            currentStageName = "",
            totalStages = ownedStageCount,
            completedStages = ownedStageCount,
            totalFiles = ownedTotalFiles,
            processedFiles = ownedTotalFiles,
            percent = 1f,
            isCompleted = true,
            hasError = _progress.value.hasError,
            etaMs = 0L,
            perStageFiles = buildPerStageFileProgress(),
        )
    }

    /**
     * 紧急重置 Idle 状态（如管道提前取消）。
     */
    fun reset() = synchronized(lock) {
        perFileStatus.clear()
        stageDisplayNames.clear()
        _progress.value = AnalysisProgress()
    }

    /**
     * 获取指定阶段的 per-file 进度快照。
     *
     * @param stageId 阶段 ID
     * @return 该阶段的文件进度，若阶段尚未开始或已结束且无数据则返回 [StageFileProgress.EMPTY]
     */
    fun getStageFileProgress(stageId: String): StageFileProgress = synchronized(lock) {
        return buildStageFileProgress(stageId)
    }

    // ---- Internal Helpers ----

    /**
     * 构建所有阶段的 [StageFileProgress] 映射。
     */
    private fun buildPerStageFileProgress(): Map<String, StageFileProgress> = synchronized(lock) {
        return perFileStatus.keys.associateWith { stageId ->
            buildStageFileProgress(stageId)
        }
    }

    /**
     * 构建单个阶段的 [StageFileProgress]。
     */
    private fun buildStageFileProgress(stageId: String): StageFileProgress = synchronized(lock) {
        val fileMap = perFileStatus[stageId] ?: return StageFileProgress.EMPTY
        val totalCount = ownedStageFilesTotal[stageId] ?: fileMap.size.coerceAtLeast(1)
        // 数字进度必须与 onFileProgress 的权威计数一致。部分阶段只上报 (processed,total)
        // 或在断点续跑时没有逐文件状态；旧逻辑仅统计 COMPLETED 状态，导致当前文件变化
        // 但阶段数字长期停在 0。FAILED 同样属于“已处理”，不能令进度永远达不到总数。
        val terminalStatusCount = fileMap.count {
            it.value == FileProcessingStatus.COMPLETED || it.value == FileProcessingStatus.FAILED
        }
        val completedCount = maxOf(
            ownedStageFilesCompleted[stageId] ?: 0,
            terminalStatusCount,
        ).coerceIn(0, totalCount)
        val displayName = stageDisplayNames[stageId] ?: stageId

        val fileProgressList = fileMap.map { (path, status) ->
            FileProgress(filePath = path, status = status)
        }

        return StageFileProgress(
            stageId = stageId,
            displayName = displayName,
            completedCount = completedCount,
            totalCount = totalCount,
            files = fileProgressList,
        )
    }

    /** 发布跨阶段聚合进度，避免并行阶段争夺“当前阶段”而覆盖 UI。 */
    private fun publishProgress(
        currentStageName: String = _progress.value.currentStageName,
        hasError: Boolean = _progress.value.hasError,
        extra: Map<String, String> = _progress.value.extra,
    ) = synchronized(lock) {
        val completedStages = ownedStageFilesCompleted.count { (stageId, completed) ->
            completed >= (ownedStageFilesTotal[stageId] ?: ownedTotalFiles)
        }
        val aggregateFraction = if (ownedStageCount > 0) {
            ownedStageFilesCompleted.entries.sumOf { (stageId, completed) ->
                val total = ownedStageFilesTotal[stageId] ?: ownedTotalFiles
                if (total > 0) completed.toDouble() / total else 0.0
            }.toFloat() / ownedStageCount
        } else 0f
        val percent = aggregateFraction.coerceIn(0f, 1f)
        val displayProcessed = (percent * ownedTotalFiles).toInt().coerceIn(0, ownedTotalFiles)
        val currentStageTotal = ownedStageFilesTotal[currentStageId] ?: ownedTotalFiles
        val currentStageProcessed = ownedStageFilesCompleted[currentStageId] ?: 0

        _progress.value = _progress.value.copy(
            currentStageName = currentStageName,
            completedStages = completedStages,
            processedFiles = displayProcessed,
            totalFiles = ownedTotalFiles,
            percent = percent,
            etaMs = estimateEta(currentStageProcessed, currentStageTotal),
            hasError = hasError,
            extra = extra,
            perStageFiles = buildPerStageFileProgress(),
        )
    }

    /** 计算完全完成的阶段数（已完成计数 >= 总数且非当前阶段） */
    private fun countFullyCompletedStages(): Int = synchronized(lock) {
        return ownedStageFilesCompleted.count {
            val stgId = it.key
            val done = it.value
            val expected = ownedStageFilesTotal[stgId] ?: ownedTotalFiles
            done >= expected && stgId != currentStageId
        }
    }

    /**
     * 估算剩余时间（ETA）。
     *
     * P2-BUGFIX: 基于当前阶段的文件进度独立计算 ETA，每阶段重置计时基准。
     *
     * @param processedFiles 当前阶段已处理文件数
     * @param stageTotal 当前阶段总文件数
     * @return 预估剩余毫秒数，数据不足时返回 -1L
     */
    private fun estimateEta(processedFiles: Int, stageTotal: Int): Long = synchronized(lock) {
        if (processedFiles <= 0 || stageTotal <= 0) return -1L
        if (processedFiles >= stageTotal) return 0L

        val now = System.currentTimeMillis()
        val elapsedMs = now - pipelineStartTimeMs
        if (elapsedMs < 1000) return -1L

        val rate = processedFiles.toDouble() / elapsedMs // 文件/毫秒
        if (rate <= 0) return -1L

        val remaining = stageTotal - processedFiles
        return (remaining / rate).toLong()
    }
}
