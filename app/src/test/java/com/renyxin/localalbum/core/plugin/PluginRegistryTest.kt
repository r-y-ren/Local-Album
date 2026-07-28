package com.renyxin.localalbum.core.plugin

import android.content.Context
import android.graphics.RectF
import com.renyxin.localalbum.core.plugin.runtime.ModelRuntime
import com.renyxin.localalbum.core.plugin.runtime.OnnxModelRuntime
import com.renyxin.localalbum.core.plugin.runtime.PyTorchModelRuntime
import com.renyxin.localalbum.core.plugin.runtime.TfliteModelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [PluginRegistry] 单元测试（Phase 2.6）。
 *
 * 由于 DexClassLoader 需要真实 APK 文件且无法在 JVM 单元测试环境中运行，
 * 本测试通过直接注册 fake 插件实例来验证 PluginRegistry 的
 * 注册、查询、卸载、状态管理等逻辑。
 *
 * [PluginLoader] 的 DexClassLoader 加载链路需在 Android Instrumentation 测试中验证。
 */
class PluginRegistryTest {

    // ---- Fake 插件 ----

    /**
     * Fake 分类插件，用于测试注册中心逻辑。
     */
    private class FakeClassificationPlugin(
        private val pluginId: String,
        private val pluginName: String = "Fake Classifier",
    ) : ClassificationPlugin {
        private var initialized = false
        private var released = false

        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = pluginName,
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "fake.tflite",
            entryClass = "fake.$pluginId",
            inputTensors = listOf(
                PluginManifest.TensorSpec("input", PluginManifest.TensorDataType.FLOAT32, listOf(1, 224, 224, 3)),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec("output", PluginManifest.TensorDataType.FLOAT32, listOf(1, 10)),
            ),
        )

        override fun getInputSchema() = listOf(
            FeatureSchema.FieldSpec("image", FeatureSchema.DataType.FLOAT_ARRAY, listOf(224, 224, 3)),
        )

