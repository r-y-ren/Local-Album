package com.renyxin.localalbum.core.plugin.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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
 * Demo 换脸插件（内置演示版）。
 *
 * 使用纯图像处理实现简单的"换脸"效果，无需外部 ONNX 模型：
 * 1. 检测图像中心区域（假定为人脸区域）
 * 2. 根据源嵌入向量的主色调进行颜色迁移
 * 3. 椭圆羽化边缘混合
 *
 * 清单参数使用真实 ReActor/InSwapper 模型格式，方便用户替换为真实模型。
 */
class DemoFaceSwapPlugin : GenerativePlugin {

    private lateinit var manifest: PluginManifest
    private var ready = false

    override fun getId() = "demo.face_swap"

    override fun getManifest(): PluginManifest {
        if (!::manifest.isInitialized) {
            val json = """
            {
                "pluginId": "demo.face_swap",
                "name": "Face Swap Demo",
                "version": "1.0.0",
                "author": "LocalAlbum Demo",
                "taskType": "GENERATIVE",
                "modelFormat": "ONNX",
                "modelFilePath": "models/inswapper_demo.onnx",
                "entryClass": "com.renyxin.localalbum.core.plugin.demo.DemoFaceSwapPlugin",
                "inputTensors": [
                    {"name": "target", "dataType": "FLOAT32", "shape": [1, 3, 256, 256]},
                    {"name": "source", "dataType": "FLOAT32", "shape": [1, 512]}
                ],
                "outputTensors": [
                    {"name": "output", "dataType": "FLOAT32", "shape": [1, 3, 256, 256]}
                ],
                "preprocessing": {
                    "resizeWidth": 256, "resizeHeight": 256,
                    "normalizeMean": [0.5, 0.5, 0.5],
                    "normalizeStd": [0.5, 0.5, 0.5]
                },
                "pipelineStage": "generative",
                "description": "[Demo] 内置演示换脸插件: 像素级颜色迁移 + 边缘羽化, 无需外部模型。替换 inswapper_demo.onnx 为真实模型即可启用完整功能。"
            }
            """.trimIndent()
            manifest = PluginJsonCodec.manifestFromJson(json)
        }
        return manifest
    }

    override fun getInputSchema() = listOf(
        PluginManifest.TensorSpec("target", PluginManifest.TensorDataType.FLOAT32, listOf(1, 3, 256, 256)),
    ).map { com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec(it.name, com.renyxin.localalbum.core.plugin.FeatureSchema.DataType.FLOAT_ARRAY, it.shape) }

    override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(
        pluginId = getId(), featureType = "generated_image", fields = listOf(),
    )

    override suspend fun initialize(context: Context, pluginContext: PluginContext) {
        ready = true
    }

    override fun isReady() = ready

    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput = withContext(Dispatchers.IO) {
        val imageInput = when (input) {
            is PluginInput.ImageInput -> input
            is PluginInput.MultiModalInput -> input.inputs.firstOrNull { it is PluginInput.ImageInput } as? PluginInput.ImageInput
                ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = "")
            else -> return@withContext PluginOutput.ImageOutput(null, sourcePath = "")
        }

        val sourcePath = imageInput.filePath
        val bitmap = imageInput.bitmap ?: decodeBitmap(sourcePath)
            ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = sourcePath)

        // 提取源嵌入向量的颜色基调（若无嵌入则使用随机暖色调）
        val sourceEmbedding: FloatArray? = when (input) {
            is PluginInput.MultiModalInput -> {
                val tensor = input.inputs.firstOrNull { it is PluginInput.TensorInput } as? PluginInput.TensorInput
                tensor?.data
            }
            else -> null
        }

        val result = simulateFaceSwap(bitmap, sourceEmbedding)

        PluginOutput.ImageOutput(bitmap = result, sourcePath = sourcePath)
    }

    override suspend fun release() {
        ready = false
    }

    /**
     * 模拟换脸：中心椭圆区域颜色迁移 + 羽化边缘
     */
    private fun simulateFaceSwap(source: Bitmap, embedding: FloatArray?): Bitmap {
        val w = source.width
        val h = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // 从嵌入向量推导色调偏移
        val hueShift = if (embedding != null && embedding.size >= 3) {
            floatArrayOf(
                (embedding[0].coerceIn(-1f, 1f) * 30f),
                (embedding[1].coerceIn(-1f, 1f) * 20f),
                (embedding[2].coerceIn(-1f, 1f) * 15f),
            )
        } else {
            floatArrayOf(15f, -5f, -10f) // 默认暖色调偏移
        }

        // 中心椭圆区域 (人脸位置假设)
        val cx = w / 2f
        val cy = h * 0.4f
        val rx = w * 0.2f
        val ry = h * 0.25f

        // 创建羽化蒙版
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        maskCanvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)

        // 高斯模糊蒙版实现羽化 (使用简单缩放模糊近似)
        val blurredMask = simpleBlur(maskBitmap, (w * 0.08f).toInt().coerceAtLeast(5))
        maskBitmap.recycle()

        // 颜色迁移层
        val colorLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) + hueShift[0].toInt()
            val g = ((p shr 8) and 0xFF) + hueShift[1].toInt()
            val b = (p and 0xFF) + hueShift[2].toInt()
            pixels[i] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
        }
        colorLayer.setPixels(pixels, 0, w, 0, 0, w, h)

        // 使用蒙版混合
        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val maskPixels = IntArray(w * h)
        blurredMask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        blurredMask.recycle()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val maskAlpha = (maskPixels[idx] and 0xFF) / 255f
                if (maskAlpha > 0.01f) {
                    blendPaint.alpha = (maskAlpha * 200f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(colorLayer,
                        Rect(x, y, x + 1, y + 1),
                        RectF(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat()),
                        blendPaint,
                    )
                }
            }
        }
        colorLayer.recycle()
        return result
    }

    private fun simpleBlur(source: Bitmap, radius: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source,
            (source.width / radius).coerceAtLeast(1),
            (source.height / radius).coerceAtLeast(1), true)
        return Bitmap.createScaledBitmap(scaled, source.width, source.height, true)
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
