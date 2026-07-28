package com.renyxin.localalbum.core.plugin.capability

import com.renyxin.localalbum.core.pipeline.AnalysisStage
import kotlin.reflect.KClass

/**
 * 描述一个可插拔的能力槽位（Phase 2 改进计划）。
 *
 * 每个槽位定义了：
 * - 它提供什么能力（通过泛型 [T] 表达 Provider 接口类型）
 * - 它被哪个 Pipeline Stage 消费
 * - 它的默认实现是什么
 *
 * ## 示例
 *
 * ```kotlin
 * CapabilitySlot(
 *     slotId = "face",
 *     displayName = "人脸检测与聚类",
 *     description = "检测图像中的人脸并提取特征向量用于聚类",
 *     interfaceClass = FaceProvider::class,
 *     defaultProviderId = "builtin:mlkit",
 *     required = true,
 * )
 * ```
 *
 * @param T Provider 接口类型（如 [FaceProvider], [SceneProvider] 等）
 * @property slotId 槽位唯一标识
 * @property displayName 显示名称
 * @property description 功能描述
 * @property interfaceClass Provider 接口的 KClass，用于运行时类型检查
 * @property defaultProviderId 默认激活的 Provider ID
 * @property icon 图标 emoji（用于 UI）
 * @property required 是否为必需槽位（如 face 必需，ocr 可选）
 */
data class CapabilitySlot<T : Any>(
    val slotId: String,
    val displayName: String,
    val description: String,
    val interfaceClass: KClass<T>,
    val defaultProviderId: String,
    val icon: String = "🧩",
    val required: Boolean = false,
)
