package com.renyxin.localalbum

import android.content.Context
import com.renyxin.localalbum.core.album.AlbumBuilder
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.pipeline.AnalysisPlanType
import com.renyxin.localalbum.core.pipeline.AnalysisStageFactory
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.pipeline.ScanFeaturePolicy
import com.renyxin.localalbum.core.pipeline.StageInclusionPolicy
import com.renyxin.localalbum.edition.EditionConfiguration
import com.renyxin.localalbum.edition.EditionFeatures
import com.renyxin.localalbum.core.plugin.PluginConfigException
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.ProgressReporter
import com.renyxin.localalbum.core.plugin.PluginLoader
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.CapabilitySlot
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.capability.ComposedFaceProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.ConceptEmbedProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.HeuristicQualityProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.HeuristicSceneProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.MlKitFaceDetector
import com.renyxin.localalbum.core.plugin.model.ArcFaceProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.InsightFaceProvider
import com.renyxin.localalbum.core.plugin.extension.InSwapperPlugin
import com.renyxin.localalbum.core.plugin.capability.builtin.Eva02ClipProvider
import com.renyxin.localalbum.core.plugin.model.CompositeModelCatalog
import com.renyxin.localalbum.core.plugin.model.HuggingFaceModelSource
import com.renyxin.localalbum.core.plugin.model.LocalModelCatalog
import com.renyxin.localalbum.core.plugin.model.ModelCatalog
import com.renyxin.localalbum.core.plugin.model.MobileCLIPProvider
import com.renyxin.localalbum.core.plugin.model.MobileNetSceneProvider
import com.renyxin.localalbum.core.plugin.model.ModelDownloadManagerV2
import com.renyxin.localalbum.core.plugin.model.ModelManagerImpl
import com.renyxin.localalbum.core.plugin.model.ModelStorageManager
import com.renyxin.localalbum.core.plugin.capability.builtin.MlKitFaceProvider
import com.renyxin.localalbum.core.plugin.demo.DemoPluginsInitializer
import com.renyxin.localalbum.core.plugin.extension.ExtensionPluginRegistry
import com.renyxin.localalbum.core.recommendation.RecommendationEngine
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.prefs.SettingsStore
import com.renyxin.localalbum.data.worker.AnalysisResumePrefs
import com.renyxin.localalbum.data.repo.AlbumRepository
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.data.repo.SettingsRepository
import com.renyxin.localalbum.data.source.MediaSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phase 2: 回前台补偿扫描节流窗口（毫秒）。 */
private const val FOREGROUND_RESCAN_THROTTLE_MS = 30_000L

/** 模型状态同步的 debounce 窗口（毫秒）：合并下载期高频进度回调，避免全量重算风暴。 */
private const val MODEL_STATUS_SYNC_DEBOUNCE_MS = 200L

/** 扩展插件加载失败最大重试次数（总尝试次数 = 次数 + 1）。 */
private const val PLUGIN_LOAD_MAX_RETRIES = 2

/** 扩展插件加载重试间隔（毫秒）。 */
private const val PLUGIN_LOAD_RETRY_DELAY_MS = 1_000L

