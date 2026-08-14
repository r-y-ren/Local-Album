package com.renyxin.localalbum.core.pipeline

import android.os.Debug
import android.util.Log
import com.renyxin.localalbum.core.concurrent.InferenceMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import com.renyxin.localalbum.data.db.entity.AnalysisStateEntity

/** Exact, order-independent identity used to retire one automatic policy's durable task scopes. */
data class RetiredPolicyScopeSelector(
    val pipelinePrefix: String,
    val planName: String,
    val policyIdentity: String,
) {
    init {
        require(pipelinePrefix.isNotBlank()) { "pipelinePrefix must not be blank" }
        require(planName.isNotBlank()) { "planName must not be blank" }
        require(policyIdentity.isNotBlank()) { "policyIdentity must not be blank" }
    }
}

/**
 * 分析管道核心编排器（Phase 2 重构）。
 *
 * 统一调度核心分析阶段（人脸、场景、语义、质量、OCR、相似度），
 * 按 DAG 拓扑排序后顺序执行。扩展 AI 插件（换脸、风格迁移等）不再参与批处理管道，
 * 改为交互式调用。
 *
 * ## 执行模式
 *
 * - **全量扫描**：[runFullScan] 对所有文件包执行全部已注册阶段
 * - **增量扫描**：[runIncremental] 对新增/修改的文件子集重新执行各阶段
 *
 * ## 进度暴露
 *
 * - [stageProgressFlow] — SharedFlow，每阶段推出 [StageProgress]
 * - [pipelineStatusFlow] — StateFlow，管道整体状态
 * - [pipelineResults] — 管道完成后所有阶段的汇总结果
 *
 * @param stages 所有已注册的核心分析阶段
 * @param dagSorter DAG 拓扑排序器
 */
