package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.concurrent.InferenceDispatchers
import com.renyxin.localalbum.core.concurrent.InferenceMetrics
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * EVA02-CLIP-L-14 语义嵌入 Provider — 真实跨模态 CLIP 检索模型（内置）。
 *
 * 替代原桌面级 bigG 模型（需 adb push 外部加载），
 * 改用 EVA02-CLIP-L-14（INT8 ONNX，768 维，336 分辨率），自包含单文件、体积可接受，
 * 直接内置打包进 APK assets，安装即用、无需手动推送。
 *
 * ## 模型布局（assets/models/eva02_clip/）
 *
 * - `eva02_visual_336_int8.onnx`：视觉编码器（输入 `image` [1,3,336,336] → 输出 `image_embeddings` [1,768]）
 * - `eva02_text_int8.onnx`：文本编码器（输入 `text` [1,77] int64 → 输出 `text_embeddings` [1,768]）
 * - `vocab.json` + `merges.txt`：CLIP BPE 词表（与 open_clip/transformers 一致，复用 [ClipBpeTokenizer]）
 * - `tokenizer_config.json`：分词器配置（CLIPTokenizer，context_length=77）
 *
 * ## 加载策略
 *
 * ONNX Runtime 需通过文件路径创建 [OrtSession]，而 assets 无法直接作为 [File] 打开。
 * 故首次加载时将两个 ONNX 从 assets 复制到 `filesDir/models/eva02_clip/`，并写入版本标记文件，
 * 后续启动若标记版本匹配则跳过复制直接加载。
 *
 * ## 风险提示
 *
 * EVA02-CLIP-L-14 为移动端可承受的中量级模型（视觉编码器 24 层），int8 量化后单图推理
 * 内存峰值约数百 MB。中低端设备仍可能较慢，但相较 bigG 已大幅改善。
 *
 * @param context Android 上下文
 * @param assetDir EVA02 模型 assets 子目录
 */
