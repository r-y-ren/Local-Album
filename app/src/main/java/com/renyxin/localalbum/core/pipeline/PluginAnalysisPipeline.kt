package com.renyxin.localalbum.core.pipeline

import android.util.Log
import com.renyxin.localalbum.core.concurrent.InferenceMetrics
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import com.renyxin.localalbum.data.db.entity.AnalysisStateEntity

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
) {
    companion object {
        private const val TAG = "PluginPipeline"

        /**
         * 工厂方法（Phase 2 重构）：从 [CapabilityRegistry] 获取激活的 Provider，
         * 组装核心分析阶段。
         *
         * Similarity 保持固定实现，不参与 Provider 槽位。
         * 扩展插件（换脸、风格迁移等）不再纳入批处理管道。
         *
         * @param mediaDao 媒体 DAO
         * @param faceDao 人脸 DAO
         * @param embeddingDao 嵌入 DAO
         * @param capabilityRegistry 能力注册表（获取各槽位的激活 Provider）
         * @return 组装完成的 [PluginAnalysisPipeline]
         */
        fun create(
            mediaDao: com.renyxin.localalbum.data.db.dao.MediaDao,
            faceDao: com.renyxin.localalbum.data.db.dao.FaceDao,
            embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao,
            capabilityRegistry: CapabilityRegistryV2,
            analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
        ): PluginAnalysisPipeline {
            val stages = mutableListOf<AnalysisStage>()

            // 动态遍历已注册槽位，按 slotId 映射到对应 Stage 构造
            for (metadata in capabilityRegistry.slotMetadataList.value) {
                val stage = when (metadata.slotId) {
                    "face" -> {
                        val provider = capabilityRegistry.getActiveProvider<FaceProvider>("face")
                        if (provider != null) {
                            com.renyxin.localalbum.core.pipeline.stages.FaceStage(provider, mediaDao, faceDao)
                        } else {
                            Log.w(TAG, "人脸检测 Provider 未就绪，跳过 FaceStage")
                            null
                        }
                    }
                    "scene" -> {
                        val provider = capabilityRegistry.getActiveProvider<SceneProvider>("scene")
                        if (provider != null) {
                            com.renyxin.localalbum.core.pipeline.stages.SceneStage(provider, mediaDao)
                        } else {
                            Log.w(TAG, "场景分类 Provider 未就绪，跳过 SceneStage")
                            null
                        }
                    }
                    "semantic" -> {
                        val provider = capabilityRegistry.getActiveProvider<SemanticEmbedProvider>("semantic")
                        if (provider != null) {
                            com.renyxin.localalbum.core.pipeline.stages.SemanticStage(provider, mediaDao, embeddingDao)
                        } else {
                            Log.w(TAG, "语义嵌入 Provider 未就绪，跳过 SemanticStage")
                            null
                        }
                    }
                    "quality" -> {
                        val provider = capabilityRegistry.getActiveProvider<QualityProvider>("quality")
                        if (provider != null) {
                            com.renyxin.localalbum.core.pipeline.stages.QualityStage(provider, mediaDao)
                        } else {
                            Log.w(TAG, "质量评估 Provider 未就绪，跳过 QualityStage")
                            null
                        }
                    }
                    "ocr" -> {
                        val provider = capabilityRegistry.getActiveProvider<OcrProvider>("ocr")
                        if (provider != null) {
                            com.renyxin.localalbum.core.pipeline.stages.OcrStage(provider, mediaDao)
                        } else {
                            Log.w(TAG, "OCR Provider 未就绪，跳过 OcrStage")
                            null
                        }
                    }
                    else -> {
                        Log.d(TAG, "跳过未知槽位: ${metadata.slotId}")
                        null
                    }
                }
                if (stage != null) stages.add(stage)
            }

            return PluginAnalysisPipeline(
                stages = stages,
                analysisStateDao = analysisStateDao,
            )
        }

        /**
         * @deprecated 使用 [create(MediaDao, FaceDao, EmbeddingDao, CapabilityRegistry)] 替代。
         * 保留用于向后兼容，扩展插件不再参与批处理管道。
         */
        @Deprecated(
            message = "使用 create(MediaDao, FaceDao, EmbeddingDao, CapabilityRegistry) 替代",
            replaceWith = ReplaceWith("create(mediaDao, faceDao, embeddingDao, capabilityRegistry)"),
        )
        @Suppress("UNUSED_PARAMETER")
        fun create(
            context: android.content.Context,
            mediaDao: com.renyxin.localalbum.data.db.dao.MediaDao,
            faceDao: com.renyxin.localalbum.data.db.dao.FaceDao,
            embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao,
            featureStoreDao: com.renyxin.localalbum.data.db.dao.FeatureStoreDao,
            pluginRegistry: com.renyxin.localalbum.core.plugin.PluginRegistry,
        ): PluginAnalysisPipeline {
            // 降级：使用硬编码内置分析器（兼容旧代码路径）
            val builtinStages: List<AnalysisStage> = listOf(
                com.renyxin.localalbum.core.pipeline.stages.BuiltinFaceStage(context, mediaDao, faceDao),
                com.renyxin.localalbum.core.pipeline.stages.BuiltinQualityStage(mediaDao),
                com.renyxin.localalbum.core.pipeline.stages.BuiltinSceneStage(mediaDao),
                com.renyxin.localalbum.core.pipeline.stages.BuiltinSemanticStage(mediaDao, embeddingDao),
                com.renyxin.localalbum.core.pipeline.stages.BuiltinOcrStage(context, mediaDao),
            )
            return PluginAnalysisPipeline(stages = builtinStages)
        }
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
     * 按 DAG 依赖关系分层的阶段列表（多核并行优化）。
     *
     * 同一层内的阶段无相互依赖，可并行执行；层与层之间串行。
     * 例如 face/scene/quality/ocr/geo 在 Layer0，semantic 在 Layer1，similarity 在 Layer2。
     *
     * 各 Stage 内部已通过 [com.renyxin.localalbum.core.concurrent.InferenceDispatchers.cpuBound]
     * 限制总并发度，因此层内并行不会导致 CPU oversubscription。
     */
    private val stageLayers: List<List<AnalysisStage>> by lazy {
        val stageById = stages.associateBy { it.stageId }
        val levelCache = mutableMapOf<String, Int>()

        fun levelOf(stage: AnalysisStage): Int {
            levelCache[stage.stageId]?.let { return it }
            if (stage.dependencies.isEmpty()) {
                levelCache[stage.stageId] = 0
                return 0
            }
            val maxDep = stage.dependencies
                .mapNotNull { stageById[it] }
                .maxOfOrNull { levelOf(it) } ?: -1
            val lvl = maxDep + 1
            levelCache[stage.stageId] = lvl
            return lvl
        }

        sortedStages.groupBy { levelOf(it) }
            .toSortedMap()
            .values
            .map { layer -> dagSorter.sort(layer) }
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
            // Phase 1 断点续跑：缓存型阶段跳过已完成文件，仅对 pending 子集推理。
            // 粒度为 (file, stage)：一个文件可能已完成 face 但未完成 ocr，各阶段独立判定。
            val donePaths: Set<String> = if (stage.isCacheable && analysisStateDao != null) {
                analysisStateDao.getDonePaths(stage.stageId, stage.modelVersion).toSet()
            } else {
                emptySet()
            }
            val pendingPaths = if (donePaths.isEmpty()) targetPaths else targetPaths - donePaths
            val skippedCount = targetPaths.size - pendingPaths.size
            val completedPaths = java.util.Collections.synchronizedSet(HashSet<String>())

            val stageResult: StageResult = if (pendingPaths.isEmpty()) {
                // 全部已完成（断点续跑命中），无需推理
                Log.i(TAG, "[${stage.stageId}] 断点续跑：${targetPaths.size} 个文件全部已完成，跳过")
                StageResult(successCount = 0, failedCount = 0)
            } else {
                InferenceMetrics.measure(
                    operation = "pipeline:stage:${stage.stageId}",
                    backend = InferenceMetrics.Backend.CPU_DEFAULT,
                ) {
                    stage.executeEnhanced(pendingPaths) { processed, _, filePath, status ->
                    // 将 pending 维度的进度映射回 targetPaths 维度，含已跳过文件
                    val overallProcessed = skippedCount + processed
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
                    progressManager.onFileProgress(overallProcessed, targetPaths.size)
                    if (filePath != null && status != null) {
                        if (status == FileProcessingStatus.COMPLETED) {
                            completedPaths.add(filePath)
                        }
                        progressManager.onFileStatusChange(stage.stageId, filePath, status)
                    }
                }
                }
            }

            // 落库本次成功的 done 状态（仅缓存型阶段，批量事务规避 999 变量上限）
            if (stage.isCacheable && analysisStateDao != null && completedPaths.isNotEmpty()) {
                val now = System.currentTimeMillis()
                completedPaths.toList().chunked(500).forEach { chunk ->
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
            Log.i(
                TAG,
                "[${stage.stageId}] 完成: 成功=${stageResult.successCount} 失败=${stageResult.failedCount} " +
                    "耗时=${elapsed}ms extra=${stageResult.extra}",
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

        // 按 DAG 分层并行执行：同层无依赖阶段并发，层间串行。
        // 各 Stage 内部文件级并行已通过 cpuBound 调度器限制总并发度，不会 oversubscription。
        var stageIdx = 0
        for (layer in stageLayers) {
            // 使用 supervisorScope 而非 coroutineScope：同层阶段相互独立，
            // 单个阶段失败（含 OOM 等 Error）不应取消同层其他阶段，避免级联取消导致
            // 无关阶段被误报为"失败: unknown"。
            supervisorScope {
                layer.map { stage ->
                    async {
                        val idx = stageIdx
                        runStage(stage, idx, sortedStages.size, filePaths, results)
                    }
                }.awaitAll()
            }
            stageIdx += layer.size
        }

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
        val stageIds = sortedStages.map { it.stageId }
        val maxTotalFiles = maxOf(incrementalPaths.size, allPaths.size)
        progressManager.beginPipeline(totalFiles = maxTotalFiles, stageIds = stageIds)

        Log.i(
            TAG,
            "增量扫描开始：增量=${incrementalPaths.size}，全量=${allPaths.size}，阶段=${sortedStages.size}",
        )

        // 按 DAG 分层并行执行：同层无依赖阶段并发，层间串行。
        var stageIdx = 0
        for (layer in stageLayers) {
            // 使用 supervisorScope：同层阶段相互独立，单阶段失败不连累其他阶段（见 runFullScan 注释）。
            supervisorScope {
                layer.map { stage ->
                    async {
                        val idx = stageIdx
                        val targetPaths = if (stage.isCacheable) incrementalPaths else allPaths
                        runStage(stage, idx, sortedStages.size, targetPaths, results)
                    }
                }.awaitAll()
            }
            stageIdx += layer.size
        }

        // 通知 ProgressManager 管道完成
        progressManager.onPipelineComplete()

        _pipelineResults.value = results.toMap()
        _pipelineStatus.value = Status.COMPLETED

        return results
    }

    // ---- Helpers ----

    private fun logStageStart(stage: AnalysisStage) {
        val depStr = if (stage.dependencies.isNotEmpty())
            " (依赖: ${stage.dependencies.joinToString()})" else ""
        Log.d(TAG, "→ 执行阶段 [${stage.stageId}] ${stage.displayName}$depStr")
    }
}