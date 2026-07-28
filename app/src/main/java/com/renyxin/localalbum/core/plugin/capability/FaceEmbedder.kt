package com.renyxin.localalbum.core.plugin.capability

import android.graphics.Bitmap

/**
 * 人脸嵌入提取器接口 — 对裁剪后的人脸区域提取特征向量（Phase 3 拆分）。
 *
 * 与 [FaceDetector] 分离，允许用户独立选择检测器和嵌入器组合。
 * 例如 YOLO-Face（高精度检测）+ ArcFace（高质量嵌入）。
 *
 * @see FaceDetector 人脸检测器
 * @see FaceProvider 便利门面（组合检测+嵌入）
 */
interface FaceEmbedder {
    /** 嵌入器唯一标识 */
    val embedderId: String

    /** 人类可读的显示名称 */
    val displayName: String

    /** 特征向量维度 */
    val embeddingDim: Int

    /**
     * 对裁剪后的人脸图像提取嵌入向量。
     *
     * @param faceCrop 已裁剪的人脸区域位图
     * @return L2 归一化嵌入向量，提取失败返回 null
     */
    suspend fun embed(faceCrop: Bitmap): FloatArray?

    /** 释放嵌入器资源 */
    suspend fun release()
}
