package com.renyxin.localalbum.core.plugin.extension

import android.content.Context
import android.graphics.Bitmap
import com.renyxin.localalbum.core.plugin.AiPlugin
import com.renyxin.localalbum.core.plugin.ClassificationPlugin
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginLoader
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ExtensionPluginRegistry] 单元测试（Phase 3 质量提升）。
 *
 * 通过 fake 插件实例验证扩展插件注册表的核心功能：
 * - 三通道注册（registerBuiltin, discoverSpiPlugins, loadAll）
 * - 插件查询与类型过滤
 * - 卸载与资源释放
 * - 状态流响应式更新
 */
class ExtensionPluginRegistryTest {

    // ---- Fake Plugins ----

    private class FakeGenerativePlugin(
        private val pluginId: String,
        private val pluginName: String = "Fake Gen",
    ) : GenerativePlugin {
        private var ready = false
        var released = false

        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = pluginName,
            taskType = PluginManifest.TaskType.GENERATIVE,
            modelFormat = PluginManifest.ModelFormat.ONNX,
            modelFilePath = "fake.onnx",
            entryClass = "fake.$pluginId",
            inputTensors = emptyList(),
            outputTensors = emptyList(),
            description = "Fake generative plugin for testing",
        )

        override fun getInputSchema() = emptyList<com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec>()
        override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(pluginId, "image", emptyList())

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            ready = true
        }

        /** 测试专用：直接置为就绪，避免依赖 Android Context */
        fun markReady() { ready = true }

        override fun isReady() = ready

        override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput =
            PluginOutput.ImageOutput(null, "", "")

        override suspend fun release() {
            ready = false
            released = true
        }
    }

    private class FakeClassificationPlugin(
        private val pluginId: String,
    ) : ClassificationPlugin {
        private var ready = false

        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = "Fake Classifier",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "fake.tflite",
            entryClass = "fake.$pluginId",
            inputTensors = emptyList(),
            outputTensors = emptyList(),
        )

        override fun getInputSchema() = emptyList<com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec>()
        override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(pluginId, "classification", emptyList())

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            ready = true
        }

        override fun isReady() = ready

        override suspend fun execute(input: PluginInput): PluginOutput.ClassificationOutput =
            PluginOutput.ClassificationOutput(emptyList())

        override suspend fun release() {
            ready = false
        }
    }

    // ---- registerBuiltin 测试 ----

    @Test
    fun `registerBuiltin adds plugin to state flow`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.gen")

        registry.registerBuiltin(plugin, plugin.getManifest())

        val states = registry.pluginStates.value
        assertEquals(1, states.size)
        assertEquals("test.gen", states[0].pluginId)
        assertEquals("Fake Gen", states[0].name)
    }

    @Test
    fun `registerBuiltin updates generative plugins flow`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.gen_v2", "Gen V2")

        registry.registerBuiltin(plugin, plugin.getManifest())

        assertEquals(1, registry.generativePlugins.value.size)
        assertEquals("test.gen_v2", registry.generativePlugins.value[0].getId())
    }

    @Test
    fun `registerBuiltin updates classification plugins flow`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeClassificationPlugin("test.cls")

        registry.registerBuiltin(plugin, plugin.getManifest())

        assertEquals(1, registry.classificationPlugins.value.size)
        assertEquals("test.cls", registry.classificationPlugins.value[0].getId())
    }

    // ---- Query 测试 ----

    @Test
    fun `getPlugin returns registered plugin`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.query")
        registry.registerBuiltin(plugin, plugin.getManifest())

        val found = registry.getPlugin("test.query")
        assertNotNull(found)
        assertEquals("test.query", found?.getId())
    }

    @Test
    fun `getPlugin returns null for unknown id`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        assertNull(registry.getPlugin("nonexistent"))
    }

    @Test
    fun `getManifest returns manifest`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.manifest")
        registry.registerBuiltin(plugin, plugin.getManifest())

        val manifest = registry.getManifest("test.manifest")
        assertNotNull(manifest)
        assertEquals("Fake Gen", manifest?.name)
    }

    @Test
    fun `getReadyGenerativePlugins filters by ready state`() = runBlocking {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.ready")

        // 未初始化时不应出现在就绪列表中
        registry.registerBuiltin(plugin, plugin.getManifest())
        assertEquals(0, registry.getReadyGenerativePlugins().size)

        // 初始化后应出现（测试中无 Android Context，直接置就绪态）
        plugin.markReady()
        assertEquals(1, registry.getReadyGenerativePlugins().size)
    }

    // ---- 卸载测试 ----

    @Test
    fun `unload removes plugin and calls release`() = runBlocking {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin = FakeGenerativePlugin("test.unload")
        registry.registerBuiltin(plugin, plugin.getManifest())

        val result = registry.unload("test.unload")
        assertTrue(result)
        assertTrue(plugin.released)
        assertNull(registry.getPlugin("test.unload"))
        assertEquals(0, registry.pluginStates.value.size)
    }

    @Test
    fun `unload unknown plugin returns false`() = runBlocking {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        assertFalse(registry.unload("nonexistent"))
    }

    // ---- discoverSpiPlugins 测试 ----

    @Test
    fun `discoverSpiPlugins returns empty list`() = runBlocking {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugins = registry.discoverSpiPlugins()
        assertTrue(plugins.isEmpty())
    }

    // ---- 状态流响应式更新测试 ----

    @Test
    fun `pluginStates reflects additions and removals`() {
        val registry = ExtensionPluginRegistry(FakePluginLoader())
        val plugin1 = FakeGenerativePlugin("test.state1", "State 1")
        val plugin2 = FakeClassificationPlugin("test.state2")

        registry.registerBuiltin(plugin1, plugin1.getManifest())
        assertEquals(1, registry.pluginStates.value.size)

        registry.registerBuiltin(plugin2, plugin2.getManifest())
        assertEquals(2, registry.pluginStates.value.size)

        runBlocking { registry.unload("test.state1") }
        assertEquals(1, registry.pluginStates.value.size)
        assertEquals("test.state2", registry.pluginStates.value[0].pluginId)
    }

    // ---- Fake PluginLoader ----

    /**
     * Fake PluginLoader 用于单元测试，不执行真正的 DexClassLoader 加载。
     */
    private class FakePluginLoader : PluginLoader(null as Context?) {
        override suspend fun loadAll(): List<PluginLoader.LoadResult> = emptyList()
    }
}
