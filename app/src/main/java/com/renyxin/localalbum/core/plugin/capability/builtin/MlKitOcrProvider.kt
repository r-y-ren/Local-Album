package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import com.renyxin.localalbum.core.analysis.OcrProvider as AnalysisOcrProvider
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import java.io.File

/**
 * 基于 ML Kit 的内置默认 OCR Provider。
 *
 * 包裹项目现有的 [AnalysisOcrProvider]，将其适配为 [OcrProvider] 接口。
 * 同时支持拉丁语系和中文识别。
 *
 * 可被 PaddleOCR/Tesseract 等 OCR 引擎替换。
 */
class MlKitOcrProvider(
    private val context: Context,
) : OcrProvider {

    override val providerId = "builtin:mlkit"
    override val displayName = "ML Kit OCR (默认)"

    private val ocr = AnalysisOcrProvider(context)

    override suspend fun recognize(file: File): OcrProvider.OcrResult {
        // 优先中文，降级拉丁
        val result = ocr.recognize(file, preferChinese = true)
        if (result == null) {
            return OcrProvider.OcrResult(fullText = "")
        }
        return OcrProvider.OcrResult(
            fullText = result.fullText,
            lines = result.lines,
            blocks = result.blocks.map { block ->
                OcrProvider.TextBlock(
                    text = block.text,
                    lines = block.lines,
                    confidence = block.confidence,
                )
            },
            averageConfidence = result.averageConfidence,
        )
    }

    override suspend fun release() {
        // ML Kit 客户端无需显式释放
    }
}