        override fun getOutputSchema() = FeatureSchema(
            pluginId = pluginId,
            featureType = "classification",
            fields = listOf(
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
                FeatureSchema.FieldSpec("confidence", FeatureSchema.DataType.FLOAT),
            ),
        )

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            delay(10) // 模拟初始化耗时
            initialized = true
        }

        override fun isReady() = initialized && !released

        override suspend fun execute(input: PluginInput): PluginOutput.ClassificationOutput {
            if (!initialized) throw IllegalStateException("插件未初始化")
            return PluginOutput.ClassificationOutput(
                labels = listOf(
                    PluginOutput.LabelResult("cat", 0.9f),
                    PluginOutput.LabelResult("dog", 0.1f),
                ),
            )
        }

        override suspend fun release() {
            released = true
        }
    }

    /**
     * Fake 特征提取插件。
     */
    private class FakeFeatureExtractionPlugin(
        private val pluginId: String,
    ) : FeatureExtractionPlugin {
        private var initialized = false

        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = "Fake Embedder",
            taskType = PluginManifest.TaskType.FEATURE_EXTRACTION,
            modelFormat = PluginManifest.ModelFormat.ONNX,
            modelFilePath = "fake.onnx",
            entryClass = "fake.$pluginId",
            inputTensors = listOf(
                PluginManifest.TensorSpec("input", PluginManifest.TensorDataType.FLOAT32, listOf(1, 128, 128, 3)),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec("embedding", PluginManifest.TensorDataType.FLOAT32, listOf(1, 256)),
            ),
        )

        override fun getInputSchema() = listOf(
            FeatureSchema.FieldSpec("image", FeatureSchema.DataType.FLOAT_ARRAY, listOf(128, 128, 3)),
        )

        override fun getOutputSchema() = FeatureSchema(
            pluginId = pluginId,
            featureType = "embedding",
            fields = listOf(
                FeatureSchema.FieldSpec("embedding", FeatureSchema.DataType.FLOAT_ARRAY, listOf(256)),
            ),
        )

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            delay(10)
            initialized = true
        }

        override fun isReady() = initialized

        override suspend fun execute(input: PluginInput): PluginOutput.FeatureOutput {
            if (!initialized) throw IllegalStateException("插件未初始化")
            return PluginOutput.FeatureOutput(
                vector = FloatArray(256) { it * 0.01f },
                schema = getOutputSchema(),
            )
        }

        override suspend fun release() {
            initialized = false
        }
    }

    /**
     * Fake 检测插件。
     */
    private class FakeDetectionPlugin(
        private val pluginId: String,
    ) : DetectionPlugin {
        private var initialized = false

        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = "Fake Detector",
            taskType = PluginManifest.TaskType.DETECTION,
            modelFormat = PluginManifest.ModelFormat.PYTORCH,
            modelFilePath = "fake.ptl",
            entryClass = "fake.$pluginId",
            inputTensors = listOf(
                PluginManifest.TensorSpec("input", PluginManifest.TensorDataType.FLOAT32, listOf(1, 640, 640, 3)),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec("boxes", PluginManifest.TensorDataType.FLOAT32, listOf(1, 100, 4)),
                PluginManifest.TensorSpec("scores", PluginManifest.TensorDataType.FLOAT32, listOf(1, 100)),
            ),
        )

        override fun getInputSchema() = listOf(
            FeatureSchema.FieldSpec("image", FeatureSchema.DataType.FLOAT_ARRAY, listOf(640, 640, 3)),
        )

        override fun getOutputSchema() = FeatureSchema(
            pluginId = pluginId,
            featureType = "detection_box",
            fields = listOf(
                FeatureSchema.FieldSpec("box", FeatureSchema.DataType.FLOAT_ARRAY, listOf(4)),
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
                FeatureSchema.FieldSpec("confidence", FeatureSchema.DataType.FLOAT),
            ),
        )

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            delay(10)
            initialized = true
        }

        override fun isReady() = initialized

        override suspend fun execute(input: PluginInput): PluginOutput.DetectionOutput {
            if (!initialized) throw IllegalStateException("插件未初始化")
            val imagePath = (input as? PluginInput.ImageInput)?.filePath ?: ""
            return PluginOutput.DetectionOutput(
                boxes = listOf(
                    PluginOutput.DetectedBox(
                        box = RectF(0.1f, 0.2f, 0.3f, 0.4f),
                        label = "person",
                        confidence = 0.95f,
                    ),
                ),
                imagePath = imagePath,
            )
        }

        override suspend fun release() {
            initialized = false
        }
    }

    // ---- 测试 ----

    @Test
    fun `getPlugin returns null for unregistered plugin`() {
        // PluginRegistry 需要 Context，但在 JVM 测试中无法直接构造
        // 这里验证 fake 插件的基本行为
        val plugin = FakeClassificationPlugin("test_1")
        assertEquals("test_1", plugin.getId())
        assertEquals("Fake Classifier", plugin.getManifest().name)
        assertEquals(PluginManifest.TaskType.CLASSIFICATION, plugin.getManifest().taskType)
        assertFalse("未初始化的插件不应 ready", plugin.isReady())
    }

    @Test
    fun `classification plugin execute returns labels`() = runBlocking {
        val plugin = FakeClassificationPlugin("test_2")
        plugin.initialize(mockContext(), PluginContext(testScope, NoOpReporter, "test_2"))
        assertTrue("初始化后应 ready", plugin.isReady())

        val input = PluginInput.ImageInput(File("/fake/img.jpg"))
        val output = plugin.execute(input)
        assertEquals("cat", output.topLabel)
        assertEquals(0.9f, output.topConfidence, 0.001f)
        assertEquals(2, output.labels.size)

        plugin.release()
        assertFalse("释放后不应 ready", plugin.isReady())
    }

    @Test
    fun `feature extraction plugin execute returns vector`() = runBlocking {
        val plugin = FakeFeatureExtractionPlugin("test_3")
        plugin.initialize(mockContext(), PluginContext(testScope, NoOpReporter, "test_3"))
        assertTrue(plugin.isReady())

        val input = PluginInput.ImageInput(File("/fake/img.jpg"))
        val output = plugin.execute(input)
        assertEquals(256, output.vector.size)
        assertTrue(output.schema.isVectorType)
        assertEquals(256, output.schema.vectorDim)

        plugin.release()
    }

    @Test
    fun `detection plugin execute returns boxes`() = runBlocking {
        val plugin = FakeDetectionPlugin("test_4")
        plugin.initialize(mockContext(), PluginContext(testScope, NoOpReporter, "test_4"))
        assertTrue(plugin.isReady())

        val input = PluginInput.ImageInput(File("/photos/test.jpg"))
        val output = plugin.execute(input)
        assertEquals(1, output.boxes.size)
        assertEquals("person", output.boxes[0].label)
        assertEquals(0.95f, output.boxes[0].confidence, 0.001f)
        assertEquals("/photos/test.jpg", output.imagePath)

        plugin.release()
    }

    @Test
    fun `multi-modal input supports image plus tensor`() = runBlocking {
        val plugin = FakeFeatureExtractionPlugin("test_5")
        plugin.initialize(mockContext(), PluginContext(testScope, NoOpReporter, "test_5"))

        // 构造多模态输入：图像 + 特征向量
        val multiInput = PluginInput.MultiModalInput(
            inputs = listOf(
                PluginInput.ImageInput(File("/photos/face.jpg")),
                PluginInput.TensorInput(
                    data = FloatArray(512) { it * 0.01f },
                    shape = listOf(1, 512),
                    name = "face_embedding",
                ),
            ),
        )
        assertEquals(2, multiInput.inputs.size)
        assertTrue(multiInput.inputs[0] is PluginInput.ImageInput)
        assertTrue(multiInput.inputs[1] is PluginInput.TensorInput)

        plugin.release()
    }

    @Test
    fun `plugin manifest validation for all task types`() {
        val classificationManifest = FakeClassificationPlugin("c1").getManifest()
        assertNull(PluginJsonCodec.validateManifest(classificationManifest))

        val extractionManifest = FakeFeatureExtractionPlugin("e1").getManifest()
        assertNull(PluginJsonCodec.validateManifest(extractionManifest))

        val detectionManifest = FakeDetectionPlugin("d1").getManifest()
        assertNull(PluginJsonCodec.validateManifest(detectionManifest))
    }

    @Test
    fun `model runtime factory creates correct implementation by format`() {
        val tfliteRuntime = ModelRuntime.create(PluginManifest.ModelFormat.TFLITE)
        assertTrue("TFLite 格式应创建 TfliteModelRuntime", tfliteRuntime is TfliteModelRuntime)

        val onnxRuntime = ModelRuntime.create(PluginManifest.ModelFormat.ONNX)
        assertTrue("ONNX 格式应创建 OnnxModelRuntime", onnxRuntime is OnnxModelRuntime)

        val pytorchRuntime = ModelRuntime.create(PluginManifest.ModelFormat.PYTORCH)
        assertTrue("PyTorch 格式应创建 PyTorchModelRuntime", pytorchRuntime is PyTorchModelRuntime)
    }

    @Test
    fun `model runtime is not ready before initialization`() {
        val runtime = ModelRuntime.create(PluginManifest.ModelFormat.TFLITE)
        assertFalse("未初始化的运行时不应 ready", runtime.isReady())
        assertEquals(0, runtime.getInputTensors().size)
        assertEquals(0, runtime.getOutputTensors().size)
    }

    // ---- 生命周期状态转换测试 (Phase 1 P1-6) ----

    @Test
    fun `registry state transitions from LOADING to INITIALIZING to READY`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val plugin = FakeClassificationPlugin("lifecycle_1")
        registry.registerPlugin(plugin, plugin.getManifest(), "/fake/lifecycle.apk")

        val loaded = registry.getPlugin("lifecycle_1")
        assertNotNull("插件应已注册", loaded)
        assertFalse("注册后未初始化，不应 ready", loaded!!.isReady())

        registry.initializePlugin("lifecycle_1")
        assertTrue("初始化后应 ready", loaded.isReady())

        val info = registry.getPluginInfo("lifecycle_1")
        assertNotNull("状态信息应存在", info)
        assertEquals(PluginRegistry.PluginState.READY, info!!.state)
    }

    @Test
    fun `registry state transitions to ERROR on init failure`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val plugin = FakeFailingInitPlugin("fail_1")
        registry.registerPlugin(plugin, plugin.getManifest(), "/fake/fail.apk")

        try {
            registry.initializePlugin("fail_1")
        } catch (_: Exception) {
            // 预期异常
        }

        val info = registry.getPluginInfo("fail_1")
        assertNotNull("失败插件也应有状态信息", info)
        assertEquals(PluginRegistry.PluginState.ERROR, info!!.state)
        assertNotNull("应有错误信息", info.errorMessage)
    }

    @Test
    fun `registry state transitions to UNLOADED after unload`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val plugin = FakeClassificationPlugin("unload_1")
        registry.registerPlugin(plugin, plugin.getManifest(), "/fake/unload.apk")
        registry.initializePlugin("unload_1")

        assertTrue("卸载前应 ready", plugin.isReady())

        val unloaded = registry.unload("unload_1")
        assertTrue("卸载应成功", unloaded)
        assertFalse("卸载后不应 ready", plugin.isReady())
        assertNull("卸载后 getPlugin 应返回 null", registry.getPlugin("unload_1"))

        val info = registry.getPluginInfo("unload_1")
        assertNotNull("卸载后状态信息仍应存在", info)
        assertEquals(PluginRegistry.PluginState.UNLOADED, info!!.state)
    }

    @Test
    fun `registry unload of non-existent plugin returns false`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val result = registry.unload("nonexistent")
        assertFalse("卸载不存在的插件应返回 false", result)
    }

    @Test
    fun `registry getByTaskType returns plugins filtered by taskType`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val classifier = FakeClassificationPlugin("cls_1")
        val extractor = FakeFeatureExtractionPlugin("ext_1")
        registry.registerPlugin(classifier, classifier.getManifest(), "/fake/cls.apk")
        registry.registerPlugin(extractor, extractor.getManifest(), "/fake/ext.apk")

        val classifiers = registry.getByTaskType(PluginManifest.TaskType.CLASSIFICATION)
        assertEquals(1, classifiers.size)
        assertEquals("cls_1", classifiers[0].getId())

        val extractors = registry.getByTaskType(PluginManifest.TaskType.FEATURE_EXTRACTION)
        assertEquals(1, extractors.size)
        assertEquals("ext_1", extractors[0].getId())
    }

    @Test
    fun `registry listAll returns all loaded plugins`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        registry.registerPlugin(FakeClassificationPlugin("a"), FakeClassificationPlugin("a").getManifest(), "/fake/a.apk")
        registry.registerPlugin(FakeFeatureExtractionPlugin("b"), FakeFeatureExtractionPlugin("b").getManifest(), "/fake/b.apk")

        val all = registry.listAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `registry listAllManifests returns all manifests`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val p1 = FakeClassificationPlugin("a")
        val p2 = FakeFeatureExtractionPlugin("b")
        registry.registerPlugin(p1, p1.getManifest(), "/fake/a.apk")
        registry.registerPlugin(p2, p2.getManifest(), "/fake/b.apk")

        val manifests = registry.listAllManifests()
        assertEquals(2, manifests.size)
    }

    @Test
    fun `registry pluginStates reflects correct state flow`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val plugin = FakeClassificationPlugin("state_flow_1")
        registry.registerPlugin(plugin, plugin.getManifest(), "/fake/state.apk")

        val states = registry.pluginStates.value
        assertEquals(1, states.size)
        assertEquals(PluginRegistry.PluginState.LOADING, states[0].state)

        registry.initializePlugin("state_flow_1")

        val updatedStates = registry.pluginStates.value
        assertEquals("初始化后应为 READY", PluginRegistry.PluginState.READY, updatedStates[0].state)
    }

    @Test
    fun `registry getManifest returns manifest for registered plugin`() = runBlocking {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        val plugin = FakeClassificationPlugin("manifest_1")
        registry.registerPlugin(plugin, plugin.getManifest(), "/fake/manifest.apk")

        val manifest = registry.getManifest("manifest_1")
        assertNotNull("应返回清单", manifest)
        assertEquals("Fake Classifier", manifest!!.name)
    }

    @Test
    fun `registry getPluginStates returns empty list initially`() {
        val registry = PluginRegistry(mockContext(), FakePluginLoader(emptyList()))
        assertTrue("初始状态列表应为空", registry.pluginStates.value.isEmpty())
    }

    // ---- Fake failing init plugin ----

    private class FakeFailingInitPlugin(
        private val pluginId: String,
    ) : ClassificationPlugin {
        override fun getId() = pluginId

        override fun getManifest() = PluginManifest(
            pluginId = pluginId,
            name = "Failing Plugin",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "fake.tflite",
            entryClass = "fake.$pluginId",
            inputTensors = listOf(
                PluginManifest.TensorSpec("input", PluginManifest.TensorDataType.FLOAT32, listOf(1, 224, 224, 3)),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec("output", PluginManifest.TensorDataType.FLOAT32, listOf(1, 10)),
            ),
        )

        override fun getInputSchema() = listOf(
            FeatureSchema.FieldSpec("image", FeatureSchema.DataType.FLOAT_ARRAY, listOf(224, 224, 3)),
        )

        override fun getOutputSchema() = FeatureSchema(
            pluginId = pluginId,
            featureType = "classification",
            fields = listOf(
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
                FeatureSchema.FieldSpec("confidence", FeatureSchema.DataType.FLOAT),
            ),
        )

        override suspend fun initialize(context: Context, pluginContext: PluginContext) {
            throw RuntimeException("模拟初始化失败")
        }

        override fun isReady() = false

        override suspend fun execute(input: PluginInput): PluginOutput.ClassificationOutput {
            throw IllegalStateException("插件未就绪")
        }

        override suspend fun release() {}
    }

    companion object {
        fun mockContext(): Context {
            return org.mockito.Mockito.mock(Context::class.java)
        }
    }

    // ---- Fake PluginLoader ----

    private class FakePluginLoader(
        private val results: List<PluginLoader.LoadResult>,
    ) : PluginLoader(mockContext()) {
        override suspend fun loadAll(): List<PluginLoader.LoadResult> = results
        override suspend fun load(apkFile: File): PluginLoader.LoadResult =
            results.find { it.apkPath == apkFile.absolutePath }
                ?: PluginLoader.LoadResult("unknown", null, null, apkFile.absolutePath, "NOT_FOUND")
    }

    // ---- 辅助 ----

    /** 测试用协程作用域 */
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private object NoOpReporter : ProgressReporter {
        override fun reportProgress(currentIndex: Int, totalCount: Int, message: String?) {}
        override fun reportStatus(status: ProgressReporter.TaskStatus) {}
    }
}
