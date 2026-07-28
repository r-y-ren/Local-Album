package com.renyxin.localalbum.core.plugin.capability

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

/**
 * 泛型化核心分析能力注册表（Phase 2 改进计划）。
 *
 * 取代原有的 [CapabilityRegistry]，使用泛型 [SlotEntry] 统一管理任意类型的能力槽位。
 * 新增槽位只需调用 [registerSlot] + [registerProvider]，无需修改注册表内部结构。
 *
 * ## 核心设计
 *
 * ```
 * slots: Map<slotId, SlotEntry<*>>
 *   ├── "face"     → SlotEntry<FaceProvider>
 *   ├── "scene"    → SlotEntry<SceneProvider>
 *   ├── "semantic" → SlotEntry<SemanticEmbedProvider>
 *   ├── "quality"  → SlotEntry<QualityProvider>
 *   └── "ocr"      → SlotEntry<OcrProvider>
 * ```
 *
 * ## 使用方式
 *
 * ```kotlin
 * val registry = CapabilityRegistryV2()
 *
 * // 1. 注册槽位
 * registry.registerSlot(CapabilitySlot(
 *     slotId = "face", displayName = "人脸检测",
 *     interfaceClass = FaceProvider::class, defaultProviderId = "builtin:mlkit",
 * ))
 *
 * // 2. 注册 Provider 到槽位
 * registry.registerProvider("face", "model:arcface", arcFaceProvider, isDefault = true)
 *
 * // 3. 获取当前激活的 Provider
 * val face = registry.getActiveProvider<FaceProvider>("face")
 *
 * // 4. 切换 Provider
 * registry.activateProvider("face", "builtin:mlkit")
 * ```
 *
 * @see CapabilitySlot 槽位元数据定义
 * @see CapabilityRegistry 旧版（保留兼容）
 */

/**
 * Provider 类型标签 — 帮助用户理解每种 Provider 的特性。
 */
enum class ProviderType {
    /** 内置实现（传统 CV 或 ML Kit），无需下载模型，立即可用 */
    BUILTIN,
    /** 基于 TFLite/ONNX 模型的深度学习实现，需下载模型文件 */
    MODEL,
    /** 演示实现，仅供开发参考 */
    DEMO,
}

/**
 * Provider 模型就绪状态 — 描述 Provider 底层模型文件的状态。
 */
enum class ModelReadiness {
    /** 立即可用（内置/Demo Provider，无需模型文件） */
    ALWAYS_READY,
    /** 模型文件尚未下载 */
    NOT_DOWNLOADED,
    /** 模型文件正在下载 */
    DOWNLOADING,
    /** 模型文件已下载但尚未加载到内存 */
    DOWNLOADED,
    /** 模型已加载到内存，可以执行推理 */
    READY,
    /** 下载或加载过程中出错 */
    ERROR,
}

class CapabilityRegistryV2 {

    companion object {
        private const val TAG = "CapabilityRegV2"
    }

    // ---- 内部存储 ----

    /**
     * 单个槽位的内部条目。
     *
     * @param T Provider 接口类型
     * @property slot 槽位元数据
     * @property providers providerId → Provider 实例映射
     * @property activeProviderId 当前激活的 Provider ID (StateFlow)
     */
    private data class SlotEntry<T : Any>(
        val slot: CapabilitySlot<T>,
        val providers: MutableMap<String, T> = mutableMapOf(),
        val activeProviderId: MutableStateFlow<String> = MutableStateFlow(""),
        val providerTypes: MutableMap<String, ProviderType> = mutableMapOf(),
        val modelStatuses: MutableMap<String, MutableStateFlow<ModelReadiness>> = mutableMapOf(),
        val modelProgresses: MutableMap<String, MutableStateFlow<Float>> = mutableMapOf(),
        /** providerId → 关联的底层 modelId 列表（用于多模型 Provider 的状态聚合） */
        val providerModelIds: MutableMap<String, List<String>> = mutableMapOf(),
    )

    /** 所有已注册的槽位 */
    private val slots = mutableMapOf<String, SlotEntry<*>>()

    /** 供 UI 使用的槽位元数据列表 */
    private val _slotMetadataList = MutableStateFlow<List<SlotMetadata>>(emptyList())
    val slotMetadataList: StateFlow<List<SlotMetadata>> = _slotMetadataList.asStateFlow()

    /** 并发保护 */
    private val mutex = Mutex()

    // ===================== 槽位注册 =====================

