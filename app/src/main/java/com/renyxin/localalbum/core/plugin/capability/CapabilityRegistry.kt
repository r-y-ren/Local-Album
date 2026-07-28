package com.renyxin.localalbum.core.plugin.capability

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 核心分析能力注册表（Phase 1 重构）。
 *
 * **已废弃** — 请使用 [CapabilityRegistryV2] 替代。
 * 此版本保留仅为向后兼容，新代码应使用泛型化的 V2 版本。
 *
 * 管理 5 个可替换的能力槽位：人脸检测、场景分类、语义嵌入、质量评估、OCR。
 * 每个槽位可注册多个 Provider，但同一时刻只有一个"激活"的 Provider。
 *
 * ## 槽位表
 *
 * | 槽位 ID   | Provider 接口           | 用途                     |
 * |-----------|------------------------|--------------------------|
 * | face      | [FaceProvider]         | 人脸检测 + 嵌入提取      |
 * | scene     | [SceneProvider]        | 场景分类                 |
 * | semantic  | [SemanticEmbedProvider]| 跨模态语义嵌入           |
 * | quality   | [QualityProvider]      | 图像质量评分             |
 * | ocr       | [OcrProvider]          | 文字识别                 |
 *
 * @deprecated 使用 [CapabilityRegistryV2] 替代，提供泛型槽位管理和更好的响应式支持
 */
@Deprecated(
    message = "使用 CapabilityRegistryV2 替代",
    replaceWith = ReplaceWith("CapabilityRegistryV2", "com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2"),
)
class CapabilityRegistry {

    companion object {
        private const val TAG = "CapabilityRegistry"
    }

    /** shutdown 并发保护 Mutex */
    private val shutdownMutex = Mutex()

    // ---- Face Slot ----

    private val _faceProviders = mutableMapOf<String, FaceProvider>()
    private var _activeFaceId: String = ""
    private val _faceSlotState = MutableStateFlow(CapabilitySlotState<FaceProvider>())
    val faceSlot: StateFlow<CapabilitySlotState<FaceProvider>> = _faceSlotState.asStateFlow()

    fun registerFaceProvider(provider: FaceProvider, isDefault: Boolean = false) {
        _faceProviders[provider.providerId] = provider
        if (isDefault || _activeFaceId.isEmpty()) {
            _activeFaceId = provider.providerId
        }
        emitFaceSlot()
    }

    fun activateFaceProvider(providerId: String): Boolean {
        if (providerId !in _faceProviders) {
            Log.w(TAG, "Face provider '$providerId' 未注册")
            return false
        }
        _activeFaceId = providerId
        emitFaceSlot()
        Log.i(TAG, "激活 Face Provider: $providerId")
        return true
    }

    fun getActiveFaceProvider(): FaceProvider? = _faceProviders[_activeFaceId]

    // ---- Scene Slot ----

    private val _sceneProviders = mutableMapOf<String, SceneProvider>()
    private var _activeSceneId: String = ""
    private val _sceneSlotState = MutableStateFlow(CapabilitySlotState<SceneProvider>())
    val sceneSlot: StateFlow<CapabilitySlotState<SceneProvider>> = _sceneSlotState.asStateFlow()

    fun registerSceneProvider(provider: SceneProvider, isDefault: Boolean = false) {
        _sceneProviders[provider.providerId] = provider
        if (isDefault || _activeSceneId.isEmpty()) {
            _activeSceneId = provider.providerId
        }
        emitSceneSlot()
    }

    fun activateSceneProvider(providerId: String): Boolean {
        if (providerId !in _sceneProviders) {
            Log.w(TAG, "Scene provider '$providerId' 未注册")
            return false
        }
        _activeSceneId = providerId
        emitSceneSlot()
        Log.i(TAG, "激活 Scene Provider: $providerId")
        return true
    }

    fun getActiveSceneProvider(): SceneProvider? = _sceneProviders[_activeSceneId]

    // ---- Semantic Slot ----

    private val _semanticProviders = mutableMapOf<String, SemanticEmbedProvider>()
    private var _activeSemanticId: String = ""
    private val _semanticSlotState = MutableStateFlow(CapabilitySlotState<SemanticEmbedProvider>())
    val semanticSlot: StateFlow<CapabilitySlotState<SemanticEmbedProvider>> = _semanticSlotState.asStateFlow()

    fun registerSemanticProvider(provider: SemanticEmbedProvider, isDefault: Boolean = false) {
        _semanticProviders[provider.providerId] = provider
        if (isDefault || _activeSemanticId.isEmpty()) {
            _activeSemanticId = provider.providerId
        }
        emitSemanticSlot()
    }

    fun activateSemanticProvider(providerId: String): Boolean {
        if (providerId !in _semanticProviders) {
            Log.w(TAG, "Semantic provider '$providerId' 未注册")
            return false
        }
        _activeSemanticId = providerId
        emitSemanticSlot()
        Log.i(TAG, "激活 Semantic Provider: $providerId")
        return true
    }

    fun getActiveSemanticProvider(): SemanticEmbedProvider? = _semanticProviders[_activeSemanticId]

    // ---- Quality Slot ----

    private val _qualityProviders = mutableMapOf<String, QualityProvider>()
    private var _activeQualityId: String = ""
    private val _qualitySlotState = MutableStateFlow(CapabilitySlotState<QualityProvider>())
    val qualitySlot: StateFlow<CapabilitySlotState<QualityProvider>> = _qualitySlotState.asStateFlow()

