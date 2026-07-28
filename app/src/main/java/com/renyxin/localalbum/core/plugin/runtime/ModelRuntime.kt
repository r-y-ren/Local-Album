package com.renyxin.localalbum.core.plugin.runtime

import android.content.Context
import com.renyxin.localalbum.core.plugin.PluginManifest
import java.io.File
import java.nio.ByteBuffer

/**
 * 模型运行时接口（Phase 2.3）。
 *
 * 统一封装三种端侧模型格式（TFLite / ONNX / PyTorch Mobile）的推理调用，
 * 使插件开发者无需关心底层框架差异。
 *
 * ## 生命周期
 * 1. [create] — 工厂方法，根据 [PluginManifest.ModelFormat] 创建对应实现
 * 2. [initialize] — 加载模型文件，分配资源
 * 3. [invoke] — 执行推理
 * 4. [close] — 释放资源
 *
 * ## 推理数据流
 * 输入：`Map<String, ByteBuffer>`（张量名 → 数据缓冲区）
 * 输出：`Map<String, ByteBuffer>`（张量名 → 结果缓冲区）
 * 预处理/后处理由插件自身负责（通过 [PluginManifest] 中的配置参数）
 */
interface ModelRuntime {

    /**
     * 初始化模型运行时，加载模型文件。
     *
     * @param context 宿主 Context（用于访问 assets / 文件系统）
     * @param modelFile 模型文件
     * @param manifest 插件清单（含张量规格、预处理/后处理配置）
     */
    suspend fun initialize(context: Context, modelFile: File, manifest: PluginManifest)

    /**
     * 是否已初始化、可执行推理。
     */
    fun isReady(): Boolean

    /**
     * 执行推理。
     *
     * @param inputs 输入张量映射（张量名 → ByteBuffer）
     * @return 输出张量映射（张量名 → ByteBuffer）
     */
    suspend fun invoke(inputs: Map<String, ByteBuffer>): Map<String, ByteBuffer>

    /**
     * 获取输入张量规格列表。
     */
    fun getInputTensors(): List<PluginManifest.TensorSpec>

    /**
     * 获取输出张量规格列表。
     */
    fun getOutputTensors(): List<PluginManifest.TensorSpec>

    /**
     * 释放模型资源。
     */
    fun close()

    companion object {

        /**
         * 工厂方法：根据模型格式创建对应的运行时实现。
         *
         * @param format 模型格式
         * @return 对应的 [ModelRuntime] 实例
         * @throws IllegalArgumentException 不支持的格式
         */
        fun create(format: PluginManifest.ModelFormat): ModelRuntime {
            return when (format) {
                PluginManifest.ModelFormat.TFLITE -> TfliteModelRuntime()
                PluginManifest.ModelFormat.ONNX -> OnnxModelRuntime()
                PluginManifest.ModelFormat.PYTORCH -> PyTorchModelRuntime()
            }
        }
    }
}