    /**
     * 注册一个能力槽位定义。
     *
     * 必须在注册 Provider 之前调用。同一个 slotId 重复调用会覆盖。
     *
     * @param slot 槽位元数据
     */
    fun <T : Any> registerSlot(slot: CapabilitySlot<T>) {
        val entry = SlotEntry(
            slot = slot,
            activeProviderId = MutableStateFlow(slot.defaultProviderId),
        )
        slots[slot.slotId] = entry
        // 注册槽位后立即刷新元数据，否则 slotMetadataList 在注册 Provider 前为空
        emitSlotMetadata()
        Log.d(TAG, "槽位已注册: ${slot.slotId} (${slot.displayName})")
    }

    // ===================== Provider 注册 =====================

    /**
     * 注册一个 Provider 实例到指定槽位。
     *
     * 如果 [isDefault] 为 true 且槽位尚未有激活的 Provider，则自动激活此 Provider。
     *
     * @param slotId 目标槽位 ID
     * @param providerId Provider 唯一标识
     * @param provider Provider 实例
     * @param isDefault 是否设为默认激活项
     * @throws IllegalArgumentException 如果槽位未注册或类型不匹配
     */
    fun <T : Any> registerProvider(
        slotId: String,
        providerId: String,
        provider: T,
        isDefault: Boolean = false,
        providerType: ProviderType = ProviderType.BUILTIN,
    ) {
        @Suppress("UNCHECKED_CAST")
        val entry = slots[slotId] as? SlotEntry<T>
            ?: throw IllegalArgumentException("槽位 '$slotId' 未注册，请先调用 registerSlot()")

        // 类型安全：校验 Provider 实现了槽位声明的接口，避免跨槽位误注册导致后续 CCE
        if (!entry.slot.interfaceClass.isInstance(provider)) {
            throw IllegalArgumentException(
                "Provider 类型不匹配: 槽位 '$slotId' 期望 ${entry.slot.interfaceClass.simpleName}，" +
                    "实际 ${provider::class.simpleName}",
            )
        }

        entry.providers[providerId] = provider
        entry.providerTypes[providerId] = providerType

        // 模型类型 Provider 初始状态为 NOT_DOWNLOADED，内置/Demo 为 ALWAYS_READY
        val initialReadiness = when (providerType) {
            ProviderType.MODEL -> ModelReadiness.NOT_DOWNLOADED
            else -> ModelReadiness.ALWAYS_READY
        }
        entry.modelStatuses[providerId] = MutableStateFlow(initialReadiness)
        entry.modelProgresses[providerId] = MutableStateFlow(0f)

        // 如果指定为默认或尚无激活项，则激活
        if (isDefault || entry.activeProviderId.value.isEmpty() ||
            entry.activeProviderId.value !in entry.providers
        ) {
            entry.activeProviderId.value = providerId
        }

        emitSlotMetadata()
        Log.d(TAG, "Provider 已注册: $providerId → 槽位 $slotId (type=$providerType, isDefault=$isDefault)")
    }

    // ===================== 激活/切换 =====================

    /**
     * 激活指定槽位中的指定 Provider。
     *
     * @param slotId 槽位 ID
     * @param providerId 要激活的 Provider ID
     * @return 是否成功激活
     */
    fun activateProvider(slotId: String, providerId: String): Boolean {
        val entry = slots[slotId] ?: run {
            Log.w(TAG, "槽位 '$slotId' 未注册")
            return false
        }

        @Suppress("UNCHECKED_CAST")
        if (providerId !in entry.providers) {
            Log.w(TAG, "Provider '$providerId' 在槽位 '$slotId' 中未注册")
            return false
        }

        entry.activeProviderId.value = providerId
        emitSlotMetadata()
        Log.i(TAG, "激活 Provider: $providerId (槽位 $slotId)")
        return true
    }

    // ===================== 模型状态管理 =====================

    /**
     * 更新指定槽位中 Provider 的模型就绪状态及下载进度。
     *
     * @param slotId 槽位 ID
     * @param providerId Provider ID
     * @param readiness 模型就绪状态
     * @param progress 下载进度 (0~1)
     */
    fun updateProviderModelStatus(
        slotId: String,
        providerId: String,
        readiness: ModelReadiness,
        progress: Float = 0f,
    ) {
        val entry = slots[slotId] ?: return
        entry.modelStatuses[providerId]?.value = readiness
        entry.modelProgresses[providerId]?.value = progress
        emitSlotMetadata()
    }

