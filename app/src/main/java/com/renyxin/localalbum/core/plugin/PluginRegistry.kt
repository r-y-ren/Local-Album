package com.renyxin.localalbum.core.plugin

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

/**
 * 插件注册中心（Phase 2.2）。
 *
 * 维护已加载的插件池，提供插件的加载、卸载、查询能力，
 * 并管理插件生命周期（加载 → 初始化 → 就绪 → 卸载）。
 *
 * ## 生命周期管理
 * 1. [loadAll] — 扫描插件目录，批量加载并初始化所有插件
 * 2. [load] — 加载单个插件 APK
 * 3. [unload] — 卸载插件（调用 release 释放资源）
 * 4. [getPlugin] / [getByTaskType] / [listAll] — 查询已加载插件
 *
 * ## 状态暴露
 * 通过 [pluginStates] StateFlow 暴露所有插件的当前状态，
 * 供 UI 层实时显示插件列表与状态。
 *
 * @param context 宿主 Context
 * @param loader 插件加载器（默认使用 [PluginLoader]）
 */
class PluginRegistry(
    private val context: Context,
    private val loader: PluginLoader = PluginLoader(context),
) {

    companion object {
        private const val TAG = "PluginRegistry"
    }

    /**
     * 插件运行状态。
     */
    enum class PluginState {
        /** 已发现但未加载 */
        DISCOVERED,
        /** 正在加载 */
        LOADING,
        /** 已加载，正在初始化 */
        INITIALIZING,
        /** 就绪，可执行推理 */
        READY,
        /** 加载/初始化失败 */
        ERROR,
        /** 已卸载 */
        UNLOADED,
    }

    /**
     * 插件状态信息（用于 UI 展示）。
     *
     * @property pluginId 插件 ID
     * @property name 插件名称
     * @property taskType 任务类型
     * @property state 当前状态
     * @property errorMessage 错误信息（ERROR 状态时）
     * @property apkPath APK 文件路径
     */
    data class PluginInfo(
        val pluginId: String,
        val name: String,
        val taskType: PluginManifest.TaskType,
        val state: PluginState,
        val errorMessage: String? = null,
        val apkPath: String,
    )

    /** 已加载的插件池：pluginId → AiPlugin 实例 */
    private val plugins = mutableMapOf<String, AiPlugin>()

    /** 插件清单缓存：pluginId → PluginManifest */
    private val manifests = mutableMapOf<String, PluginManifest>()

    /** 插件状态缓存：pluginId → PluginInfo */
    private val stateMap = mutableMapOf<String, PluginInfo>()

    /** 插件专属协程作用域池：pluginId → CoroutineScope */
    private val scopes = mutableMapOf<String, CoroutineScope>()

    /** 状态 Flow，供 UI 实时观察 */
    private val _pluginStates = MutableStateFlow<List<PluginInfo>>(emptyList())
    val pluginStates: StateFlow<List<PluginInfo>> = _pluginStates.asStateFlow()

    /** 注册中心自身的协程作用域 */
    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 状态转换互斥锁，保护 plugins/manifests/stateMap/scopes 的并发访问 */
    private val mutex = Mutex()

    /** 正在进行中的初始化 Deferred，用于 awaitAll 并发等待 */
    private val pendingInitializations = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

    /**
     * 扫描插件目录，批量加载并初始化所有插件。
     *
     * 使用 [mutex] 保护注册阶段的插件池状态，防止并发 loadAll/load 导致状态不一致。
     * 初始化阶段在锁外并发执行，避免长时间持锁。
     *
     * @return 加载结果摘要
     */
    suspend fun loadAll(): LoadSummary {
        Log.i(TAG, "开始批量加载所有插件…")
        val results = loader.loadAll()

        var successCount = 0
        var failedCount = 0

        // 注册阶段：加锁保护 plugins/manifests/stateMap/scopes 的并发写入
        mutex.withLock {
            for (result in results) {
                if (result.isSuccess) {
                    val plugin = result.plugin!!
                    val manifest = result.manifest!!
                    registerPluginLocked(plugin, manifest, result.apkPath)
                    successCount++
                } else {
                    failedCount++
                    Log.w(TAG, "插件加载失败: ${result.pluginId} — ${result.error}")
                    stateMap[result.pluginId] = PluginInfo(
                        pluginId = result.pluginId,
                        name = result.manifest?.name ?: result.pluginId,
                        taskType = result.manifest?.taskType ?: PluginManifest.TaskType.CLASSIFICATION,
                        state = PluginState.ERROR,
                        errorMessage = result.error,
                        apkPath = result.apkPath,
                    )
                }
            }
            updateStateFlowLocked()
        }

        // 初始化阶段：在锁外并发执行，避免长时间持锁
        val deferreds = mutex.withLock {
            plugins.values.map { plugin -> initializePluginAsync(plugin) }
        }
        pendingInitializations.addAll(deferreds)
        deferreds.awaitAll()
        pendingInitializations.clear()

        val summary = LoadSummary(
            totalDiscovered = results.size,
            successCount = successCount,
            failedCount = failedCount,
        )
        Log.i(TAG, "批量加载完成: ${summary.successCount} 成功, ${summary.failedCount} 失败")
        return summary
    }

    /**
     * 加载单个插件 APK。
     *
     * @param apkFile APK 文件
     * @return 加载结果，调用方可通过 [PluginLoader.LoadResult.isSuccess] 判断成功
     *         或通过 [PluginLoader.LoadResult.error] 区分不同失败原因
     */
    suspend fun load(apkFile: java.io.File): PluginLoader.LoadResult {
        val result = loader.load(apkFile)
        if (!result.isSuccess) {
            mutex.withLock { updateStateFlowLocked() }
            return result
        }
        val plugin = result.plugin!!
        val manifest = result.manifest!!
        mutex.withLock {
            registerPluginLocked(plugin, manifest, result.apkPath)
            updateStateFlowLocked()
        }
        val deferred = initializePluginAsync(plugin)
        deferred.await()
        return result
    }

    /**
     * 卸载插件（释放模型资源）。
     *
     * 使用 [mutex] 保护 remove-then-null-check 的原子性，
     * 防止并发卸载导致的状态不一致。
     *
     * @param pluginId 插件 ID
     * @return 是否卸载成功
     */
    suspend fun unload(pluginId: String): Boolean {
        return mutex.withLock {
            val plugin = plugins.remove(pluginId)
            val manifest = manifests.remove(pluginId)
            val scope = scopes.remove(pluginId)

            if (plugin == null) {
                Log.w(TAG, "卸载失败：插件未找到 $pluginId")
                return@withLock false
            }

            try {
                plugin.release()
            } catch (e: Exception) {
                Log.w(TAG, "插件资源释放异常: $pluginId", e)
            }

            scope?.cancel()

            stateMap[pluginId] = PluginInfo(
                pluginId = pluginId,
                name = manifest?.name ?: pluginId,
                taskType = manifest?.taskType ?: PluginManifest.TaskType.CLASSIFICATION,
                state = PluginState.UNLOADED,
                apkPath = stateMap[pluginId]?.apkPath ?: "",
            )
            updateStateFlowLocked()
            Log.i(TAG, "插件已卸载: $pluginId")
            true
        }
    }

    /**
     * 关闭注册中心，释放所有资源。
     *
     * 取消所有插件专属协程作用域、注册中心自身协程作用域，
     * 并释放所有已加载插件的模型资源。
     * 应在 Application.onTerminate() 或宿主销毁时调用。
     *
     * 调用后注册中心不可再使用，需重新实例化。
     */
    fun shutdown() {
        Log.i(TAG, "正在关闭注册中心…")
        // 先释放所有插件资源
        runBlocking {
            mutex.withLock {
                for ((pluginId, plugin) in plugins) {
                    try {
                        plugin.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "插件资源释放异常: $pluginId", e)
                    }
                }
                plugins.clear()
                manifests.clear()
                stateMap.clear()
            }
        }
        // 取消所有插件专属协程作用域
        for ((pluginId, scope) in scopes) {
            scope.cancel()
            Log.d(TAG, "已取消插件协程作用域: $pluginId")
        }
        scopes.clear()
        // 取消注册中心自身协程作用域
        registryScope.cancel()
        Log.i(TAG, "注册中心已关闭")
    }

    /**
     * 获取已加载的插件实例。
     */
    fun getPlugin(pluginId: String): AiPlugin? = plugins[pluginId]

    /**
     * 获取插件清单。
     */
    fun getManifest(pluginId: String): PluginManifest? = manifests[pluginId]

    /**
     * 按任务类型查询已就绪的插件列表。
     */
    fun getByTaskType(taskType: PluginManifest.TaskType): List<AiPlugin> {
        return plugins.values.filter { plugin ->
            plugin.getManifest().taskType == taskType
        }
    }

    /**
     * 获取所有已加载且就绪的 AI 插件列表。
     *
     * 供 [PluginAnalysisPipeline.create] 工厂方法使用，
     * 将外部插件包裹为 [PluginAnalysisStage] 嵌入管道编排。
     */
    fun getLoadedPlugins(): List<AiPlugin> {
        return plugins.values.filter { it.isReady() }
    }

    /**
     * 列出所有已加载的插件。
     */
    fun listAll(): List<AiPlugin> = plugins.values.toList()

    /**
     * 列出所有已加载插件的清单。
     */
    fun listAllManifests(): List<PluginManifest> = manifests.values.toList()

    /**
     * @VisibleForTesting — 获取单个插件的状态信息。
     */
    @VisibleForTesting
    fun getPluginInfo(pluginId: String): PluginInfo? = stateMap[pluginId]

    /**
     * 注册内置插件（不经过 APK 加载链路）。
     *
     * 内置插件（如 QualityAnalyzer、SceneClassifier 等）已随 APK 编译，
     * 无需通过 DexClassLoader 动态加载，直接注册即可。
     * 生命周期简化：跳过 DISCOVERED/LOADING 状态，直接进入 INITIALIZING → READY。
     *
     * @param plugin 已实例化的内置插件
     * @param apkPath 用于标识来源（内置插件传空字符串或 "builtin"）
     */
    fun registerBuiltInPlugin(plugin: AiPlugin, apkPath: String = "builtin") {
        val manifest = plugin.getManifest()
        runBlocking {
            mutex.withLock {
                registerPluginLocked(plugin, manifest, apkPath)
                updateStateFlowLocked()
            }
        }
        val deferred = initializePluginAsync(plugin)
        runBlocking {
            deferred.await()
        }
    }

    /**
     * @VisibleForTesting — 直接在注册中心注册一个已实例化的插件。
     * 仅用于 JVM 单元测试（绕过 DexClassLoader 加载链路）。
     */
    @VisibleForTesting
    fun registerPlugin(plugin: AiPlugin, manifest: PluginManifest, apkPath: String) {
        runBlocking {
            mutex.withLock {
                registerPluginLocked(plugin, manifest, apkPath)
                updateStateFlowLocked()
            }
        }
    }

    /**
     * @VisibleForTesting — 同步初始化指定插件（阻塞当前线程直到初始化完成）。
     * 仅用于 JVM 单元测试，避免依赖异步 [CoroutineScope]。
     */
    @VisibleForTesting
    suspend fun initializePlugin(pluginId: String) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("插件未注册: $pluginId")
        val deferred = initializePluginAsync(plugin)
        deferred.await()
    }

    // ---- 内部方法 ----

    /**
     * 注册插件到插件池（必须在 [mutex] 锁内调用）。
     */
    private fun registerPluginLocked(plugin: AiPlugin, manifest: PluginManifest, apkPath: String) {
        val pluginId = plugin.getId()
        plugins[pluginId] = plugin
        manifests[pluginId] = manifest
        scopes[pluginId] = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        stateMap[pluginId] = PluginInfo(
            pluginId = pluginId,
            name = manifest.name,
            taskType = manifest.taskType,
            state = PluginState.LOADING,
            apkPath = apkPath,
        )
        Log.i(TAG, "插件已注册: $pluginId (${manifest.name})")
    }

    /**
     * 异步初始化插件，返回 Deferred 供调用方 await 完成。
     */
    private fun initializePluginAsync(plugin: AiPlugin): kotlinx.coroutines.Deferred<Unit> {
        val pluginId = plugin.getId()
        val scope = scopes[pluginId] ?: return kotlinx.coroutines.CompletableDeferred(Unit)

        updatePluginState(pluginId, PluginState.INITIALIZING)

        return scope.async {
            try {
                val pluginContext = PluginContext(
                    coroutineScope = scope,
                    progressReporter = NoOpProgressReporter,
                    pluginId = pluginId,
                )
                plugin.initialize(context, pluginContext)
                updatePluginState(pluginId, PluginState.READY)
                Log.i(TAG, "插件初始化完成: $pluginId")
            } catch (e: Exception) {
                Log.e(TAG, "插件初始化失败: $pluginId", e)
                updatePluginState(pluginId, PluginState.ERROR, e.message)
            }
        }
    }

    /**
     * 等待所有正在进行的初始化完成。
     *
     * @return 已就绪的插件 ID 列表
     */
    suspend fun waitForReady(): List<String> {
        pendingInitializations.awaitAll()
        pendingInitializations.clear()
        return plugins.filter { it.value.isReady() }.keys.toList()
    }

    /**
     * 更新单个插件状态。
     */
    private fun updatePluginState(pluginId: String, state: PluginState, error: String? = null) {
        val existing = stateMap[pluginId] ?: return
        stateMap[pluginId] = existing.copy(state = state, errorMessage = error)
        updateStateFlowLocked()
    }

    /**
     * 刷新 StateFlow（必须在 [mutex] 锁内或确信无并发竞争时调用）。
     */
    private fun updateStateFlowLocked() {
        _pluginStates.value = stateMap.values.toList()
    }

    /**
     * 加载结果摘要。
     */
    data class LoadSummary(
        val totalDiscovered: Int,
        val successCount: Int,
        val failedCount: Int,
    )

    /**
     * 空操作 ProgressReporter（初始化阶段无进度上报需求）。
     */
    private object NoOpProgressReporter : ProgressReporter {
        override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {}
        override fun reportStatus(status: ProgressReporter.TaskStatus) {}
    }
}
