package com.renyxin.localalbum.core.plugin.capability

import android.graphics.RectF
import java.io.File

/**
 * 人脸检测器接口 — 仅负责在图像中定位人脸位置（Phase 3 拆分）。
 *
 * 与 [FaceEmbedder] 分离，允许用户独立选择检测器和嵌入器组合。
 * 例如 YOLO-Face（高精度检测）+ ArcFace（高质量嵌入）。
 *
 * @see FaceEmbedder 人脸嵌入提取器
 * @see FaceProvider 便利门面（组合检测+嵌入）
 */
interface FaceDetector {
    /** 检测器唯一标识 */
    val detectorId: String

    /** 人类可读的显示名称 */
    val displayName: String

    /**
     * 检测图像中的所有人脸边界框。
     *
     * @param file 图像文件
     * @return 归一化边界框列表（各分量 ∈ [0, 1]），无人脸时返回空列表
     */
    suspend fun detect(file: File): List<RectF>

    /** 释放检测器资源 */
    suspend fun release()
}