class Eva02ClipProvider(
    private val context: Context,
    private val assetDir: String = DEFAULT_ASSET_DIR,
) : SemanticEmbedProvider {

    companion object {
        private const val TAG = "Eva02Clip"
        private const val DEFAULT_ASSET_DIR = "models/eva02_clip"
        private const val VISUAL_ONNX = "eva02_visual_336_int8.onnx"
        private const val TEXT_ONNX = "eva02_text_int8.onnx"

        /** 模型版本号，升级模型后递增以触发嵌入增量重建。
         *  v3: 默认 Provider 切换为 EVA02-CLIP-L-14（768 维），与 bigG（1280 维）不兼容，需全量重建。
         *  v4: 修复 Split 算子 num_outputs 属性兼容性（opset 17 不支持），强制重新复制模型文件。
         *  v5: 修复文本模型 ArgMax INT64 输入不兼容问题（插入 Cast 节点转为 FLOAT）。 */
        const val MODEL_VERSION = 5

        // 预处理参数（EVA02-CLIP 采用标准 CLIP 归一化）
        private const val IMAGE_SIZE = 336
        private const val CONTEXT_LENGTH = 77
        private const val EMBED_DIM = 768
        private val NORM_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val NORM_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    override val providerId = "builtin:eva02_clip"
    override val modelId = "eva02-clip-l14-336"
    override val modelVersion = MODEL_VERSION
    override val displayName = "EVA02-CLIP 跨模态语义检索"
    override val embeddingDim = EMBED_DIM

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val initMutex = Mutex()

    @Volatile private var visualSession: OrtSession? = null
    @Volatile private var textSession: OrtSession? = null
    @Volatile private var tokenizer: ClipBpeTokenizer? = null
    @Volatile private var visualInputName: String = ""
    @Volatile private var textInputName: String = ""
    // 修复：移除永久 loadFailed 标记，允许每次调用时重试加载。
    // 原实现首次失败后永久放弃，导致瞬态失败（如启动时内存压力）后永远不可用。

    // ---- 视觉 session 池（原生内存有界）----
    // EVA02 visual 单个 session 的权重与工作区达到数百 MB，且 ORT/XNNPACK 工作区不会随
    // 模型文件 mmap 完全共享。真机验证中 4 个 session 令 PSS 超过 4GB，因此固定单飞；
    // 以吞吐换取可预测的内存上界，后续只有在逐设备内存基线通过后才允许扩大。
    private val visualPoolSize: Int = 1
    private val visualSessionPool = ConcurrentLinkedDeque<OrtSession>()
    private val visualSemaphore = Semaphore(visualPoolSize)
    private val visualPoolMutex = Mutex()
    @Volatile private var visualModelPath: String? = null

    /**
     * 模型在 filesDir 中的工作目录（ONNX 从 assets 复制到此）。
     */
    private val workDir: File by lazy { File(context.filesDir, "models/eva02_clip") }

    private val accelerationPrefs by lazy {
        context.getSharedPreferences("acceleration_policy", Context.MODE_PRIVATE)
    }

    /**
     * 真机结果：MT6991 上 EVA02 visual 的 NNAPI 单图约 2.16s，远慢于原 CPU 路径，
     * 故默认熔断。保留探测与回退实现，待模型转换/驱动版本变化后以新实验版本复测。
     */
    private fun isVisualNnapiEnabled(): Boolean = false

    private fun disableVisualNnapi() {
        accelerationPrefs.edit().putBoolean("eva02_visual_nnapi_disabled:v1", true).apply()
    }

    /** 视觉模型独立尝试 NNAPI；文本编码器继续使用 CPU，避免扩大未验证的图覆盖范围。 */
    private fun createSessionOptions(useNnapi: Boolean): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // 关闭会保留峰值工作区的 arena 与 memory pattern。EVA02 固定形状推理会因此
            // 多一些分配开销，但能在长批次结束后把原生内存归还给系统。
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            if (useNnapi) {
                addNnapi(
                    EnumSet.of(
                        ai.onnxruntime.providers.NNAPIFlags.USE_FP16,
                        ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED,
                    ),
                )
                Log.i(TAG, "EVA02 visual ONNX NNAPI 候选后端已启用")
            } else {
                runCatching { addXnnpack(emptyMap()) }
                    .onFailure { Log.w(TAG, "XNNPACK 启用失败: ${it.message}") }
            }
        }
    }

    private fun createVisualSession(path: String): OrtSession = try {
        env.createSession(path, createSessionOptions(isVisualNnapiEnabled()))
    } catch (error: Throwable) {
        if (!isVisualNnapiEnabled()) throw error
        Log.w(TAG, "EVA02 visual NNAPI 初始化失败，熔断并回退 CPU", error)
        disableVisualNnapi()
        env.createSession(path, createSessionOptions(false))
    }

    /**
     * 懒加载：首次嵌入时将 ONNX 从 assets 复制到 filesDir 并创建会话。
     *
     * 修复：
     * - 移除永久 loadFailed 标记，允许每次调用重试
     * - 不使用 XNNPACK（兼容性问题），纯 CPU 执行
     * - catch Throwable 而非 Exception，捕获 OOM/Error
     * - 逐步日志精确定位失败环节
     */
    private suspend fun ensureLoaded(): Boolean {
        if (visualSession != null && textSession != null && tokenizer != null) return true
        return initMutex.withLock {
            if (visualSession != null && textSession != null && tokenizer != null) return@withLock true
            try {
                // 1. 从 assets 复制 ONNX 到 filesDir
                Log.i(TAG, "开始加载 EVA02-CLIP 模型…")
                val visualFile = copyAssetIfNeeded(VISUAL_ONNX)
                val textFile = copyAssetIfNeeded(TEXT_ONNX)
                Log.i(TAG, "模型文件就绪: visual=${visualFile.length()}B, text=${textFile.length()}B")

                // 2. 分词器
                Log.i(TAG, "初始化 BPE 分词器…")
                val tk = ClipBpeTokenizer(context, assetDir, CONTEXT_LENGTH)
                tokenizer = tk
                Log.i(TAG, "分词器就绪: vocabSize=${tk.vocabSize}")

                // 3. 视觉模型单独尝试 NNAPI；文本模型保持 CPU。
                visualModelPath = visualFile.absolutePath

                Log.i("CrashDebug", ">> EVA02 createSession visual (${visualFile.length()}B, 线程=${Thread.currentThread().name})")
                val vs = createVisualSession(visualModelPath!!)
                Log.i("CrashDebug", "<< EVA02 createSession visual 完成")
                Log.i(TAG, "视觉编码器会话就绪: inputs=${vs.inputNames}")

                Log.i("CrashDebug", ">> EVA02 createSession text (${textFile.length()}B, 线程=${Thread.currentThread().name})")
                val ts = env.createSession(textFile.absolutePath, createSessionOptions(false))
                Log.i("CrashDebug", "<< EVA02 createSession text 完成")
                Log.i(TAG, "文本编码器会话就绪: inputs=${ts.inputNames}")

                visualInputName = vs.inputNames.first()
                textInputName = ts.inputNames.first()
                visualSession = vs
                textSession = ts
                visualSessionPool.add(vs)
                Log.i(TAG, "EVA02-CLIP 全部会话就绪")
                true
            } catch (e: Throwable) {
                // 修复：catch Throwable 而非 Exception，捕获 OutOfMemoryError / UnsatisfiedLinkError 等
                Log.e(TAG, "EVA02-CLIP 加载失败: ${e.javaClass.simpleName}: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 将 assets 下的模型文件复制到 [workDir]（若不存在或版本变更）。
     * 写入 `.copied_v{MODEL_VERSION}` 标记文件，版本升级时强制重新复制。
     */
    private fun copyAssetIfNeeded(assetName: String): File {
        workDir.mkdirs()
        val target = File(workDir, assetName)
        // 修复：每个文件使用独立的标记文件，避免一个文件复制后创建共享标记
        // 导致另一个文件跳过复制（使用旧版本）
        val marker = File(workDir, ".copied_v${MODEL_VERSION}_$assetName")
        if (target.exists() && target.length() > 0 && marker.exists()) return target
        context.assets.open("$assetDir/$assetName").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        marker.createNewFile()
        return target
    }

    override suspend fun embedImage(
        file: File,
        context: SemanticEmbedProvider.ImageContext?,
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) return@withContext null
        val bitmap = decodeBitmap(file) ?: return@withContext null
        try {
            val input = preprocessImage(bitmap)
            // 视觉模型是语义扫描主瓶颈；统一记录 CPU/NNAPI 的 P50/P95 对照数据。
            withVisualSession { session ->
                val tensor = OnnxTensor.createTensor(
                    env, input,
                    longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
                )
                val backend = if (isVisualNnapiEnabled()) {
                    InferenceMetrics.Backend.ONNX_NNAPI
                } else {
                    InferenceMetrics.Backend.CPU_DEFAULT
                }
                val result = InferenceMetrics.measure("model:eva02_visual:onnx", backend) {
                    session.run(mapOf(visualInputName to tensor))
                }
                try {
                    val vec = extractPooledVector(result, EMBED_DIM)
                    vec?.let { l2Normalize(it) }
                } finally {
                    result.close()
                    tensor.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EVA02 图像嵌入失败: ${file.name}", e)
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 从视觉 session 池获取一个独立实例执行推理（多核并行）。
     * 池大小 [visualPoolSize]，每个 session 独立 intra-op 线程池。
     */
    private suspend fun <T> withVisualSession(block: suspend (OrtSession) -> T): T {
        val path = visualModelPath ?: throw IllegalStateException("EVA02 visual 未加载")
        return visualSemaphore.withPermit {
            var reusableSession = visualSessionPool.poll() ?: visualPoolMutex.withLock {
                visualSessionPool.poll() ?: createVisualSession(path)
            }
            try {
                block(reusableSession)
            } catch (error: Throwable) {
                if (!isVisualNnapiEnabled()) throw error
                Log.w(TAG, "EVA02 visual NNAPI 推理失败，熔断并以 CPU 重试", error)
                runCatching { reusableSession.close() }
                disableVisualNnapi()
                reusableSession = createVisualSession(path)
                block(reusableSession)
            } finally {
                visualSessionPool.add(reusableSession)
            }
        }
    }

    @Suppress("unused")
    private fun visualSessionRef(): OrtSession? = visualSession

    override suspend fun embedText(query: String): FloatArray = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) {
            Log.e(TAG, "embedText: ensureLoaded 返回 false，模型未就绪")
            return@withContext FloatArray(EMBED_DIM)
        }
        Log.i(TAG, "embedText: ensureLoaded成功, textSession=${textSession != null}, tokenizer=${tokenizer != null}")
        val session = textSession ?: return@withContext FloatArray(EMBED_DIM)
        val tk = tokenizer ?: return@withContext FloatArray(EMBED_DIM)
        try {
            val ids = tk.encode(query)
            Log.i(TAG, "embedText: query=\"$query\", ids前10=${ids.take(10).toList()}")
            // 修复：使用 LongBuffer 明确指定 INT64 类型，避免 ByteBuffer 被误解为 UINT8
            val longBuf = java.nio.LongBuffer.wrap(ids)
            val tensor = OnnxTensor.createTensor(env, longBuf, longArrayOf(1, CONTEXT_LENGTH.toLong()))
            val result = session.run(mapOf(textInputName to tensor))
            tensor.close()
            val vec = extractPooledVector(result, EMBED_DIM)
            result.close()
            if (vec == null || vec.all { it == 0f }) {
                Log.e(TAG, "embedText: 推理返回空向量或全零向量, vec=${vec?.size}")
                FloatArray(EMBED_DIM)
            } else {
                Log.i(TAG, "embedText: 成功, dim=${vec.size}, first=${vec[0]}")
                l2Normalize(vec)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "embedText: 文本嵌入失败: $query", e)
            FloatArray(EMBED_DIM)
        }
    }

    override suspend fun release() {
        // 池中包含所有视觉 session 实例（含首个），统一由池关闭，避免重复 close
        visualSession = null
        visualSessionPool.forEach { runCatching { it.close() } }
        visualSessionPool.clear()
        visualModelPath = null
        textSession?.close()
        textSession = null
        tokenizer = null
    }

    // ---- 图像预处理：短边缩放到 336 + 中心裁剪 336，归一化，CHW ----

    private fun preprocessImage(bitmap: Bitmap): java.nio.FloatBuffer {
        val cropped = centerCrop(bitmap, IMAGE_SIZE)
        val buf = ByteBuffer.allocateDirect(IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        cropped.getPixels(px, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        for (c in 0 until 3) {
            val mean = NORM_MEAN[c]
            val std = NORM_STD[c]
            for (i in px.indices) {
                val raw = ((px[i] shr (16 - 8 * c)) and 0xFF) / 255f
                buf.put((raw - mean) / std)
            }
        }
        buf.rewind()
        if (cropped !== bitmap) cropped.recycle()
        return buf
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        // 短边缩放到 size
        val scale = size.toFloat() / minOf(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(1)
        val sh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = if (sw != w || sh != h) Bitmap.createScaledBitmap(src, sw, sh, true) else src
        val x = (sw - size) / 2
        val y = (sh - size) / 2
        return Bitmap.createBitmap(scaled, x, y, size, size)
    }

    /**
     * 从 ONNX 输出中提取池化后的 [dim] 维向量。
     * EVA02 输出 `*_embeddings`([1,dim])，优先取一维输出；若仅有多维输出则对 token 维取均值。
     */
    private fun extractPooledVector(result: OrtSession.Result, dim: Int): FloatArray? {
        for (entry in result) {
            val value = entry.value.value ?: continue
            // 一维输出 [1, dim]
            if (value is Array<*> && value.size == 1 && value[0] is FloatArray) {
                val arr = value[0] as FloatArray
                if (arr.size == dim) return arr
            }
        }
        // 兜底：取第一个 [1, N, dim] 输出，对 N 维均值池化
        for (entry in result) {
            val value = entry.value.value ?: continue
            if (value is Array<*> && value.size == 1 && value[0] is Array<*>) {
                val tokens = value[0] as Array<*>
                if (tokens.isNotEmpty() && tokens[0] is FloatArray && (tokens[0] as FloatArray).size == dim) {
                    val out = FloatArray(dim)
                    for (t in tokens) {
                        val fa = t as FloatArray
                        for (i in 0 until dim) out[i] += fa[i]
                    }
                    val inv = 1f / tokens.size
                    for (i in 0 until dim) out[i] *= inv
                    return out
                }
            }
        }
        return null
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vec) sum += (v * v).toDouble()
        val norm = kotlin.math.sqrt(sum)
        if (norm > 1e-6f) {
            val inv = (1.0 / norm).toFloat()
            for (i in vec.indices) vec[i] *= inv
        }
        return vec
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            // 仅解码尺寸以决定采样率，避免大图 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val maxDim = 768
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}
