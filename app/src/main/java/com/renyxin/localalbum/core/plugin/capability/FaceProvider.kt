package com.renyxin.localalbum.core.plugin.capability

import android.graphics.RectF
import java.io.File

/**
 * 人脸检测与嵌入提供者接口。
 *
 * 封装人脸检测模型的核心能力：在图像中定位人脸并提取可用于聚类/识别的特征向量。
 * 每种实现（MLKit、FaceNet、ArcFace 等）提供不同的精度/速度权衡。
 *
 * ## 使用场景
 * - 管道批处理：[FaceStage] 逐图调用 [detectFaces]
 * - 交互式：用户在 FacesScreen 中手动选择人脸后，通过嵌入进行换脸等操作
 *
 * @see com.renyxin.localalbum.core.plugin.capability.builtin.MlKitFaceProvider 内置默认实现
 */
interface FaceProvider {
    /** Provider 唯一标识，如 "builtin:mlkit"、"plugin:facenet_v2" */
    val providerId: String

    /** 人类可读的显示名称，如 "ML Kit 人脸检测 (默认)" */
    val displayName: String

    /** 特征向量维度 */
    val embeddingDim: Int

    /**
     * 检测单张图片中的人脸并提取嵌入向量。
     *
     * @param file 图片文件
     * @return 检测到的人脸列表，无脸时返回空列表
     */
    suspend fun detectFaces(file: File): List<DetectedFace>

    /** 释放模型资源 */
    suspend fun release()

    /**
     * 检测到的单个人脸。
     *
     * @property box 归一化边界框（相对图片宽高，各分量 ∈ [0, 1]）
     * @property embedding 特征向量，已 L2 归一化，长度 = [embeddingDim]
     * @property landmarks 5 个面部关键点归一化坐标 [x0,y0,x1,y1,...,x4,y4]，
     *           顺序：左眼、右眼、鼻尖、嘴左、嘴右。各分量 ∈ [0,1]。
     *           为 null 表示检测器不支持关键点（如 ML Kit 几何方案）。
     *           换脸 Reactor 流水线依赖关键点进行仿射对齐。
     */
    data class DetectedFace(
        val box: RectF,
        val embedding: FloatArray,
        val landmarks: FloatArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DetectedFace) return false
            return box == other.box && embedding.contentEquals(other.embedding) &&
                landmarks.contentEquals(other.landmarks)
        }
        override fun hashCode(): Int {
            var result = box.hashCode()
            result = 31 * result + embedding.contentHashCode()
            result = 31 * result + (landmarks?.contentHashCode() ?: 0)
            return result
        }
    }
}
