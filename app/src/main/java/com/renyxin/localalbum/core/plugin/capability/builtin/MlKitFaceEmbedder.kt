package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import com.renyxin.localalbum.core.analysis.FaceDetector
import com.renyxin.localalbum.core.plugin.capability.FaceEmbedder

/**
 * 基于 ML Kit 的人脸嵌入提取器（21 维几何特征）— Phase 3 拆分。
 *
 * 使用 ML Kit 的关键点坐标 + 距离比例 + 轮廓统计构建几何嵌入向量，
 * 适合快速本地聚类。检测功能已拆分到 [MlKitFaceDetector]。
 *
 * @see MlKitFaceDetector ML Kit 人脸检测器
 */
class MlKitFaceEmbedder(
    private val context: Context,
) : FaceEmbedder {

    override val embedderId = "builtin:mlkit"
    override val displayName = "ML Kit 几何嵌入 (21-dim)"
    override val embeddingDim = FaceDetector.EMBEDDING_DIM // 21

    private val detector = FaceDetector(context)

    override suspend fun embed(faceCrop: Bitmap): FloatArray? {
        // ML Kit 的嵌入提取依赖其人脸检测流程中的关键点信息，
        // 这里采用简化方式：对整个图像执行一次检测，返回第一个检测到的人脸的嵌入。
        // 实际应用中，检测和嵌入在 ML Kit 中是一体的，这里只是为了接口分离。
        return try {
            // 将 Bitmap 写为临时文件供 FaceDetector 使用
            val tmpFile = java.io.File(context.cacheDir, "mlkit_face_embed_${System.nanoTime()}.jpg")
            java.io.FileOutputStream(tmpFile).use { out ->
                faceCrop.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val result = detector.detect(tmpFile)
            tmpFile.delete()

            result.faces.firstOrNull()?.embedding?.copyOf()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun release() {
        detector.close()
    }
}
