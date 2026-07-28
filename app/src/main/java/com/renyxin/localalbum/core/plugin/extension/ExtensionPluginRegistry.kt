package com.renyxin.localalbum.core.plugin.extension

import android.util.Log
import com.renyxin.localalbum.core.plugin.AiPlugin
import com.renyxin.localalbum.core.plugin.ClassificationPlugin
import com.renyxin.localalbum.core.plugin.DetectionPlugin
import com.renyxin.localalbum.core.plugin.FeatureExtractionPlugin
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginLoader
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 扩展插件注册表（Phase 3-4 重构）。
 *
 * 独立管理交互式调用的扩展 AI 插件（换脸、风格迁移、自定义检测等），
 * 与核心管道的 [com.renyxin.localalbum.core.plugin.capability.CapabilityRegistry] 完全分离。
 *
 * ## 三通道注册（Phase 4）
 *
 * 1. **代码注册** ([registerBuiltin]) — 内置 Demo 插件、通过代码直接注入
 * 2. **SPI 发现** ([discoverSpiPlugins]) — META-INF/services/ 自动发现（预留）
 * 3. **动态加载** ([loadAll]) — DexClassLoader + APK 用户导入
 *
 * @param loader 插件加载器（通道 3 使用）
 */
class ExtensionPluginRegistry(
    private val loader: PluginLoader,
    private val appContext: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "ExtPluginRegistry"
    }

    /** 注册中心自身的协程作用域 */
    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 状态转换互斥锁 */
    private val mutex = Mutex()

    // ---- Plugin Pools ----

    private val _allPlugins = mutableMapOf<String, AiPlugin>()
    private val _manifests = mutableMapOf<String, PluginManifest>()

    // ---- State Flows ----

    data class PluginState(
        val pluginId: String,
        val name: String,
        val taskType: PluginManifest.TaskType,
        val isReady: Boolean,
        val description: String?,
    )

    private val _pluginStates = MutableStateFlow<List<PluginState>>(emptyList())
    val pluginStates: StateFlow<List<PluginState>> = _pluginStates.asStateFlow()

    private val _generativePlugins = MutableStateFlow<List<GenerativePlugin>>(emptyList())
    val generativePlugins: StateFlow<List<GenerativePlugin>> = _generativePlugins.asStateFlow()

    private val _detectionPlugins = MutableStateFlow<List<DetectionPlugin>>(emptyList())
    val detectionPlugins: StateFlow<List<DetectionPlugin>> = _detectionPlugins.asStateFlow()

    private val _classificationPlugins = MutableStateFlow<List<ClassificationPlugin>>(emptyList())
    val classificationPlugins: StateFlow<List<ClassificationPlugin>> = _classificationPlugins.asStateFlow()

    private val _featureExtractionPlugins = MutableStateFlow<List<FeatureExtractionPlugin>>(emptyList())
    val featureExtractionPlugins: StateFlow<List<FeatureExtractionPlugin>> = _featureExtractionPlugins.asStateFlow()

    // ===================== 三通道注册 =====================

    /**
     * 通道 1: 代码注册内置插件（Demo 插件、内置扩展等）— Phase 4。
     *
     * 内置插件不需要经过 DexClassLoader 动态加载，
     * 直接注入已初始化的插件实例和清单。
     *
     * @param plugin 已初始化的插件实例
     * @param manifest 插件清单
     */
    fun registerBuiltin(plugin: AiPlugin, manifest: PluginManifest) {
        val pluginId = plugin.getId()
        _allPlugins[pluginId] = plugin
        _manifests[pluginId] = manifest
        updateStateFlows()
        Log.i(TAG, "内置插件已注册: $pluginId (${manifest.name})")
    }

    /**
     * 通道 2: SPI 服务发现（预留接口，Phase 4）。
     *
     * 扫描 META-INF/services/ 下声明的插件实现类。
     *
     * 当前为预留接口。Android 上 SPI 机制的使用场景有限，
     * 大多数插件通过通道 1 (registerBuiltin) 或通道 3 (loadAll) 注册。
     * 若未来需要支持 SPI 插件，需实现：
     * 1. 扫描 APK 的 META-INF/services/com.renyxin.localalbum.core.plugin.AiPlugin
     * 2. 读取声明中的实现类全限定名
     * 3. 通过 DexClassLoader 加载并实例化
     */
    suspend fun discoverSpiPlugins(): List<AiPlugin> {
        Log.d(TAG, "SPI 插件发现暂未实现（Android 环境 SPI 使用场景有限）")
        return emptyList()
    }

    /**
     * 通道 3: 动态加载（DexClassLoader + APK 用户导入）。
     *
     * 扫描插件目录，批量加载所有 APK 格式的扩展插件。
     */
    data class LoadSummary(
        val totalDiscovered: Int,
        val successCount: Int,
        val failedCount: Int,
    )

    suspend fun loadAll(): LoadSummary {
        Log.i(TAG, "开始批量加载扩展插件…")
        val results = loader.loadAll()

        var successCount = 0
        var failedCount = 0
        var initializedCount = 0

        mutex.withLock {
            for (result in results) {
                if (result.isSuccess) {
                    val plugin = result.plugin!!
                    val manifest = result.manifest!!
                    _allPlugins[plugin.getId()] = plugin
                    _manifests[plugin.getId()] = manifest
                    successCount++
                } else {
                    failedCount++
                    Log.w(TAG, "扩展插件加载失败: ${result.pluginId} — ${result.error}")
                }
            }
            updateStateFlows()
        }

        // 初始化已加载的插件
        val ctx = appContext
        for (result in results) {
            if (result.isSuccess) {
                val plugin = result.plugin!!
                try {
                    if (!plugin.isReady() && ctx != null) {
                        Log.d(TAG, "正在初始化插件: ${plugin.getId()}")
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val pluginContext = com.renyxin.localalbum.core.plugin.PluginContext(
                            coroutineScope = scope,
                            progressReporter = object : com.renyxin.localalbum.core.plugin.ProgressReporter {
                                override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {}
                                override fun reportStatus(status: com.renyxin.localalbum.core.plugin.ProgressReporter.TaskStatus) {}
                            },
                            pluginId = plugin.getId(),
                        )
                        plugin.initialize(ctx, pluginContext)
                        initializedCount++
                        Log.i(TAG, "插件初始化完成: ${plugin.getId()}")
                    } else if (!plugin.isReady() && ctx == null) {
                        Log.w(TAG, "插件 ${plugin.getId()} 未初始化且 Context 不可用，跳过初始化")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "插件初始化失败: ${plugin.getId()}", e)
                }
            }
        }

        if (initializedCount > 0) {
            updateStateFlows()
        }

        val summary = LoadSummary(
            totalDiscovered = results.size,
            successCount = successCount,
            failedCount = failedCount,
        )
        Log.i(TAG, "扩展插件加载完成: ${summary.successCount} 成功, ${summary.failedCount} 失败, ${initializedCount} 已初始化")
        return summary
    }

    /**
     * 卸载指定插件。
     */
    suspend fun unload(pluginId: String): Boolean {
        return mutex.withLock {
            val plugin = _allPlugins.remove(pluginId)
            _manifests.remove(pluginId)

            if (plugin == null) {
                Log.w(TAG, "卸载失败：插件未找到 $pluginId")
                return@withLock false
            }

            try {
                plugin.release()
            } catch (e: Exception) {
                Log.w(TAG, "插件资源释放异常: $pluginId", e)
            }

            updateStateFlows()
            Log.i(TAG, "扩展插件已卸载: $pluginId")
            true
        }
    }

    // ---- Query ----

    /** 按 ID 获取插件实例 */
    fun getPlugin(pluginId: String): AiPlugin? = _allPlugins[pluginId]

    /** 按 ID 获取插件清单 */
    fun getManifest(pluginId: String): PluginManifest? = _manifests[pluginId]

    /** 获取已就绪的生成式插件 */
    fun getReadyGenerativePlugins(): List<GenerativePlugin> =
        _allPlugins.values.filterIsInstance<GenerativePlugin>().filter { it.isReady() }

    /** 获取已就绪的检测插件 */
    fun getReadyDetectionPlugins(): List<DetectionPlugin> =
        _allPlugins.values.filterIsInstance<DetectionPlugin>().filter { it.isReady() }

    /** 获取已就绪的分类插件 */
    fun getReadyClassificationPlugins(): List<ClassificationPlugin> =
        _allPlugins.values.filterIsInstance<ClassificationPlugin>().filter { it.isReady() }

    // ---- Interactive Execution ----

    suspend fun executeGenerative(pluginId: String, input: PluginInput): PluginOutput.ImageOutput? {
        val plugin = _allPlugins[pluginId] as? GenerativePlugin ?: return null
        if (!plugin.isReady()) return null
        return plugin.execute(input)
    }

    suspend fun executeDetection(pluginId: String, input: PluginInput): PluginOutput.DetectionOutput? {
        val plugin = _allPlugins[pluginId] as? DetectionPlugin ?: return null
        if (!plugin.isReady()) return null
        return plugin.execute(input)
    }

    suspend fun executeClassification(pluginId: String, input: PluginInput): PluginOutput.ClassificationOutput? {
        val plugin = _allPlugins[pluginId] as? ClassificationPlugin ?: return null
        if (!plugin.isReady()) return null
        return plugin.execute(input)
    }

    // ---- Shutdown ----

    fun shutdown() {
        Log.i(TAG, "关闭扩展插件注册表…")
        for ((id, plugin) in _allPlugins) {
            try {
                kotlinx.coroutines.runBlocking { plugin.release() }
            } catch (e: Exception) {
                Log.w(TAG, "插件资源释放异常: $id", e)
            }
        }
        _allPlugins.clear()
        _manifests.clear()
        registryScope.cancel()
    }

    // ---- Internal ----

    private fun updateStateFlows() {
        val states = _allPlugins.map { (id, plugin) ->
            val manifest = _manifests[id]
            PluginState(
                pluginId = id,
                name = manifest?.name ?: id,
                taskType = manifest?.taskType ?: PluginManifest.TaskType.CLASSIFICATION,
                isReady = plugin.isReady(),
                description = manifest?.description,
            )
        }
        _pluginStates.value = states
        _generativePlugins.value = _allPlugins.values.filterIsInstance<GenerativePlugin>()
        _detectionPlugins.value = _allPlugins.values.filterIsInstance<DetectionPlugin>()
        _classificationPlugins.value = _allPlugins.values.filterIsInstance<ClassificationPlugin>()
        _featureExtractionPlugins.value = _allPlugins.values.filterIsInstance<FeatureExtractionPlugin>()
    }
}
