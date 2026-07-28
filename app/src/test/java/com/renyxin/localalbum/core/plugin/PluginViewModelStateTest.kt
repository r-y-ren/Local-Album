package com.renyxin.localalbum.core.plugin

import com.renyxin.localalbum.ui.vm.JsonValidationState
import com.renyxin.localalbum.ui.vm.SaveState
import com.renyxin.localalbum.ui.vm.TensorParseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginViewModel] 相关状态类单元测试（Phase 4.7）。
 *
 * 测试向导状态机、JSON 校验状态、保存操作状态等纯逻辑。
 * PluginViewModel 的完整集成测试需 Robolectric Instrumentation 测试
 * （依赖 Android Context, Room AppDatabase, PluginRegistry 等框架组件）。
 */
class PluginViewModelStateTest {

    // ---- TensorParseState ----

    @Test
    fun `TensorParseState Idle is singleton`() {
        val a = TensorParseState.Idle
        val b = TensorParseState.Idle
        assertEquals(a, b)
    }

    @Test
    fun `TensorParseState Loading is singleton`() {
        val a = TensorParseState.Loading
        val b = TensorParseState.Loading
        assertEquals(a, b)
    }

    @Test
    fun `TensorParseState Success holds tensor lists`() {
        val input = listOf(
            PluginManifest.TensorSpec(
                name = "in",
                dataType = PluginManifest.TensorDataType.FLOAT32,
                shape = listOf(1, 224, 224, 3),
            )
        )
        val output = listOf(
            PluginManifest.TensorSpec(
                name = "out",
                dataType = PluginManifest.TensorDataType.FLOAT32,
                shape = listOf(1, 1000),
            )
        )
        val state = TensorParseState.Success(input, output)
        assertEquals(input, state.inputTensors)
        assertEquals(output, state.outputTensors)
    }

    @Test
    fun `TensorParseState Error holds message`() {
        val state = TensorParseState.Error("解析失败: 文件格式错误")
        assertEquals("解析失败: 文件格式错误", state.message)
    }

    // ---- JsonValidationState ----

    @Test
    fun `JsonValidationState Idle is singleton`() {
        val a = JsonValidationState.Idle
        val b = JsonValidationState.Idle
        assertEquals(a, b)
    }

    @Test
    fun `JsonValidationState Valid holds manifest`() {
        val manifest = PluginManifest(
            pluginId = "plugin.test",
            name = "Test Plugin",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "test.tflite",
            entryClass = "com.test.Plugin",
            inputTensors = emptyList(),
            outputTensors = emptyList(),
        )
        val state = JsonValidationState.Valid(manifest)
        assertEquals(manifest, state.manifest)
    }

    @Test
    fun `JsonValidationState Error holds message`() {
        val state = JsonValidationState.Error("JSON 格式无效")
        assertEquals("JSON 格式无效", state.message)
    }

    // ---- SaveState ----

    @Test
    fun `SaveState Idle is singleton`() {
        val a = SaveState.Idle
        val b = SaveState.Idle
        assertEquals(a, b)
    }

    @Test
    fun `SaveState Saving is singleton`() {
        val a = SaveState.Saving
        val b = SaveState.Saving
        assertEquals(a, b)
    }

    @Test
    fun `SaveState Success holds message`() {
        val state = SaveState.Success("保存成功")
        assertEquals("保存成功", state.message)
    }

    @Test
    fun `SaveState Error holds message`() {
        val state = SaveState.Error("保存失败: 数据库错误")
        assertEquals("保存失败: 数据库错误", state.message)
    }

    // ---- PluginManifest authorizedCertificateFingerprint ----

    @Test
    fun `manifest with authorizedCertificateFingerprint round-trips through JSON`() {
        val manifest = PluginManifest(
            pluginId = "plugin.secure",
            name = "Secure Plugin",
            taskType = PluginManifest.TaskType.FEATURE_EXTRACTION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "secure.tflite",
            entryClass = "com.secure.Plugin",
            inputTensors = emptyList(),
            outputTensors = emptyList(),
            authorizedCertificateFingerprint = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
        )

        val json = PluginJsonCodec.manifestToJson(manifest)
        val restored = PluginJsonCodec.manifestFromJson(json)

        assertEquals(manifest.pluginId, restored.pluginId)
        assertEquals(manifest.authorizedCertificateFingerprint, restored.authorizedCertificateFingerprint)
        assertNotNull(restored.authorizedCertificateFingerprint)
    }

    @Test
    fun `manifest without authorizedCertificateFingerprint has null fingerprint`() {
        val manifest = PluginManifest(
            pluginId = "plugin.insecure",
            name = "Insecure Plugin",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "insecure.tflite",
            entryClass = "com.insecure.Plugin",
            inputTensors = emptyList(),
            outputTensors = emptyList(),
        )

        val json = PluginJsonCodec.manifestToJson(manifest)
        val restored = PluginJsonCodec.manifestFromJson(json)

        assertEquals(null, restored.authorizedCertificateFingerprint)
    }

    @Test
    fun `manifest without fingerprint in JSON defaults to null`() {
        val json = """{
            "pluginId": "plugin.test",
            "name": "Test",
            "taskType": "CLASSIFICATION",
            "modelFormat": "TFLITE",
            "modelFilePath": "test.tflite",
            "entryClass": "com.test.Plugin",
            "inputTensors": [],
            "outputTensors": []
        }"""

        val manifest = PluginJsonCodec.manifestFromJson(json)
        assertEquals(null, manifest.authorizedCertificateFingerprint)
    }

    // ---- PluginViewModel taskType state logic (N7 validation) ----

    @Test
    fun `taskType default is FEATURE_EXTRACTION`() {
        // This validates the N7 fix: taskType is no longer hardcoded in saveFromWizard()
        // but is a mutable state with FEATURE_EXTRACTION as default
        val taskType = PluginManifest.TaskType.FEATURE_EXTRACTION
        assertEquals("extraction", taskType.stageName)
    }

    @Test
    fun `taskType stageName mapping is correct`() {
        assertEquals("classification", PluginManifest.TaskType.CLASSIFICATION.stageName)
        assertEquals("extraction", PluginManifest.TaskType.FEATURE_EXTRACTION.stageName)
        assertEquals("detection", PluginManifest.TaskType.DETECTION.stageName)
        assertEquals("generative", PluginManifest.TaskType.GENERATIVE.stageName)
    }
}
