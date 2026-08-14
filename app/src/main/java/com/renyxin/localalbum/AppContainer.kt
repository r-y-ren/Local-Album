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

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val processStartedAtMs = System.currentTimeMillis()

    val editionFeatures: EditionFeatures = EditionConfiguration.features
    val database = AppDatabase.getDatabase(context)

    private val settingsStore = SettingsStore(context.applicationContext)
    val settingsRepository = SettingsRepository(settingsStore)

    private val mediaSource = MediaSource(context.applicationContext)

    // ---- Phase 1 改进: 统一模型管理器 ----

    /**
     * 统一的模型管理器单例（Phase 1 改进计划）。
     *
     * 集中管理所有需要下载的 AI 模型的生命周期：
     * 注册 → 下载 → 加载到内存 → 推理 → 卸载。
     * 所有模型类 Provider 通过此实例获取底层 TFLite [org.tensorflow.lite.Interpreter]。
     */
    val modelManager = ModelManagerImpl(context.applicationContext)

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
     * Phase 1: 订阅 [PluginAnalysisPipeline.pipelineStatusFlow]，管道运行中置位中断标志，
     * 正常结束（COMPLETED/ERROR）清零。进程被杀后标志保持，下次启动触发续跑。
     */
    private fun startAnalysisResumeFlagTracking() {
        appScope.launch {
            pluginAnalysisPipeline.pipelineStatusFlow.collect { status ->
                when (status) {
                    PluginAnalysisPipeline.Status.RUNNING ->
                        AnalysisResumePrefs.setPending(appContext, true)
                    PluginAnalysisPipeline.Status.COMPLETED,
                    PluginAnalysisPipeline.Status.ERROR ->
                        AnalysisResumePrefs.setPending(appContext, false)
                    PluginAnalysisPipeline.Status.IDLE -> Unit
                }
            }
        }
    }

    /**
     * Phase 1: 启动时若存在中断未完成的分析，自动续跑（跳过已完成阶段）。
     * 仅在 [AnalysisResumePrefs.isPending] 为 true 时执行，避免每次冷启动都跑重负载。
     * 应在 Application.onCreate 中、插件加载之后调用。
     */
    fun maybeResumeAnalysis() {
        appScope.launch {
            try {
                // Only runs created by an earlier process are stale. This avoids racing a
                // WorkManager-triggered scan that starts during Application initialization.
                database.scanRunDao().abortStaleRunning(
                    now = System.currentTimeMillis(),
                    startedBefore = processStartedAtMs,
                )
                albumRepository.resumeAnalysisIfNeeded()
            } catch (e: Exception) {
                android.util.Log.e("AppContainer", "续跑分析失败", e)
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
            modelManager.getAllModelStates().debounce(200L).collect { states ->
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

    private val thumbnailScheduler = com.renyxin.localalbum.data.repo.ThumbnailScheduler(
        context = appContext,
        taskDao = database.thumbnailTaskDao(),
        cacheDao = database.thumbnailCacheDao(),
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
        postRestoreTaskPolicy = com.renyxin.localalbum.data.backup.PostRestoreTaskPolicy(
            profileId = editionFeatures.editionId,
            pipelineScope = pluginAnalysisPipeline.pipelineScope,
            enqueueAutomaticAnalysis = pluginAnalysisPipeline.requiredStageIds.isNotEmpty(),
        ),
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
                        progress.currentStageName.ifEmpty { "正在分析" },
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

    fun registerContentObserver() {
        hybridIndexer.registerContentObserver(onChanges = { changes ->
            appScope.launch {
                // Persist first: a busy scan lock or process death must not discard the changed set.
                hybridIndexer.recordMediaChanges(changes)
                com.renyxin.localalbum.data.worker.ScanWorker.schedule(appContext)
                albumRepository.incrementalRescanIfIdle()
            }
        })
    }

    fun maybeResumePendingScanChanges() {
        appScope.launch {
            if (hybridIndexer.hasPendingReconciliation() || hybridIndexer.hasOutstandingMediaChanges()) {
                com.renyxin.localalbum.data.worker.ScanWorker.schedule(appContext)
            }
            // A process can die after core preemption was persisted but before that core attempt
            // reaches its cleanup path. PREEMPTED is transient; user PAUSED is intentionally not
            // touched here.
            database.scanRunDao().resumePreemptedEnhancements()
            // Policy v2 disables unapproved Lite automatic Scene/Quality. Converge v1 tasks before
            // deciding whether an AnalysisWorker is still needed; otherwise an unclaimable old
            // scope would remain PENDING forever and keep EnhancementState non-terminal.
            if (pluginAnalysisPipeline.retiredAutomaticScopeSelectors.isNotEmpty()) {
                val unsettledScanIds = database.scanRunDao().getUnsettledEnhancementScanIds().toSet()
                val settled = EnhancementResourceGate.tryWithAutomaticEnhancement {
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
                if (settled == null) return@launch
            }
            // Restore each durable enhancement queue independently. A process may die after
            // handoff has drained the outbox but before analysis or thumbnail work finishes.
            if (database.enhancementOutboxDao().hasRunnableEntries()) {
                com.renyxin.localalbum.data.worker.EnhancementHandoffWorker.enqueue(appContext)
            }
            if (database.analysisTaskDao().countActiveEnhancementTasks() > 0) {
                com.renyxin.localalbum.data.worker.AnalysisWorker.enqueue(appContext)
            }
            if (database.thumbnailTaskDao().countAutomaticRunnable() > 0) {
                com.renyxin.localalbum.data.worker.ThumbnailWorker.enqueueBackground(appContext)
            }
            if (database.thumbnailTaskDao().countInteractiveActive() > 0) {
                com.renyxin.localalbum.data.worker.ThumbnailWorker.enqueue(appContext)
            }
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
     * 首次前台严格执行“快照恢复 → 后台校准”，避免扫描先抢到互斥锁而阻塞相册首屏。
     * 后续回前台仍沿用节流；恢复入口幂等，因此与 Compose 初始化并发也安全。
     */
    fun maybeRescanOnForeground() {
        val now = System.currentTimeMillis()
        if (startupRestoreCompleted && now - lastForegroundRescanMs < FOREGROUND_RESCAN_THROTTLE_MS) {
            android.util.Log.d("AppContainer", "回前台节流：距上次扫描不足 ${FOREGROUND_RESCAN_THROTTLE_MS}ms，跳过")
            return
        }
        lastForegroundRescanMs = now
        appScope.launch {
            if (!startupRestoreCompleted) {
                albumRepository.restoreFromDbIfNeeded()
                startupRestoreCompleted = true
            }
            albumRepository.reconcileIfIdle()
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
            val maxRetries = 2
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
                        delay(1000L)
                    }
                } catch (e: Exception) {
                    lastError = e
                    attempts++
                    if (attempts <= maxRetries) {
                        android.util.Log.w("AppContainer", "扩展插件加载异常，${maxRetries - attempts + 1} 秒后重试…", e)
                        delay(1000L)
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