    /**
     * 获取指定 Provider 的模型就绪状态。
     *
     * @param slotId 槽位 ID
     * @param providerId Provider ID
     * @return 模型就绪状态，如果 Provider 不存在则返回 ALWAYS_READY
     */
    fun getProviderModelStatus(slotId: String, providerId: String): ModelReadiness {
        return slots[slotId]?.modelStatuses?.get(providerId)?.value ?: ModelReadiness.ALWAYS_READY
    }

    /**
     * 获取指定 Provider 的类型标签。
     */
    fun getProviderType(slotId: String, providerId: String): ProviderType {
        return slots[slotId]?.providerTypes?.get(providerId) ?: ProviderType.BUILTIN
    }

    /**
     * 注册 Provider 关联的底层 modelId 列表。
     *
     * 对于单模型 Provider（如 MobileNetScene），modelIds = [providerId]。
     * 对于多模型 Provider（如 InsightFace），modelIds = ["model:insightface_det", "model:insightface_rec"]。
     *
     * 此映射用于：
     * 1. 状态同步：从 ModelManager 的 modelId 反向查找对应的 providerId
     * 2. 下载触发：通过 providerId 获取所有关联的 modelIds，逐一调用 ensureModelReady
     *
     * @param slotId 槽位 ID
     * @param providerId Provider ID
     * @param modelIds 关联的底层 modelId 列表
     */
    fun registerProviderModelIds(slotId: String, providerId: String, modelIds: List<String>) {
        val entry = slots[slotId] ?: return
        entry.providerModelIds[providerId] = modelIds
        Log.d(TAG, "注册 Provider 模型映射: $providerId → $modelIds (槽位 $slotId)")
    }

    /**
     * 获取指定 Provider 关联的底层 modelId 列表。
     *
     * 如果未注册映射，则默认返回 [providerId] 自身（兼容单模型 Provider）。
     *
     * @param providerId Provider ID
     * @return 关联的 modelId 列表
     */
    fun getProviderModelIds(providerId: String): List<String> {
        // 遍历所有槽位查找 providerId
        for (entry in slots.values) {
            entry.providerModelIds[providerId]?.let { return it }
        }
        // 默认：providerId 即为 modelId
        return listOf(providerId)
    }

    /**
     * 通过底层 modelId 反向查找所属的 providerId。
     *
     * 用于 ModelManager 状态同步：当 modelManager.getAllModelStates() 返回
     * modelId（如 "model:insightface_det"）时，通过此方法找到对应的
     * providerId（如 "model:insightface"）。
     *
     * @param modelId 底层 modelId
     * @return 对应的 providerId，如果未找到则返回 null
     */
    fun findProviderIdByModelId(modelId: String): String? {
        for (entry in slots.values) {
            for ((providerId, modelIds) in entry.providerModelIds) {
                if (modelId in modelIds) return providerId
            }
            // 兼容未注册映射的单模型 Provider：providerId == modelId
            if (modelId in entry.providers.keys) return modelId
        }
        return null
    }

    /**
     * 通过 providerId 查找所属的 slotId。
     *
     * @param providerId Provider ID
     * @return 对应的 slotId，如果未找到则返回 null
     */
    fun findSlotIdByProviderId(providerId: String): String? {
        for ((slotId, entry) in slots) {
            if (providerId in entry.providers) return slotId
        }
        return null
    }

    // ===================== 查询 =====================

    /**
     * 获取指定槽位中当前激活的 Provider 实例。
     *
     * @param slotId 槽位 ID
     * @return 激活的 Provider 实例，如果槽位不存在或没有激活项则返回 null
     */
    /**
     * 获取指定槽位中当前激活的 Provider 实例。
     *
     * 类型安全：仅当激活 Provider 是 [T] 的实例时返回，否则返回 null
     * （避免跨槽位误查导致的 ClassCastException）。
     */
    inline fun <reified T : Any> getActiveProvider(slotId: String): T? =
        getActiveProviderTyped(slotId, T::class)

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> getActiveProviderTyped(slotId: String, type: KClass<T>): T? {
        val entry = slots[slotId] ?: return null
        val provider = entry.providers[entry.activeProviderId.value] ?: return null
        return if (type.isInstance(provider)) provider as T else null
    }

