package com.renyxin.localalbum.core.plugin.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.plugin.GenerativePlugin
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * InSwapper 换脸插件 — 使用 inswapper_128.onnx 进行真实换脸（拓展功能）。
 *
 * 作为交互式拓展插件，不参与批处理管道，由用户在详情页/插件管理页主动触发。
 *
 * ## 推理流程（对齐 ComfyUI-ReActor / InsightFace 原生实现）
 *
 * 1. 从源图检测人脸 → 取最大脸的 ArcFace 嵌入（512 维），L2 归一化得 normed_embedding
 * 2. **emap 变换**：`latent = normed_embedding @ emap`（emap 从模型 initializer 提取，构建期
 *    导出为 assets/models/emap_512.bin），再 L2 归一化 —— inswapper_128.onnx 的强制要求
 * 3. 从目标图检测人脸 → 取最大脸 5 关键点，用相似变换（estimateAffinePartial2D）+ X 偏移
 *    对齐到 128×128（保存仿射矩阵 M）
 * 4. inswapper_128.onnx 输入：target=[1,3,128,128]（对齐底图）、source=[1,512]（emap latent）
 *    输出：output=[1,3,128,128]（换脸后裁剪）
 * 5. **diff-mask 贴回**：用 |fake - alignedTarget| 差异图 + 全白 mask 逆变换回原图，
 *    erode/dilate/GaussianBlur 生成软 mask，alpha 混合（InsightFace 原生 paste-back）
 *
 * ## 输入约定
 *
 * [PluginInput.MultiModalInput] 携带两张图：首张为**源人脸图**，次张为**目标图**。
 * 若仅传入单张图，则退化为"自换脸"（源=目标），仅作演示。
 *
 * @param modelManager 模型管理器（管理 inswapper_128.onnx 生命周期）
 * @param faceProviderFactory 人脸能力工厂，惰性获取当前激活的 [FaceProvider]（检测+嵌入）
 */
