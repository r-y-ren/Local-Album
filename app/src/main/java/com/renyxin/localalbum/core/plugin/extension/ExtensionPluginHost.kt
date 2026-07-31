package com.renyxin.localalbum.core.plugin.extension

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 扩展插件交互式调用宿主（Phase 3）。
 *
 * 提供便捷方法，让 UI 层（[MediaViewerScreen]、[FacesScreen] 等）能轻松调用扩展插件，
 * 无需关心 [PluginInput] 的构造细节和 [PluginOutput] 的解包逻辑。
 *
 * ## 使用示例
 *
 * ```kotlin
 * // 换脸
 * val result = host.faceSwap(
 *     plugin = reactorPlugin,
 *     targetImage = File("/sdcard/photo.jpg"),
 *     sourceEmbedding = floatArrayOf(...),
 * )
 * // result?.bitmap → 显示给用户
 * ```
 */
object ExtensionPluginHost {

    /**
     * 换脸操作：将源人脸嵌入应用到目标图像中的人脸上。
     *
     * @param plugin 已就绪的生成式插件
     * @param targetImage 目标图像文件（底图）
     * @param sourceEmbedding 源人脸的嵌入向量
     * @param sourceFaceBox 源人脸在目标图中的边界框（可选，用于裁剪目标人脸区域）
     * @return 生成结果，含 [Bitmap] 和输出路径
     */
    @Deprecated(
        message = "输入约定与 InSwapperPlugin 不兼容：本方法构造 (targetImage + sourceEmbedding TensorInput)，" +
            "而 InSwapperPlugin 约定 MultiModalInput 首图=源、次图=目标，且自行重新检测提取嵌入（忽略传入的 embedding）。" +
            "若按此调用 InSwapperPlugin，filterIsInstance<ImageInput>() 后只剩 target 一张图，会退化为 target 自换脸。" +
            "实际换脸请走 PluginViewModel.performFaceSwap（首图源、次张目标，约定正确）。",
        level = DeprecationLevel.WARNING,
    )
    suspend fun faceSwap(
        plugin: GenerativePlugin,
        targetImage: File,
        sourceEmbedding: FloatArray,
        @Suppress("UNUSED_PARAMETER") sourceFaceBox: android.graphics.RectF? = null,
    ): PluginOutput.ImageOutput? {
        val targetInput = PluginInput.ImageInput(
            file = targetImage,
            bitmap = decodeBitmap(targetImage),
        )
        val embeddingInput = PluginInput.TensorInput(
            data = sourceEmbedding,
            shape = listOf(1, sourceEmbedding.size),
        )
        // 构建多模态输入：目标图像 + 源人脸嵌入
        // sourceFaceBox 保留用于未来实现人脸区域裁剪
        val inputs = PluginInput.MultiModalInput(listOf(targetInput, embeddingInput))

        return if (plugin.isReady()) {
            plugin.execute(inputs)
        } else null
    }

    /**
     * 风格迁移：将风格参考图的艺术风格应用到内容图上。
     *
     * @param plugin 已就绪的生成式插件
     * @param contentImage 内容图像（保持内容结构）
     * @param styleImage 风格参考图像（提取艺术风格）
     */
    suspend fun styleTransfer(
        plugin: GenerativePlugin,
        contentImage: File,
        styleImage: File,
    ): PluginOutput.ImageOutput? {
        val inputs = PluginInput.MultiModalInput(listOf(
            PluginInput.ImageInput(file = contentImage),
            PluginInput.ImageInput(file = styleImage),
        ))
        return if (plugin.isReady()) {
            plugin.execute(inputs)
        } else null
    }

    /**
     * 图像增强/超分。
     *
     * @param plugin 已就绪的生成式插件
     * @param image 待增强的图像
     */
    suspend fun enhance(
        plugin: GenerativePlugin,
        image: File,
    ): PluginOutput.ImageOutput? {
        val input = PluginInput.ImageInput(file = image)
        return if (plugin.isReady()) {
            plugin.execute(input)
        } else null
    }

    /**
     * 单图处理（分类/检测/特征提取等）。
     *
     * @param plugin 任意已就绪的 AiPlugin
     * @param image 输入图像
     * @return 插件输出
     */
    suspend fun processImage(
        plugin: com.renyxin.localalbum.core.plugin.AiPlugin,
        image: File,
    ): PluginOutput? {
        if (!plugin.isReady()) return null
        val input = PluginInput.ImageInput(file = image)
        return plugin.execute(input)
    }

    // ---- Internal ----

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (file.length() > 10 * 1024 * 1024) 2 else 1
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}
