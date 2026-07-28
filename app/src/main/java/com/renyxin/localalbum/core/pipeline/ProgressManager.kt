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
        ownedStageFilesCompleted[stageId] = 0
        ownedStageFilesTotal[stageId] = totalFilesInStage
        stageDisplayNames[stageId] = displayName

        // 初始化该阶段的 per-file 追踪
        perFileStatus[stageId] = mutableMapOf()

        // P2-BUGFIX: 每阶段重置 ETA 计时基准，避免跨阶段累计导致估算失真
        pipelineStartTimeMs = System.currentTimeMillis()
        lastProcessedFiles = 0
        lastProgressTimeMs = pipelineStartTimeMs

        _progress.value = _progress.value.copy(
            currentStageName = displayName,
            processedFiles = 0,
            percent = (_progress.value.completedStages.toFloat() / ownedStageCount.coerceAtLeast(1)),
        )
    }

    /**
     * 单文件进度回执。
     *
     * P2-BUGFIX: 文件计数不跨阶段累加，每阶段独立计数。全局进度基于阶段完成比例。
     */
    fun onFileProgress(processed: Int, total: Int) = synchronized(lock) {
        ownedStageFilesCompleted[currentStageId] = processed

        val fullyCompletedStages = countFullyCompletedStages()
        val currentTotal = ownedStageFilesTotal[currentStageId] ?: total

        val stageFraction = if (ownedStageCount > 0) {
            (fullyCompletedStages + (processed.toFloat() / currentTotal.coerceAtLeast(1))) / ownedStageCount
        } else 0f

        val percent = stageFraction.coerceIn(0f, 1f)
        val etaMs = estimateEta(processed, currentTotal)

        _progress.value = _progress.value.copy(
            completedStages = fullyCompletedStages,
            processedFiles = processed,  // 仅当前阶段计数
            totalFiles = currentTotal,   // 仅当前阶段总数
            percent = percent,
            etaMs = etaMs,
        )
    }

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
        errorMessage: String? = null,
    ) = synchronized(lock) {
        val stageMap = perFileStatus.getOrPut(stageId) { mutableMapOf() }
        stageMap[filePath] = status

        // P2-BUGFIX: 仅更新当前阶段的完成计数，不跨阶段累加
        if (status == FileProcessingStatus.COMPLETED || status == FileProcessingStatus.FAILED) {
            val currentCount = stageMap.count {
                it.value == FileProcessingStatus.COMPLETED || it.value == FileProcessingStatus.FAILED
            }
            ownedStageFilesCompleted[stageId] = currentCount
        }

        // 使用当前阶段计数调用 updateProgress
        val currentProcessed = ownedStageFilesCompleted[stageId] ?: 0
        updateProgress(processedFiles = currentProcessed)
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

        updateProgress(
            processedFiles = totalInStage,  // 仅当前阶段计数
            completedStages = ownedCompletedStages,
        )
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

        updateProgress(
            processedFiles = totalInStage,  // 仅当前阶段计数
            completedStages = ownedCompletedStages,
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
        val completedCount = fileMap.count {
            it.value == FileProcessingStatus.COMPLETED
        }
        val totalCount = ownedStageFilesTotal[stageId] ?: fileMap.size.coerceAtLeast(1)
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

    /**
     * P2-BUGFIX: 统一进度计算。使用 only the current stage fraction approach。
     * 不跨阶段累加文件数，避免多阶段导致 processedFiles 超过 totalFiles。
     */
    private fun updateProgress(
        processedFiles: Int,
        completedStages: Int = _progress.value.completedStages,
        hasError: Boolean = _progress.value.hasError,
        extra: Map<String, String> = _progress.value.extra,
    ) = synchronized(lock) {
        val stageFraction = if (ownedStageCount > 0) {
            completedStages.toFloat() / ownedStageCount
        } else {
            0f
        }
        // 当前阶段的文件进度（单阶段占比 / 总阶段数）
        val fileProgress = if (ownedStageCount > 0) {
            val currentStageTotal = ownedStageFilesTotal[currentStageId] ?: ownedTotalFiles
            if (currentStageTotal > 0) {
                (processedFiles.toFloat() / currentStageTotal) / ownedStageCount
            } else 0f
        } else 0f

        // 全局进度 = 已完成阶段占比 + 当前阶段文件进度
        val mixedPercent = (stageFraction + fileProgress).coerceIn(0f, 1f)

        val currentStageTotal = ownedStageFilesTotal[currentStageId] ?: ownedTotalFiles
        val etaMs = estimateEta(processedFiles, currentStageTotal)
        val perStage = buildPerStageFileProgress()

        _progress.value = _progress.value.copy(
            completedStages = completedStages,
            processedFiles = processedFiles,
            totalFiles = currentStageTotal,
            percent = mixedPercent,
            etaMs = etaMs,
            hasError = hasError,
            extra = extra,
            perStageFiles = perStage,
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
