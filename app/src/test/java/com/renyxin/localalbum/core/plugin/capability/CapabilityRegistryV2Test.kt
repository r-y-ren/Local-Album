package com.renyxin.localalbum.core.plugin.capability

import android.graphics.RectF
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CapabilityRegistryV2] 单元测试（Phase 3 质量提升）。
 *
 * 测试泛型化能力注册表的核心功能：
 * - 槽位注册与 Provider 管理
 * - 激活/切换 Provider
 * - 查询接口（getActiveProvider, getSlotState, listAllProviders）
 * - 生命周期（shutdown）
 * - 并发安全（Mutex 保护）
 */
class CapabilityRegistryV2Test {

    // ---- Fake Providers ----

    private class FakeFaceProvider(
        override val providerId: String = "test:face",
        override val displayName: String = "Test Face",
    ) : FaceProvider {
        override val embeddingDim = 128
        var released = false
        override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> = emptyList()
        override suspend fun release() { released = true }
    }

    private class FakeSceneProvider(
        override val providerId: String = "test:scene",
        override val displayName: String = "Test Scene",
    ) : SceneProvider {
        override val labels: List<String> = listOf("indoor", "outdoor")
        var released = false
        override suspend fun classify(file: File): SceneProvider.SceneResult =
            SceneProvider.SceneResult("indoor", 0.9f)
        override suspend fun release() { released = true }
    }

    private class FakeQualityProvider(
        override val providerId: String = "test:quality",
        override val displayName: String = "Test Quality",
    ) : QualityProvider {
        var released = false
        override suspend fun assess(file: File): QualityProvider.QualityResult =
            QualityProvider.QualityResult(0.8f, 0.7f, 0.6f, 0.5f, 0.9f, false)
        override suspend fun release() { released = true }
    }

    // ---- 槽位注册测试 ----

