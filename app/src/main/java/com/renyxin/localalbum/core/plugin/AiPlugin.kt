package com.renyxin.localalbum.core.plugin

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * AI 插件顶层 SPI 接口。
 *
 * 所有外部自定义模型插件（分类、特征提取、检测、AIGC 生成式）必须实现此接口。
 * 插件通过 DexClassLoader 动态加载后，由 PluginRegistry 实例化并注册。
 *
 * 生命周期：
 * 1. [initialize] — 加载后调用一次，初始化模型资源
 * 2. [execute] — 管道编排器按需调用执行推理
 * 3. [release] — 卸载时调用，释放模型资源
 *
 * 子接口按任务类型细分：
 * - [ClassificationPlugin]：图像 → 标签 + 置信度
 * - [FeatureExtractionPlugin]：图像 → 任意维度特征向量
 * - [DetectionPlugin]：图像 → 边界框列表
 * - [GenerativePlugin]：多模态输入 → 生成图像
 */
interface AiPlugin {

    /**
     * 获取插件唯一标识（对应 [PluginManifest.pluginId]）。
     */
    fun getId(): String

    /**
     * 获取插件清单配置。
     */
    fun getManifest(): PluginManifest

    /**
     * 获取输入 Schema 描述（描述该插件接受什么类型的输入）。
     */
    fun getInputSchema(): List<FeatureSchema.FieldSpec>

    /**
     * 获取输出 Schema 描述（描述该插件输出什么类型的特征）。
     */
    fun getOutputSchema(): FeatureSchema

    /**
     * 初始化插件（加载模型文件、分配资源）。
     * 在插件被注册到 PluginRegistry 时调用一次。
     *
     * @param context 宿主 Context
     * @param pluginContext 插件运行上下文（提供协程作用域、进度上报等能力）
     */
    suspend fun initialize(context: Context, pluginContext: PluginContext)

    /**
     * 是否已完成初始化、可执行推理。
     */
    fun isReady(): Boolean

    /**
     * 执行推理。
     *
     * 调用方约定：
     * - 调用前应检查 [isReady] 确保模型已完成初始化
     * - 抛出的异常由外部管道统一捕获与处理，插件实现不应自行吞没致命错误
     * - 耗时推理应在 [PluginContext.coroutineScope] 中调度
     *
     * @param input 多模态输入
     * @return 多模态输出
     * @throws IllegalStateException 当运行时检测到 [PluginInput] 与 [getInputSchema] 不匹配时
     * @throws RuntimeException 模型推理失败（异常由宿主统一捕获并处理为 [PluginState.ERROR]）
     */
    suspend fun execute(input: PluginInput): PluginOutput

    /**
     * 释放插件资源（关闭模型、释放内存）。
     * 在插件被卸载时调用。
     */
    suspend fun release()
}

/**
 * 插件运行上下文。
 *
 * 由宿主注入到每个插件实例，提供：
 * - 协程作用域（插件内异步操作）
 * - 进度上报接口（细粒度进度反馈）
 * - 日志标签
 *
 * @param coroutineScope 插件专属协程作用域（绑定插件生命周期）
 * @param progressReporter 进度上报接口
 * @param pluginId 插件 ID（用于日志标识）
 */
data class PluginContext(
    val coroutineScope: CoroutineScope,
    val progressReporter: ProgressReporter,
    val pluginId: String,
) {

    /**
     * 日志 Tag，格式为 "Plugin:<pluginId>"。
     */
    val logTag: String get() = "Plugin:$pluginId"
}

/**
 * 进度上报接口。
 *
 * 插件在执行耗时推理（如逐图特征提取）时，通过此接口向
 * ProgressManager 上报细粒度进度，实现全局可见的进度指示器。
 */
interface ProgressReporter {

    /**
     * 上报当前处理进度。
     *
     * @param currentIndex 当前处理的图片索引（0-based）
     * @param totalCount 总图片数
     * @param message 可选的状态描述（如 "正在提取特征: img_001.jpg"）
     */
    fun reportProgress(currentIndex: Int, totalCount: Int, message: String? = null)

    /**
     * 上报当前阶段状态变更。
     *
     * @param status 阶段状态
     */
    fun reportStatus(status: TaskStatus)

    /**
     * 任务状态枚举。
     */
    enum class TaskStatus {
        STARTING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }
}

// ---- 按任务类型细分的子接口 ----

/**
 * 分类插件接口。
 *
 * 输入：图像 → 输出：标签 + 置信度列表。
 * 典型场景：场景分类、物体分类。
 */
interface ClassificationPlugin : AiPlugin {
    override suspend fun execute(input: PluginInput): PluginOutput.ClassificationOutput
}

/**
 * 特征提取插件接口。
 *
 * 输入：图像 → 输出：任意维度特征向量。
 * 典型场景：语义嵌入、人脸特征向量、图像指纹。
 */
interface FeatureExtractionPlugin : AiPlugin {
    override suspend fun execute(input: PluginInput): PluginOutput.FeatureOutput
}

/**
 * 检测插件接口。
 *
 * 输入：图像 → 输出：边界框列表（含类别 + 置信度）。
 * 典型场景：人脸检测、物体检测。
 */
interface DetectionPlugin : AiPlugin {
    override suspend fun execute(input: PluginInput): PluginOutput.DetectionOutput
}

/**
 * 生成式插件接口（AIGC）。
 *
 * 输入：多模态（图像 + 特征向量等） → 输出：生成图像。
 * 典型场景：换脸、风格迁移、图像增强。
 *
 * 支持多输入场景，例如换脸任务：
 * - inputs[0] = ImageInput（底图）
 * - inputs[1] = TensorInput（目标人脸特征向量）
 */
interface GenerativePlugin : AiPlugin {
    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput
}
