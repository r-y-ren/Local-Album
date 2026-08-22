package com.renyxin.localalbum.core.plugin.capability

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import java.io.File

/**
 * 组合式 FaceProvider — 将独立的 [FaceDetector] 和 [FaceEmbedder] 组合为 [FaceProvider]（Phase 3）。
 *
 * 职责：
 * 1. 调用 [FaceDetector.detect] 获取人脸边界框
 * 2. 逐框裁剪人脸区域
 * 3. 调用 [FaceEmbedder.embed] 提取嵌入
 * 4. 返回 `List<DetectedFace>` 供 [FaceStage] 使用
 *
 * 这样用户可自由组合检测器和嵌入器（如 YOLO-Face + ArcFace），
 * 而 Pipeline 则通过统一的 [FaceProvider] 接口消费。
 *
 * @param detector 人脸检测器
 * @param embedder 人脸嵌入提取器
 */
class ComposedFaceProvider(
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
) : FaceProvider {

    override val providerId: String = "${detector.detectorId}+${embedder.embedderId}"
    override val displayName: String = "${detector.displayName} + ${embedder.displayName}"
    override val embeddingDim: Int = embedder.embeddingDim

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> {
        // Step 1: 检测人脸框
        val boxes = detector.detect(file)
        if (boxes.isEmpty()) return emptyList()

        // Step 2: 解码图像
        val bitmap = decodeBitmap(file) ?: return emptyList()
        val result = mutableListOf<FaceProvider.DetectedFace>()

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        for (box in boxes) {
            // Step 3: 裁剪人脸区域
            val left = (box.left * w).toInt().coerceAtLeast(0)
            val top = (box.top * h).toInt().coerceAtLeast(0)
            val right = (box.right * w).toInt().coerceAtMost(bitmap.width)
            val bottom = (box.bottom * h).toInt().coerceAtMost(bitmap.height)

            if (right <= left || bottom <= top) continue

            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)

            // Step 4: 提取嵌入
            val embedding = embedder.embed(crop)

            if (embedding != null) {
                result.add(FaceProvider.DetectedFace(box = box, embedding = embedding))
            }
        }

        return result
    }

    override suspend fun release() {
        detector.release()
        embedder.release()
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }
}
