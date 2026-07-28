package com.renyxin.localalbum.core.plugin.demo

import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.ProgressReporter
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.extension.ExtensionPluginRegistry
import com.renyxin.localalbum.data.db.dao.PluginManifestDao
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Demo 插件初始化器。
 *
 * 在 App 首次启动时注册所有内置 Demo 插件和 Provider：
 * - 2 个扩展插件: FaceSwap + StyleTransfer
 * - 5 个核心槽位 Demo Provider
 * - 预填充 manifest 数据库条目
 *
 * 初始化方法 [initialize] 为异步设计，在内部使用协程执行，
 * 避免阻塞调用线程（通常是主线程）。
 */
object DemoPluginsInitializer {
    private const val TAG = "DemoPluginsInit"

    /** 已初始化标志（通过数据库记录避免重复注册） */
    private const val DEMO_INIT_FLAG = "demo_init_v1"

    /** 初始化专用协程作用域 */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 初始化所有 Demo 插件（幂等操作，多次调用不会重复注册）。
     *
     * 异步执行，不阻塞调用线程。初始化结果通过日志输出。
     */
    fun initialize(
        context: Context,
        capabilityRegistry: CapabilityRegistryV2,
        extensionRegistry: ExtensionPluginRegistry,
        pluginManifestDao: PluginManifestDao,
    ) {
        initScope.launch {
            try {
                // 检查是否已初始化
                val existing = pluginManifestDao.getByPluginId(DEMO_INIT_FLAG)
                if (existing != null) {
                    Log.d(TAG, "Demo 插件已初始化，跳过")
                    return@launch
                }

                Log.i(TAG, "开始初始化 Demo 插件…")

                // ---- 1. 注册核心槽位 Demo Provider ----
                registerDemoProviders(capabilityRegistry)

                // ---- 2. 注册扩展 Demo 插件 ----
                registerDemoExtensions(context, extensionRegistry)

                // ---- 3. Seed 数据库 ----
                seedDatabase(pluginManifestDao)

                // 标记已初始化
                pluginManifestDao.insert(
                    PluginManifestEntity(
                        pluginId = DEMO_INIT_FLAG,
                        manifestJson = "{}",
                        isEnabled = true,
                        pipelineStage = "system",
                        createdAtMs = System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                )

                Log.i(TAG, "Demo 插件初始化完成")
            } catch (e: Exception) {
                Log.e(TAG, "Demo 插件初始化失败", e)
            }
        }
    }

    private fun registerDemoProviders(registry: CapabilityRegistryV2) {
        try {
            registry.registerProvider("face", "demo:face", DemoFaceProvider(), isDefault = false)
            registry.registerProvider("scene", "demo:scene", DemoSceneProvider(), isDefault = false)
            registry.registerProvider("semantic", "demo:semantic", DemoSemanticProvider(), isDefault = false)
            registry.registerProvider("quality", "demo:quality", DemoQualityProvider(), isDefault = false)
            registry.registerProvider("ocr", "demo:ocr", DemoOcrProvider(), isDefault = false)
            Log.i(TAG, "核心槽位 Demo Provider 已注册")
        } catch (e: Exception) {
            Log.w(TAG, "Demo Provider 注册失败", e)
        }
    }

    private suspend fun registerDemoExtensions(context: Context, registry: ExtensionPluginRegistry) {
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pluginContext = PluginContext(
                coroutineScope = scope,
                progressReporter = object : ProgressReporter {
                    override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {}
                    override fun reportStatus(status: ProgressReporter.TaskStatus) {}
                },
                pluginId = "demo",
            )

            // FaceSwap Demo — 通过 registerBuiltin() 直接注册（Phase 4）
            val faceSwap = DemoFaceSwapPlugin()
            faceSwap.initialize(context, pluginContext)
            registry.registerBuiltin(
                plugin = faceSwap,
                manifest = faceSwap.getManifest(),
            )

            // StyleTransfer Demo
            val styleTransfer = DemoStyleTransferPlugin()
            styleTransfer.initialize(context, pluginContext)
            registry.registerBuiltin(
                plugin = styleTransfer,
                manifest = styleTransfer.getManifest(),
            )

            Log.i(TAG, "Demo 扩展插件已通过 registerBuiltin() 注册")
        } catch (e: Exception) {
            Log.w(TAG, "Demo 扩展插件注册失败", e)
        }
    }

    /**
     * 预填充 plugin_manifest 表，使 Demo 插件显示在管理列表中。
     * 当用户导入真实 APK 模型时会覆盖这些 Demo 条目。
     */
    private suspend fun seedDatabase(dao: PluginManifestDao) {
        val faceSwapManifestJson = """
        {
            "pluginId": "demo.face_swap",
            "name": "Face Swap Demo",
            "version": "1.0.0",
            "author": "LocalAlbum Demo",
            "taskType": "GENERATIVE",
            "modelFormat": "ONNX",
            "modelFilePath": "models/inswapper_demo.onnx",
            "entryClass": "com.renyxin.localalbum.core.plugin.demo.DemoFaceSwapPlugin",
            "inputTensors": [
                {"name": "target", "dataType": "FLOAT32", "shape": [1, 3, 256, 256]},
                {"name": "source", "dataType": "FLOAT32", "shape": [1, 512]}
            ],
            "outputTensors": [
                {"name": "output", "dataType": "FLOAT32", "shape": [1, 3, 256, 256]}
            ],
            "preprocessing": {
                "resizeWidth": 256, "resizeHeight": 256,
                "normalizeMean": [0.5, 0.5, 0.5],
                "normalizeStd": [0.5, 0.5, 0.5]
            },
            "pipelineStage": "generative",
            "description": "[Demo] 像素级颜色迁移 + 边缘羽化, 无需外部模型"
        }
        """.trimIndent()

        val styleTransferManifestJson = """
        {
            "pluginId": "demo.style_transfer",
            "name": "Style Transfer Demo",
            "version": "1.0.0",
            "author": "LocalAlbum Demo",
            "taskType": "GENERATIVE",
            "modelFormat": "ONNX",
            "modelFilePath": "models/style_demo.onnx",
            "entryClass": "com.renyxin.localalbum.core.plugin.demo.DemoStyleTransferPlugin",
            "inputTensors": [
                {"name": "content", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]},
                {"name": "style", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]}
            ],
            "outputTensors": [
                {"name": "output", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]}
            ],
            "preprocessing": {
                "resizeWidth": 512, "resizeHeight": 512,
                "normalizeMean": [0.485, 0.456, 0.406],
                "normalizeStd": [0.229, 0.224, 0.225]
            },
            "pipelineStage": "generative",
            "description": "[Demo] 复古/素描/霓虹三种滤镜, 无需外部模型"
        }
        """.trimIndent()

        val now = System.currentTimeMillis()
        listOf(
            PluginManifestEntity(
                pluginId = "demo.face_swap",
                manifestJson = faceSwapManifestJson,
                isEnabled = true,
                pipelineStage = "generative",
                createdAtMs = now,
                updatedAtMs = now,
            ),
            PluginManifestEntity(
                pluginId = "demo.style_transfer",
                manifestJson = styleTransferManifestJson,
                isEnabled = true,
                pipelineStage = "generative",
                createdAtMs = now,
                updatedAtMs = now,
            ),
        ).forEach { entity ->
            val existing = dao.getByPluginId(entity.pluginId)
            if (existing == null) {
                dao.insert(entity)
                Log.i(TAG, "Demo 插件清单已写入: ${entity.pluginId}")
            }
        }
    }
}