class PluginAnalysisPipeline(
    private val stages: List<AnalysisStage>,
    private val dagSorter: StageDagSorter = StageDagSorter,
    val progressManager: ProgressManager = ProgressManager(),
    private val analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
    private val pipelineScopeOverride: String? = null,
    private val compatiblePipelineScopes: List<String> = emptyList(),
    val retiredAutomaticScopeSelectors: List<RetiredPolicyScopeSelector> = emptyList(),
    private val releaseStageResources: suspend (String) -> Unit = {},
) {
    /** Current task identity; policy, plan, Provider and stage versions are included for factory plans. */
    val pipelineScope: String by lazy {
        pipelineScopeOverride ?: buildPipelineScope(
            stages.map { "${it.stageId}@${it.modelVersion}" },
        )
    }

    /** Ordered aggregate scopes retained for tasks created before per-stage task identities. */
    val claimablePipelineScopes: List<String> by lazy {
        (listOf(pipelineScope) + compatiblePipelineScopes).distinct()
    }

    /** Worker uses this to reject partial DAG results as task completion. */
    val requiredStageIds: Set<String> by lazy { stages.mapTo(linkedSetOf()) { it.stageId } }

    /**
     * Durable identity for each admitted Stage. The scope includes the complete policy/provider
     * pipeline identity, so a policy or Provider change cannot make a stale Stage task claimable.
     */
    val stageTaskScopes: Map<String, String> by lazy {
        sortedStages.associateTo(linkedMapOf()) { stage ->
            stage.stageId to buildStageTaskScope(
                pipelineScope = pipelineScope,
                stageId = stage.stageId,
                modelVersion = stage.modelVersion,
            )
        }
    }

    /**
     * Legacy aggregate identities run first during an in-place upgrade; new Stage identities then
     * follow topological order. Stage caches make overlapping upgrade work idempotent.
     */
    val claimableTaskScopes: List<String> by lazy {
        (claimablePipelineScopes + stageTaskScopes.values).distinct()
    }

    /** Current outbox rows expand per Stage; legacy compatible rows preserve aggregate semantics. */
    fun taskScopesForOutbox(sourcePipelineScope: String): List<String> = when {
        sourcePipelineScope == pipelineScope -> stageTaskScopes.values.toList()
        sourcePipelineScope in compatiblePipelineScopes -> listOf(sourcePipelineScope)
        else -> emptyList()
    }

    /** Maps one durable task identity to exactly the Stage set that is allowed to satisfy it. */
    fun requiredStageIdsForTaskScope(taskScope: String): Set<String> {
        val stageId = stageTaskScopes.entries.firstOrNull { it.value == taskScope }?.key
        return when {
            stageId != null -> setOf(stageId)
            taskScope in claimablePipelineScopes -> requiredStageIds
            else -> emptySet()
        }
    }

    companion object {
        private const val TAG = "PluginPipeline"
        private const val PIPELINE_SCOPE_VERSION = 1
        private const val STAGE_TASK_SCOPE_VERSION = 1

        /**
         * 分析断点表按路径批操作的分批大小（SQLite IN 子句变量上限约束；
         * 与 HybridIndexer.CHANGE_BATCH_SIZE 同值同语义，二者各属其文件）。
         */
        private const val ANALYSIS_STATE_BATCH_SIZE = 500

        internal fun buildPipelineScope(parts: List<String>): String =
            "pipeline:v$PIPELINE_SCOPE_VERSION|" + parts.sorted().joinToString("|")

        internal fun buildStageTaskScope(
            pipelineScope: String,
            stageId: String,
            modelVersion: Int,
        ): String = "$pipelineScope|stage-task:v$STAGE_TASK_SCOPE_VERSION=$stageId@$modelVersion"

        fun create(
            plan: AnalysisStagePlan,
            analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
            retiredAutomaticScopeSelectors: List<RetiredPolicyScopeSelector> = emptyList(),
            releaseStageResources: suspend (String) -> Unit = {},
        ): PluginAnalysisPipeline {
            val compatibilityScopes = if (plan.claimLegacyFullAnalysisTasks) {
                listOf(
                    buildPipelineScope(plan.legacyScopeParts),
                    com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE,
                )
            } else {
                emptyList()
            }
            return PluginAnalysisPipeline(
                stages = plan.stages,
                analysisStateDao = analysisStateDao,
                pipelineScopeOverride = buildPipelineScope(plan.scopeParts),
                compatiblePipelineScopes = compatibilityScopes,
                retiredAutomaticScopeSelectors = retiredAutomaticScopeSelectors.distinct(),
                releaseStageResources = releaseStageResources,
            )
        }

        /**
         * Selects aggregate and per-Stage tasks from one retired policy without assuming where
         * sorted scope parts occur. DAO matching treats plan and policy as delimiter-bounded tokens,
         * preventing a Lite retirement from touching Full, manual, or another policy version.
         */
        fun retiredPolicyScopeSelector(
            planType: AnalysisPlanType,
            policyId: String,
            policyVersion: Int,
        ): RetiredPolicyScopeSelector = RetiredPolicyScopeSelector(
            pipelinePrefix = "pipeline:v$PIPELINE_SCOPE_VERSION|",
            planName = planType.persistedName,
            policyIdentity = "$policyId@$policyVersion",
        )
    }

    /** 管道整体状态枚举 */
    enum class Status {
        IDLE,
        RUNNING,
        COMPLETED,
        ERROR,
    }

    // ---- Progress Exposure ----

    private val _stageProgress = MutableSharedFlow<StageProgress>(replay = 64, extraBufferCapacity = 64)
    val stageProgressFlow: SharedFlow<StageProgress> = _stageProgress.asSharedFlow()

    private val _pipelineStatus = MutableStateFlow(Status.IDLE)
    val pipelineStatusFlow: StateFlow<Status> = _pipelineStatus.asStateFlow()

    /** 管道完成后所有阶段的汇总结果 */
    private val _pipelineResults = MutableStateFlow<Map<String, StageResult>>(emptyMap())
    val pipelineResults: StateFlow<Map<String, StageResult>> = _pipelineResults.asStateFlow()

    // ---- Topological Sort Cache ----

    /**
     * 根据阶段间依赖关系进行拓扑排序后的阶段列表（缓存，stages 不变则不变）。
     */
    private val sortedStages: List<AnalysisStage> by lazy {
        dagSorter.sort(stages)
    }

    /**
     * 执行单个阶段并桥接进度回调到 [_stageProgress] / [progressManager]。
     */
    private suspend fun runStage(
        stage: AnalysisStage,
        stageIdx: Int,
        totalStages: Int,
        targetPaths: List<String>,
        results: MutableMap<String, StageResult>,
    ) {
        val stageStart = System.currentTimeMillis()
        val startPssKb = currentPssKb()
        Log.i("CrashDebug", "=== 阶段开始 [${stage.stageId}] ${stage.displayName} (依赖=${stage.dependencies}) ===")
        logStageStart(stage)

        _stageProgress.emit(
            StageProgress(
                stageId = stage.stageId,
                displayName = stage.displayName,
                stageIndex = stageIdx,
                totalStages = totalStages,
                stageStatus = StageProgress.StageStatus.RUNNING,
                processedFiles = 0,
                totalFiles = targetPaths.size,
            ),
        )
        progressManager.onStageStart(stage.stageId, stage.displayName, targetPaths.size)

        try {
            // 断点续跑只查询当前调用批次，禁止为一个 250 条批次加载该阶段数万条历史路径。
            // chunked 同时保护未来调用方传入较大批次时不超过 SQLite IN 参数上限。
            val donePaths: Set<String> = if (stage.isCacheable && analysisStateDao != null) {
                targetPaths.chunked(ANALYSIS_STATE_BATCH_SIZE).flatMap { batch ->
                    analysisStateDao.getDonePathsInBatch(stage.stageId, stage.modelVersion, batch)
                }.toSet()
            } else {
                emptySet()
            }
            val pendingPaths = if (donePaths.isEmpty()) targetPaths else targetPaths - donePaths
            val skippedCount = targetPaths.size - pendingPaths.size
            val completedPaths = java.util.Collections.synchronizedSet(HashSet<String>())
            val observedFailedPaths = java.util.Collections.synchronizedSet(HashSet<String>())
            // ParallelFileProcessor 的多个 worker 会并发回调。AtomicInteger 自增顺序与
            // 回调实际执行顺序不同，直接转发会让 SharedFlow 及通知 UI 收到倒退的数字。
            val progressLock = Any()
            var lastOverallProcessed = skippedCount

            val rawStageResult: StageResult = if (pendingPaths.isEmpty()) {
                // 全部已完成（断点续跑命中），无需推理
                Log.i(TAG, "[${stage.stageId}] 断点续跑：${targetPaths.size} 个文件全部已完成，跳过")
                StageResult(successCount = 0, failedCount = 0)
            } else {
                InferenceMetrics.measure(
                    operation = "pipeline:stage:${stage.stageId}",
                    backend = InferenceMetrics.Backend.CPU_DEFAULT,
                ) {
                    stage.executeEnhanced(pendingPaths) { processed, _, filePath, status ->
                        // 将 pending 维度的进度映射回 targetPaths 维度，含已跳过文件。
                        // 同步计算与发布，确保旧回调不会在新回调之后写入较小的进度。
                        synchronized(progressLock) {
                            val overallProcessed = maxOf(
                                lastOverallProcessed,
                                (skippedCount + processed).coerceIn(0, targetPaths.size),
                            )
                            lastOverallProcessed = overallProcessed
                            _stageProgress.tryEmit(
                                StageProgress(
                                    stageId = stage.stageId,
                                    displayName = stage.displayName,
                                    stageIndex = stageIdx,
                                    totalStages = totalStages,
                                    stageStatus = StageProgress.StageStatus.RUNNING,
                                    processedFiles = overallProcessed,
                                    totalFiles = targetPaths.size,
                                ),
                            )
                            progressManager.onFileProgress(
                                stageId = stage.stageId,
                                processed = overallProcessed,
                                total = targetPaths.size,
                            )
                            if (filePath != null && status != null) {
                                when (status) {
                                    FileProcessingStatus.COMPLETED -> completedPaths.add(filePath)
                                    FileProcessingStatus.FAILED -> observedFailedPaths.add(filePath)
                                    else -> Unit
                                }
                                progressManager.onFileStatusChange(stage.stageId, filePath, status)
                            }
                        }
                    }
                }
            }
            val stageResult = normalizeStageResult(rawStageResult, pendingPaths, observedFailedPaths)
            completedPaths.removeAll(stageResult.failedPaths)

            // 落库本次成功的 done 状态（仅缓存型阶段，批量事务规避 999 变量上限）
            if (stage.isCacheable && analysisStateDao != null && completedPaths.isNotEmpty()) {
                val now = System.currentTimeMillis()
                completedPaths.toList().chunked(ANALYSIS_STATE_BATCH_SIZE).forEach { chunk ->
                    analysisStateDao.upsertAll(
                        chunk.map {
                            AnalysisStateEntity(
                                filePath = it,
                                stageId = stage.stageId,
                                status = AnalysisStateEntity.STATUS_DONE,
                                modelVersion = stage.modelVersion,
                                updatedAtMs = now,
                            )
                        },
                    )
                }
            }

            results[stage.stageId] = stageResult

            val elapsed = System.currentTimeMillis() - stageStart
            val endPssKb = currentPssKb()
            val filesPerMinute = if (elapsed > 0L) {
                stageResult.successCount * 60_000.0 / elapsed
            } else {
                0.0
            }
            Log.i(
                TAG,
                "[${stage.stageId}] 完成: 成功=${stageResult.successCount} 失败=${stageResult.failedCount} " +
                    "耗时=${elapsed}ms 吞吐=${"%.2f".format(filesPerMinute)}文件/分钟 " +
                    "PSS=${startPssKb}KB→${endPssKb}KB extra=${stageResult.extra}",
            )
            _stageProgress.emit(
                StageProgress(
                    stageId = stage.stageId,
                    displayName = stage.displayName,
                    stageIndex = stageIdx,
                    totalStages = totalStages,
                    stageStatus = StageProgress.StageStatus.COMPLETED,
                    // 断点续跑：阶段完成时所有 targetPaths 均已处理（含跳过），进度记满
                    processedFiles = targetPaths.size,
                    totalFiles = targetPaths.size,
                ),
            )
            progressManager.onStageComplete(stage.stageId, stageResult.successCount, stageResult.failedCount)
        } catch (e: Throwable) {
            // 结构化并发：CancellationException 表示协程被取消（用户取消扫描 / 父作用域取消），
            // 必须重新抛出，不能当作阶段业务错误上报，否则会吞掉取消信号并误报"阶段失败: unknown"。
            if (e is CancellationException) throw e
            // 捕获 Throwable（含 OOM 等 Error）而非仅 Exception：避免单阶段抛出 Error 时
            // 冒泡到 runFullScan 的 coroutineScope 触发同层取消级联，连累其他无关阶段
            // （如 face/scene/ocr 的 MODEL 类型 Provider 加载大模型 OOM 时连累 quality）。
            val errorMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.w(TAG, "[${stage.stageId}] 阶段执行异常", e)
            results[stage.stageId] = StageResult(
                successCount = 0,
                failedCount = targetPaths.size,
                extra = mapOf("error" to errorMsg),
                failedPaths = targetPaths.toSet(),
            )
            _stageProgress.emit(
                StageProgress(
                    stageId = stage.stageId,
                    displayName = stage.displayName,
                    stageIndex = stageIdx,
                    totalStages = totalStages,
                    stageStatus = StageProgress.StageStatus.ERROR,
                    processedFiles = 0,
                    totalFiles = targetPaths.size,
                    error = errorMsg,
                ),
            )
            progressManager.onStageError(stage.stageId, errorMsg)
        } finally {
            runCatching { releaseStageResources(stage.stageId) }
                .onFailure { error -> Log.w(TAG, "[${stage.stageId}] 阶段资源释放失败", error) }
        }
    }

    // ---- Full Scan ----

    /**
     * 全量扫描：对所有文件顺序执行全部已注册阶段。
     *
     * @param filePaths 媒体文件绝对路径列表
     * @return 每个阶段 ID 到执行结果的映射
     */
    suspend fun runFullScan(filePaths: List<String>): Map<String, StageResult> {
        if (filePaths.isEmpty()) {
            Log.i(TAG, "文件列表为空，跳过扫描")
            _pipelineStatus.value = Status.IDLE
            return emptyMap()
        }

        _pipelineStatus.value = Status.RUNNING
        // 同层阶段并行执行时多个 runStage 会并发写入 results，使用 ConcurrentHashMap 避免并发修改异常。
        val results = ConcurrentHashMap<String, StageResult>()

        // 初始化 ProgressManager
        val stageIds = sortedStages.map { it.stageId }
        progressManager.beginPipeline(totalFiles = filePaths.size, stageIds = stageIds)

        Log.i(TAG, "全量扫描开始：${filePaths.size} 个文件，${stages.size} 个阶段（排序后 ${sortedStages.size}）")

        runStagesWithSafeParallelism(filePaths, filePaths, results)

        // 通知 ProgressManager 管道完成
        progressManager.onPipelineComplete()

        _pipelineResults.value = results.toMap()
        _pipelineStatus.value = Status.COMPLETED
        InferenceMetrics.logSnapshot("full-scan")
        Log.i(TAG, "全量扫描完成：${results.size} 个阶段")

        return results
    }

    // ---- Incremental Scan ----

    /**
     * 增量扫描：对新增/修改的文件子集重新执行各阶段。
     *
     * 缓存型阶段（isCacheable=true）仅处理本次增量文件，
     * 非缓存型阶段（如相似度聚类）仍需对全部文件执行。
     *
     * @param incrementalPaths 新增/修改的文件路径
     * @param allPaths 全量文件路径（用于非缓存型阶段的全量执行）
     * @return 每个阶段 ID 到执行结果的映射
     */
    suspend fun runIncremental(
        incrementalPaths: List<String>,
        allPaths: List<String>,
    ): Map<String, StageResult> = runIncrementalStages(
        incrementalPaths = incrementalPaths,
        allPaths = allPaths,
        executionStages = sortedStages,
    )

    /** Executes only the Stage represented by a new task scope, or all stages for a legacy scope. */
    suspend fun runIncrementalForTaskScope(
        taskScope: String,
        incrementalPaths: List<String>,
        allPaths: List<String>,
    ): Map<String, StageResult> {
        val requiredStageIds = requiredStageIdsForTaskScope(taskScope)
        require(requiredStageIds.isNotEmpty()) { "Unknown analysis task scope: $taskScope" }
        return runIncrementalStages(
            incrementalPaths = incrementalPaths,
            allPaths = allPaths,
            executionStages = sortedStages.filter { it.stageId in requiredStageIds },
        )
    }

    private suspend fun runIncrementalStages(
        incrementalPaths: List<String>,
        allPaths: List<String>,
        executionStages: List<AnalysisStage>,
    ): Map<String, StageResult> {
        if (incrementalPaths.isEmpty()) {
            Log.i(TAG, "增量文件列表为空，跳过扫描")
            _pipelineStatus.value = Status.IDLE
            return emptyMap()
        }

        _pipelineStatus.value = Status.RUNNING
        // 同层阶段并行执行时多个 runStage 会并发写入 results，使用 ConcurrentHashMap 避免并发修改异常。
        val results = ConcurrentHashMap<String, StageResult>()

        // 初始化 ProgressManager（使用可能的最大总文件数来保证进度平滑）
        val stageIds = executionStages.map { it.stageId }
        val maxTotalFiles = maxOf(incrementalPaths.size, allPaths.size)
        progressManager.beginPipeline(totalFiles = maxTotalFiles, stageIds = stageIds)

        Log.i(
            TAG,
            "增量扫描开始：增量=${incrementalPaths.size}，全量=${allPaths.size}，阶段=${executionStages.size}",
        )

        runStagesWithSafeParallelism(
            incrementalPaths = incrementalPaths,
            allPaths = allPaths,
            results = results,
            executionStages = executionStages,
        )

        // 通知 ProgressManager 管道完成
        progressManager.onPipelineComplete()

        _pipelineResults.value = results.toMap()
        _pipelineStatus.value = Status.COMPLETED

        return results
    }

    // ---- Helpers ----

    /**
     * 仅恢复经过内存审计的安全组合并行：Face（受 2-session 池约束）与无模型的
     * Quality 同时运行。Semantic/EVA02、Scene 和 OCR 仍串行，避免大型模型峰值相加。
     */
    private suspend fun runStagesWithSafeParallelism(
        incrementalPaths: List<String>,
        allPaths: List<String>,
        results: MutableMap<String, StageResult>,
        executionStages: List<AnalysisStage> = sortedStages,
    ) = coroutineScope {
        val indexedStages = executionStages.withIndex().associateBy { it.value.stageId }
        val face = indexedStages[AnalysisStage.STAGE_FACE]
        val quality = indexedStages[AnalysisStage.STAGE_QUALITY]
        val safelyPairedIds = if (
            com.renyxin.localalbum.core.concurrent.AnalysisSchedulingRuntime.profile.allowFaceQualityParallel &&
            face != null && quality != null &&
            face.value.dependencies.isEmpty() && quality.value.dependencies.isEmpty()
        ) {
            setOf(AnalysisStage.STAGE_FACE, AnalysisStage.STAGE_QUALITY)
        } else {
            emptySet()
        }

        var safePairExecuted = false
        executionStages.forEachIndexed { stageIdx, stage ->
            if (stage.stageId in safelyPairedIds) {
                if (!safePairExecuted) {
                    safePairExecuted = true
                    listOf(requireNotNull(face), requireNotNull(quality)).map { indexed ->
                        async {
                            val targetPaths = if (indexed.value.isCacheable) incrementalPaths else allPaths
                            runStage(indexed.value, indexed.index, executionStages.size, targetPaths, results)
                        }
                    }.awaitAll()
                }
            } else {
                val targetPaths = if (stage.isCacheable) incrementalPaths else allPaths
                runStage(stage, stageIdx, executionStages.size, targetPaths, results)
            }
        }
    }

    /**
     * 将阶段汇总计数与逐文件回调归一化为可靠的失败路径集合。
     * 若阶段声明失败但没有给出足够的路径，保守地只重试该阶段实际尝试的窗口，
     * 绝不把无法证明成功的文件写入 done。
     */
    private fun normalizeStageResult(
        result: StageResult,
        attemptedPaths: List<String>,
        observedFailedPaths: Set<String>,
    ): StageResult {
        val attempted = attemptedPaths.toSet()
        val explicit = (result.failedPaths + observedFailedPaths).filterTo(linkedSetOf()) { it in attempted }
        val reportedFailureCount = maxOf(result.failedCount, explicit.size)
        val failures = when {
            reportedFailureCount == 0 -> emptySet()
            explicit.size >= reportedFailureCount -> explicit
            else -> attempted
        }
        return result.copy(
            failedCount = reportedFailureCount,
            failedPaths = failures,
        )
    }

    private fun currentPssKb(): Long = runCatching { Debug.getPss() }.getOrDefault(-1L)

    private fun logStageStart(stage: AnalysisStage) {
        val depStr = if (stage.dependencies.isNotEmpty())
            " (依赖: ${stage.dependencies.joinToString()})" else ""
        Log.d(TAG, "→ 执行阶段 [${stage.stageId}] ${stage.displayName}$depStr")
    }
}