    fun registerQualityProvider(provider: QualityProvider, isDefault: Boolean = false) {
        _qualityProviders[provider.providerId] = provider
        if (isDefault || _activeQualityId.isEmpty()) {
            _activeQualityId = provider.providerId
        }
        emitQualitySlot()
    }

    fun activateQualityProvider(providerId: String): Boolean {
        if (providerId !in _qualityProviders) {
            Log.w(TAG, "Quality provider '$providerId' 未注册")
            return false
        }
        _activeQualityId = providerId
        emitQualitySlot()
        Log.i(TAG, "激活 Quality Provider: $providerId")
        return true
    }

    fun getActiveQualityProvider(): QualityProvider? = _qualityProviders[_activeQualityId]

    // ---- OCR Slot ----

    private val _ocrProviders = mutableMapOf<String, OcrProvider>()
    private var _activeOcrId: String = ""
    private val _ocrSlotState = MutableStateFlow(CapabilitySlotState<OcrProvider>())
    val ocrSlot: StateFlow<CapabilitySlotState<OcrProvider>> = _ocrSlotState.asStateFlow()

    fun registerOcrProvider(provider: OcrProvider, isDefault: Boolean = false) {
        _ocrProviders[provider.providerId] = provider
        if (isDefault || _activeOcrId.isEmpty()) {
            _activeOcrId = provider.providerId
        }
        emitOcrSlot()
    }

    fun activateOcrProvider(providerId: String): Boolean {
        if (providerId !in _ocrProviders) {
            Log.w(TAG, "OCR provider '$providerId' 未注册")
            return false
        }
        _activeOcrId = providerId
        emitOcrSlot()
        Log.i(TAG, "激活 OCR Provider: $providerId")
        return true
    }

    fun getActiveOcrProvider(): OcrProvider? = _ocrProviders[_activeOcrId]

    // ---- Global Queries ----

    /** 列出所有已注册的 Provider（跨所有槽位）。 */
    fun listAllProviders(): Map<String, List<ProviderMeta>> {
        return mapOf(
            "face" to _faceProviders.map { ProviderMeta(it.key, it.value.displayName, it.key == _activeFaceId) },
            "scene" to _sceneProviders.map { ProviderMeta(it.key, it.value.displayName, it.key == _activeSceneId) },
            "semantic" to _semanticProviders.map { ProviderMeta(it.key, it.value.displayName, it.key == _activeSemanticId) },
            "quality" to _qualityProviders.map { ProviderMeta(it.key, it.value.displayName, it.key == _activeQualityId) },
            "ocr" to _ocrProviders.map { ProviderMeta(it.key, it.value.displayName, it.key == _activeOcrId) },
        )
    }

    /** 释放所有已注册 Provider 的资源。 */
    suspend fun shutdown() {
        shutdownMutex.withLock {
            Log.i(TAG, "关闭能力注册表，释放所有 Provider 资源...")
            for ((_, provider) in _faceProviders) {
                try { provider.release() } catch (_: Exception) {}
            }
            for ((_, provider) in _sceneProviders) {
                try { provider.release() } catch (_: Exception) {}
            }
            for ((_, provider) in _semanticProviders) {
                try { provider.release() } catch (_: Exception) {}
            }
            for ((_, provider) in _qualityProviders) {
                try { provider.release() } catch (_: Exception) {}
            }
            for ((_, provider) in _ocrProviders) {
                try { provider.release() } catch (_: Exception) {}
            }
            _faceProviders.clear()
            _sceneProviders.clear()
            _semanticProviders.clear()
            _qualityProviders.clear()
            _ocrProviders.clear()
        }
    }

    // ---- Internal ----

    private fun emitFaceSlot() {
        _faceSlotState.value = CapabilitySlotState(
            slotId = "face",
            slotName = "人脸检测",
            activeProviderId = _activeFaceId,
            providers = _faceProviders.values.toList(),
        )
    }

    private fun emitSceneSlot() {
        _sceneSlotState.value = CapabilitySlotState(
            slotId = "scene",
            slotName = "场景分类",
            activeProviderId = _activeSceneId,
            providers = _sceneProviders.values.toList(),
        )
    }

    private fun emitSemanticSlot() {
        _semanticSlotState.value = CapabilitySlotState(
            slotId = "semantic",
            slotName = "语义嵌入",
            activeProviderId = _activeSemanticId,
            providers = _semanticProviders.values.toList(),
        )
    }

    private fun emitQualitySlot() {
        _qualitySlotState.value = CapabilitySlotState(
            slotId = "quality",
            slotName = "质量评估",
            activeProviderId = _activeQualityId,
            providers = _qualityProviders.values.toList(),
        )
    }

    private fun emitOcrSlot() {
        _ocrSlotState.value = CapabilitySlotState(
            slotId = "ocr",
            slotName = "文字识别 (OCR)",
            activeProviderId = _activeOcrId,
            providers = _ocrProviders.values.toList(),
        )
    }
}

/**
 * 单个能力槽位的状态快照。
 *
 * @param T Provider 类型
 * @property slotId 槽位标识
 * @property slotName 槽位显示名称
 * @property activeProviderId 当前激活的 Provider ID
 * @property providers 所有已注册的 Provider 列表
 */
data class CapabilitySlotState<T>(
    val slotId: String = "",
    val slotName: String = "",
    val activeProviderId: String = "",
    val providers: List<T> = emptyList(),
)

/**
 * Provider 元信息（用于 UI 列表展示）。
 *
 * @property providerId Provider 标识
 * @property displayName 显示名称
 * @property isActive 是否为当前激活项
 */
data class ProviderMeta(
    val providerId: String,
    val displayName: String,
    val isActive: Boolean,
)
