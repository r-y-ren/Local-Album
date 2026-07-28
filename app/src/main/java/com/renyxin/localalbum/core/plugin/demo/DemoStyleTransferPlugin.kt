package com.renyxin.localalbum.core.plugin.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo 风格迁移插件（内置演示版）。
 *
 * 使用 ColorMatrix 变换 + 边缘提取实现三种艺术风格滤镜，无需外部模型：
 * - vintage: 复古暖色调（Sepia + 暖色偏移）
 * - sketch: 素描效果（灰度 + 边缘增强）
 * - neon: 霓虹风格（高对比 + 色相旋转 + 暗角）
 *
 * 清单参数使用真实 ONNX 风格迁移模型格式（如 CartoonGAN/AdaIN），方便替换。
 */
class DemoStyleTransferPlugin : GenerativePlugin {

    private lateinit var manifest: PluginManifest
    private var ready = false

    /** 当前选择的风格: "vintage", "sketch", "neon" */
    private var activeStyle = "vintage"

    override fun getId() = "demo.style_transfer"

    override fun getManifest(): PluginManifest {
        if (!::manifest.isInitialized) {
            val json = """
            {
                "pluginId": "demo.style_transfer",
                "name": "Style Transfer Demo",
                "version": "1.0.0",
                "author": "LocalAlbum Demo",
                "taskType": "GENERATIVE",
                "modelFormat": "ONNX",
                "modelFilePath": "models/style_demo.onnx",
                "entryClass": "com.renyxin.localalbum.core.plugin.demo.DemoStyleTransferPlugin",
                "inputTensors": [
                    {"name": "content", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]},
                    {"name": "style", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]}
                ],
                "outputTensors": [
                    {"name": "output", "dataType": "FLOAT32", "shape": [1, 3, 512, 512]}
                ],
                "preprocessing": {
                    "resizeWidth": 512, "resizeHeight": 512,
                    "normalizeMean": [0.485, 0.456, 0.406],
                    "normalizeStd": [0.229, 0.224, 0.225]
                },
                "pipelineStage": "generative",
                "description": "[Demo] 内置演示风格迁移插件: 复古/素描/霓虹三种滤镜, 无需外部模型。替换 style_demo.onnx 为真实模型即可启用完整功能。"
            }
            """.trimIndent()
            manifest = PluginJsonCodec.manifestFromJson(json)
        }
        return manifest
    }

    override fun getInputSchema() = listOf(
        com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec(
            "content", com.renyxin.localalbum.core.plugin.FeatureSchema.DataType.FLOAT_ARRAY, listOf(1, 3, 512, 512),
        ),
    )

    override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(
        pluginId = getId(), featureType = "generated_image", fields = listOf(),
    )

    override suspend fun initialize(context: Context, pluginContext: PluginContext) {
        ready = true
    }

    override fun isReady() = ready

    /**
     * 切换风格。支持: "vintage", "sketch", "neon"
     */
    fun setStyle(style: String) {
        activeStyle = style
    }

    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput = withContext(Dispatchers.IO) {
        val imageInput = when (input) {
            is PluginInput.ImageInput -> input
            is PluginInput.MultiModalInput -> input.inputs.firstOrNull { it is PluginInput.ImageInput } as? PluginInput.ImageInput
            else -> null
        } ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = "")

        val sourcePath = imageInput.filePath
        val bitmap = imageInput.bitmap ?: decodeBitmap(sourcePath)
            ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = sourcePath)

        val result = when (activeStyle) {
            "sketch" -> applySketch(bitmap)
            "neon" -> applyNeon(bitmap)
            else -> applyVintage(bitmap)
        }

        PluginOutput.ImageOutput(bitmap = result, sourcePath = sourcePath)
    }

    override suspend fun release() {
        ready = false
    }

    // ---- 风格滤镜 ----

    /** 复古暖色调: Sepia + 暖色偏移 + 降低对比度 */
    private fun applyVintage(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val cm = ColorMatrix().apply {
            setSaturation(0.6f) // 降低饱和度
        }
        // Sepia 矩阵
        val sepia = ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 20f,
            0.349f, 0.686f, 0.168f, 0f, 10f,
            0.272f, 0.534f, 0.131f, 0f, -5f,
            0f, 0f, 0f, 1f, 0f,
        ))
        cm.postConcat(sepia)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /** 素描效果: 灰度 + Sobel 边缘检测叠加 */
    private fun applySketch(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 转为灰度
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        // 简单 Sobel 边缘检测 (X + Y)
        val edge = FloatArray(w * h)
        var maxEdge = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = -gray[idx - w - 1] + gray[idx - w + 1]
                -2 * gray[idx - 1] + 2 * gray[idx + 1]
                -gray[idx + w - 1] + gray[idx + w + 1]
                val gy = -gray[idx - w - 1] - 2 * gray[idx - w] - gray[idx - w + 1]
                +gray[idx + w - 1] + 2 * gray[idx + w] + gray[idx + w + 1]
                edge[idx] = kotlin.math.sqrt(gx * gx + gy * gy)
                if (edge[idx] > maxEdge) maxEdge = edge[idx]
            }
        }

        // 生成素描图像: 白底 + 黑边
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val resultPixels = IntArray(w * h)
        for (i in pixels.indices) {
            val invEdge = if (maxEdge > 0) (1f - (edge[i] / maxEdge).coerceIn(0f, 1f)) else 1f
            val v = (invEdge * 240f + 15f).toInt().coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        result.setPixels(resultPixels, 0, w, 0, 0, w, h)
        return result
    }

    /** 霓虹风格: 高对比 + 色相旋转 + 暗角 */
    private fun applyNeon(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // 高对比度 + 色相偏移
        val cm = ColorMatrix().apply {
            setScale(1.3f, 1.3f, 1.3f, 1f) // 提高亮度
            // 色相旋转 ~180度 (蓝→橙, 红→青)
            set(floatArrayOf(
                -0.3f, 0.7f, 0.6f, 0f, 30f,
                0.8f, -0.1f, 0.3f, 0f, 20f,
                0.5f, 0.4f, -0.2f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(source, 0f, 0f, paint)

        // 暗角效果: 四角绘制半透明黑色
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0..3) {
            val alpha = (60 + i * 30).coerceAtMost(200)
            vignettePaint.color = android.graphics.Color.argb(alpha, 0, 0, 0)
            vignettePaint.style = Paint.Style.STROKE
            vignettePaint.strokeWidth = (w * 0.12f) * (4 - i)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignettePaint)
        }

        return result
    }

    private fun decodeBitmap(path: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (File(path).length() > 5 * 1024 * 1024) 2 else 1
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) { null }
    }
}
