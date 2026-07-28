package com.renyxin.localalbum.core.plugin.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo 人脸检测 Provider — 基于肤色启发式的简单人脸定位。
 *
 * 提供 `demo:simple_face` 作为人脸槽位的第二选项，演示 Provider 切换能力。
 * 实际人脸检测仍建议使用 `builtin:mlkit` 以获得更好的精度。
 */
class DemoFaceProvider : FaceProvider {
    override val providerId = "demo:simple_face"
    override val displayName = "简易人脸检测 (Demo)"
    override val embeddingDim = 8 // 简化嵌入

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext emptyList()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        // 肤色范围: R > 95, G > 40, B > 20, R > G, R > B, |R-G| > 15
        var skinCount = 0
        var sumX = 0f
        var sumY = 0f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 95 && g > 40 && b > 20 && r > g && r > b && kotlin.math.abs(r - g) > 15) {
                    skinCount++
                    sumX += x
                    sumY += y
                }
            }
        }

        if (skinCount < w * h * 0.01f) return@withContext emptyList()

        val cx = sumX / skinCount / w
        val cy = sumY / skinCount / h

        listOf(
            FaceProvider.DetectedFace(
                box = RectF(
                    (cx - 0.15f).coerceIn(0f, 1f),
                    (cy - 0.2f).coerceIn(0f, 1f),
                    (cx + 0.15f).coerceIn(0f, 1f),
                    (cy + 0.2f).coerceIn(0f, 1f),
                ),
                embedding = FloatArray(8) { 0.5f },
            ),
        )
    }

    override suspend fun release() {}

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }
}