class InSwapperPlugin(
    private val modelManager: ModelManager,
    private val faceProviderFactory: () -> FaceProvider? = { null },
) : GenerativePlugin {

    companion object {
        private const val TAG = "InSwapper"
        const val MODEL_ID = "model:inswapper"
        private const val INPUT_SIZE = 128
        private const val SOURCE_EMBED_DIM = 512
        /** emap 矩阵维度（inswapper_128 最后一个 initializer 为 512×512） */
        private const val EMAP_DIM = 512
        /** assets 中 emap 二进制文件路径（构建期由 scripts/extract_emap.py 导出） */
        private const val EMAP_ASSET_PATH = "models/emap_512.bin"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var manifest: PluginManifest
    private var ready = false
    /** emap 矩阵（行主序，[EMAP_DIM]×[EMAP_DIM]），null 表示未加载（将退回无 emap 路径） */
    private var emap: FloatArray? = null
    /** initialize 时保存的 Context，用于从 assets 加载 emap */
    private var appContext: Context? = null

    override fun getId() = "plugin.inswapper"

    override fun getManifest(): PluginManifest {
        if (!::manifest.isInitialized) {
            val json = """
            {
                "pluginId": "plugin.inswapper",
                "name": "InSwapper 换脸",
                "version": "1.1.0",
                "author": "LocalAlbum",
                "taskType": "GENERATIVE",
                "modelFormat": "ONNX",
                "modelFilePath": "models/inswapper_128.onnx",
                "entryClass": "com.renyxin.localalbum.core.plugin.extension.InSwapperPlugin",
                "inputTensors": [
                    {"name": "target", "dataType": "FLOAT32", "shape": [1, 3, 128, 128]},
                    {"name": "source", "dataType": "FLOAT32", "shape": [1, 512]}
                ],
                "outputTensors": [
                    {"name": "output", "dataType": "FLOAT32", "shape": [1, 3, 128, 128]}
                ],
                "preprocessing": {
                    "resizeWidth": 128, "resizeHeight": 128,
                    "normalizeMean": [0.0, 0.0, 0.0],
                    "normalizeStd": [255.0, 255.0, 255.0]
                },
                "pipelineStage": "generative",
                "description": "InSwapper 真实换脸插件: 检测人脸 → ArcFace 嵌入 → inswapper 推理 → 贴回原图"
            }
            """.trimIndent()
            manifest = PluginJsonCodec.manifestFromJson(json)
        }
        return manifest
    }

    override fun getInputSchema() = listOf(
        com.renyxin.localalbum.core.plugin.FeatureSchema.FieldSpec(
            "target", com.renyxin.localalbum.core.plugin.FeatureSchema.DataType.FLOAT_ARRAY,
            listOf(1, 3, INPUT_SIZE, INPUT_SIZE),
        ),
    )

    override fun getOutputSchema() = com.renyxin.localalbum.core.plugin.FeatureSchema(
        pluginId = getId(), featureType = "generated_image", fields = listOf(),
    )

    override suspend fun initialize(context: Context, pluginContext: PluginContext) {
        appContext = context.applicationContext
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = MODEL_ID,
                displayName = "InSwapper 128",
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, INPUT_SIZE, INPUT_SIZE),
                outputShape = intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE),
                fileSizeBytes = 554_300_000,
                memoryFootprintBytes = 20_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "inswapper_128.onnx",
            )
        )
        val result = modelManager.ensureModelReady(MODEL_ID)
        ready = result.isSuccess
        if (ready) Log.i(TAG, "InSwapper 模型就绪")
        else Log.w(TAG, "InSwapper 模型未就绪: ${result.exceptionOrNull()?.message}")

        // 校验 OpenCV 原生库已加载（换脸流水线依赖 Mat/warpAffine/estimateAffinePartial2D 等）。
        // 若未加载（如 .so 因 __emutls_get_address 等符号缺失而 dlopen 失败），标记未就绪，
        // 避免 execute 时抛 UnsatisfiedLinkError 闪退。
        val opencvOk = OpenCVLoader.initLocal()
        if (!opencvOk) {
            Log.e(TAG, "OpenCV 原生库加载失败，换脸插件不可用（请检查 libopencv_java5.so 是否适配当前 Android 版本）")
            ready = false
        } else {
            Log.i(TAG, "OpenCV 原生库已加载")
        }

        // 加载 emap 矩阵（inswapper_128 source latent 变换所必需）
        emap = loadEmap()
        if (emap == null) {
            Log.w(TAG, "emap 未加载: $EMAP_ASSET_PATH 缺失，换脸质量将严重下降（source latent 未变换）")
        } else {
            Log.i(TAG, "emap 加载完成: ${EMAP_DIM}×${EMAP_DIM}")
        }
    }

    /**
     * 从 assets 加载 emap 矩阵（float32 行主序，[EMAP_DIM]×[EMAP_DIM]）。
     * 文件由构建期脚本 scripts/extract_emap.py 从 inswapper_128.onnx 的最后一个 initializer 导出。
     */
    private fun loadEmap(): FloatArray? {
        return try {
            val ctx = appContext ?: return null
            val expected = EMAP_DIM * EMAP_DIM
            ctx.assets.open(EMAP_ASSET_PATH).use { input ->
                val bytes = ByteArray(expected * 4)
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read != bytes.size) {
                    Log.w(TAG, "emap 文件大小不符: 期望 ${bytes.size}，实际 $read")
                    return null
                }
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val arr = FloatArray(expected)
                buf.get(arr)
                arr
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载 emap 失败: ${e.message}")
            null
        }
    }

    /**
     * emap 变换：`latent = normedEmbed(1×512) @ emap(512×512)`，再 L2 归一化。
     * 这是 inswapper_128.onnx 对 source 输入的强制要求（参照 ReActor inswap.py:INSwapper.get）。
     * 若 emap 未加载，则直接返回输入（降级，效果差但不崩溃）。
     */
    private fun applyEmap(normedEmbed: FloatArray): FloatArray {
        val m = emap ?: return normedEmbed
        val latent = FloatArray(EMAP_DIM)
        var sum = 0.0
        for (j in 0 until EMAP_DIM) {
            var s = 0f
            // emap 行主序: emap[i*EMAP_DIM + j] 表示第 i 行第 j 列
            // latent[j] = sum_i embed[i] * emap[i][j]
            var i = 0
            while (i < EMAP_DIM) {
                s += normedEmbed[i] * m[i * EMAP_DIM + j]
                i++
            }
            latent[j] = s
            sum += (s * s).toDouble()
        }
        val norm = sqrt(sum)
        if (norm > 1e-6) {
            val inv = (1.0 / norm).toFloat()
            for (i in latent.indices) latent[i] *= inv
        }
        return latent
    }

    override fun isReady() = ready

    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput = withContext(Dispatchers.IO) {
        Log.i(TAG, "换脸 execute 开始, input=${input::class.simpleName}")
        // 解析源图与目标图
        val images = when (input) {
            is PluginInput.MultiModalInput -> input.inputs.filterIsInstance<PluginInput.ImageInput>()
            is PluginInput.ImageInput -> listOf(input)
            else -> emptyList()
        }
        if (images.isEmpty()) {
            Log.w(TAG, "换脸失败: 无图像输入")
            return@withContext PluginOutput.ImageOutput(null, sourcePath = "")
        }
        Log.i(TAG, "换脸输入图像数: ${images.size}, paths=${images.map { it.filePath }}")

        val sourceInput = images.first()
        val targetInput = images.getOrElse(1) { sourceInput } // 单图：自换脸演示
        val targetPath = targetInput.filePath

        // N11/B1: 源图存在性/可解码性由 detectLargestFace 链路自然兜底，
        // 无需在此预解码 sourceBmp（该 Bitmap 解码后从未被消费，属死代码 + 内存泄漏）。
        // V4: 换脸为交互式单图操作，目标图必须全分辨率解码（禁用 inSampleSize 降采样），
        // 否则 >5MB 底图换脸结果会被贴回降采样图，输出分辨率直接减半。
        val targetBmp = targetInput.bitmap ?: decodeBitmapFullResolution(targetPath)
            ?: run {
                Log.w(TAG, "换脸失败: 目标图解码失败 $targetPath")
                return@withContext PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }
        Log.i(TAG, "目标图 ${targetBmp.width}x${targetBmp.height}")

        val faceProvider = faceProviderFactory()
        if (faceProvider == null) {
            Log.w(TAG, "换脸失败: FaceProvider 未就绪")
            return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
        }

        val session = modelManager.getOnnxSession(MODEL_ID)
        if (session == null) {
            Log.w(TAG, "换脸失败: InSwapper 会话未加载")
            return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
        }

        try {
            // ===== Reactor 流水线（对齐 InsightFace/ReActor 原生实现）=====

            // R5: 校验嵌入维度 —— inswapper_128 强制要求 ArcFace 512-dim
            if (faceProvider.embeddingDim != SOURCE_EMBED_DIM) {
                Log.w(TAG, "换脸失败: FaceProvider 嵌入维度 ${faceProvider.embeddingDim} ≠ $SOURCE_EMBED_DIM，" +
                    "请切换为 ArcFace/InsightFace 系 Provider")
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }

            // 1. 检测与对齐：源图 + 底图 SCRFD 检测 bbox + 5关键点
            val sourceFace = detectLargestFace(faceProvider, sourceInput.filePath)
            if (sourceFace == null || sourceFace.landmarks == null) {
                Log.w(TAG, "换脸失败: 源图未检测到人脸或无关键点 ${sourceInput.filePath}")
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }
            // N5: 源嵌入零范数检查 —— rec 模型未就绪时 InsightFaceProvider 会降级返回全零嵌入，
            // 全零嵌入经 emap 后仍为零 latent，inswapper 会输出模型相关的退化结果（与源无相似），
            // 且无任何错误提示（比 det 无脸更隐蔽的静默质量陷阱）。此处按失败处理（返回原图+日志）。
            var embedNorm = 0.0
            for (v in sourceFace.embedding) embedNorm += (v * v).toDouble()
            if (sqrt(embedNorm) < 1e-3) {
                Log.w(TAG, "换脸失败: 源人脸嵌入范数≈0（rec 识别模型可能未就绪），无法提取有效特征")
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }
            // R1: normed_embedding → emap 变换 → L2 归一化，得到 inswapper source latent
            val normedEmbed = normalizeTo(sourceFace.embedding, SOURCE_EMBED_DIM)
            val sourceLatent = applyEmap(normedEmbed)
            // 诊断：latent 范数与前 5 值（应为非零、范数≈1）
            var lnorm = 0.0
            for (v in sourceLatent) lnorm += (v * v).toDouble()
            Log.i(TAG, "源人脸嵌入: normed ${normedEmbed.size}d, emap latent ${sourceLatent.size}d, emapLoaded=${emap != null}, latentNorm=${sqrt(lnorm)}, latent[0..4]=${sourceLatent.take(5).joinToString(",")}")

            val targetFace = detectLargestFace(faceProvider, targetInput.filePath)
            if (targetFace == null || targetFace.landmarks == null) {
                Log.w(TAG, "换脸失败: 底图未检测到人脸或无关键点 $targetPath")
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }
            Log.i(TAG, "底图人脸框: ${targetFace.box}, 有关键点")

            // 2. 相似变换对齐底图人脸到 128×128（inswapper 输入），保存仿射矩阵 M
            val targetLandmarksPx = FaceAligner.denormalizeLandmarks(
                targetFace.landmarks!!, targetBmp.width, targetBmp.height
            )
            Log.i(TAG, "step: denormalize landmarks done, pts=${targetLandmarksPx.size}")
            val affineM = FaceAligner.computeAffineMatrix(targetLandmarksPx, INPUT_SIZE)
            Log.i(TAG, "step: estimateAffinePartial2D done, empty=${affineM.empty()}, rows=${affineM.rows()}, cols=${affineM.cols()}")
            if (affineM.empty()) {
                Log.w(TAG, "换脸失败: estimateAffinePartial2D 返回空矩阵（关键点退化）")
                affineM.release()
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }
            // 对齐底图 Mat（BGR），供 diff-mask 贴回计算 |fake - alignedTarget|
            val alignedTargetMat = FaceAligner.warpAffineMat(targetBmp, affineM, INPUT_SIZE)
            Log.i(TAG, "step: warpAffineMat done, ${alignedTargetMat.cols()}x${alignedTargetMat.rows()}")
            val alignedTargetBmp = FaceAligner.matBGRToBitmap(alignedTargetMat)
            Log.i(TAG, "底图仿射对齐完成: ${alignedTargetMat.cols()}x${alignedTargetMat.rows()}, 均值RGB=${bitmapMeanRgb(alignedTargetBmp)}")

            // 3. 换脸推理：128×128 对齐底图 + 512 维 emap latent → inswapper → 128×128 换脸脸
            Log.i(TAG, "step: runSwap begin")
            val swappedCrop = runSwap(session, alignedTargetBmp, sourceLatent)
            if (swappedCrop == null) {
                alignedTargetBmp.recycle()
                Log.w(TAG, "换脸失败: runSwap 返回 null（ONNX 输出解析失败）")
                affineM.release()
                alignedTargetMat.release()
                return@withContext PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
            }
            Log.i(TAG, "换脸推理成功, 输出 ${swappedCrop.width}x${swappedCrop.height}, 均值RGB=${bitmapMeanRgb(swappedCrop)}")
            Log.i(TAG, "diag: maxDiff(alignedTarget, swapped)=${bitmapMaxDiff(alignedTargetBmp, swappedCrop)}")
            alignedTargetBmp.recycle()

            // 4. diff-mask 贴回（InsightFace 原生 paste-back）：M⁻¹ 逆变换 + 差异 mask + alpha 混合
            Log.i(TAG, "step: pasteBackDiffMask begin, target=${targetBmp.width}x${targetBmp.height}")
            val result = pasteBackDiffMask(targetBmp, swappedCrop, alignedTargetMat, affineM)
            affineM.release()
            alignedTargetMat.release()
            Log.i(TAG, "换脸完成, 结果 ${result.width}x${result.height}, maxDiff(result,target)=${bitmapMaxDiff(result, targetBmp)}")
            PluginOutput.ImageOutput(result, sourcePath = targetPath)
        } catch (e: Throwable) {
            Log.e(TAG, "换脸失败(Throwable): ${e.javaClass.name}: ${e.message}", e)
            PluginOutput.ImageOutput(targetBmp, sourcePath = targetPath)
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(MODEL_ID, "InSwapper")
        ready = false
    }

    // ---- 人脸检测辅助 ----

    /** 检测图中最大人脸（含 bbox、嵌入、5关键点），无脸或文件不可用时返回 null */
    private suspend fun detectLargestFace(
        provider: FaceProvider,
        path: String,
    ): FaceProvider.DetectedFace? {
        val file = File(path)
        if (!file.exists()) return null
        val faces = provider.detectFaces(file)
        if (faces.isEmpty()) return null
        return faces.maxByOrNull { faceArea(it.box) }
    }

    private fun faceArea(box: RectF): Float =
        (box.right - box.left) * (box.bottom - box.top)

    /** 将嵌入向量规整为指定维度（截断或补零），并 L2 归一化 */
    private fun normalizeTo(vec: FloatArray, dim: Int): FloatArray {
        val out = FloatArray(dim)
        val n = minOf(vec.size, dim)
        System.arraycopy(vec, 0, out, 0, n)
        var sum = 0.0
        for (v in out) sum += (v * v).toDouble()
        val norm = kotlin.math.sqrt(sum)
        if (norm > 1e-6) {
            val inv = (1.0 / norm).toFloat()
            for (i in out.indices) out[i] *= inv
        }
        return out
    }

    // ---- ONNX 推理 ----

    private fun runSwap(session: OrtSession, targetBmp: Bitmap, sourceEmbed: FloatArray): Bitmap? {
        // 按名称约定识别 target(图像) 与 source(嵌入) 两个输入
        // inswapper_128 的标准输入名为 "target"([1,3,128,128]) 与 "source"([1,512])
        val inputNames = session.inputNames.toList()
        if (inputNames.size < 2) {
            Log.w(TAG, "InSwapper 输入数不足: $inputNames")
            return null
        }
        val targetName = inputNames.firstOrNull { it.equals("target", true) } ?: inputNames[0]
        val sourceName = inputNames.firstOrNull { it.equals("source", true) && it != targetName }
            ?: inputNames.first { it != targetName }

        val targetTensor = OnnxTensor.createTensor(
            env, preprocessImage(targetBmp),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        val sourceTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(sourceEmbed),
            longArrayOf(1, sourceEmbed.size.toLong()),
        )
        return try {
            android.util.Log.i("CrashDebug", ">> InSwapper session.run (线程=${Thread.currentThread().name})")
            val result = session.run(mapOf(targetName to targetTensor, sourceName to sourceTensor))
            android.util.Log.i("CrashDebug", "<< InSwapper session.run 完成")
            // 诊断：打印输出张量信息
            val firstOutput = result.iterator().next()
            val outputValue = firstOutput.value.value
            Log.i(TAG, "runSwap 输出: name=${firstOutput.key}, info=${firstOutput.value.info}, valueClass=${outputValue?.javaClass?.name}")
            // inswapper_128 输出形状 [1,3,128,128]，ONNX Runtime Java 反序列化为
            // Array<Array<Array<FloatArray>>>（4D 嵌套）。提取 [0][c] 得到每通道的 [128][128]，
            // 再扁平化为 FloatArray(128*128)，构造 [3][128*128] 的 frame 供 postprocessImage 使用。
            val batch = outputValue as? Array<*>
            result.close()
            val channels = batch?.getOrNull(0) as? Array<*>
            if (channels == null || channels.size < 3) {
                Log.w(TAG, "runSwap 输出解析失败: batch=${batch?.javaClass?.name}, channels=${channels?.size}")
                return null
            }
            val frame = Array(3) { c ->
                val row = channels[c] as? Array<FloatArray>  // [128][128]
                if (row == null || row.size != INPUT_SIZE) {
                    Log.w(TAG, "runSwap 通道 $c 解析失败: row=${row?.size}")
                    return null
                }
                // 扁平化 [128][128] → FloatArray(16384)
                FloatArray(INPUT_SIZE * INPUT_SIZE) { i -> row[i / INPUT_SIZE][i % INPUT_SIZE] }
            }
            Log.i(TAG, "runSwap frame: channels=${frame.size}, hw=${frame.firstOrNull()?.size}")
            postprocessImage(frame)
        } finally {
            targetTensor.close()
            sourceTensor.close()
        }
    }

    /** 预处理：NCHW，归一化 pixel/255.0（Reactor 标准：均值0，标准差255） */
    private fun preprocessImage(bitmap: Bitmap): java.nio.FloatBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (c in 0 until 3) {
            for (i in px.indices) {
                val raw = (px[i] shr (16 - 8 * c)) and 0xFF
                buf.put(raw / 255.0f)
            }
        }
        buf.rewind()
        return buf
    }

    /** 后处理：[3,H,W] float → Bitmap（反归一化 ×255，clamp 0..255） */
    private fun postprocessImage(frame: Array<FloatArray>): Bitmap {
        val h = INPUT_SIZE
        val w = INPUT_SIZE
        val px = IntArray(h * w)
        for (i in 0 until h * w) {
            val r = ((frame[0][i] * 255.0f).coerceIn(0f, 255f)).toInt()
            val g = ((frame[1][i] * 255.0f).coerceIn(0f, 255f)).toInt()
            val b = ((frame[2][i] * 255.0f).coerceIn(0f, 255f)).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * InsightFace 原生 diff-mask 贴回（ReActor inswap.py:paste_back 移植）。
     *
     * 相比 seamlessClone 泊松融合，diff-mask alpha 混合更稳定、无色偏，且更快：
     * 1. 计算仿射矩阵 M 的逆矩阵 M⁻¹
     * 2. 用 M⁻¹ 将 128×128 换脸脸 warp 回原图尺寸
     * 3. 全白 mask（128×128）warp 回原图 → 阈值(>20)清理边缘插值
     * 4. 由非零区域估算 mask_size，erode + GaussianBlur 生成软 mask
     * 5. mask/255 归一化到 [0,1]，扩为 3 通道
     * 6. alpha 混合：result = mask * fake + (1-mask) * target
     *
     * @param target 原始底图
     * @param swapped 128×128 换脸结果
     * @param aimg 对齐底图 Mat（BGR 128×128），预留用于差异 mask 细化（当前按 ReActor 用白 mask）
     * @param affineM 底图→128 对齐的仿射矩阵（2×3）
     * @return 融合后的最终 Bitmap
     */
    @Suppress("UNUSED_PARAMETER")
    private fun pasteBackDiffMask(
        target: Bitmap,
        swapped: Bitmap,
        aimg: Mat,
        affineM: Mat,
    ): Bitmap {
        val tw = target.width
        val th = target.height
        val targetSize = Size(tw.toDouble(), th.toDouble())

        // 1. 逆矩阵 M⁻¹
        Log.i(TAG, "pb: step1 invertAffine begin")
        val invAffine = FaceAligner.invertAffine(affineM)
        Log.i(TAG, "pb: step1 invertAffine done")

        // 2. 换脸脸 warp 回原图尺寸
        val bgrFake = FaceAligner.bitmapToMatBGR(swapped)
        val bgrFakeWarped = Mat()
        Imgproc.warpAffine(bgrFake, bgrFakeWarped, invAffine, targetSize,
            Imgproc.INTER_LINEAR, 0 /* BORDER_CONSTANT */, Scalar.all(0.0))
        bgrFake.release()
        Log.i(TAG, "pb: step2 warpFake done ${bgrFakeWarped.cols()}x${bgrFakeWarped.rows()}")

        // 3. 全白 mask (128×128, float32) warp 回原图
        val imgWhite = Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_32F, Scalar.all(255.0))
        val imgWhiteWarped = Mat()
        Imgproc.warpAffine(imgWhite, imgWhiteWarped, invAffine, targetSize,
            Imgproc.INTER_LINEAR, 0 /* BORDER_CONSTANT */, Scalar.all(0.0))
        imgWhite.release()
        invAffine.release()
        Log.i(TAG, "pb: step3 warpWhite done")

        // 4. 阈值 >20 → 255（清理近边缘插值伪影）
        Imgproc.threshold(imgWhiteWarped, imgWhiteWarped, 20.0, 255.0, Imgproc.THRESH_BINARY)
        Log.i(TAG, "pb: step4 threshold done, type=${imgWhiteWarped.type()}, channels=${imgWhiteWarped.channels()}")

        // 5. 由非零区域估算 mask_size，确定 erode/blur kernel 大小
        val nonZero = Mat()
        Core.findNonZero(imgWhiteWarped, nonZero)
        var maskSize = 0.0
        if (nonZero.rows() > 0) {
            val rect = Geometry.boundingRect(nonZero)
            maskSize = sqrt(rect.height.toDouble() * rect.width.toDouble())
            Log.i(TAG, "pb: step5 nonzero=${nonZero.rows()}, rect=${rect.width}x${rect.height}, maskSize=$maskSize")
        } else {
            Log.w(TAG, "pb: step5 nonzero empty!")
        }
        nonZero.release()

        if (maskSize > 0) {
            // erode img_mask: k = max(mask_size/10, 10)
            var k = (maskSize / 10).toInt().coerceAtLeast(10)
            val erodeKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())
            )
            Imgproc.erode(imgWhiteWarped, imgWhiteWarped, erodeKernel)
            erodeKernel.release()
            // GaussianBlur img_mask: k = max(mask_size/20, 5)
            k = (maskSize / 20).toInt().coerceAtLeast(5)
            val bSize = Size((k * 2 + 1).toDouble(), (k * 2 + 1).toDouble())
            Imgproc.GaussianBlur(imgWhiteWarped, imgWhiteWarped, bSize, 0.0)
        }
        Log.i(TAG, "pb: step5 erode/blur done")

        // 6. img_mask / 255 → [0,1]，扩为 3 通道（用 cvtColor GRAY2BGR，避免 merge 同一 Mat 3 次的潜在问题）
        val imgMask = Mat()
        Core.divide(imgWhiteWarped, Scalar.all(255.0), imgMask, 1.0, CvType.CV_32F)
        imgWhiteWarped.release()
        Log.i(TAG, "pb: step6 divide done, type=${imgMask.type()}")
        val mask3c = Mat()
        Imgproc.cvtColor(imgMask, mask3c, Imgproc.COLOR_GRAY2BGR)
        imgMask.release()
        Log.i(TAG, "pb: step6 gray2bgr done, channels=${mask3c.channels()}")

        // 7. alpha 混合：result = mask3c * bgrFakeWarped + (1-mask3c) * target （float32）
        val bgrFakeF = Mat()
        bgrFakeWarped.convertTo(bgrFakeF, CvType.CV_32F)
        bgrFakeWarped.release()
        val targetMat = FaceAligner.bitmapToMatBGR(target)
        val targetF = Mat()
        targetMat.convertTo(targetF, CvType.CV_32F)
        targetMat.release()
        Log.i(TAG, "pb: step7 convert done, fake=${bgrFakeF.cols()}x${bgrFakeF.rows()}, tgt=${targetF.cols()}x${targetF.rows()}, mask=${mask3c.cols()}x${mask3c.rows()}")

        val fakeContrib = Mat()
        Core.multiply(bgrFakeF, mask3c, fakeContrib)
        bgrFakeF.release()
        // invMask = 1 - mask3c（OpenCV 5.0 Java 无 subtract(Scalar, Mat) 重载，用 -mask+1 等价）
        val invMask = Mat()
        Core.multiply(mask3c, Scalar.all(-1.0), invMask)
        Core.add(invMask, Scalar.all(1.0), invMask)
        mask3c.release()
        val targetContrib = Mat()
        Core.multiply(targetF, invMask, targetContrib)
        targetF.release()
        invMask.release()
        val resultF = Mat()
        Core.add(fakeContrib, targetContrib, resultF)
        fakeContrib.release()
        targetContrib.release()
        Log.i(TAG, "pb: step7 blend done")

        // 8. → uint8 → Bitmap
        val result8u = Mat()
        resultF.convertTo(result8u, CvType.CV_8U)
        resultF.release()
        val output = FaceAligner.matBGRToBitmap(result8u)
        result8u.release()
        Log.i(TAG, "pb: step8 done, output=${output.width}x${output.height}")
        return output
    }

    /**
     * 全分辨率解码（V4）：换脸为交互式单图操作，目标图必须全分辨率以保证输出质量。
     * 不做 inSampleSize 降采样——否则 >5MB 底图换脸结果会被贴回降采样图，输出分辨率减半。
     */
    private fun decodeBitmapFullResolution(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (_: Exception) {
            null
        }
    }

    /** 诊断：计算两同尺寸 Bitmap 的最大像素差（用于判断换脸是否真的改变了像素）。 */
    private fun bitmapMaxDiff(a: Bitmap, b: Bitmap): String {
        return try {
            val w = minOf(a.width, b.width)
            val h = minOf(a.height, b.height)
            val pa = IntArray(w * h); val pb = IntArray(w * h)
            a.getPixels(pa, 0, w, 0, 0, w, h)
            b.getPixels(pb, 0, w, 0, 0, w, h)
            var maxDiff = 0
            for (i in pa.indices) {
                val dr = Math.abs(((pa[i] shr 16) and 0xFF) - ((pb[i] shr 16) and 0xFF))
                val dg = Math.abs(((pa[i] shr 8) and 0xFF) - ((pb[i] shr 8) and 0xFF))
                val db = Math.abs((pa[i] and 0xFF) - (pb[i] and 0xFF))
                val m = maxOf(dr, dg, db)
                if (m > maxDiff) maxDiff = m
            }
            maxDiff.toString()
        } catch (e: Exception) { "err" }
    }

    /** 诊断：计算 Bitmap 的平均 RGB（用于对比换脸前后是否真的变化）。 */
    private fun bitmapMeanRgb(bmp: Bitmap): String {
        return try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var rs = 0L; var gs = 0L; var bs = 0L
            for (p in px) {
                rs += (p shr 16) and 0xFF
                gs += (p shr 8) and 0xFF
                bs += p and 0xFF
            }
            val n = px.size.toLong()
            "(R=${rs / n},G=${gs / n},B=${bs / n})"
        } catch (e: Exception) {
            "(err)"
        }
    }
}