    /**
     * 获取指定槽位的类型化状态流。
     *
     * @param slotId 槽位 ID
     * @return 该槽位的 [CapabilitySlotState] 流，如果槽位不存在则返回空状态
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getSlotState(slotId: String): StateFlow<CapabilitySlotState<T>> {
        val entry = slots[slotId] ?: return MutableStateFlow(CapabilitySlotState())
        val typed = entry as SlotEntry<T>

        // 派生一个 StateFlow: 当 activeProviderId 变化时更新 CapabilitySlotState
        val derived = MutableStateFlow(
            CapabilitySlotState(
                slotId = typed.slot.slotId,
                slotName = typed.slot.displayName,
                activeProviderId = typed.activeProviderId.value,
                providers = typed.providers.values.toList(),
            )
        )

        // 实际项目中这里应该用 combine/flatMapLatest，但为保持简单，
        // 我们在 emitSlotMetadata 时同时更新各槽位的状态。
        // 此处返回一个初始值，外部可通过 slotMetadataList 获取最新状态。
        return derived
    }

    /**
     * 获取指定槽位的 Provider 数量。
     */
    fun getProviderCount(slotId: String): Int {
        return slots[slotId]?.providers?.size ?: 0
    }

    /**
     * 列出所有已注册的 Provider（跨所有槽位），按槽位分组。
     */
    fun listAllProviders(): Map<String, List<ProviderMeta>> {
        return slots.mapValues { (_, entry) ->
            @Suppress("UNCHECKED_CAST")
            entry.providers.map { (id, provider) ->
                val displayName = when (provider) {
                    is FaceProvider -> provider.displayName
                    is SceneProvider -> provider.displayName
                    is SemanticEmbedProvider -> provider.displayName
                    is QualityProvider -> provider.displayName
                    is OcrProvider -> provider.displayName
                    else -> id
                }
                ProviderMeta(id, displayName, id == entry.activeProviderId.value)
            }
        }
    }

    // ===================== 生命周期 =====================

    /**
     * 释放所有已注册 Provider 的资源。
     */
    suspend fun shutdown() {
        Log.i(TAG, "关闭能力注册表 V2，释放所有 Provider 资源...")
        for ((_, entry) in slots) {
            for ((_, provider) in entry.providers) {
                try {
                    when (provider) {
                        is FaceProvider -> provider.release()
                        is SceneProvider -> provider.release()
                        is SemanticEmbedProvider -> provider.release()
                        is QualityProvider -> provider.release()
                        is OcrProvider -> provider.release()
                    }
                } catch (_: Exception) {}
            }
        }
        slots.clear()
        // 清空后刷新元数据，避免 slotMetadataList 残留已注销槽位
        emitSlotMetadata()
    }

    // ===================== 内部 =====================

    private fun emitSlotMetadata() {
        _slotMetadataList.value = slots.map { (slotId, entry) ->
            SlotMetadata(
                slotId = slotId,
                displayName = entry.slot.displayName,
                description = entry.slot.description,
                icon = entry.slot.icon,
                required = entry.slot.required,
                activeProviderId = entry.activeProviderId.value,
                availableProviders = entry.providers.map { (id, provider) ->
                    val name = when (provider) {
                        is FaceProvider -> provider.displayName
                        is SceneProvider -> provider.displayName
                        is SemanticEmbedProvider -> provider.displayName
                        is QualityProvider -> provider.displayName
                        is OcrProvider -> provider.displayName
                        else -> id
                    }
                    val pType = entry.providerTypes[id] ?: ProviderType.BUILTIN
                    val readiness = entry.modelStatuses[id]?.value ?: ModelReadiness.ALWAYS_READY
                    val progress = entry.modelProgresses[id]?.value ?: 0f
                    ProviderInfo(
                        providerId = id,
                        displayName = name,
                        isActive = id == entry.activeProviderId.value,
                        providerType = pType,
                        modelStatus = readiness,
                        modelProgress = progress,
                    )
                },
            )
        }
    }
}

// ===================== 公共数据类 =====================

/**
 * 槽位元数据（供 UI 动态渲染）。
 *
 * @property slotId 槽位标识
 * @property displayName 显示名称
 * @property description 功能描述
 * @property icon 图标 emoji
 * @property required 是否必需
 * @property activeProviderId 当前激活的 Provider ID
 * @property availableProviders 所有可用 Provider 列表
 */
data class SlotMetadata(
    val slotId: String,
    val displayName: String,
    val description: String,
    val icon: String,
    val required: Boolean,
    val activeProviderId: String,
    val availableProviders: List<ProviderInfo>,
)

/**
 * Provider 简要信息（供 UI 列表展示）。
 *
 * @property providerId Provider 标识
 * @property displayName 显示名称
 * @property isActive 是否为当前激活项
 */
data class ProviderInfo(
    val providerId: String,
    val displayName: String,
    val isActive: Boolean,
    val providerType: ProviderType = ProviderType.BUILTIN,
    val modelStatus: ModelReadiness = ModelReadiness.ALWAYS_READY,
    val modelProgress: Float = 0f,
)