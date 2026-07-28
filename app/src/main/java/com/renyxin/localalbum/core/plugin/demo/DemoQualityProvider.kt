package com.renyxin.localalbum.core.plugin.demo

import android.graphics.BitmapFactory
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo 质量评估 Provider — 基于图片分辨率的简单评分。
 *
 * 提供 `demo:simple_quality` 作为质量槽位的第二选项。
 */
class DemoQualityProvider : QualityProvider {
    override val providerId = "demo:simple_quality"
    override val displayName = "简易质量评估 (Demo)"

    override suspend fun assess(file: File): QualityProvider.QualityResult = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return@withContext QualityProvider.QualityResult(0f, 0f, 0f, 0f, 0f, true)

        val megapixels = (w * h) / 1_000_000f
        // 分辨率评分: 8MP=1.0, 2MP=0.5, <1MP=0.2
        val resolutionScore = (megapixels / 8f).coerceIn(0.1f, 1f)
        // 文件大小评分
        val fileSizeScore = (file.length() / (5 * 1024 * 1024f)).coerceIn(0.2f, 0.9f)
        val overall = (resolutionScore * 0.7f + fileSizeScore * 0.3f)

        QualityProvider.QualityResult(
            overall = overall,
            blurScore = 0.6f, // 无法检测模糊
            exposureScore = 0.7f,
            contrastScore = 0.5f,
            colorScore = 0.6f,
            isTooBlurry = false,
        )
    }

    override suspend fun release() {}
}
