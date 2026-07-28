package com.renyxin.localalbum.core.plugin.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Demo 场景分类 Provider — 基于主色调的简单场景判别。
 *
 * 提供 `demo:simple_scene` 作为场景槽位的第二选项。
 */
class DemoSceneProvider : SceneProvider {
    override val providerId = "demo:simple_scene"
    override val displayName = "简易场景分类 (Demo)"
    override val labels = listOf("sky", "green", "warm", "cool", "dark", "bright")

    override suspend fun classify(file: File): SceneProvider.SceneResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext SceneProvider.SceneResult("unknown", 1f)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumBrightness = 0f

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r
            sumG += g
            sumB += b
            sumBrightness += (0.299f * r + 0.587f * g + 0.114f * b)
        }

        val total = pixels.size
        val avgR = sumR.toFloat() / total
        val avgG = sumG.toFloat() / total
        val avgB = sumB.toFloat() / total
        val avgBrightness = sumBrightness / total

        val scores = mutableMapOf<String, Float>()

        // sky: 蓝色占主导
        scores["sky"] = if (avgB > avgR && avgB > avgG && avgBrightness > 128f) 0.8f else 0.2f
        // green: 绿色占主导
        scores["green"] = if (avgG > avgR && avgG > avgB) 0.75f else 0.15f
        // warm: 红色占主导
        scores["warm"] = if (avgR > avgG && avgR > avgB) 0.7f else 0.2f
        // cool: 蓝色 + 低亮度
        scores["cool"] = if (avgB > avgR && avgBrightness < 150f) 0.7f else 0.15f
        // dark: 低亮度
        scores["dark"] = if (avgBrightness < 80f) 0.85f else 0.1f
        // bright: 高亮度
        scores["bright"] = if (avgBrightness > 200f) 0.8f else 0.15f

        val top = scores.maxByOrNull { it.value } ?: return@withContext SceneProvider.SceneResult("unknown", 1f)
        SceneProvider.SceneResult(topLabel = top.key, confidence = top.value, allScores = scores)
    }

    override suspend fun release() {}

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }
}