/**
 * 手写 DI 容器：应用进程级的全部单例装配点与后台任务宿主。
 *
 * ## 职责
 * 构造即完成全部核心组件的装配（按属性声明顺序）：
 * [database] → [settingsRepository] → [modelDownloadManager]/[modelManager]/[modelStorageManager]/
 * [modelCatalog] → [capabilityRegistry]（含各能力槽位与 Provider 注册）→ [extensionPluginRegistry]
 * → [pluginAnalysisPipeline]（by lazy 单例）→ [hybridIndexer] → [albumRepository]。
 *
 * ## init 副作用清单
 * 构造末尾的 init 块会启动以下后台任务（均跑在内部 [appScope] 上）：
 * 1. [modelStorageManager] 存储统计刷新；
 * 2. Demo 插件初始化（仅 [EditionFeatures.showPluginManager] 为 true 的 edition）；
 * 3. [startModelStatusSync] —— ModelManager 状态到能力注册表的同步协程；
 * 4. [registerBuiltinExtensions] —— 内置换脸插件注册（仅 [EditionFeatures.showFaceSwap]）；
 * 5. [startScanProgressNotification] —— 增强分析进度通知；
 * 6. [startAnalysisResumeFlagTracking] —— 「中断待续跑」标志维护。
 *
 * 注意属性声明顺序即初始化顺序：[pluginAnalysisPipeline] 必须声明在 init 块之前，
 * 否则 init 中经 appScope.launch 异步访问它会触发 NPE（历史教训见该属性 KDoc）。
 *
 * ## 与 LocalAlbumApplication 的生命周期契约
 * - 容器在 `Application.onCreate` 中构造一次，存活至进程结束（所有 Activity 停止时仅注销
 *   ContentObserver，不 shutdown 容器）；
 * - `Application.onCreate` 随后依序调用 [registerContentObserver]、[maybeResumePendingScanChanges]、
 *   [loadPlugins]、[maybeResumeAnalysis] 完成启动恢复；
 * - 回到前台（首个 Activity onStart）时由宿主调用 [maybeRescanOnForeground] 触发补偿扫描。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val editionFeatures: EditionFeatures = EditionConfiguration.features
    val database = AppDatabase.getDatabase(context)

    /** 本 Application 进程唯一的租约所有者前缀；容器发布给任何 Worker 前先完成旧进程恢复。 */
    val thumbnailProcessEpoch: String = UUID.randomUUID().toString()

    private val thumbnailColdStartRecovered: Unit = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        database.thumbnailTaskDao().recoverThumbnailProcessBoundary(
            processEpoch = thumbnailProcessEpoch,
            now = System.currentTimeMillis(),
        )
    }

    private val settingsStore = SettingsStore(context.applicationContext)
    val settingsRepository = SettingsRepository(settingsStore) { reason ->
        // The callback runs only after construction, when a user actually changes scan scope.
        // Wake resumes an already-authorized initial scan, but NEEDS_REBUILD remains user-gated.
        libraryPipelineCoordinator.markScanScopeChanged(reason)
        libraryPipelineCoordinator.wake()
    }

    private val mediaSource = MediaSource(context.applicationContext)

    // ---- Phase 1 改进: 统一模型管理器 ----

    /**
     * 全进程唯一的模型下载器（Phase 4 单例收敛）。
     *
     * `MainActivity` 的 `PluginViewModel`（市场下载）与 [modelManager]（管线模型下载）
     * 共用此实例，避免双实例并发下载同一模型时 `.tmp` 临时文件互写。
     */
    val modelDownloadManager by lazy { ModelDownloadManagerV2(context.applicationContext) }

    /**
     * 统一的模型管理器单例（Phase 1 改进计划）。
     *
     * 集中管理所有需要下载的 AI 模型的生命周期：
     * 注册 → 下载 → 加载到内存 → 推理 → 卸载。
     * 所有模型类 Provider 通过此实例获取底层 TFLite [org.tensorflow.lite.Interpreter]。
     */
    val modelManager = ModelManagerImpl(context.applicationContext, modelDownloadManager)

    /**
     * 模型存储管理器（Phase 4 新增）。
     *
     * 管理 AI 模型文件的总占用空间、各模型大小明细、
     * 缓存清理、存储空间不足警告等功能。
     */
    val modelStorageManager = ModelStorageManager(context.applicationContext)

    /**
     * 多源模型目录（Phase 5a 升级）。
     *
     * 合并本地 models/ 目录 + HuggingFace 远程仓库，
     * 提供统一的模型浏览和搜索体验。
     */
    val modelCatalog: ModelCatalog = CompositeModelCatalog(
        localCatalog = LocalModelCatalog(context.applicationContext, modelManager),
        remoteSources = listOf(HuggingFaceModelSource()),
    )

    // ---- Phase 2 重构: 泛型化能力槽位系统 ----

    /**
     * 泛型化能力注册表（Phase 2 改进计划）。
     *
     * 使用 [CapabilityRegistryV2] 统一管理任意类型的能力槽位。
     * 新增槽位只需调用 [registerSlot] + [registerProvider]，无需修改注册表内部结构。
     * 模型类 Provider 注入 [modelManager] 以统一管理模型生命周期。
     */
    val capabilityRegistry = CapabilityRegistryV2().apply {
        if (editionFeatures.allowsCapabilitySlot("face")) {
            registerSlot(CapabilitySlot(
                slotId = "face", displayName = "人脸检测与聚类",
                description = "检测图像中的人脸并提取特征向量用于聚类",
                interfaceClass = FaceProvider::class, defaultProviderId = "model:insightface",
                icon = "🧑", required = true,
            ))
            registerProvider("face", "model:insightface",
                InsightFaceProvider(modelManager, appContext), isDefault = true,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL)
            registerProvider("face", "model:arcface",
                ComposedFaceProvider(
                    detector = MlKitFaceDetector(appContext),
                    embedder = ArcFaceProvider(modelManager, appContext),
                ), isDefault = false, providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL)
            registerProvider("face", "builtin:mlkit",
                MlKitFaceProvider(appContext), isDefault = false,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.BUILTIN)
            registerProviderModelIds("face", "model:insightface",
                listOf("model:insightface_det", "model:insightface_rec"))
            registerProviderModelIds("face", "model:arcface", listOf("model:arcface"))
        }

        if (editionFeatures.allowsCapabilitySlot("scene")) {
            registerSlot(CapabilitySlot(
                slotId = "scene", displayName = "场景分类",
                description = "识别图像的场景类别（自然、城市、美食等）",
                interfaceClass = SceneProvider::class, defaultProviderId = "model:mobilenet_v2",
                icon = "🏷️", required = false,
            ))
            registerProvider("scene", "model:mobilenet_v2",
                MobileNetSceneProvider(modelManager, appContext), isDefault = true,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL)
            registerProvider("scene", "builtin:heuristic",
                HeuristicSceneProvider(), isDefault = false,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.BUILTIN)
            registerProviderModelIds("scene", "model:mobilenet_v2", listOf("model:mobilenet_v2"))
        }

        if (editionFeatures.allowsCapabilitySlot("semantic")) {
            registerSlot(CapabilitySlot(
                slotId = "semantic", displayName = "语义嵌入",
                description = "提取图像的跨模态语义向量用于自然语言搜索",
                interfaceClass = SemanticEmbedProvider::class, defaultProviderId = "builtin:eva02_clip",
                icon = "🔍", required = false,
            ))
            registerProvider("semantic", "builtin:eva02_clip",
                Eva02ClipProvider(appContext), isDefault = true,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.BUILTIN)
            registerProvider("semantic", "model:mobileclip",
                MobileCLIPProvider(modelManager, appContext), isDefault = false,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL)
            registerProvider("semantic", "builtin:concept",
                ConceptEmbedProvider(), isDefault = false,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.BUILTIN)
            registerProviderModelIds("semantic", "model:mobileclip",
                listOf("model:mobileclip_image", "model:mobileclip_text"))
        }

        if (editionFeatures.allowsCapabilitySlot("quality")) {
            registerSlot(CapabilitySlot(
                slotId = "quality", displayName = "质量评估",
                description = "评估图像的清晰度、曝光度、色彩等质量指标",
                interfaceClass = QualityProvider::class, defaultProviderId = "builtin:heuristic",
                icon = "⭐", required = false,
            ))
            registerProvider("quality", "builtin:heuristic",
                HeuristicQualityProvider(), isDefault = true,
                providerType = com.renyxin.localalbum.core.plugin.capability.ProviderType.BUILTIN)
        }

        EditionConfiguration.registerEditionCapabilityProviders(
            registry = this,
            modelManager = modelManager,
            appContext = appContext,
        )
    }

    // ---- Phase 3: 扩展插件系统 ----

    /**
     * 扩展插件注册表（Phase 3 重构）。
     *
     * 独立管理交互式调用的扩展 AI 插件（换脸、风格迁移、自定义检测等）。
     * 这些插件不参与批处理管道，通过 UI 入口交互式触发。
     */
    val extensionPluginRegistry = ExtensionPluginRegistry(
        loader = PluginLoader(context.applicationContext),
        appContext = context.applicationContext,
    )

    /**
     * 协程作用域，用于后台任务（ContentObserver、插件加载等）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val scanFeaturePolicy: ScanFeaturePolicy = editionFeatures.scanFeaturePolicy

    private val analysisStageFactory: AnalysisStageFactory by lazy {
        AnalysisStageFactory(
            mediaDao = database.mediaDao(),
            faceDao = database.faceDao(),
            faceClusterDao = database.faceClusterDao(),
            embeddingDao = database.embeddingDao(),
            capabilityRegistry = capabilityRegistry,
            semanticDao = database.semanticDao(),
        )
    }

    /**
     * 核心分析管道（Phase 2 重构 + 修复：单例化，保证进度 UI 与运行管线共用同一 ProgressManager）。
     *
     * 仅包含核心分析阶段（人脸/场景/语义/质量/OCR/地理），
     * 各阶段从 [capabilityRegistry] 获取当前激活的 Provider。
     * 扩展 AI 插件（换脸、风格迁移等）不再参与批处理管道。
     *
     * 修复说明：原实现使用 `get()` 计算属性，每次访问都新建实例，导致
     * HybridIndexer 持有的运行管线、ViewModel 注入的 progressManager、
     * GlobalProgressIndicator 观察的 progressManager 分属不同实例，进度 UI 永不更新。
     * 现改为 `by lazy` 单例，确保全应用共用同一管线与 [ProgressManager]。
     *
     * 注意：单例在首次访问时快照当时的激活 Provider；运行时切换 Provider 后
     * 需调用方显式重建管线（或改为在 execute 时实时取 Provider）。
     *
     * 修复 NPE：必须在 init 块之前声明 by lazy 委托。Kotlin 属性初始化按声明顺序
     * 执行，init 块中的 startScanProgressNotification/startAnalysisResumeFlagTracking
     * 通过 appScope.launch 异步访问此属性，若委托在 init 之后声明则可能为 null。
     */
    val pluginAnalysisPipeline: PluginAnalysisPipeline by lazy {
        val plan = analysisStageFactory.createPlan(
            inclusionPolicy = StageInclusionPolicy(scanFeaturePolicy),
            planType = AnalysisPlanType.ENHANCEMENT,
        )
        PluginAnalysisPipeline.create(
            plan = plan,
            analysisStateDao = database.analysisStateDao(),
            retiredAutomaticScopeSelectors = if (scanFeaturePolicy.policyId == "lite") {
                listOf(
                    PluginAnalysisPipeline.retiredPolicyScopeSelector(
                        planType = AnalysisPlanType.ENHANCEMENT,
                        policyId = "lite",
                        policyVersion = 1,
                    ),
                )
            } else {
                emptyList()
            },
            releaseStageResources = { stageId ->
                // 每个模型阶段结束后立即卸载其 session，避免上一阶段权重/工作区与下一阶段
                // 峰值叠加。搜索或下一批分析会按需懒加载；启发式阶段无需释放。
                when (stageId) {
                    "core:semantic" ->
                        capabilityRegistry.getActiveProvider<SemanticEmbedProvider>("semantic")?.release()
                    "core:face" ->
                        capabilityRegistry.getActiveProvider<FaceProvider>("face")?.release()
                    "core:scene" ->
                        capabilityRegistry.getActiveProvider<SceneProvider>("scene")?.release()
                    "core:ocr" ->
                        capabilityRegistry.getActiveProvider<OcrProvider>("ocr")?.release()
                }
                modelManager.evictUnusedModels()
            },
        )
    }

    // ---- Demo 插件初始化 ----

    init {
        // 启动时刷新存储统计
        modelStorageManager.refresh()
        if (editionFeatures.showPluginManager) {
            DemoPluginsInitializer.initialize(
                context = appContext,
                capabilityRegistry = capabilityRegistry,
                extensionRegistry = extensionPluginRegistry,
                pluginManifestDao = database.pluginManifestDao(),
            )
        }

        // 同步 ModelManager 模型状态到 CapabilityRegistryV2。启动期只观察 descriptor 状态，
        // 不复制 assets/models，也不创建任何 native runtime/session。
        startModelStatusSync()

        // 换脸是独立交互能力，不依赖通用插件管理入口。初始化仅注册 descriptor；模型文件、
        // emutls/OpenCV/ORT 与 emap 均在用户执行且取得交互 AI lane 后按需准备。
        if (editionFeatures.showFaceSwap) {
            registerBuiltinExtensions()
        }

        // Phase 1: 分析属于核心扫描之后的独立增强生命周期，使用独立通知。
        startScanProgressNotification()
        // Phase 1: 订阅管道整体状态，维护「中断待续跑」标志
        startAnalysisResumeFlagTracking()
    }

    /**
     * Generic pipeline status may belong to scan-owned work and therefore must never turn a user
     * pause back on. Explicit queue publication sets the marker; terminal status may only converge an
     * already-enabled marker after Room reports no remaining user-owned task.
     */
    private fun startAnalysisResumeFlagTracking() {
        appScope.launch {
            pluginAnalysisPipeline.pipelineStatusFlow.collect { status ->
                if (
                    status in setOf(
                        PluginAnalysisPipeline.Status.COMPLETED,
                        PluginAnalysisPipeline.Status.ERROR,
                    ) &&
                    AnalysisResumePrefs.isPending(appContext) &&
                    database.analysisTaskDao().countActiveUserTasks() == 0
                ) {
                    AnalysisResumePrefs.setPending(appContext, false)
                }
            }
        }
    }

    /**
     * Compatibility startup hook. The durable coordinator restores scan-owned work from the current
     * stage and admits explicit null-scanId user tasks only while the library is idle.
     */
    fun maybeResumeAnalysis() {
        appScope.launch {
            try {
                libraryPipelineCoordinator.wake()
            } catch (e: Exception) {
                android.util.Log.e("AppContainer", "续跑持久图库流水线失败", e)
            }
        }
    }

    /**
     * 内置人脸模型包（assets/models/buffalo_l.zip）是否随包分发。
     *
     * 纯 assets 元数据查询：不打开模型文件、不复制、不初始化任何 native runtime，
     * 符合冷启动架构约束（NativeRuntimeArchitectureTest）。结果随包固定，缓存一次。
     */
    private val bundledFaceModelPackAvailable: Boolean by lazy {
        runCatching { appContext.assets.list("models")?.contains("buffalo_l.zip") == true }
            .getOrDefault(false)
            .also { available ->
                if (!available) {
                    android.util.Log.w("AppContainer", "buffalo_l.zip 未随包，人脸聚类模型需按需来源")
                }
            }
    }

    /**
     * 启动 ModelManager → CapabilityRegistryV2 的状态同步协程。
     *
     * 持续监听 [modelManager] 中所有模型的 [ModelManager.ModelState] 变化，
     * 并更新到对应槽位中 Provider 的 [ModelReadiness] 状态。
     */
    @OptIn(FlowPreview::class)
    private fun startModelStatusSync() {
        appScope.launch {
            // 修复：debounce 合并下载期间高频进度回调，避免每次状态变更都触发
            // O(slots×providers×models) 全量重算造成 CPU 开销过大。
            modelManager.getAllModelStates().debounce(MODEL_STATUS_SYNC_DEBOUNCE_MS).collect { states ->
                // 诊断日志：打印所有模型状态，便于排查"内置模型未就绪"问题
                android.util.Log.i("AppContainer", "模型状态快照: ${states.joinToString { "${it.modelId}=${it.status}" }}")
                // modelId -> status/progress 映射
                val modelStatusMap = mutableMapOf<String, com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus>()
                val modelProgressMap = mutableMapOf<String, Float>()
                for (ms in states) {
                    modelStatusMap[ms.modelId] = ms.status
                    modelProgressMap[ms.modelId] = ms.progress
                }

                // 遍历所有槽位，对每个 MODEL 类型 Provider 聚合其所有子模型的状态
                for (slotMeta in capabilityRegistry.slotMetadataList.value) {
                    for (provider in slotMeta.availableProviders) {
                        if (provider.providerType != com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL) continue

                        val modelIds = capabilityRegistry.getProviderModelIds(provider.providerId)
                        var hasError = false
                        var hasDownloading = false
                        var hasNotDownloaded = false
                        var totalProgress = 0f
                        var modelCount = 0

                        for (modelId in modelIds) {
                            val status = modelStatusMap[modelId]
                            val progress = modelProgressMap[modelId] ?: 0f
                            modelCount++
                            totalProgress += progress
                            when (status) {
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.ERROR -> hasError = true
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.DOWNLOADING,
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.LOADING -> hasDownloading = true
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.NOT_DOWNLOADED,
                                null -> hasNotDownloaded = true
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.DOWNLOADED,
                                com.renyxin.localalbum.core.plugin.model.ModelManager.ModelStatus.LOADED -> Unit
                            }
                        }

                        val aggregatedReadiness = when {
                            hasError -> com.renyxin.localalbum.core.plugin.capability.ModelReadiness.ERROR
                            hasDownloading -> com.renyxin.localalbum.core.plugin.capability.ModelReadiness.DOWNLOADING
                            hasNotDownloaded -> com.renyxin.localalbum.core.plugin.capability.ModelReadiness.NOT_DOWNLOADED
                            else -> com.renyxin.localalbum.core.plugin.capability.ModelReadiness.READY
                        }
                        val aggregatedProgress = if (modelCount > 0) totalProgress / modelCount else 0f

                        capabilityRegistry.updateProviderModelStatus(
                            slotId = slotMeta.slotId,
                            providerId = provider.providerId,
                            readiness = aggregatedReadiness,
                            progress = aggregatedProgress,
                        )
                    }
                }

                // EVA02-CLIP 为 BUILTIN 类型（随包打包，不经过 ModelManager 状态同步），
                // 模型内置即用，恒为 READY，避免 UI 误显示"未就绪"。
                val clipProvider = capabilityRegistry.getActiveProvider<SemanticEmbedProvider>("semantic")
                if (clipProvider is Eva02ClipProvider) {
                    capabilityRegistry.updateProviderModelStatus(
                        slotId = "semantic",
                        providerId = clipProvider.providerId,
                        readiness = com.renyxin.localalbum.core.plugin.capability.ModelReadiness.READY,
                        progress = 0f,
                    )
                }

                // InsightFace（face 槽位默认 Provider）与 EVA02-CLIP 同理：模型随包内置
                // （assets/models/buffalo_l.zip），运行时经 ensureModelReady 的 asset 旁路
                // 按需解压加载，不经下载状态机。冷启动受架构约束（NativeRuntimeArchitectureTest
                // 禁止启动期调用 prepareBundledModels）不触发 asset 解析，聚合状态因此停留在
                // NOT_DOWNLOADED，导致"AI 插件管理"界面人脸聚类恒显"未就绪"。
                // 仅在尚未被运行时触碰（NOT_DOWNLOADED）且 buffalo_l.zip 确实随包时修正展示语义，
                // 不覆盖真实的 DOWNLOADING/ERROR 状态。
                if (bundledFaceModelPackAvailable) {
                    val faceProviderId = capabilityRegistry.getActiveProviderId("face")
                    if (faceProviderId == "model:insightface" &&
                        capabilityRegistry.getProviderModelStatus("face", faceProviderId) ==
                        com.renyxin.localalbum.core.plugin.capability.ModelReadiness.NOT_DOWNLOADED
                    ) {
                        capabilityRegistry.updateProviderModelStatus(
                            slotId = "face",
                            providerId = faceProviderId,
                            readiness = com.renyxin.localalbum.core.plugin.capability.ModelReadiness.READY,
                            progress = 0f,
                        )
                    }
                }
            }
        }
    }

    private fun registerBuiltinExtensions() {
        appScope.launch {
            try {
                val ctx = appContext
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val pluginContext = PluginContext(
                    coroutineScope = scope,
                    progressReporter = object : ProgressReporter {
                        override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {}
                        override fun reportStatus(status: ProgressReporter.TaskStatus) {}
                    },
                    pluginId = "builtin",
                )

                val inSwapper = InSwapperPlugin(
                    modelManager = modelManager,
                    faceProviderFactory = {
                        capabilityRegistry.getActiveProvider<FaceProvider>("face")
                    },
                    faceModelIdsFactory = {
                        capabilityRegistry.getActiveProviderId("face")
                            ?.let(capabilityRegistry::getProviderModelIds)
                            .orEmpty()
                    },
                )
                inSwapper.initialize(ctx, pluginContext)
                extensionPluginRegistry.registerBuiltin(inSwapper, inSwapper.getManifest())

                android.util.Log.i("AppContainer", "内置扩展插件注册完成（InSwapper）")
            } catch (e: Exception) {
                android.util.Log.e("AppContainer", "内置扩展插件注册失败", e)
            }
        }
    }

    /**
     * AppContainer 级别的协程作用域管理。
     * 用于控制后台同步任务（如模型状态同步）的生命周期。
     */
    @Volatile
    private var isDisposed = false

    /** 获取共享的协程作用域（若已释放则抛出异常） */
    val sharedScope: CoroutineScope
        get() {
            if (isDisposed) throw IllegalStateException("AppContainer 已释放")
            return appScope
        }

    /** 释放 AppContainer 持有的后台协程资源 */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        appScope.cancel()
        android.util.Log.i("AppContainer", "后台协程资源已释放")
    }

    val thumbnailWakeDispatcher = com.renyxin.localalbum.data.repo.ThumbnailWakeDispatcher(
        context = appContext,
        taskDao = database.thumbnailTaskDao(),
        scope = appScope,
        processEpoch = thumbnailProcessEpoch.also { thumbnailColdStartRecovered },
    )

    private val thumbnailScheduler = com.renyxin.localalbum.data.repo.ThumbnailScheduler(
        context = appContext,
        taskDao = database.thumbnailTaskDao(),
        cacheDao = database.thumbnailCacheDao(),
        dispatcher = thumbnailWakeDispatcher,
    )

    val mediaDeletionCoordinator = com.renyxin.localalbum.data.repo.MediaDeletionCoordinator(
        database = database,
        profileId = editionFeatures.editionId,
    )
    val deletionService = com.renyxin.localalbum.data.repo.PersistentDeletionService(
        database.deletionTombstoneDao(),
        mediaDeletionCoordinator,
    )

    private val hybridIndexer = HybridIndexer(
        context = context.applicationContext,
        profileId = editionFeatures.editionId,
        mediaDao = database.mediaDao(),
        database = database,
        faceDao = database.faceDao(),
        embeddingDao = database.embeddingDao(),
        mediaSource = mediaSource,
        pluginPipeline = pluginAnalysisPipeline,
        analysisStateDao = database.analysisStateDao(),
        analysisTaskDao = database.analysisTaskDao(),
        featureStoreDao = database.featureStoreDao(),
        enhancementOutboxDao = database.enhancementOutboxDao(),
        scanRunDao = database.scanRunDao(),
        scanStagingDao = database.scanStagingDao(),
        mediaChangeDao = database.mediaChangeDao(),
        deletionTombstoneDao = database.deletionTombstoneDao(),
        mediaDeletionCoordinator = mediaDeletionCoordinator,
    )

    val libraryPipelineCoordinator = com.renyxin.localalbum.data.repo.LibraryPipelineCoordinator(
        context = appContext,
        database = database,
        indexer = hybridIndexer,
        analysisPipeline = { pluginAnalysisPipeline },
    )

    val albumRepository = AlbumRepository(
        settingsRepository = settingsRepository,
        mediaDao = database.mediaDao(),
        database = database,
        faceDao = database.faceDao(),
        embeddingDao = database.embeddingDao(),
        mediaSource = mediaSource,
        albumBuilder = AlbumBuilder(),
        recommendationEngine = RecommendationEngine(
            semanticClusterRecommender = if (editionFeatures.enableSemanticSearch) {
                com.renyxin.localalbum.core.recommendation.SemanticClusterRecommender(
                    embeddingDao = database.embeddingDao(),
                    mediaDao = database.mediaDao(),
                    semanticDao = database.semanticDao(),
                )
            } else {
                null
            },
        ),
        hybridIndexer = hybridIndexer,
        libraryPipelineCoordinator = libraryPipelineCoordinator,
        thumbnailScheduler = thumbnailScheduler,
        deletionService = deletionService,
        mediaDeletionCoordinator = mediaDeletionCoordinator,
        keywordSearchProfile = editionFeatures.keywordSearchProfile,
        semanticSearchEnabled = editionFeatures.enableSemanticSearch,
        semanticProviderFactory = if (editionFeatures.enableSemanticSearch) {
            { capabilityRegistry.getActiveProvider<SemanticEmbedProvider>("semantic") }
        } else {
            null
        },
        allowFaceClusterMaintenance = editionFeatures.allowFaceClusterMaintenance,
        context = appContext,
    )

    /**
     * Phase 1: 订阅分析管道阶段进度，更新独立的增强通知。
     *
     * 分析不得覆盖核心扫描通知；通知本身不持有核心扫描 FGS 引用。
     */
    private fun startScanProgressNotification() {
        appScope.launch {
            // 不直接订阅 stageProgressFlow：同一 DAG 层可并行执行多个阶段，其局部计数
            // （例如 A: 800/1000 与 B: 20/1000）会交替写入同一通知，表现为数字倒退。
            // 统一使用 ProgressManager 汇聚后的全局计数。
            pluginAnalysisPipeline.progressManager.progress.collect { progress ->
                if (!progress.isCompleted && progress.processedFiles > 0) {
                    val text = appContext.getString(
                        com.renyxin.localalbum.R.string.scan_notif_stage,
                        progress.currentStageName.ifEmpty {
                            appContext.getString(com.renyxin.localalbum.R.string.scan_notif_stage_fallback)
                        },
                        progress.processedFiles,
                        progress.totalFiles,
                    )
                    com.renyxin.localalbum.data.worker.ScanServiceController.updateEnhancementProgress(
                        appContext,
                        text,
                    )
                }
            }
        }
    }

    /**
     * 注册 MediaStore ContentObserver，将图片/视频变更导入增量扫描链路。
     *
     * 回调契约（顺序不可换）：先把变更集持久化到 Room（[HybridIndexer.recordMediaChanges]），
     * 再通知持久协调器。若缩略图或分析正在运行，通知不会追加任何 Worker，只让 journal
     * 等待当前阶段完整结束；「先持久化」保证进程被杀时变更集不丢失。
     *
     * 生命周期：由 `LocalAlbumApplication.onCreate` 与每次回前台（首个 Activity onStart）调用；
     * 重复注册会被 [HybridIndexer.registerContentObserver] 内部拒绝；所有 Activity 停止时
     * 调用 [unregisterContentObserver] 注销。
     */
    fun registerContentObserver() {
        hybridIndexer.registerContentObserver(onChanges = { changes ->
            appScope.launch {
                // Persist first. Busy stages do not receive another WorkRequest; the journal waits.
                hybridIndexer.recordMediaChanges(changes)
                libraryPipelineCoordinator.onMediaChangesRecorded()
            }
        })
    }

    /**
     * 进程重启后的唯一恢复入口（`LocalAlbumApplication.onCreate` 中调用）。
     *
     * 旧版本策略任务只做一次兼容性数据库收敛；之后只唤醒持久图库协调器。协调器根据
     * `library_pipeline.stage` 恢复同一扫描、缩略图或识别阶段，禁止启动代码直接唤醒任意
     * 自动队列并绕过阶段门禁。重复调用完全幂等，不会创建新的完整扫描。
     */
    fun maybeResumePendingScanChanges() {
        appScope.launch {
            libraryPipelineCoordinator.ensureState()
            settingsRepository.replayPendingScanScopeChange()
            // Policy v2 disables unapproved Lite automatic Scene/Quality. Converge v1 tasks before
            // the persisted stage resumes; otherwise retired tasks can remain pending forever.
            if (pluginAnalysisPipeline.retiredAutomaticScopeSelectors.isNotEmpty()) {
                val unsettledScanIds = database.scanRunDao().getUnsettledEnhancementScanIds().toSet()
                EnhancementResourceGate.tryWithAutomaticEnhancement {
                    val now = System.currentTimeMillis()
                    pluginAnalysisPipeline.retiredAutomaticScopeSelectors.forEach { selector ->
                        database.analysisTaskDao().supersedeRetiredPolicyScopes(
                            pipelinePrefix = selector.pipelinePrefix,
                            planName = selector.planName,
                            policyIdentity = selector.policyIdentity,
                            reason = "retired_automatic_policy",
                            now = now,
                        )
                    }
                    com.renyxin.localalbum.data.worker.EnhancementHandoffWorker
                        .settleEnhancementScans(database, unsettledScanIds, now)
                }
            }
            libraryPipelineCoordinator.wake()
        }
    }

    /**
     * Phase 2: 回前台补偿增量扫描。
     *
     * App 在后台期间 Observer 已注销，可能遗漏 MediaStore 变更。回到前台时触发一次增量扫描补齐。
     * 加 30s 节流，避免频繁切前后台导致重复扫描与 UI 进度条闪烁。
     * 复用 scanMutex 串行，与 Observer 防抖任务互斥。
     */
    @Volatile
    private var lastForegroundRescanMs = 0L

    @Volatile
    private var startupRestoreCompleted = false

    /**
     * Foreground resume only restores the durable stage. It never requests full reconciliation and
     * never preempts an active thumbnail/analysis phase. Missed changes are handled by persisted
     * MediaStore watermarks/journal; ambiguous coverage is exposed as NEEDS_REBUILD.
     */
    fun maybeRescanOnForeground() {
        val now = System.currentTimeMillis()
        if (startupRestoreCompleted && now - lastForegroundRescanMs < FOREGROUND_RESCAN_THROTTLE_MS) return
        lastForegroundRescanMs = now
        appScope.launch {
            if (!startupRestoreCompleted) {
                albumRepository.restoreFromDbIfNeeded()
                startupRestoreCompleted = true
            }
            runCatching { albumRepository.discoverForegroundChanges() }
                .onFailure { error ->
                    android.util.Log.e("AppContainer", "前台增量补偿检查失败", error)
                    libraryPipelineCoordinator.wake()
                }
        }
    }

    /**
     * 加载所有已安装的扩展 AI 插件（Phase 3）。
     * 应在 Application.onCreate() 中调用，在 [registerContentObserver] 之后。
     *
     * 包含重试机制：最多重试 2 次，间隔 1 秒。
     */
    fun loadPlugins() {
        if (!editionFeatures.showPluginManager) return
        appScope.launch {
            var attempts = 0
            val maxRetries = PLUGIN_LOAD_MAX_RETRIES
            var lastError: Throwable? = null

            while (attempts <= maxRetries) {
                try {
                    val summary = extensionPluginRegistry.loadAll()
                    android.util.Log.i(
                        "AppContainer",
                        "扩展插件加载成功: ${summary.successCount} 成功, ${summary.failedCount} 失败 (尝试 ${attempts + 1} 次)",
                    )
                    return@launch
                } catch (e: PluginConfigException) {
                    lastError = e
                    attempts++
                    if (attempts <= maxRetries) {
                        android.util.Log.w("AppContainer", "扩展插件加载失败，${maxRetries - attempts + 1} 秒后重试…", e)
                        delay(PLUGIN_LOAD_RETRY_DELAY_MS)
                    }
                } catch (e: Exception) {
                    lastError = e
                    attempts++
                    if (attempts <= maxRetries) {
                        android.util.Log.w("AppContainer", "扩展插件加载异常，${maxRetries - attempts + 1} 秒后重试…", e)
                        delay(PLUGIN_LOAD_RETRY_DELAY_MS)
                    }
                }
            }

            android.util.Log.e(
                "AppContainer",
                "扩展插件加载最终失败（已尝试 ${maxRetries + 1} 次）: ${lastError?.message}",
                lastError,
            )
        }
    }

    /**
     * 注销 ContentObserver，应在 Application 退出时调用。
     */
    fun unregisterContentObserver() {
        hybridIndexer.unregisterContentObserver()
    }

    /**
     * 关闭所有资源（能力注册表、插件注册中心、协程作用域等）。
     * 应在 Application.onTerminate() 中调用。
     */
    fun shutdown() {
        // 修复：增加幂等守卫，避免重复调用导致 runBlocking 重复执行 / scope 重复取消，
        // 并与 dispose() 的 isDisposed 语义保持一致。
        if (isDisposed) return
        isDisposed = true
        albumRepository.dispose()
        extensionPluginRegistry.shutdown()
        kotlinx.coroutines.runBlocking {
            capabilityRegistry.shutdown()
        }
        appScope.cancel()
    }
}