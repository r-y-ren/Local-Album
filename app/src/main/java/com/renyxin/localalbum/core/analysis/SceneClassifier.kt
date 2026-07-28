package com.renyxin.localalbum.core.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于图像属性的场景分类器。
 *
 * 通过分析图像直方图、宽高比、饱和度和亮度分布等特征，
 * 将图像分类为预定义场景类型。可作为 ML Kit 的降级替代方案。
 *
 * 支持的场景类型:
 * - landscape:  风景 (高蓝绿色占比、低中心饱和度)
 * - food:       食物 (暖色调、高饱和度、近距)
 * - document:   文档/截图 (均一纹理、高对比度、16:9/非标准比例)
 * - pet:        宠物 (中心区域纹理丰富)
 * - selfie:     自拍 (中心高亮度、人物肤色范围)
 * - night:      夜景 (低整体亮度、点光源)
 * - macro:      微距 (中心高细节 + 背景模糊过度)
 * - screenshot: 截图 (特定宽高比 + 低自然纹理 + 高饱和度 UI 色块)
 * - whiteboard: 白板/文字 (高亮度、低饱和度、高对比度)
 * - outdoor:    户外 (高蓝/绿通道占比)
 * - indoor:     室内 (低蓝绿通道、暖光源)
 * - unknown:    未知/无法分类
 */
class SceneClassifier {

    /**
     * 场景分类标签。
     */
    enum class SceneLabel(val displayName: String) {
        LANDSCAPE("风景"),
        FOOD("美食"),
        DOCUMENT("文档"),
        PET("宠物"),
        SELFIE("自拍"),
        NIGHT("夜景"),
        MACRO("微距"),
        SCREENSHOT("截图"),
        WHITEBOARD("白板"),
        OUTDOOR("户外"),
        INDOOR("室内"),
        UNKNOWN("未知"),
    }

    /**
     * 分类结果，包含标签及置信度。
     */
    data class ClassificationResult(
        val label: SceneLabel,
        val confidence: Float,
        val allScores: Map<SceneLabel, Float> = emptyMap(),
    )

    /**
     * 对单个图像文件进行场景分类。
     * 采样至 MAX_DIMENSION 以避免内存问题。
     */
    suspend fun classify(file: File): ClassificationResult = withContext(Dispatchers.IO) {
        val features = extractFeatures(file) ?: return@withContext ClassificationResult(
            SceneLabel.UNKNOWN, 1.0f,
        )
        val scores = computeAllScores(features)
        val best = scores.maxByOrNull { it.value }
        if (best == null) {
            return@withContext ClassificationResult(SceneLabel.UNKNOWN, 1.0f)
        }
        ClassificationResult(label = best.key, confidence = best.value, allScores = scores)
    }

