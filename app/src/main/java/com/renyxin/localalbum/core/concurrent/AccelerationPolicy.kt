package com.renyxin.localalbum.core.concurrent

import android.content.Context
import android.os.Build

/**
 * 模型执行后端策略。
 *
 * 硬件后端默认关闭，待真机通过正确性、稳定性、吞吐与能耗验证后再由后续阶段启用。
 * 该策略层先统一对象池并发度和指标标签，避免把 CPU 多实例策略错误复用于 NPU。
 */
enum class AccelerationBackend(
    val metricsBackend: InferenceMetrics.Backend,
    val maxPoolSize: Int,
) {
    // 模型实例的原生工作区并不随模型 mmap 共享。真机验证显示按 CPU 核数扩到 8 个
    // session/interpreter 会让仅 100 张图片的 PSS 超过 4GB，因此 CPU 池硬限制为 2。
    CPU_XNNPACK(InferenceMetrics.Backend.CPU_XNNPACK, 2),
    CPU_DEFAULT(InferenceMetrics.Backend.CPU_DEFAULT, 2),
    TFLITE_NNAPI(InferenceMetrics.Backend.TFLITE_NNAPI, 1),
    ONNX_NNAPI(InferenceMetrics.Backend.ONNX_NNAPI, 1),
}

/**
 * 单个模型的运行策略。
 *
 * [enabled] 为 false 时必须使用 CPU 后端；后续 NNAPI 探测通过后才能创建 enabled=true 的策略。
 */
data class ModelAccelerationPolicy(
    val backend: AccelerationBackend,
    val enabled: Boolean = false,
) {
    val effectiveBackend: AccelerationBackend
        get() = if (enabled) backend else fallbackBackend

    val poolSize: Int
        get() = effectiveBackend.maxPoolSize.coerceAtLeast(1)

    private val fallbackBackend: AccelerationBackend
        get() = when (backend) {
            AccelerationBackend.TFLITE_NNAPI,
            AccelerationBackend.CPU_XNNPACK -> AccelerationBackend.CPU_XNNPACK
            AccelerationBackend.ONNX_NNAPI,
            AccelerationBackend.CPU_DEFAULT -> AccelerationBackend.CPU_DEFAULT
        }
}

/** 阶段 B 的安全默认策略：所有模型继续使用既有 CPU 后端。 */
object AccelerationPolicyRegistry {
    private const val PREFS_NAME = "acceleration_policy"
    // 模型输出容器修复后需重新验证，不能沿用该修复前写入的熔断记录。
    private const val TFLITE_NNAPI_DISABLED_PREFIX = "tflite_nnapi_disabled:v2:"
    private const val ONNX_NNAPI_DISABLED_PREFIX = "onnx_nnapi_disabled:v1:"
    private const val DIMENSITY_9400_SOC_MODEL = "MT6991"
    private const val MOBILENET_MODEL_ID = "model:mobilenet_v2"
    // 当前批处理固定使用 InsightFace：检测模型保留 CPU（已知 XNNPACK/原生稳定性约束），
    // 仅对识别模型先试验 NNAPI，避免要求 UI 支持切换 Provider 才能完成真机验证。
    private val onnxCandidateModels = setOf("model:insightface_rec")

    /**
     * 仅对首要验收机的 MobileNetV3 开启 NNAPI 候选路径。
     *
     * NNAPI 初始化成功不代表一定使用 NPU；运行时异常会写入熔断标记，下一次加载直接
     * 回退至 CPU。其他天玑、骁龙和所有 ONNX 模型仍保持 CPU 基线，等待逐模型验证。
     */
    /** 无 Android 上下文时的保守默认值，供 JVM 测试及非设备路径使用。 */
    fun forTflite(modelId: String): ModelAccelerationPolicy {
        require(modelId.isNotBlank()) { "modelId 不能为空" }
        return ModelAccelerationPolicy(AccelerationBackend.CPU_XNNPACK)
    }

    fun forTflite(context: Context, modelId: String): ModelAccelerationPolicy {
        require(modelId.isNotBlank()) { "modelId 不能为空" }
        val isTargetExperiment = modelId == MOBILENET_MODEL_ID &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            Build.SOC_MANUFACTURER.equals("Mediatek", ignoreCase = true) &&
            Build.SOC_MODEL.equals(DIMENSITY_9400_SOC_MODEL, ignoreCase = true)
        val isCircuitBroken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(TFLITE_NNAPI_DISABLED_PREFIX + modelId, false)
        return ModelAccelerationPolicy(
            backend = AccelerationBackend.TFLITE_NNAPI,
            enabled = isTargetExperiment && !isCircuitBroken,
        )
    }

    fun disableTfliteNnapi(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(TFLITE_NNAPI_DISABLED_PREFIX + modelId, true)
            .apply()
    }

    fun forOnnx(modelId: String): ModelAccelerationPolicy {
        require(modelId.isNotBlank()) { "modelId 不能为空" }
        return ModelAccelerationPolicy(AccelerationBackend.CPU_DEFAULT)
    }

    /**
     * ONNX Runtime NNAPI EP 的首轮候选仅限当前全功能扫描实际会调用的
     * InsightFace 识别模型。检测模型、EVA02-CLIP、OCR/SCRFD 保持 CPU；EVA02-CLIP
     * 自管 session 池，须在独立改造后才能接入该策略。
     */
    fun forOnnx(context: Context, modelId: String): ModelAccelerationPolicy {
        require(modelId.isNotBlank()) { "modelId 不能为空" }
        val isTargetExperiment = modelId in onnxCandidateModels &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            Build.SOC_MANUFACTURER.equals("Mediatek", ignoreCase = true) &&
            Build.SOC_MODEL.equals(DIMENSITY_9400_SOC_MODEL, ignoreCase = true)
        val isCircuitBroken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ONNX_NNAPI_DISABLED_PREFIX + modelId, false)
        return ModelAccelerationPolicy(
            backend = AccelerationBackend.ONNX_NNAPI,
            enabled = isTargetExperiment && !isCircuitBroken,
        )
    }

    fun disableOnnxNnapi(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ONNX_NNAPI_DISABLED_PREFIX + modelId, true)
            .apply()
    }
}
