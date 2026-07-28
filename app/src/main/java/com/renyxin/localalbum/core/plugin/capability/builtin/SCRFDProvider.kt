package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SCRFD 轻量人脸检测 Provider (scrfd_person_2.5g.onnx)。
 * 快速人体/人脸检测，仅返回检测框，不提取嵌入向量。
 */
class SCRFDProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : FaceProvider {

    companion object {
        private const val TAG = "SCRFD"
        const val MODEL_ID = "model:scrfd"
        private const val INPUT_SIZE = 640
    }

    override val providerId = "model:scrfd"
    override val displayName = "SCRFD 轻量人体检测"
    override val embeddingDim = 0

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = MODEL_ID,
                displayName = "SCRFD Person 2.5g",
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, INPUT_SIZE, INPUT_SIZE),
                outputShape = intArrayOf(1, 0),
                fileSizeBytes = 3_710_000,
                memoryFootprintBytes = 10_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "scrfd_person_2.5g.onnx",
            )
        )
    }

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext emptyList()
        val result = modelManager.ensureModelReady(MODEL_ID)
        if (result.isFailure) return@withContext emptyList()
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            // SCRFD 检测输入：NCHW，RGB，(pixel-127.5)/128.0 归一化（ReActor 标准）
            val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (p in pixels) buf.put(((p shr 16 and 0xFF) - 127.5f) / 128.0f) // R plane
            for (p in pixels) buf.put(((p shr 8 and 0xFF) - 127.5f) / 128.0f)  // G plane
            for (p in pixels) buf.put(((p and 0xFF) - 127.5f) / 128.0f)         // B plane
            buf.rewind()
            if (resized != bitmap) resized.recycle()

            // 通过 session 池获取独立 session（独立 intra-op 线程池），实现多核并行推理
            modelManager.withOnnxSession(MODEL_ID) { session ->
                val env = OrtEnvironment.getEnvironment()
                val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
                val output = session.run(mapOf(session.inputNames.iterator().next() to input))
                input.close()

                // SCRFD 输出 score_{s}/bbox_{s} (s=8/16/32)，按多尺度 anchor 解码
                val outputs = mutableMapOf<String, OnnxValue>()
                for (entry in output) outputs[entry.key] = entry.value
                val faces = scrfdDecode(outputs)
                output.close()
                faces
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCRFD 检测失败", e)
            emptyList()
        }
    }

    /**
     * SCRFD 多尺度后处理（对齐 InsightFace/ReActor 原生实现）。
     *
     * 按张量形状识别 score[N,1]/bbox[N,4]（兼容数字索引输出名），
     * num_anchors=2 per spatial position，anchor 中心 = (col*stride, row*stride)。
     *
     * ⚠️ S2 警告：本方法的坐标归一化 `/size` 与 detectFaces 中的**直接拉伸**预处理配套
     * （createScaledBitmap 全图→全画布映射，无需 letterbox 修正，对拉伸是正确的）。
     * 若未来将预处理改为等比 letterbox（保持宽高比+黑边），必须同步更新此处的坐标解码
     * （撤销 letterbox scale），否则会引入与 InsightFaceProvider 问题 2 相同的坐标错位 bug。
     */
    private fun scrfdDecode(outputs: Map<String, OnnxValue>): List<FaceProvider.DetectedFace> {
        val faces = mutableListOf<Pair<RectF, Float>>()
        val size = INPUT_SIZE.toFloat()

        // 收集所有 score/bbox 候选：按第二维 C 识别（兼容数字输出名）
        val scoreCandidates = mutableListOf<Pair<Int, Array<FloatArray>>>()  // (N, scores) C=1
        val bboxCandidates = mutableListOf<Pair<Int, Array<FloatArray>>>()   // (N, bboxes) C=4
        for ((name, tensor) in outputs) {
            val arr = try {
                @Suppress("UNCHECKED_CAST")
                tensor.getValue() as? Array<FloatArray>
            } catch (_: Exception) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val nested = tensor.getValue() as? Array<Array<FloatArray>>
                    nested?.firstOrNull()
                } catch (_: Exception) { null }
            } ?: continue
            if (arr.isEmpty()) continue
            val c = arr[0]?.size ?: 0
            when (c) {
                1 -> scoreCandidates.add(arr.size to arr)
                4 -> bboxCandidates.add(arr.size to arr)
            }
        }

        // M2: 从 stride 动态反推 N = (INPUT_SIZE/stride)² * numAnchors，避免硬编码查表
        // 在改 INPUT_SIZE 或切换 fmc/num_anchors 不同的 SCRFD 模型时全部 miss → 零检测静默降级。
        val numAnchors = 2  // fmc=3 → num_anchors=2
        val featStrides = listOf(8, 16, 32)
        val strideByN = featStrides.associate { stride ->
            val cells = INPUT_SIZE / stride
            (cells * cells * numAnchors) to stride
        }
        for ((n, stride) in strideByN) {
            val scores = scoreCandidates.firstOrNull { it.first == n }?.second ?: continue
            val bboxes = bboxCandidates.firstOrNull { it.first == n }?.second ?: continue
            val count = minOf(scores.size, bboxes.size)
            val cols = INPUT_SIZE / stride
            for (i in 0 until count) {
                val scoreRow = scores.getOrNull(i) ?: continue
                val score = scoreRow.getOrElse(0) { 0f }
                if (score < 0.5f) continue
                val bbox = bboxes.getOrNull(i) ?: continue
                if (bbox.size < 4) continue
                // anchor 中心 = (col*stride, row*stride)，无 +stride/2；每位置 2 个 anchor
                val pos = i / numAnchors
                val cx = (pos % cols) * stride
                val cy = (pos / cols) * stride
                val x1 = (cx - bbox[0] * stride).coerceIn(0f, size) / size
                val y1 = (cy - bbox[1] * stride).coerceIn(0f, size) / size
                val x2 = (cx + bbox[2] * stride).coerceIn(0f, size) / size
                val y2 = (cy + bbox[3] * stride).coerceIn(0f, size) / size
                if (x2 <= x1 || y2 <= y1) continue
                faces.add(RectF(x1, y1, x2, y2) to score)
            }
        }
        return nms(faces).map { FaceProvider.DetectedFace(it.first, FloatArray(0)) }
    }

    private fun nms(boxes: List<Pair<RectF, Float>>, thresh: Float = 0.4f): List<Pair<RectF, Float>> {
        val sorted = boxes.sortedByDescending { it.second }.toMutableList()
        val result = mutableListOf<Pair<RectF, Float>>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); result.add(best)
            val iter = sorted.iterator()
            while (iter.hasNext()) if (iou(best.first, iter.next().first) > thresh) iter.remove()
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

    override suspend fun release() {
        modelManager.unregisterConsumer(MODEL_ID, "SCRFD")
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
    }
}
