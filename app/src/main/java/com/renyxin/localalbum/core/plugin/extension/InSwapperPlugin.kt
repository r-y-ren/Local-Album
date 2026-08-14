package com.renyxin.localalbum.core.plugin.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    private val faceModelIdsFactory: () -> List<String> = { emptyList() },
) : OnDemandGenerativePlugin {

    companion object {
        private const val TAG = "InSwapper"
        private const val CONSUMER_ID_PREFIX = "InSwapper:execute"
        const val MODEL_ID = "model:inswapper"
        private const val INPUT_SIZE = 128
        private const val SOURCE_EMBED_DIM = 512
        /** emap 矩阵维度（inswapper_128 最后一个 initializer 为 512×512） */
        private const val EMAP_DIM = 512
        /** assets 中 emap 二进制文件路径（构建期由 scripts/extract_emap.py 导出） */
        private const val EMAP_ASSET_PATH = "models/emap_512.bin"
    }

    private lateinit var manifest: PluginManifest
    @Volatile private var descriptorReady = false
    private val _runtimeState = MutableStateFlow(OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD)
    override val runtimeState: StateFlow<OnDemandRuntimeState> = _runtimeState.asStateFlow()

    /** emap is resident only while an admitted interactive execution owns the heavy lane. */
    private var emap: FloatArray? = null
    /** Descriptor initialization stores only the application context; no asset is copied here. */
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
        descriptorReady = true
        _runtimeState.value = OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD
        Log.i(TAG, "InSwapper descriptor registered; native runtime remains unloaded")
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

    override fun isReady() = descriptorReady

    override suspend fun execute(input: PluginInput): PluginOutput.ImageOutput = withContext(Dispatchers.IO) {
        if (!descriptorReady) {
            throw FaceSwapExecutionPolicy.descriptorFailure()
        }
        _runtimeState.value = if (EnhancementResourceGate.isCoreRequested) {
            OnDemandRuntimeState.WAITING_FOR_CORE
        } else {
            OnDemandRuntimeState.LOADING
        }
        try {
            EnhancementResourceGate.withInteractiveAi(
                onWaitingForCore = {
                    _runtimeState.value = OnDemandRuntimeState.WAITING_FOR_CORE
                },
            ) {
                executeAdmitted(input)
            }
        } finally {
            // Cancellation and pre-session compatibility failures can happen before executeAdmitted()
            // installs its model cleanup boundary. Never leave a usable descriptor in a transient or
            // sticky ERROR state; capability compatibility is derived independently by the UI.
            if (
                _runtimeState.value == OnDemandRuntimeState.WAITING_FOR_CORE ||
                _runtimeState.value == OnDemandRuntimeState.LOADING ||
                (_runtimeState.value == OnDemandRuntimeState.ERROR && descriptorReady)
            ) {
                _runtimeState.value = FaceSwapExecutionPolicy.idleState(descriptorReady)
            }
        }
    }

    private suspend fun executeAdmitted(input: PluginInput): PluginOutput.ImageOutput {
        _runtimeState.value = OnDemandRuntimeState.LOADING
        val faceProvider = faceProviderFactory()
        val compatibilityFailure = FaceSwapExecutionPolicy.compatibilityFailure(
            providerDisplayName = faceProvider?.displayName,
            embeddingDim = faceProvider?.embeddingDim,
            supportsFivePointLandmarks = faceProvider?.supportsFivePointLandmarks == true,
        )
        if (compatibilityFailure != null) throw compatibilityFailure
        checkNotNull(faceProvider)

        val modelIds = (faceModelIdsFactory() + MODEL_ID).distinct()
        val consumerId = "$CONSUMER_ID_PREFIX:session:${System.nanoTime()}"
        val registered = mutableListOf<String>()
        return try {
            // The interactive lane is already owned here. Native load order is strict and neither
            // this method nor descriptor initialization is reachable from automatic Lite stages.
            NativeAiRuntime.ensureFaceSwapRuntime()
            modelManager.prepareBundledModels(modelIds)
            for (modelId in modelIds) {
                modelManager.registerConsumer(modelId, consumerId)
                registered += modelId
            }
            for (modelId in modelIds) {
                modelManager.ensureModelReady(modelId).getOrThrow()
            }
            emap = checkNotNull(loadEmap()) {
                "Required InSwapper emap asset is missing: $EMAP_ASSET_PATH"
            }
            _runtimeState.value = OnDemandRuntimeState.READY
            executeLoaded(input, faceProvider, NativeAiRuntime.getOrtEnvironment())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "换脸交互资源加载或执行失败", error)
            _runtimeState.value = OnDemandRuntimeState.ERROR
            throw FaceSwapExecutionPolicy.operationalFailure(error)
        } finally {
            withContext(NonCancellable) {
                emap = null
                registered.asReversed().forEach { modelId ->
                    modelManager.unregisterConsumer(modelId, consumerId)
                }
                modelIds.asReversed().forEach(modelManager::evictModelIfUnused)
            }
            // Native/model failures are retryable after the failed session has been fully cleaned.
            _runtimeState.value = FaceSwapExecutionPolicy.idleState(descriptorReady)
        }
    }

    private fun inputSourcePath(input: PluginInput): String = when (input) {
        is PluginInput.ImageInput -> input.filePath
        is PluginInput.MultiModalInput ->
            input.inputs.filterIsInstance<PluginInput.ImageInput>().lastOrNull()?.filePath.orEmpty()
        else -> ""
    }

    private suspend fun executeLoaded(
        input: PluginInput,
        faceProvider: FaceProvider,
        env: OrtEnvironment,
    ): PluginOutput.ImageOutput {
        Log.i(TAG, "换脸 execute 开始, input=${input::class.simpleName}")
        val images = when (input) {
            is PluginInput.MultiModalInput -> input.inputs.filterIsInstance<PluginInput.ImageInput>()
            is PluginInput.ImageInput -> listOf(input)
            else -> emptyList()
        }
        if (images.isEmpty()) {
            Log.w(TAG, "换脸失败: 无图像输入")
            return PluginOutput.ImageOutput(null, sourcePath = "")
        }

        val sourceInput = images.first()
        val targetInput = images.getOrElse(1) { sourceInput }
        val targetPath = targetInput.filePath
        val targetBitmapWasSupplied = targetInput.bitmap != null
        val targetBmp = targetInput.bitmap ?: decodeBitmapFullResolution(targetPath)
            ?: return PluginOutput.ImageOutput(null, sourcePath = targetPath)

        var affineM: Mat? = null
        var alignedTargetMat: Mat? = null
        var alignedTargetBmp: Bitmap? = null
        var swappedCrop: Bitmap? = null
        return try {
            val sourceFace = detectLargestFace(faceProvider, sourceInput.filePath)
            if (sourceFace?.landmarks == null) {
                Log.w(TAG, "换脸失败: 源图未检测到带关键点的人脸 ${sourceInput.filePath}")
                return PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }
            var embedNorm = 0.0
            for (value in sourceFace.embedding) embedNorm += (value * value).toDouble()
            if (sqrt(embedNorm) < 1e-3) {
                Log.w(TAG, "换脸失败: 源人脸嵌入无效")
                return PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }
            val sourceLatent = applyEmap(normalizeTo(sourceFace.embedding, SOURCE_EMBED_DIM))

            val targetFace = detectLargestFace(faceProvider, targetPath)
            val targetLandmarks = targetFace?.landmarks
            if (targetLandmarks == null) {
                Log.w(TAG, "换脸失败: 底图未检测到带关键点的人脸 $targetPath")
                return PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }

            val targetLandmarksPx = FaceAligner.denormalizeLandmarks(
                targetLandmarks,
                targetBmp.width,
                targetBmp.height,
            )
            val stableAffine = FaceAligner.computeAffineMatrix(targetLandmarksPx, INPUT_SIZE)
            affineM = stableAffine
            if (stableAffine.empty()) {
                Log.w(TAG, "换脸失败: 关键点仿射矩阵为空")
                return PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }

            val stableAlignedMat = FaceAligner.warpAffineMat(targetBmp, stableAffine, INPUT_SIZE)
            alignedTargetMat = stableAlignedMat
            val stableAlignedBitmap = FaceAligner.matBGRToBitmap(stableAlignedMat)
            alignedTargetBmp = stableAlignedBitmap

            swappedCrop = modelManager.withOnnxSession(MODEL_ID) { session ->
                runSwap(session, stableAlignedBitmap, sourceLatent, env)
            }
            val stableSwapped = swappedCrop
            if (stableSwapped == null) {
                Log.w(TAG, "换脸失败: InSwapper 输出解析失败")
                return PluginOutput.ImageOutput(null, sourcePath = targetPath)
            }

            val result = pasteBackDiffMask(
                targetBmp,
                stableSwapped,
                stableAlignedMat,
                stableAffine,
            )
            Log.i(TAG, "换脸完成: ${result.width}x${result.height}")
            PluginOutput.ImageOutput(result, sourcePath = targetPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Unexpected OpenCV/ONNX/provider failures are actionable and must not be collapsed into
            // the normal "no face detected" empty result.
            Log.e(TAG, "换脸执行失败", error)
            throw error
        } finally {
            swappedCrop?.let { if (!it.isRecycled) it.recycle() }
            alignedTargetBmp?.let { if (!it.isRecycled) it.recycle() }
            alignedTargetMat?.release()
            affineM?.release()
            if (!targetBitmapWasSupplied && !targetBmp.isRecycled) targetBmp.recycle()
        }
    }

    override suspend fun releaseRuntimeResources() {
        EnhancementResourceGate.withInteractiveAi {
            emap = null
            (faceModelIdsFactory() + MODEL_ID).distinct().asReversed().forEach {
                modelManager.evictModelIfUnused(it)
            }
            if (descriptorReady) {
                _runtimeState.value = OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD
            }
        }
    }

    override suspend fun release() {
        descriptorReady = false
        releaseRuntimeResources()
        _runtimeState.value = OnDemandRuntimeState.ERROR
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

    private fun runSwap(
        session: OrtSession,
        targetBmp: Bitmap,
        sourceEmbed: FloatArray,
        env: OrtEnvironment = NativeAiRuntime.getOrtEnvironment(),
    ): Bitmap? {
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
                val rows = channels[c] as? Array<*>  // [128][128]
                if (rows == null || rows.size != INPUT_SIZE || rows.any { it !is FloatArray }) {
                    Log.w(TAG, "runSwap 通道 $c 解析失败: rows=${rows?.size}")
                    return null
                }
                // 扁平化 [128][128] → FloatArray(16384)，逐行验证后再读取。
                FloatArray(INPUT_SIZE * INPUT_SIZE) { i ->
                    (rows[i / INPUT_SIZE] as FloatArray)[i % INPUT_SIZE]
                }
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
        val mats = MatResourceScope()
        return try {
            val tw = target.width
            val th = target.height
            val targetSize = Size(tw.toDouble(), th.toDouble())

            // 1. 逆矩阵 M⁻¹
            Log.i(TAG, "pb: step1 invertAffine begin")
            val invAffine = mats.track(FaceAligner.invertAffine(affineM))
            Log.i(TAG, "pb: step1 invertAffine done")

            // 2. 换脸脸 warp 回原图尺寸
            val bgrFake = mats.track(FaceAligner.bitmapToMatBGR(swapped))
            val bgrFakeWarped = mats.track(Mat())
            Imgproc.warpAffine(bgrFake, bgrFakeWarped, invAffine, targetSize,
                Imgproc.INTER_LINEAR, 0 /* BORDER_CONSTANT */, Scalar.all(0.0))
            mats.release(bgrFake)
            Log.i(TAG, "pb: step2 warpFake done ${bgrFakeWarped.cols()}x${bgrFakeWarped.rows()}")

            // 3. 全白 mask (128×128, float32) warp 回原图
            val imgWhite = mats.track(Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_32F, Scalar.all(255.0)))
            val imgWhiteWarped = mats.track(Mat())
            Imgproc.warpAffine(imgWhite, imgWhiteWarped, invAffine, targetSize,
                Imgproc.INTER_LINEAR, 0 /* BORDER_CONSTANT */, Scalar.all(0.0))
            mats.release(imgWhite)
            mats.release(invAffine)
            Log.i(TAG, "pb: step3 warpWhite done")

            // 4. 阈值 >20 → 255（清理近边缘插值伪影）
            Imgproc.threshold(imgWhiteWarped, imgWhiteWarped, 20.0, 255.0, Imgproc.THRESH_BINARY)
            Log.i(TAG, "pb: step4 threshold done, type=${imgWhiteWarped.type()}, channels=${imgWhiteWarped.channels()}")

            // 5. 由非零区域估算 mask_size，确定 erode/blur kernel 大小
            val nonZero = mats.track(Mat())
            Core.findNonZero(imgWhiteWarped, nonZero)
            var maskSize = 0.0
            if (nonZero.rows() > 0) {
                val rect = Geometry.boundingRect(nonZero)
                maskSize = sqrt(rect.height.toDouble() * rect.width.toDouble())
                Log.i(TAG, "pb: step5 nonzero=${nonZero.rows()}, rect=${rect.width}x${rect.height}, maskSize=$maskSize")
            } else {
                Log.w(TAG, "pb: step5 nonzero empty!")
            }
            mats.release(nonZero)

            if (maskSize > 0) {
                // erode img_mask: k = max(mask_size/10, 10)
                var k = (maskSize / 10).toInt().coerceAtLeast(10)
                val erodeKernel = mats.track(Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())
                ))
                Imgproc.erode(imgWhiteWarped, imgWhiteWarped, erodeKernel)
                mats.release(erodeKernel)
                // GaussianBlur img_mask: k = max(mask_size/20, 5)
                k = (maskSize / 20).toInt().coerceAtLeast(5)
                val bSize = Size((k * 2 + 1).toDouble(), (k * 2 + 1).toDouble())
                Imgproc.GaussianBlur(imgWhiteWarped, imgWhiteWarped, bSize, 0.0)
            }
            Log.i(TAG, "pb: step5 erode/blur done")

            // 6. img_mask / 255 → [0,1]，扩为 3 通道（用 cvtColor GRAY2BGR，避免 merge 同一 Mat 3 次的潜在问题）
            val imgMask = mats.track(Mat())
            Core.divide(imgWhiteWarped, Scalar.all(255.0), imgMask, 1.0, CvType.CV_32F)
            mats.release(imgWhiteWarped)
            Log.i(TAG, "pb: step6 divide done, type=${imgMask.type()}")
            val mask3c = mats.track(Mat())
            Imgproc.cvtColor(imgMask, mask3c, Imgproc.COLOR_GRAY2BGR)
            mats.release(imgMask)
            Log.i(TAG, "pb: step6 gray2bgr done, channels=${mask3c.channels()}")

            // 7. alpha 混合：result = mask3c * bgrFakeWarped + (1-mask3c) * target （float32）
            val bgrFakeF = mats.track(Mat())
            bgrFakeWarped.convertTo(bgrFakeF, CvType.CV_32F)
            mats.release(bgrFakeWarped)
            val targetMat = mats.track(FaceAligner.bitmapToMatBGR(target))
            val targetF = mats.track(Mat())
            targetMat.convertTo(targetF, CvType.CV_32F)
            mats.release(targetMat)
            Log.i(TAG, "pb: step7 convert done, fake=${bgrFakeF.cols()}x${bgrFakeF.rows()}, tgt=${targetF.cols()}x${targetF.rows()}, mask=${mask3c.cols()}x${mask3c.rows()}")

            val fakeContrib = mats.track(Mat())
            Core.multiply(bgrFakeF, mask3c, fakeContrib)
            mats.release(bgrFakeF)
            // invMask = 1 - mask3c（OpenCV 5.0 Java 无 subtract(Scalar, Mat) 重载，用 -mask+1 等价）
            val invMask = mats.track(Mat())
            Core.multiply(mask3c, Scalar.all(-1.0), invMask)
            Core.add(invMask, Scalar.all(1.0), invMask)
            mats.release(mask3c)
            val targetContrib = mats.track(Mat())
            Core.multiply(targetF, invMask, targetContrib)
            mats.release(targetF)
            mats.release(invMask)
            val resultF = mats.track(Mat())
            Core.add(fakeContrib, targetContrib, resultF)
            mats.release(fakeContrib)
            mats.release(targetContrib)
            Log.i(TAG, "pb: step7 blend done")

            // 8. → uint8 → Bitmap
            val result8u = mats.track(Mat())
            resultF.convertTo(result8u, CvType.CV_8U)
            mats.release(resultF)
            val output = FaceAligner.matBGRToBitmap(result8u)
            mats.release(result8u)
            Log.i(TAG, "pb: step8 done, output=${output.width}x${output.height}")
            output
        } finally {
            mats.releaseAll()
        }
    }

    /** Identity-based tracker that releases every OpenCV allocation on success or failure. */
    private class MatResourceScope {
        private val tracked = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Mat, Boolean>(),
        )

        fun track(mat: Mat): Mat = mat.also(tracked::add)

        fun release(mat: Mat) {
            if (tracked.remove(mat)) runCatching(mat::release)
        }

        fun releaseAll() {
            tracked.toList().asReversed().forEach { runCatching(it::release) }
            tracked.clear()
        }
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
