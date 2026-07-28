package com.renyxin.localalbum.core.plugin

/**
 * 插件清单（model_manifest.json 的 Kotlin 映射）。
 *
 * 描述一个 AI 插件的完整配置信息，包括身份标识、任务类型、模型格式、
 * 张量规格、入口类名以及预处理/后处理参数。
 *
 * 该对象可通过 JSON 序列化/反序列化，用于：
 * 1. 可视化向导模式：用户选模型文件后自动解析张量元数据回填，再通过表单完善
 * 2. 高级 JSON 模式：用户直接编写/导入纯文本 model_manifest.json
 *
 * @property pluginId 插件唯一标识（如 "face_swap_v1"）
 * @property name 插件显示名称
 * @property version 插件版本号
 * @property author 作者
 * @property taskType 任务类型
 * @property modelFormat 模型文件格式
 * @property modelFilePath 模型文件相对路径（相对于插件 APK 根目录）
 * @property entryClass 插件入口类全限定名（实现 AiPlugin 接口）
 * @property inputTensors 输入张量规格列表
 * @property outputTensors 输出张量规格列表
 * @property preprocessing 预处理参数（归一化均值、标准差、resize 方式等）
 * @property postprocessing 后处理参数（NMS 阈值、标签映射等）
 * @property description 插件描述（可选）
 * @property enabled 是否启用
 * @property pipelineStage 管道阶段标识（决定执行顺序，如 "detection" → "extraction"）
 * @property dependsOn 该阶段依赖的前置插件 ID 列表（用于 DAG 编排）
 * @property authorizedCertificateFingerprint 授权签名证书 SHA-256 指纹（可选，用于 APK 签名校验）
 */
data class PluginManifest(
    val pluginId: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val taskType: TaskType,
    val modelFormat: ModelFormat,
    val modelFilePath: String,
    val entryClass: String,
    val inputTensors: List<TensorSpec>,
    val outputTensors: List<TensorSpec>,
    val preprocessing: PreprocessingConfig = PreprocessingConfig(),
    val postprocessing: PostprocessingConfig = PostprocessingConfig(),
    val description: String? = null,
    val enabled: Boolean = true,
    val pipelineStage: String = taskType.stageName,
    val dependsOn: List<String> = emptyList(),
    val authorizedCertificateFingerprint: String? = null,
) {

    /**
     * AI 任务类型枚举。
     *
     * @param stageName 管道阶段名称，用于 DAG 编排排序
     */
    enum class TaskType(val stageName: String) {
        CLASSIFICATION("classification"),
        FEATURE_EXTRACTION("extraction"),
        DETECTION("detection"),
        GENERATIVE("generative"),
    }

    /**
     * 支持的模型文件格式。
     */
    enum class ModelFormat {
        TFLITE,
        ONNX,
        PYTORCH,
    }

    /**
     * 张量规格定义。
     *
     * @param name 张量名称（对应模型输入/输出节点名）
     * @param dataType 数据类型
     * @param shape 形状（如 [1, 224, 224, 3]）
     * @param quantizationParams 量化参数（可选，量化模型使用）
     */
    data class TensorSpec(
        val name: String,
        val dataType: TensorDataType,
        val shape: List<Int>,
        val quantizationParams: QuantizationParams? = null,
    ) {
        /**
         * 张量总元素数（shape 各维度乘积）。
         */
        val elementCount: Int get() = if (shape.isEmpty()) 0 else shape.reduce(Int::times)
    }

    /**
     * 张量数据类型枚举。
     */
    enum class TensorDataType {
        FLOAT32,
        FLOAT16,
        INT32,
        INT64,
        INT8,
        UINT8,
        BOOL,
        STRING,
    }

    /**
     * 量化参数（量化模型使用）。
     *
     * @param scale 缩放因子
     * @param zeroPoint 零点偏移
     */
    data class QuantizationParams(
        val scale: Float,
        val zeroPoint: Long,
    )

    /**
     * 预处理配置。
     *
     * @param resizeWidth 目标宽度（0 表示不 resize）
     * @param resizeHeight 目标高度（0 表示不 resize）
     * @param normalizeMean 归一化均值（每个通道，如 [0.485, 0.456, 0.406]）
     * @param normalizeStd 归一化标准差（每个通道，如 [0.229, 0.224, 0.225]）
     * @param bgrToRgb 是否需要 BGR→RGB 转换
     * @param cropToSquare 是否裁剪为正方形
     */
    data class PreprocessingConfig(
        val resizeWidth: Int = 0,
        val resizeHeight: Int = 0,
        val normalizeMean: List<Float> = listOf(0f, 0f, 0f),
        val normalizeStd: List<Float> = listOf(1f, 1f, 1f),
        val bgrToRgb: Boolean = false,
        val cropToSquare: Boolean = false,
    )

    /**
     * 后处理配置。
     *
     * @param nmsThreshold NMS IoU 阈值（检测任务使用，0 表示不做 NMS）
     * @param confidenceThreshold 置信度过滤阈值
     * @param labelMapPath 标签映射文件路径（类别 ID → 标签名称）
     * @param topK 取 Top-K 结果（分类任务使用，0 表示全部）
     */
    data class PostprocessingConfig(
        val nmsThreshold: Float = 0f,
        val confidenceThreshold: Float = 0f,
        val labelMapPath: String? = null,
        val topK: Int = 0,
    )
}