    /**
     * 批量分类，返回路径到标签的映射。
     */
    suspend fun classifyBatch(files: List<File>): Map<String, SceneLabel> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, SceneLabel>()
        for (file in files) {
            val classification = classify(file)
            results[file.absolutePath] = classification.label
        }
        results
    }

    // ---- 特征提取 ----

    private data class ImageFeatures(
        val width: Int,
        val height: Int,
        val aspectRatio: Float,
        val avgRed: Float,
        val avgGreen: Float,
        val avgBlue: Float,
        val avgBrightness: Float,
        val avgSaturation: Float,
        val contrast: Float,
        val blueGreenRatio: Float,
        val centerBrightness: Float,
        val edgeBrightness: Float,
        val centerSaturation: Float,
        val isSquarish: Boolean,
        val isWideScreen: Boolean,
        val hasUniformTexture: Boolean,
    )

    private suspend fun extractFeatures(file: File): ImageFeatures? = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val srcW = options.outWidth
        val srcH = options.outHeight
        if (srcW <= 0 || srcH <= 0) return@withContext null

        var sampleSize = 1
        while (srcW / sampleSize > MAX_DIMENSION || srcH / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null

        val w = bitmap.width
        val h = bitmap.height
        val total = w * h
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumBrightness = 0f
        var sumSaturation = 0f

        // 中心区域和边缘区域分别统计
        val centerXStart = (w * 0.25).toInt()
        val centerXEnd = (w * 0.75).toInt()
        val centerYStart = (h * 0.25).toInt()
        val centerYEnd = (h * 0.75).toInt()

        var centerSumBrightness = 0f
        var centerPixels = 0
        var centerSumSaturation = 0f

        var edgeSumBrightness = 0f
        var edgePixels = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val pixel = pixels[idx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                sumR += r
                sumG += g
                sumB += b

                val brightness = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val max = maxOf(r, g, b).toFloat()
                val min = minOf(r, g, b).toFloat()
                val saturation = if (max > 0f) (max - min) / max else 0f

                sumBrightness += brightness
                sumSaturation += saturation

                val isCenter = x in centerXStart..centerXEnd && y in centerYStart..centerYEnd
                if (isCenter) {
                    centerSumBrightness += brightness
                    centerSumSaturation += saturation
                    centerPixels++
                } else {
                    edgeSumBrightness += brightness
                    edgePixels++
                }
            }
        }

        bitmap.recycle()

        val avgR = sumR.toFloat() / total / 255f
        val avgG = sumG.toFloat() / total / 255f
        val avgB = sumB.toFloat() / total / 255f
        val avgBrightness = sumBrightness / total
        val avgSaturation = sumSaturation / total

        // 对比度：简化为通道间的方差
        val contrast = kotlin.math.sqrt(
            ((avgR - avgBrightness) * (avgR - avgBrightness) +
                (avgG - avgBrightness) * (avgG - avgBrightness) +
                (avgB - avgBrightness) * (avgB - avgBrightness)) / 3f,
        )

        val blueGreenRatio = if (avgG > 0) avgB / avgG else avgB
        val aspectRatio = srcW.toFloat() / srcH.toFloat()

        ImageFeatures(
            width = srcW,
            height = srcH,
            aspectRatio = aspectRatio,
            avgRed = avgR,
            avgGreen = avgG,
            avgBlue = avgB,
            avgBrightness = avgBrightness,
            avgSaturation = avgSaturation,
            contrast = contrast,
            blueGreenRatio = blueGreenRatio,
            centerBrightness = if (centerPixels > 0) centerSumBrightness / centerPixels else avgBrightness,
            edgeBrightness = if (edgePixels > 0) edgeSumBrightness / edgePixels else avgBrightness,
            centerSaturation = if (centerPixels > 0) centerSumSaturation / centerPixels else avgSaturation,
            isSquarish = aspectRatio in 0.85f..1.15f,
            isWideScreen = aspectRatio > 1.6f || aspectRatio < 0.6f,
            hasUniformTexture = contrast < 0.05f,
        )
    }

    // ---- 评分计算 ----

    private fun computeAllScores(f: ImageFeatures): Map<SceneLabel, Float> {
        return mapOf(
            SceneLabel.LANDSCAPE to landscapeScore(f),
            SceneLabel.FOOD to foodScore(f),
            SceneLabel.DOCUMENT to documentScore(f),
            SceneLabel.PET to petScore(f),
            SceneLabel.SELFIE to selfieScore(f),
            SceneLabel.NIGHT to nightScore(f),
            SceneLabel.MACRO to macroScore(f),
            SceneLabel.SCREENSHOT to screenshotScore(f),
            SceneLabel.WHITEBOARD to whiteboardScore(f),
            SceneLabel.OUTDOOR to outdoorScore(f),
            SceneLabel.INDOOR to indoorScore(f),
        )
    }

    private fun landscapeScore(f: ImageFeatures): Float {
        // 风景: 高蓝绿色占比 + 中等饱和度 + 正常宽高比 + 中心亮度均匀
        var score = 0f
        score += (f.blueGreenRatio * 0.3f).coerceIn(0f, 1f) * 0.4f // 蓝绿占比高
        score += f.avgSaturation.coerceIn(0.2f, 0.6f) * 0.3f         // 中等饱和度
        score += (if (f.aspectRatio in 1.3f..2.3f) 1f else 0.5f) * 0.2f // 横向构图
        score += (f.centerBrightness * 0.1f).coerceIn(0f, 1f)         // 均匀亮度
        return score.coerceIn(0f, 1f)
    }

    private fun foodScore(f: ImageFeatures): Float {
        // 美食: 暖色调 (R 高)、高饱和度、中心饱和度更高、方形构图
        var score = 0f
        val warmth = ((f.avgRed - f.avgBlue).coerceIn(0f, 0.3f) / 0.3f)
        score += warmth * 0.3f
        score += f.avgSaturation.coerceIn(0.3f, 1f) * 0.3f
        score += (if (f.isSquarish) 1f else 0.3f) * 0.2f
        score += (f.centerSaturation - f.avgSaturation).coerceIn(0f, 0.2f) * 2.5f * 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun documentScore(f: ImageFeatures): Float {
        // 文档/截图: 低自然饱和度、高对比度、高亮度、非标准比例
        var score = 0f
        // 截图特征比例 (16:9, 9:16, 2:1 等)
        val isScreenshotAspect = f.aspectRatio in 1.6f..1.85f ||
            f.aspectRatio in 0.5f..0.65f ||
            f.aspectRatio > 1.9f
        score += (if (isScreenshotAspect) 0.8f else 0.2f) * 0.3f
        score += (1f - f.avgSaturation).coerceIn(0f, 1f) * 0.2f
        score += f.avgBrightness.coerceIn(0.5f, 1f) * 0.2f
        score += f.contrast.coerceIn(0f, 0.2f) * 2.5f * 0.15f
        score += (if (f.hasUniformTexture) 1f else 0.2f) * 0.15f
        return score.coerceIn(0f, 1f)
    }

    private fun petScore(f: ImageFeatures): Float {
        // 宠物: 中心纹理丰富、中等饱和度、暖色调中等
        var score = 0f
        score += (1f - f.avgBrightness.coerceIn(0.2f, 0.8f)).coerceIn(0f, 1f) * 0.3f
        score += f.centerSaturation.coerceIn(0.2f, 0.7f) * 0.3f
        score += (f.centerBrightness / (f.edgeBrightness + 0.01f)).coerceIn(0f, 2f) * 0.25f * 0.2f
        score += f.avgSaturation.coerceIn(0.2f, 0.6f) * 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun selfieScore(f: ImageFeatures): Float {
        // 自拍: 中心高亮、肤色色调、高中心亮度、接近方形
        var score = 0f
        // 肤色: R > G > B 且特定范围
        val skinTone = if (f.avgRed > f.avgGreen && f.avgGreen > f.avgBlue &&
            f.avgRed - f.avgBlue in 0.05f..0.35f
        ) 1f else 0.3f
        score += skinTone * 0.3f
        score += (f.centerBrightness.coerceIn(0.5f, 1f)) * 0.25f
        score += (f.centerBrightness / (f.edgeBrightness + 0.01f)).coerceIn(0.5f, 2f) * 0.5f * 0.25f
        score += (if (f.isSquarish || f.aspectRatio in 0.65f..0.8f) 1f else 0.3f) * 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun nightScore(f: ImageFeatures): Float {
        // 夜景: 低整体亮度、高中心-边缘亮度和对比、点光源
        var score = 0f
        score += (1f - f.avgBrightness).coerceIn(0.4f, 1f) * 0.4f
        val centerEdgeRatio = f.centerBrightness / (f.edgeBrightness + 0.01f)
        score += (centerEdgeRatio.coerceIn(1f, 3f) / 3f) * 0.2f
        score += f.contrast.coerceIn(0f, 0.3f) * 1.5f * 0.2f
        score += (1f - f.avgSaturation).coerceIn(0f, 1f) * 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun macroScore(f: ImageFeatures): Float {
        // 微距: 中心高细节 + 边缘模糊 (亮度过渡)、高蓝绿
        var score = 0f
        val centerEdgeDiff = kotlin.math.abs(f.centerBrightness - f.edgeBrightness)
        score += centerEdgeDiff.coerceIn(0.1f, 0.5f) * 1.5f * 0.35f
        score += f.blueGreenRatio.coerceIn(0f, 1f) * 0.25f
        score += f.centerSaturation.coerceIn(0.3f, 1f) * 0.2f
        score += (1f - f.avgBrightness).coerceIn(0f, 0.5f) * 1f * 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun screenshotScore(f: ImageFeatures): Float {
        // 纯截图: 特定比例 + 高饱和度 UI 色 + 没有自然纹理
        var score = 0f
        val isScreenAspect = f.aspectRatio in 1.5f..2.0f || f.aspectRatio in 0.5f..0.67f
        score += (if (isScreenAspect) 1f else 0.1f) * 0.4f
        // 截图通常有高饱和度的纯色块
        score += f.avgSaturation.coerceIn(0.3f, 0.9f) * 0.3f
        score += f.avgBrightness.coerceIn(0.6f, 1f) * 0.15f
        score += (if (f.width in 750..2600 && f.height in 400..1700) 1f else 0.3f) * 0.15f
        return score.coerceIn(0f, 1f)
    }

    private fun whiteboardScore(f: ImageFeatures): Float {
        // 白板: 高亮度 + 低饱和度 + 中高对比度
        var score = 0f
        score += f.avgBrightness.coerceIn(0.6f, 1f) * 0.35f
        score += (1f - f.avgSaturation).coerceIn(0.5f, 1f) * 0.35f
        score += f.contrast.coerceIn(0f, 0.15f) * 3f * 0.3f
        return score.coerceIn(0f, 1f)
    }

    private fun outdoorScore(f: ImageFeatures): Float {
        // 户外: 高蓝绿占比 + 高亮度
        var score = 0f
        score += f.blueGreenRatio.coerceIn(0.5f, 1.5f) * 0.4f
        score += f.avgBrightness.coerceIn(0.3f, 1f) * 0.35f
        score += f.avgSaturation.coerceIn(0.2f, 0.7f) * 0.25f
        return score.coerceIn(0f, 1f)
    }

    private fun indoorScore(f: ImageFeatures): Float {
        // 室内: 低蓝绿 + 暖色调 (低色温) + 中等亮度
        var score = 0f
        val warmth = ((f.avgRed - f.avgBlue).coerceIn(0f, 0.25f) / 0.25f)
        score += warmth * 0.35f
        score += (1f - f.blueGreenRatio.coerceIn(0.5f, 1.5f)).coerceIn(0f, 1f) * 0.35f
        score += f.avgBrightness.coerceIn(0.2f, 0.7f) * 0.3f
        return score.coerceIn(0f, 1f)
    }

    companion object {
        const val MAX_DIMENSION = 384
        /** 置信度阈值，低于此值返回 UNKNOWN */
        const val CONFIDENCE_THRESHOLD = 0.35f
    }
}