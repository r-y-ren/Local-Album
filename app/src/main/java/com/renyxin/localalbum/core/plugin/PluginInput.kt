package com.renyxin.localalbum.core.plugin

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File

/**
 * 插件输入数据模型（多模态）。
 *
 * 支持多输入场景，例如换脸任务可同时输入图像底图和人脸特征向量。
 * 使用 sealed class 确保类型安全，管道编排器根据 [PluginManifest.inputSchema]
 * 构造对应的输入实例传递给插件。
 *
 * 使用方式：
 * - 单图像输入：[ImageInput]
 * - 单张量输入：[TensorInput]
 * - 多模态组合输入：[MultiModalInput]
 */
sealed class PluginInput {

    /**
     * 图像输入。
     *
     * @param file 图像文件
     * @param bitmap 可选的已解码 Bitmap（避免重复解码；为 null 时插件需自行解码）
     * @param filePath 文件路径快捷访问（= file.absolutePath）
     */
    data class ImageInput(
        val file: File,
        val bitmap: Bitmap? = null,
        val filePath: String = file.absolutePath,
    ) : PluginInput()

    /**
     * 张量输入（特征向量、坐标等）。
     *
     * @param data 浮点数组数据
     * @param shape 数据形状（如 [1, 512] 表示 512 维向量）
     * @param name 张量名称（对应模型输入节点名，可选）
     */
    data class TensorInput(
        val data: FloatArray,
        val shape: List<Int>,
        val name: String? = null,
    ) : PluginInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TensorInput) return false
            return data.contentEquals(other.data) && shape == other.shape && name == other.name
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + shape.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 多模态组合输入。
     *
     * 用于需要同时输入多种数据的场景，例如换脸任务：
     * - inputs[0] = ImageInput（底图）
     * - inputs[1] = TensorInput（目标人脸特征向量）
     *
     * @param inputs 子输入列表，顺序与 [PluginManifest.inputSchema] 定义一致
     */
    data class MultiModalInput(
        val inputs: List<PluginInput>,
    ) : PluginInput()
}

/**
 * 插件输出数据模型（多模态）。
 *
 * 对应四类 AI 任务的输出：分类、特征提取、检测、生成。
 * 使用 sealed class 确保类型安全，管道编排器可根据输出类型
 * 决定如何持久化或传递给下一阶段。
 */
sealed class PluginOutput {

    /**
     * 分类输出（标签 + 置信度）。
     *
     * @param labels 分类结果列表，按置信度降序
     * @param topLabel 最高置信度标签
     * @param topConfidence 最高置信度值 (0~1)
     */
    data class ClassificationOutput(
        val labels: List<LabelResult>,
    ) : PluginOutput() {
        val topLabel: String? get() = labels.firstOrNull()?.label
        val topConfidence: Float get() = labels.firstOrNull()?.confidence ?: 0f
    }

    /**
     * 单个分类结果。
     *
     * @param label 标签名称
     * @param confidence 置信度 (0~1)
     */
    data class LabelResult(
        val label: String,
        val confidence: Float,
    )

    /**
     * 特征提取输出（任意维度向量 + schema 描述）。
     *
     * @param vector 特征向量（已扁平化为一维 FloatArray）
     * @param schema 该特征的 schema 描述（维度、类型、含义）
     * @param metadata 附加元数据（如检测到的人脸框、区域信息等）
     */
    data class FeatureOutput(
        val vector: FloatArray,
        val schema: FeatureSchema,
        val metadata: Map<String, String> = emptyMap(),
    ) : PluginOutput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FeatureOutput) return false
            return vector.contentEquals(other.vector) && schema == other.schema && metadata == other.metadata
        }
        override fun hashCode(): Int {
            var result = vector.contentHashCode()
            result = 31 * result + schema.hashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }

    /**
     * 检测输出（边界框列表）。
     *
     * @param boxes 检测到的目标列表
     * @param imagePath 被检测的图片路径
     */
    data class DetectionOutput(
        val boxes: List<DetectedBox>,
        val imagePath: String,
    ) : PluginOutput()

    /**
     * 单个检测结果（边界框 + 类别 + 置信度）。
     *
     * @param box 归一化边界框（相对图片宽高，0~1）
     * @param label 目标类别标签
     * @param confidence 置信度 (0~1)
     * @param embedding 可选的该目标特征向量（如人脸检测附带人脸嵌入）
     */
    data class DetectedBox(
        val box: RectF,
        val label: String,
        val confidence: Float,
        val embedding: FloatArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DetectedBox) return false
            return box == other.box && label == other.label && confidence == other.confidence &&
                (embedding == null && other.embedding == null ||
                    (embedding != null && other.embedding != null && embedding.contentEquals(other.embedding)))
        }
        override fun hashCode(): Int {
            var result = box.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (embedding?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * 生成式输出（AIGC，如换脸结果图）。
     *
     * @param bitmap 生成的图像（调用方负责持久化）
     * @param outputPath 可选的已保存输出路径
     * @param sourcePath 源图路径
     */
    data class ImageOutput(
        val bitmap: Bitmap?,
        val outputPath: String? = null,
        val sourcePath: String,
    ) : PluginOutput()
}
