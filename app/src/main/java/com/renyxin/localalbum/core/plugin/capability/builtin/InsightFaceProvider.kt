package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.extension.FaceAligner
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InsightFaceProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : FaceProvider {

    companion object {
        private const val TAG = "InsightFace"
        private const val CONSUMER_ID_PREFIX = "InsightFace:detect"
        const val DET_MODEL_ID = "model:insightface_det"
        const val REC_MODEL_ID = "model:insightface_rec"
        private const val DET_INPUT_SIZE = 640
        private const val REC_INPUT_SIZE = 112
        const val EMBEDDING_DIM = 512
        private const val DET_CONF_THRESHOLD = 0.5f
        private const val DET_NMS_THRESHOLD = 0.4f
        private const val MAX_FACES_PER_IMAGE = 8
    }

    override val providerId = "model:insightface"
    override val displayName = "InsightFace 人脸识别 (Buffalo_L)"
    override val embeddingDim = EMBEDDING_DIM
    override val supportsFivePointLandmarks = true

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = DET_MODEL_ID, displayName = "InsightFace Det",
                format = PluginManifest.ModelFormat.ONNX, fileUrl = "",
                inputShape = listOf(1, 3, DET_INPUT_SIZE, DET_INPUT_SIZE),
                outputShape = intArrayOf(1, 0), fileSizeBytes = 16_900_000,
                memoryFootprintBytes = 30_000_000, runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "det_10g.onnx",
            )
        )
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = REC_MODEL_ID, displayName = "InsightFace Rec",
                format = PluginManifest.ModelFormat.ONNX, fileUrl = "",
                inputShape = listOf(1, 3, REC_INPUT_SIZE, REC_INPUT_SIZE),
                outputShape = intArrayOf(1, EMBEDDING_DIM),
                fileSizeBytes = 174_000_000, memoryFootprintBytes = 80_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "w600k_r50.onnx",
            )
        )
    }

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> = withContext(Dispatchers.IO) {
        // Face alignment uses OpenCV. Initialization remains lazy and happens only after a Full face
        // stage or an admitted interactive face swap actually invokes this provider.
        NativeAiRuntime.ensureOpenCvRuntime()
        // 保护完整的检测 + 特征提取事务。批处理阶段结束时会调用 evictUnusedModels()；
        // 若不登记消费者，它可能在交互式换脸刚 ensure 完模型后关闭 session，导致换脸失效。
        val consumerId = "$CONSUMER_ID_PREFIX:${System.nanoTime()}"
        modelManager.registerConsumer(DET_MODEL_ID, consumerId)
        modelManager.registerConsumer(REC_MODEL_ID, consumerId)
        var original: Bitmap? = null
        try {
            original = decodeBitmap(file) ?: return@withContext emptyList()
            val detResult = modelManager.ensureModelReady(DET_MODEL_ID)
            if (detResult.isFailure) return@withContext emptyList()
            val faces = modelManager.withOnnxSession(DET_MODEL_ID) { detSession ->
                detectFacesOnnx(original, detSession)
            } ?: return@withContext emptyList()

            // 群像、海报或误检可能使单图人脸数异常膨胀。仅保留面积最大的有限人脸，
            // 控制 ArcFace 调用次数与临时 Bitmap/仿射矩阵的峰值内存。
            val facesForRecognition = faces
                .sortedByDescending { face -> face.box.width() * face.box.height() }
                .take(MAX_FACES_PER_IMAGE)
            if (facesForRecognition.size < faces.size) {
                Log.i(TAG, "人脸识别限流: detected=${faces.size}, selected=${facesForRecognition.size}")
            }
            val recResult = modelManager.ensureModelReady(REC_MODEL_ID)
            if (recResult.isFailure) {
                return@withContext facesForRecognition.map { FaceProvider.DetectedFace(it.box, FloatArray(EMBEDDING_DIM)) }
            }
            val results = mutableListOf<FaceProvider.DetectedFace>()
            for (face in facesForRecognition) {
                // 通过 session 池获取独立 rec session（独立 intra-op 线程池），实现多核并行
                val emb = modelManager.withOnnxSession(REC_MODEL_ID) { recSession ->
                    // ArcFace 嵌入必须基于 5 关键点对齐到 112×112（ReActor ArcFaceONNX.get 的 norm_crop）。
                    // Every temporary face bitmap is owned and recycled inside this session block.
                    val faceBitmap = if (face.landmarks != null) {
                        val lmkPx = FaceAligner.denormalizeLandmarks(
                            face.landmarks,
                            original.width,
                            original.height,
                        )
                        val affineM = FaceAligner.computeAffineMatrix(lmkPx, REC_INPUT_SIZE)
                        try {
                            if (!affineM.empty()) {
                                FaceAligner.warpAffine(original, affineM, REC_INPUT_SIZE)
                            } else {
                                null
                            }
                        } finally {
                            affineM.release()
                        } ?: cropFace(original, face.box)
                    } else {
                        cropFace(original, face.box)
                    }
                    if (faceBitmap == null) {
                        FloatArray(EMBEDDING_DIM)
                    } else {
                        try {
                            extractEmbedding(faceBitmap, recSession) ?: FloatArray(EMBEDDING_DIM)
                        } finally {
                            if (faceBitmap !== original && !faceBitmap.isRecycled) faceBitmap.recycle()
                        }
                    }
                }
                results.add(FaceProvider.DetectedFace(face.box, emb, face.landmarks))
            }
            results
        } finally {
            original?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            modelManager.unregisterConsumer(REC_MODEL_ID, consumerId)
            modelManager.unregisterConsumer(DET_MODEL_ID, consumerId)
        }
    }

    override suspend fun release() = Unit

    private fun detectFacesOnnx(bitmap: Bitmap, session: OrtSession): List<FaceProvider.DetectedFace>? {
        val env = NativeAiRuntime.getOrtEnvironment()
        return try {
            // 等比缩放（letterbox）：保持宽高比，padding 到 DET_INPUT_SIZE×DET_INPUT_SIZE
            // ComfyUI Reactor 标准预处理：非等比缩放会扭曲人脸导致 SCRFD 置信度降低
            val scale = minOf(
                DET_INPUT_SIZE.toFloat() / bitmap.width,
                DET_INPUT_SIZE.toFloat() / bitmap.height,
            )
            val scaledW = (bitmap.width * scale).toInt()
            val scaledH = (bitmap.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
            val padded = Bitmap.createBitmap(DET_INPUT_SIZE, DET_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(padded)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled != bitmap) scaled.recycle()

            val buf = preprocessDetection(padded)
            padded.recycle()

            val inputName = session.inputNames.iterator().next()
            val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, DET_INPUT_SIZE.toLong(), DET_INPUT_SIZE.toLong()))
            Log.i("CrashDebug", ">> InsightFace detect session.run (线程=${Thread.currentThread().name})")
            val result = session.run(mapOf(inputName to tensor))
            Log.i("CrashDebug", "<< InsightFace detect session.run 完成")
            tensor.close()

            // SCRFD det_10g 输出 9 个张量：score/bbox/kps × stride 8/16/32。
            val outputs = mutableMapOf<String, OnnxValue>()
            for (entry in result) outputs[entry.key] = entry.value
            Log.i(TAG, "SCRFD 输出张量: ${outputs.entries.associate { it.key to it.value.info.toString() }}")
            val faces = scrfdDecode(outputs, scale, bitmap.width, bitmap.height)
            Log.i(TAG, "SCRFD 解码人脸数: ${faces.size}")
            result.close()
            faces
        } catch (e: Exception) {
            Log.w(TAG, "ONNX detect failed", e)
            null
        }
    }

    private fun extractEmbedding(faceBitmap: Bitmap, session: OrtSession): FloatArray? {
        val env = NativeAiRuntime.getOrtEnvironment()
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, REC_INPUT_SIZE, REC_INPUT_SIZE, true)
            val buf = preprocessRecognition(resized)
            if (resized != faceBitmap) resized.recycle()

            val inputName = session.inputNames.iterator().next()
            val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, REC_INPUT_SIZE.toLong(), REC_INPUT_SIZE.toLong()))
            Log.i("CrashDebug", ">> InsightFace rec session.run (线程=${Thread.currentThread().name})")
            val result = session.run(mapOf(inputName to tensor))
            Log.i("CrashDebug", "<< InsightFace rec session.run 完成")
            tensor.close()

            val firstOutput = result.iterator().let { iterator ->
                if (iterator.hasNext()) iterator.next() else null
            }
            val embedding = firstOutput?.value?.value?.let(::asSingleEmbedding)
            result.close()

            embedding?.let(::l2Normalize)
        } catch (e: Exception) {
            Log.w(TAG, "ONNX embed failed", e)
            null
        }
    }

    private fun preprocessDetection(bitmap: Bitmap): FloatBuffer {
        // SCRFD det_10g 输入：NCHW，RGB，(pixel-127.5)/128.0 归一化（Reactor 标准）
        val s = DET_INPUT_SIZE
        val buf = ByteBuffer.allocateDirect(s * s * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(s * s)
        bitmap.getPixels(px, 0, s, 0, 0, s, s)
        for (p in px) buf.put(((p shr 16 and 0xFF) - 127.5f) / 128.0f) // R plane
        for (p in px) buf.put(((p shr 8 and 0xFF) - 127.5f) / 128.0f)  // G plane
        for (p in px) buf.put(((p and 0xFF) - 127.5f) / 128.0f)         // B plane
        buf.rewind(); return buf
    }

    private fun preprocessRecognition(bitmap: Bitmap): FloatBuffer {
        // ArcFace w600k_r50 输入：NCHW，RGB，(x/127.5 - 1) 归一化到 [-1,1]
        val s = REC_INPUT_SIZE
        val buf = ByteBuffer.allocateDirect(s * s * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(s * s)
        bitmap.getPixels(px, 0, s, 0, 0, s, s)
        for (p in px) buf.put((p shr 16 and 0xFF) / 127.5f - 1f) // R plane
        for (p in px) buf.put((p shr 8 and 0xFF) / 127.5f - 1f)  // G plane
        for (p in px) buf.put((p and 0xFF) / 127.5f - 1f)         // B plane
        buf.rewind(); return buf
    }

    /**
     * SCRFD 多尺度后处理（对齐 InsightFace/ReActor 原生实现）。
     *
     * det_10g.onnx 输出 9 个张量（数字索引命名，如 448/451/454/471/...），
     * 按 stride 8/16/32 分组，每组含 score[N,1]、bbox[N,4]、kps[N,10]。
     *
     * ## anchor 布局（关键）
     *
     * det_10g 的 fmc=3 → num_anchors=2，即每个空间位置有 2 个 anchor。
     * anchor 中心位于 cell 左上角 (col*stride, row*stride)，**无** +stride/2。
     * 索引 i 对应的空间位置：pos = i / num_anchors; col = pos % w; row = pos // w。
     * （参考 ReActor inswap.py:94,124-127）
     *
     * ## 坐标系统
     *
     * 检测前做了等比 letterbox（[scale]=min(640/w,640/h)），模型输出在 640 画布像素空间。
     * 本方法将坐标换算为**相对原图的归一化坐标**（∈[0,1]），与 [DetectedFace.box] 契约一致。
     *
     * @param outputs ONNX 原始输出（按名称索引）
     * @param scale letterbox 缩放比（min(640/imgW, 640/imgH)）
     * @param imgW 原图宽度（像素）
     * @param imgH 原图高度（像素）
     */
    /** SCRFD 解码中间结果：box + score + 5关键点(归一化) */
    private data class ScrfdDetection(val box: RectF, val score: Float, val landmarks: FloatArray?)

    private fun scrfdDecode(outputs: Map<String, OnnxValue>, scale: Float, imgW: Int, imgH: Int): List<FaceProvider.DetectedFace> {
        val faces = mutableListOf<ScrfdDetection>()
        val size = DET_INPUT_SIZE.toFloat()
        // letterbox 反归一化系数：将"相对 640 画布"归一化坐标换算为"相对原图"归一化坐标
        val invScaleX = size / (scale * imgW)
        val invScaleY = size / (scale * imgH)

        // 收集所有 score/bbox/kps 候选：按第二维 C 识别
        val scoreCandidates = mutableListOf<Pair<Int, Array<FloatArray>>>()  // (N, scores) C=1
        val bboxCandidates = mutableListOf<Pair<Int, Array<FloatArray>>>()   // (N, bboxes) C=4
        val kpsCandidates = mutableListOf<Pair<Int, Array<FloatArray>>>()    // (N, kps) C=10
        for ((_, tensor) in outputs) {
            val arr = asRows(tensor.getValue()) ?: continue
            if (arr.isEmpty()) continue
            val n = arr.size
            val c = arr.first().size
            when (c) {
                1 -> scoreCandidates.add(n to arr)
                4 -> bboxCandidates.add(n to arr)
                10 -> kpsCandidates.add(n to arr)
            }
        }

        // M2: 从 stride 动态反推 N，避免硬编码查表在改 DET_INPUT_SIZE 或切换 fmc/num_anchors
        // 不同的 SCRFD 模型时全部 miss → 零检测静默降级。
        // N = (INPUT_SIZE/stride)² * numAnchors；det_10g (fmc=3, num_anchors=2, 640²) → 12800/3200/800。
        val numAnchorsForN = 2  // det_10g: fmc=3 → num_anchors=2
        val featStrides = listOf(8, 16, 32)
        val strideByN = featStrides.associate { stride ->
            val cells = DET_INPUT_SIZE / stride
            (cells * cells * numAnchorsForN) to stride
        }
        for ((n, stride) in strideByN) {
            val scores = scoreCandidates.firstOrNull { it.first == n }?.second ?: continue
            val bboxes = bboxCandidates.firstOrNull { it.first == n }?.second ?: continue
            val kps = kpsCandidates.firstOrNull { it.first == n }?.second
            val count = minOf(scores.size, bboxes.size)
            val cols = DET_INPUT_SIZE / stride
            val numAnchors = 2  // det_10g: fmc=3 → num_anchors=2 (ReActor inswap.py:94)
            for (i in 0 until count) {
                val scoreRow = scores.getOrNull(i) ?: continue
                val score = scoreRow.getOrElse(0) { 0f }
                if (score < DET_CONF_THRESHOLD) continue
                val bbox = bboxes.getOrNull(i) ?: continue
                if (bbox.size < 4) continue
                // anchor 中心 = (col*stride, row*stride)，无 +stride/2；每位置 2 个 anchor
                val pos = i / numAnchors
                val cx = (pos % cols) * stride
                val cy = (pos / cols) * stride
                // bbox: 640 画布像素 → 原图归一化坐标
                val x1 = ((cx - bbox[0] * stride).coerceIn(0f, size) / size * invScaleX).coerceIn(0f, 1f)
                val y1 = ((cy - bbox[1] * stride).coerceIn(0f, size) / size * invScaleY).coerceIn(0f, 1f)
                val x2 = ((cx + bbox[2] * stride).coerceIn(0f, size) / size * invScaleX).coerceIn(0f, 1f)
                val y2 = ((cy + bbox[3] * stride).coerceIn(0f, size) / size * invScaleY).coerceIn(0f, 1f)
                if (x2 <= x1 || y2 <= y1) continue

                // 解码 5 关键点（左眼、右眼、鼻尖、嘴左、嘴右），原图归一化到 [0,1]
                val landmarks = if (kps != null && kps.size > i && kps[i].size >= 10) {
                    val kp = kps[i]
                    FloatArray(10) { j ->
                        val axis = j % 2  // 0=x, 1=y
                        val base = if (axis == 0) cx else cy
                        val invScale = if (axis == 0) invScaleX else invScaleY
                        (((base + kp[j] * stride).coerceIn(0f, size)) / size * invScale).coerceIn(0f, 1f)
                    }
                } else null

                faces.add(ScrfdDetection(RectF(x1, y1, x2, y2), score, landmarks))
            }
        }
        Log.i(TAG, "SCRFD 解码候选人脸数: ${faces.size}")
        return nms(faces).map {
            FaceProvider.DetectedFace(it.box, FloatArray(EMBEDDING_DIM), it.landmarks)
        }
    }

    private fun cropFace(source: Bitmap, nBox: RectF): Bitmap? {
        val x = (nBox.left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (nBox.top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = ((nBox.right - nBox.left) * source.width).toInt().coerceAtLeast(1)
        val h = ((nBox.bottom - nBox.top) * source.height).toInt().coerceAtLeast(1)
        val rw = (x + w).coerceAtMost(source.width) - x
        val rh = (y + h).coerceAtMost(source.height) - y
        return if (rw > 0 && rh > 0) Bitmap.createBitmap(source, x, y, rw, rh) else null
    }

    private fun nms(detections: List<ScrfdDetection>, thresh: Float = DET_NMS_THRESHOLD): List<ScrfdDetection> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<ScrfdDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); result.add(best)
            val iter = sorted.iterator()
            while (iter.hasNext()) if (iou(best.box, iter.next().box) > thresh) iter.remove()
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = maxOf(a.left, b.left); val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.right, b.right); val y2 = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val aA = (a.right - a.left) * (a.bottom - a.top)
        val aB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (aA + aB - inter + 1e-6f)
    }

    /** 解析 [1, embeddingDim] 或直接 [embeddingDim] 的识别输出。 */
    private fun asSingleEmbedding(value: Any): FloatArray? = when (value) {
        is FloatArray -> value.takeIf { it.size == EMBEDDING_DIM }
        is Array<*> -> (value.singleOrNull() as? FloatArray)?.takeIf { it.size == EMBEDDING_DIM }
        else -> null
    }

    /** 解析 SCRFD [N, C] 或 [1, N, C] 输出，拒绝空行和不规则数组。 */
    private fun asRows(value: Any): Array<FloatArray>? {
        val rows: Array<*> = when (value) {
            is Array<*> -> when (value.firstOrNull()) {
                is FloatArray -> value
                is Array<*> -> value.singleOrNull() as? Array<*> ?: return null
                else -> return null
            }
            else -> return null
        }
        val parsed = ArrayList<FloatArray>(rows.size)
        for (row in rows) {
            parsed += (row as? FloatArray)?.takeIf { it.isNotEmpty() } ?: return null
        }
        return parsed.toTypedArray()
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(vec.map { it * it }.sum())
        if (norm > 1e-6f) vec.forEachIndexed { i, v -> vec[i] = v / norm }
        return vec
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = if (file.length() > 5 * 1024 * 1024) 2 else 1
            })
        } catch (_: Exception) { null }
    }
}
