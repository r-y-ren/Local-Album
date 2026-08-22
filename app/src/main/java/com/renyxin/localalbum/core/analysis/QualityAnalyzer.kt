package com.renyxin.localalbum.core.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图像质量评分引擎。
 *
 * 对照片进行多维度客观质量评估，产生 0.0~1.0 的综合评分：
 * - [blurScore]: 拉普拉斯方差模糊检测 (占用权重 0.40)
 * - [exposureScore]: 亮度直方图曝光评估 (占用权重 0.25)
 * - [contrastScore]: RMS 对比度评估 (占用权重 0.20)
 * - [colorScore]: 饱和度和色彩丰富度 (占用权重 0.15)
 */
class QualityAnalyzer {

    /**
     * 质量分析结果。
     */
    data class QualityResult(
        /** 综合质量评分 (0.0~1.0)，越高越好 */
        val overall: Float,
        /** 清晰度评分 (Laplacian 方差归一化) */
        val blurScore: Float,
        /** 曝光评分 (亮度分布偏离中度灰的程度) */
        val exposureScore: Float,
        /** 对比度评分 (RMS 对比度) */
        val contrastScore: Float,
        /** 色彩丰富度评分 */
        val colorScore: Float,
        /** 是否过于模糊 */
        val isTooBlurry: Boolean,
    ) {
        companion object {
            /** 质量过低的阈值 */
            const val LOW_QUALITY_THRESHOLD = 0.25f
        }
    }

    /**
     * 分析指定图像文件的质量。
     * 为避免 OOM，采样缩小至 512px 最大边长。
     */
    suspend fun analyze(file: File): QualityResult = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            return@withContext QualityResult(0f, 0f, 0f, 0f, 0f, true)
        }

        val sampleSize = calculateSampleSize(srcWidth, srcHeight, MAX_DIMENSION)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // 16-bit PNG 会解码为 RGBA_F16，强制 8 位（详见 InsightFaceProvider）
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
        if (bitmap == null) {
            return@withContext QualityResult(0f, 0f, 0f, 0f, 0f, true)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = extractLuminance(pixels, width, height)

        val blurScore = evaluateBlur(gray, width, height)
        val exposureScore = evaluateExposure(gray)
        val contrastScore = evaluateContrast(gray)
        val colorScore = evaluateColor(pixels)

        bitmap.recycle()

        val overall = (
            blurScore * BLUR_WEIGHT +
                exposureScore * EXPOSURE_WEIGHT +
                contrastScore * CONTRAST_WEIGHT +
                colorScore * COLOR_WEIGHT
            ).coerceIn(0f, 1f)

        QualityResult(
            overall = overall,
            blurScore = blurScore,
            exposureScore = exposureScore,
            contrastScore = contrastScore,
            colorScore = colorScore,
            isTooBlurry = blurScore < BLUR_THRESHOLD,
        )
    }

    /**
     * 快速模糊检测：仅检查模糊度，不解码完整像素。
     * 用于大规模筛选场景，速度优先。
     */
    suspend fun quickBlurCheck(file: File): Boolean = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 8 // 极速采样
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext false
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = extractLuminance(pixels, width, height)
        val blur = evaluateBlur(gray, width, height)
        bitmap.recycle()
        blur >= BLUR_THRESHOLD
    }

    // ---- 内部评分方法 ----

    /**
     * 拉普拉斯方差模糊评分。
     * 值越大越清晰。归一化映射到 0~1。
     */
    private fun evaluateBlur(gray: FloatArray, width: Int, height: Int): Float {
        val laplacian = FloatArray(gray.size)
        var sum = 0f
        var sumSq = 0f
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val valLap = (
                    4f * gray[idx] -
                        gray[idx - 1] -
                        gray[idx + 1] -
                        gray[idx - width] -
                        gray[idx + width]
                    )
                laplacian[idx] = valLap
                sum += valLap
                sumSq += valLap * valLap
                count++
            }
        }

        if (count == 0) return 0f

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)

        // 经验阈值: variance >= 100 → 非常清晰, < 10 → 极其模糊
        return (variance / 100f).coerceIn(0f, 1f)
    }

    /**
     * 曝光评分：基于平均亮度与中度灰的偏差。
     */
    private fun evaluateExposure(gray: FloatArray): Float {
        if (gray.isEmpty()) return 0f
        val avgLuminance = gray.average().toFloat()
        // 理想亮度为 0.5 (中度灰)，偏离越远分数越低
        val deviation = kotlin.math.abs(avgLuminance - IDEAL_LUMINANCE)
        // 偏差 0 → 1.0, 偏差 1.0 → 0.0
        return (1f - deviation * 2f).coerceIn(0f, 1f)
    }

    /**
     * RMS 对比度评分。
     */
    private fun evaluateContrast(gray: FloatArray): Float {
        if (gray.isEmpty()) return 0f
        val mean = gray.average().toFloat()
        val variance = gray.map { (it - mean) * (it - mean) }.average().toFloat()
        val rmsContrast = kotlin.math.sqrt(variance)
        // RMS 0.0 → 0分, RMS 0.3+ → 1分
        return (rmsContrast / 0.3f).coerceIn(0f, 1f)
    }

    /**
     * 色彩丰富度评分：基于饱和度直方图。
     */
    private fun evaluateColor(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var saturationSum = 0f
        var count = 0

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val maxVal = maxOf(r, g, b)
            val minVal = minOf(r, g, b)
            val saturation = if (maxVal > 0f) (maxVal - minVal) / maxVal else 0f
            saturationSum += saturation
            count++
        }

        val avgSaturation = if (count > 0) saturationSum / count else 0f
        // 平均饱和度 0.5+ → 满分
        return (avgSaturation / 0.5f).coerceIn(0f, 1f)
    }

    /**
     * 从像素数组提取亮度值 (0~1)。
     */
    private fun extractLuminance(pixels: IntArray, width: Int, height: Int): FloatArray {
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            // 使用 Rec. 601 亮度公式
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return gray
    }

    private fun calculateSampleSize(srcWidth: Int, srcHeight: Int, maxDim: Int): Int {
        var sampleSize = 1
        var w = srcWidth
        var h = srcHeight
        while (w > maxDim || h > maxDim) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }
        return sampleSize
    }

    companion object {
        private const val MAX_DIMENSION = 512
        private const val IDEAL_LUMINANCE = 0.5f
        private const val BLUR_THRESHOLD = 0.15f

        // 权重分配
        private const val BLUR_WEIGHT = 0.40f
        private const val EXPOSURE_WEIGHT = 0.25f
        private const val CONTRAST_WEIGHT = 0.20f
        private const val COLOR_WEIGHT = 0.15f
    }
}