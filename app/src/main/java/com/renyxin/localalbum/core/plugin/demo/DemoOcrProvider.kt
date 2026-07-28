package com.renyxin.localalbum.core.plugin.demo

import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import java.io.File

/**
 * Demo OCR Provider — 空实现，仅展示槽位切换。
 *
 * 提供 `demo:noop_ocr` 作为 OCR 槽位的第二选项。
 */
class DemoOcrProvider : OcrProvider {
    override val providerId = "demo:noop_ocr"
    override val displayName = "OCR 占位 (Demo 演示)"

    override suspend fun recognize(file: File): OcrProvider.OcrResult {
        return OcrProvider.OcrResult(
            fullText = "[Demo OCR - 无实际识别功能]",
            averageConfidence = 0f,
        )
    }

    override suspend fun release() {}
}
