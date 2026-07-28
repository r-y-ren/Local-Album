package com.renyxin.localalbum.core.plugin.capability

import java.io.File

/**
 * OCR 文字识别提供者接口。
 *
 * 封装 OCR 文字识别模型，从图像中提取文字内容。
 * 每种实现（MLKit、PaddleOCR、Tesseract 等）提供不同的语言支持/精度权衡。
 *
 * ## 使用场景
 * - 管道批处理：[OcrStage] 逐图调用 [recognize]
 * - 搜索：关键词搜索 → 匹配 OCR 识别出的文字
 *
 * @see com.renyxin.localalbum.core.plugin.capability.builtin.MlKitOcrProvider 内置默认实现
 */
interface OcrProvider {
    /** Provider 唯一标识 */
    val providerId: String

    /** 人类可读显示名称 */
    val displayName: String

    /**
     * 识别单张图片中的文字。
     *
     * @param file 图片文件
     * @return OCR 结果，未识别到文字时 [OcrResult.fullText] 为空字符串
     */
    suspend fun recognize(file: File): OcrResult

    /** 释放模型资源 */
    suspend fun release()

    /**
     * OCR 识别结果。
     *
     * @property fullText 识别的全部文字
     * @property lines 文本行列表
     * @property blocks 文本块列表
     * @property averageConfidence 置信度估算 (0~1)
     */
    data class OcrResult(
        val fullText: String,
        val lines: List<String> = emptyList(),
        val blocks: List<TextBlock> = emptyList(),
        val averageConfidence: Float = 0f,
    )

    /**
     * OCR 文本块。
     *
     * @property text 块内文字
     * @property lines 块内文本行
     * @property confidence 置信度
     */
    data class TextBlock(
        val text: String,
        val lines: List<String> = emptyList(),
        val confidence: Float = 0f,
    )
}