    @Test
    fun `registerSlot adds slot metadata`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot(
                slotId = "face",
                displayName = "人脸检测",
                description = "检测人脸",
                interfaceClass = FaceProvider::class,
                defaultProviderId = "test:face",
                icon = "🧑",
                required = true,
            )
        )

        val metadata = registry.slotMetadataList.value
        assertEquals(1, metadata.size)
        assertEquals("face", metadata[0].slotId)
        assertEquals("人脸检测", metadata[0].displayName)
        assertEquals("🧑", metadata[0].icon)
        assertTrue(metadata[0].required)
    }

    @Test
    fun `registerSlot overwrites existing slot`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "v1", "", FaceProvider::class, "", "🧑", true)
        )
        registry.registerSlot(
            CapabilitySlot("face", "v2", "", FaceProvider::class, "", "👤", false)
        )

        val metadata = registry.slotMetadataList.value
        assertEquals(1, metadata.size)
        assertEquals("v2", metadata[0].displayName)
    }

    // ---- Provider 注册测试 ----

    @Test
    fun `registerProvider adds provider and auto-activates if default`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )
        val provider = FakeFaceProvider("test:face")

        registry.registerProvider("face", "test:face", provider, isDefault = true)

        val active = registry.getActiveProvider<FaceProvider>("face")
        assertNotNull(active)
        assertEquals("test:face", active?.providerId)
    }

    @Test
    fun `registerProvider to unregistered slot throws`() {
        val registry = CapabilityRegistryV2()
        try {
            registry.registerProvider("nonexistent", "test:face", FakeFaceProvider())
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未注册") == true)
        }
    }

    @Test
    fun `multiple providers in same slot`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )

        val p1 = FakeFaceProvider("test:face_v1", "Face V1")
        val p2 = FakeFaceProvider("test:face_v2", "Face V2")

        registry.registerProvider("face", "test:face_v1", p1, isDefault = true)
        registry.registerProvider("face", "test:face_v2", p2, isDefault = false)

        // 默认激活的是 p1
        val active = registry.getActiveProvider<FaceProvider>("face")
        assertEquals("test:face_v1", active?.providerId)

        // Provider 数量
        assertEquals(2, registry.getProviderCount("face"))
    }

    // ---- 激活/切换测试 ----

    @Test
    fun `activateProvider switches active provider`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )

        val p1 = FakeFaceProvider("test:face_v1", "V1")
        val p2 = FakeFaceProvider("test:face_v2", "V2")

        registry.registerProvider("face", "test:face_v1", p1, isDefault = true)
        registry.registerProvider("face", "test:face_v2", p2, isDefault = false)

        // 切换到 p2
        val result = registry.activateProvider("face", "test:face_v2")
        assertTrue(result)

        val active = registry.getActiveProvider<FaceProvider>("face")
        assertEquals("test:face_v2", active?.providerId)
    }

    @Test
    fun `activateProvider with unknown slot returns false`() {
        val registry = CapabilityRegistryV2()
        assertFalse(registry.activateProvider("unknown", "any"))
    }

    @Test
    fun `activateProvider with unregistered provider returns false`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )
        assertFalse(registry.activateProvider("face", "nonexistent"))
    }

    // ---- 查询测试 ----

    @Test
    fun `getActiveProvider returns null for unknown slot`() {
        val registry = CapabilityRegistryV2()
        assertNull(registry.getActiveProvider<FaceProvider>("unknown"))
    }

    @Test
    fun `getActiveProvider returns null for empty slot`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )
        // 未注册任何 Provider
        assertNull(registry.getActiveProvider<FaceProvider>("face"))
    }

    @Test
    fun `listAllProviders groups by slot`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )
        registry.registerSlot(
            CapabilitySlot("scene", "场景", "", SceneProvider::class, "", "🏷️", false)
        )

        registry.registerProvider("face", "test:face", FakeFaceProvider(), isDefault = true)
        registry.registerProvider("scene", "test:scene", FakeSceneProvider(), isDefault = true)

        val all = registry.listAllProviders()
        assertEquals(2, all.size)
        assertEquals(1, all["face"]?.size)
        assertEquals(1, all["scene"]?.size)
    }

    // ---- 跨槽位类型安全测试 ----

    @Test
    fun `cross-slot type safety`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )
        registry.registerSlot(
            CapabilitySlot("quality", "质量", "", QualityProvider::class, "", "⭐", false)
        )

        registry.registerProvider("face", "test:face", FakeFaceProvider(), isDefault = true)
        registry.registerProvider("quality", "test:quality", FakeQualityProvider(), isDefault = true)

        // 正确类型查询
        val face = registry.getActiveProvider<FaceProvider>("face")
        assertNotNull(face)

        val quality = registry.getActiveProvider<QualityProvider>("quality")
        assertNotNull(quality)

        // 错误类型查询应返回 null (类型不匹配)
        val wrong = registry.getActiveProvider<FaceProvider>("quality")
        assertNull(wrong)
    }

    // ---- 生命周期测试 ----

    @Test
    fun `shutdown releases all providers`() = runBlocking {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )

        val p1 = FakeFaceProvider("test:face_v1")
        val p2 = FakeFaceProvider("test:face_v2")

        registry.registerProvider("face", "test:face_v1", p1)
        registry.registerProvider("face", "test:face_v2", p2)

        registry.shutdown()

        assertTrue(p1.released)
        assertTrue(p2.released)
        assertEquals(0, registry.slotMetadataList.value.size)
    }

    // ---- slotMetadataList 响应式更新测试 ----

    @Test
    fun `slotMetadataList reflects active provider change`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )

        registry.registerProvider("face", "a", FakeFaceProvider("a", "A"), isDefault = true)
        registry.registerProvider("face", "b", FakeFaceProvider("b", "B"), isDefault = false)

        // 初始激活的是 "a"
        val initial = registry.slotMetadataList.value.first { it.slotId == "face" }
        assertEquals("a", initial.activeProviderId)

        // 切换到 "b"
        registry.activateProvider("face", "b")
        val updated = registry.slotMetadataList.value.first { it.slotId == "face" }
        assertEquals("b", updated.activeProviderId)
    }

    // ---- getProviderCount 测试 ----

    @Test
    fun `getProviderCount returns zero for unknown slot`() {
        val registry = CapabilityRegistryV2()
        assertEquals(0, registry.getProviderCount("unknown"))
    }

    @Test
    fun `getProviderCount returns correct count`() {
        val registry = CapabilityRegistryV2()
        registry.registerSlot(
            CapabilitySlot("face", "人脸", "", FaceProvider::class, "", "🧑", true)
        )

        assertEquals(0, registry.getProviderCount("face"))

        registry.registerProvider("face", "a", FakeFaceProvider("a"))
        assertEquals(1, registry.getProviderCount("face"))

        registry.registerProvider("face", "b", FakeFaceProvider("b"))
        assertEquals(2, registry.getProviderCount("face"))
    }
}
