package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.renyxin.localalbum.core.plugin.DetectionPlugin
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MobileSAM 分割插件 — Meta 发布的轻量级 Segment Anything Model。
 *
 * MobileSAM 可以在移动设备上运行图像分割，自动识别图像中的主要物体区域。
 * 作为 GenerativePlugin，输出分割后的图像（背景替换/抠图）。
 *
 * ## 模型信息
 *
 * - 模型: MobileSAM (TFLite), encoder-decoder 架构
 * - 输入: 1024×1024 RGB, FLOAT32
 * - 输出: 256×256 mask (1 通道 FLOAT32)
 * - 大小: ~40MB
 * - 下载: HuggingFace
 */
class MobileSAMPlugin(
    private val modelManager: ModelManager? = null,
) : GenerativePlugin {

    companion object {
        private const val TAG = "MobileSAM"
        const val MODEL_ID = "model:mobilesam"
        const val MODEL_FILE = "mobile_sam_encoder.tflite"
        const val MODEL_URL =
            "https://huggingface.co/dhkim2810/MobileSAM/resolve/main/mobile_sam.pt"
        const val INPUT_SIZE = 1024
        const val MASK_SIZE = 256
    }

    private lateinit var manifest: PluginManifest
    private var interpreter: Interpreter? = null
    private var ready = false
    private lateinit var appContext: Context

    override fun getId() = "builtin.mobilesam"

    override fun getManifest(): PluginManifest {
        if (!::manifest.isInitialized) {
            val json = """
            {
                "pluginId": "builtin.mobilesam",
                "name": "MobileSAM 智能分割",
                "version": "1.0.0",
                "author": "Meta / LocalAlbum",
                "taskType": "GENERATIVE",
                "modelFormat": "TFLITE",
                "modelFilePath": "models/$MODEL_FILE",
                "entryClass": "com.renyxin.localalbum.core.plugin.model.MobileSAMPlugin",
                "inputTensors": [
                    {"name": "image", "dataType": "FLOAT32", "shape": [1, 3, 1024, 1024]}
                ],
                "outputTensors": [
                    {"name": "mask", "dataType": "FLOAT32", "shape": [1, 1, 256, 256]}
                ],
                "preprocessing": {
                    "resizeWidth": 1024, "resizeHeight": 1024,
                    "normalizeMean": [0.485, 0.456, 0.406],
                    "normalizeStd": [0.229, 0.224, 0.225]
                },
                "pipelineStage": "generative",
                "description": "MobileSAM 智能分割: 自动识别并分离图像中的主体物体, 支持背景替换"
            }
            """.trimIndent()
            manifest = PluginJsonCodec.manifestFromJson(json)
        }
        return manifest
    }

    override fun getInputSchema() = listOf(
        com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec(
            "image", com.renyxin.localalbum.core.plugin.FeatureSchema.DataType.FLOAT_ARRAY, listOf(1, 3, 1024, 1024),
        ),
    )

    override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(
        pluginId = getId(), featureType = "segmentation_mask", fields = listOf(),
    )

    override suspend fun initialize(context: Context, pluginContext: PluginContext) {
        appContext = context.applicationContext
        try {
            // 优先使用注入的 ModelManager，回退到静态下载管理器
            val mm = modelManager
            if (mm != null) {
                mm.registerModel(
                    ModelManager.ModelDescriptor(
                        modelId = MODEL_ID,
                        displayName = "MobileSAM Encoder",
                        format = PluginManifest.ModelFormat.TFLITE,
                        fileUrl = MODEL_URL,
                        inputShape = listOf(1, INPUT_SIZE, INPUT_SIZE, 3),
                        outputShape = intArrayOf(1, 1, MASK_SIZE, MASK_SIZE),
                        fileSizeBytes = 40_000_000,
                        memoryFootprintBytes = 80_000_000,
                    )
                )
                val result = mm.ensureModelReady(MODEL_ID)
                if (result.isSuccess) {
                    ready = true
                    Log.i(TAG, "MobileSAM 模型通过 ModelManager 加载成功")
                } else {
                    Log.w(TAG, "MobileSAM 模型未就绪: ${result.exceptionOrNull()?.message}")
                }
            } else {
                // 回退到静态下载管理器
                val modelFile = ModelDownloadManager.ensureModel(appContext, MODEL_FILE, MODEL_URL)
                if (modelFile != null) {
                    NativeAiRuntime.ensureNativePrerequisite()
                    interpreter = Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(4) })
                    ready = true
                    Log.i(TAG, "MobileSAM 模型加载成功（静态下载）")
                } else {
                    Log.w(TAG, "MobileSAM 模型下载失败")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MobileSAM 初始化失败", e)
            ready = false
        }
    }

    override fun isReady() = ready

    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput = withContext(Dispatchers.IO) {
        val imageInput = when (input) {
            is PluginInput.ImageInput -> input
            is PluginInput.MultiModalInput -> input.inputs.firstOrNull { it is PluginInput.ImageInput } as? PluginInput.ImageInput
            else -> null
        } ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = "")

        val sourcePath = imageInput.filePath
        val bitmap = imageInput.bitmap ?: decodeBitmap(sourcePath)
            ?: return@withContext PluginOutput.ImageOutput(null, sourcePath = sourcePath)

        try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = preprocess(resized)
            if (resized != bitmap) resized.recycle()

            val outputMask = Array(1) { Array(1) { FloatArray(MASK_SIZE * MASK_SIZE) } }
            val mm = modelManager
            if (mm != null) {
                mm.withInterpreter(MODEL_ID) { it.run(inputBuffer, outputMask) }
            } else {
                requireNotNull(interpreter) { "MobileSAM Interpreter 未就绪" }.run(inputBuffer, outputMask)
            }

            val result = applyMask(bitmap, outputMask[0][0])
            PluginOutput.ImageOutput(bitmap = result, sourcePath = sourcePath)
        } catch (e: Exception) {
            Log.w(TAG, "MobileSAM 推理失败", e)
            PluginOutput.ImageOutput(null, sourcePath = sourcePath)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override suspend fun release() {
        // ModelManager 所有的池化 Interpreter 由其统一管理；仅关闭静态回退实例。
        if (modelManager == null) interpreter?.close()
        interpreter = null
        ready = false
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val fb = buffer.asFloatBuffer()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            fb.put((((p shr 16) and 0xFF) / 255f - 0.485f) / 0.229f)
            fb.put((((p shr 8) and 0xFF) / 255f - 0.456f) / 0.224f)
            fb.put(((p and 0xFF) / 255f - 0.406f) / 0.225f)
        }
        buffer.rewind()
        return buffer
    }

    /** 将 mask 应用到原图：保留主体，背景半透明灰色 */
    private fun applyMask(source: Bitmap, mask: FloatArray): Bitmap {
        val sw = source.width
        val sh = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(sw * sh)
        result.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val mx = (x.toFloat() / sw * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
                val my = (y.toFloat() / sh * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
                val m = mask[my * MASK_SIZE + mx].coerceIn(0f, 1f)

                if (m < 0.5f) {
                    val idx = y * sw + x
                    val p = pixels[idx]
                    val r = ((p shr 16) and 0xFF)
                    val g = ((p shr 8) and 0xFF)
                    val b = (p and 0xFF)
                    // 背景混合浅灰
                    val blendR = (r * m + 200 * (1 - m)).toInt()
                    val blendG = (g * m + 200 * (1 - m)).toInt()
                    val blendB = (b * m + 200 * (1 - m)).toInt()
                    pixels[idx] = (0xFF shl 24) or
                        (blendR.coerceIn(0, 255) shl 16) or
                        (blendG.coerceIn(0, 255) shl 8) or
                        blendB.coerceIn(0, 255)
                }
            }
        }
        result.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        return result
    }

    private fun decodeBitmap(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
        } catch (_: Exception) { null }
    }
}
