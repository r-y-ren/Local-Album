package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PaddleOCR Provider — 使用 PP-OCRv6 (检测) + PP-OCRv5 (识别) ONNX 模型。
 *
 * 双模型串联：
 * 1. PP-OCRv6_small_det_infer/inference.onnx → DB 检测文字区域（概率图阈值 + 连通域外接框）
 * 2. PP-OCRv5_mobile_rec_infer/inference.onnx → CTC 识别文字内容（ppocrv5_dict.txt 字典）
 *
 * 字典位于 assets/ppocrv5_dict.txt（每行一个字符，18383 条），CTC blank 位于 index 0（PaddleOCR CTCLabelDecode 约定）。
 */
class PaddleOCRProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : OcrProvider {

    companion object {
        private const val TAG = "PaddleOCR"
        const val DET_MODEL_ID = "model:paddleocr_det"
        const val REC_MODEL_ID = "model:paddleocr_rec"
        private const val DET_INPUT_SIZE = 640
        // 修复：PP-OCRv5_mobile_rec 模型要求输入高度为 48（见 inference.yml RecResizeImg.image_shape: [3, 48, 320]），
        // 原值 32 导致 ONNX Runtime 报错 ORT_INVALID_ARGUMENT (Got: 32 Expected: 48)。
        private const val REC_IMG_H = 48
        private const val REC_IMG_W = 320
        private const val DET_THRESH = 0.3f
        private const val MIN_BOX_AREA = 100
        private const val MIN_REC_BOX_WIDTH = 12
        private const val MIN_REC_BOX_HEIGHT = 10
        private const val MAX_TEXT_REGIONS = 12

        // 检测使用 ImageNet 归一化
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // PP-OCRv5 多语言字典（18383 字符），配合 PP-OCRv5_mobile_rec 模型（输出 18385 类 = 18384 字符 + 1 blank）。
        // 原 ppocr_keys_v1.txt 仅 6623 字符（PP-OCRv4），与 PP-OCRv5 模型输出维度不匹配，
        // 导致 CTC 解码时大部分 argmax 索引超出字典范围，输出空文本。
        private const val DICT_PATH = "ppocrv5_dict.txt"
    }

    override val providerId = "model:paddleocr"
    override val displayName = "PaddleOCR 文字识别"

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = DET_MODEL_ID,
                displayName = "PP-OCRv6 Det",
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, DET_INPUT_SIZE, DET_INPUT_SIZE),
                outputShape = intArrayOf(1, 1, DET_INPUT_SIZE, DET_INPUT_SIZE),
                fileSizeBytes = 8_000_000,
                memoryFootprintBytes = 20_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetSubDir = "PP-OCRv6_small_det_infer",
                assetFileName = "inference.onnx",
            )
        )
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = REC_MODEL_ID,
                displayName = "PP-OCRv5 Rec",
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, REC_IMG_H, REC_IMG_W),
                outputShape = intArrayOf(1, 0),
                fileSizeBytes = 14_000_000,
                memoryFootprintBytes = 20_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetSubDir = "PP-OCRv5_mobile_rec_infer",
                assetFileName = "inference.onnx",
            )
        )
    }

    /** 字符字典（每行一个字符），懒加载 */
    private val charDict: List<String> by lazy { loadCharDict() }

    /** CTC blank 索引：PaddleOCR CTCLabelDecode 将 blank 置于 index 0，dict 字符位于 1..N */
    private val blankIndex: Int get() = 0

    private fun loadCharDict(): List<String> {
        return try {
            context.assets.open(DICT_PATH).bufferedReader().useLines { it.toList() }
        } catch (e: Exception) {
            Log.w(TAG, "加载字符字典失败: $DICT_PATH", e)
            emptyList()
        }
    }

    override suspend fun recognize(file: File): OcrProvider.OcrResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext OcrProvider.OcrResult("")
        try {
            val detResult = modelManager.ensureModelReady(DET_MODEL_ID)
            if (detResult.isFailure) return@withContext OcrProvider.OcrResult("OCR检测模型未就绪", averageConfidence = 0f)
            val recResult = modelManager.ensureModelReady(REC_MODEL_ID)
            if (recResult.isFailure) return@withContext OcrProvider.OcrResult("OCR识别模型未就绪", averageConfidence = 0f)

            // 通过 session 池获取独立 session（独立 intra-op 线程池），实现多核并行
            val regions = modelManager.withOnnxSession(DET_MODEL_ID) { detSession ->
                detectTextRegions(bitmap, detSession)
            }
            if (regions.isEmpty()) return@withContext OcrProvider.OcrResult("")

            // 识别模型调用次数是 OCR 的主要成本。检测结果按阅读顺序排列后，跳过极小
            // 噪声框，并保留面积最大的有限数量，避免海报/漫画中的碎片框拖慢整批扫描。
            val recognitionRegions = regions
                .filter { region ->
                    region[2] - region[0] >= MIN_REC_BOX_WIDTH &&
                        region[3] - region[1] >= MIN_REC_BOX_HEIGHT
                }
                .sortedByDescending { region -> (region[2] - region[0]) * (region[3] - region[1]) }
                .take(MAX_TEXT_REGIONS)
                .sortedWith(compareBy({ it[1] }, { it[0] }))
            val lines = mutableListOf<String>()
            for (region in recognitionRegions) {
                val crop = safeCrop(bitmap, region) ?: continue
                val text = modelManager.withOnnxSession(REC_MODEL_ID) { recSession ->
                    recognizeText(crop, recSession)
                }
                if (text.isNotBlank()) lines.add(text)
            }
            val fullText = lines.joinToString("\n")
            OcrProvider.OcrResult(
                fullText = fullText,
                lines = lines,
                averageConfidence = if (lines.isNotEmpty()) 0.85f else 0f,
            )
        } catch (e: Exception) {
            Log.w(TAG, "OCR 失败", e)
            OcrProvider.OcrResult("")
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(DET_MODEL_ID, "PaddleOCR")
        modelManager.unregisterConsumer(REC_MODEL_ID, "PaddleOCR")
    }

    // ---- 检测：DB 概率图 → 阈值 → 连通域外接框 ----

    private fun detectTextRegions(bitmap: Bitmap, session: OrtSession): List<IntArray> {
        val env = OrtEnvironment.getEnvironment()
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, DET_INPUT_SIZE, DET_INPUT_SIZE, true)
            val buf = preprocessDet(resized)
            if (resized != bitmap) resized.recycle()

            val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, DET_INPUT_SIZE.toLong(), DET_INPUT_SIZE.toLong()))
            android.util.Log.i("CrashDebug", ">> PaddleOCR det session.run (线程=${Thread.currentThread().name})")
            val result = session.run(mapOf(session.inputNames.iterator().next() to input))
            android.util.Log.i("CrashDebug", "<< PaddleOCR det session.run 完成")
            input.close()

            // 输出 [1,1,H,W] 概率图
            val probMap = (result.iterator().next().value.value as? Array<Array<Array<FloatArray>>>)?.getOrNull(0)?.getOrNull(0)
            result.close()
            if (probMap == null || probMap.isEmpty()) return emptyList()

            val boxes = findTextBoxes(probMap, DET_THRESH)
            // 缩放回原图坐标
            val sx = bitmap.width.toFloat() / DET_INPUT_SIZE
            val sy = bitmap.height.toFloat() / DET_INPUT_SIZE
            boxes.map { b ->
                intArrayOf(
                    (b[0] * sx).toInt().coerceIn(0, bitmap.width - 1),
                    (b[1] * sy).toInt().coerceIn(0, bitmap.height - 1),
                    (b[2] * sx).toInt().coerceIn(1, bitmap.width),
                    (b[3] * sy).toInt().coerceIn(1, bitmap.height),
                )
            }.filter { it[2] - it[0] > 0 && it[3] - it[1] > 0 }
                .sortedWith(compareBy({ it[1] }, { it[0] }))
        } catch (e: Exception) {
            Log.w(TAG, "文字检测失败", e)
            emptyList()
        }
    }

    /** DB 后处理：阈值化 + 4 连通域，返回各连通域的外接矩形 [x1,y1,x2,y2] */
    private fun findTextBoxes(probMap: Array<FloatArray>, thresh: Float): List<IntArray> {
        val h = probMap.size
        val w = if (h > 0) probMap[0].size else 0
        if (h == 0 || w == 0) return emptyList()

        val binary = BooleanArray(h * w) { probMap[it / w][it % w] > thresh }
        val visited = BooleanArray(h * w)
        val boxes = mutableListOf<IntArray>()

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                val startIdx = sy * w + sx
                if (!binary[startIdx] || visited[startIdx]) continue
                // BFS 连通域
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                val stack = java.util.ArrayDeque<Int>()
                stack.push(startIdx)
                visited[startIdx] = true
                while (stack.isNotEmpty()) {
                    val idx = stack.pop()
                    val x = idx % w
                    val y = idx / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    // 4 邻接
                    if (x > 0) { val n = idx - 1; if (binary[n] && !visited[n]) { visited[n] = true; stack.push(n) } }
                    if (x < w - 1) { val n = idx + 1; if (binary[n] && !visited[n]) { visited[n] = true; stack.push(n) } }
                    if (y > 0) { val n = idx - w; if (binary[n] && !visited[n]) { visited[n] = true; stack.push(n) } }
                    if (y < h - 1) { val n = idx + w; if (binary[n] && !visited[n]) { visited[n] = true; stack.push(n) } }
                }
                val area = (maxX - minX + 1) * (maxY - minY + 1)
                if (area >= MIN_BOX_AREA) {
                    boxes.add(intArrayOf(minX, minY, maxX + 1, maxY + 1))
                }
            }
        }
        return boxes
    }

    /** 检测预处理：NCHW，ImageNet 归一化 */
    private fun preprocessDet(bitmap: Bitmap): java.nio.FloatBuffer {
        val s = DET_INPUT_SIZE
        val buf = ByteBuffer.allocateDirect(s * s * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(s * s)
        bitmap.getPixels(px, 0, s, 0, 0, s, s)
        for (c in 0 until 3) {
            val mean = DET_MEAN[c]
            val std = DET_STD[c]
            for (i in px.indices) {
                val raw = (px[i] shr (16 - 8 * c) and 0xFF) / 255f
                buf.put((raw - mean) / std)
            }
        }
        buf.rewind()
        return buf
    }

    // ---- 识别：CTC 解码 ----

    private fun recognizeText(crop: Bitmap, session: OrtSession): String {
        val env = OrtEnvironment.getEnvironment()
        return try {
            val resized = Bitmap.createScaledBitmap(crop, REC_IMG_W, REC_IMG_H, true)
            val buf = preprocessRec(resized)
            if (resized != crop) resized.recycle()

            val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, REC_IMG_H.toLong(), REC_IMG_W.toLong()))
            android.util.Log.i("CrashDebug", ">> PaddleOCR rec session.run (线程=${Thread.currentThread().name})")
            val result = session.run(mapOf(session.inputNames.iterator().next() to input))
            android.util.Log.i("CrashDebug", "<< PaddleOCR rec session.run 完成")
            input.close()

            // 输出 [1, W, C]
            val out = result.iterator().next().value.value as? Array<Array<FloatArray>>
            result.close()
            if (out == null || out.isEmpty()) return ""

            ctcDecode(out[0])
        } catch (e: Exception) {
            Log.w(TAG, "文字识别失败", e)
            ""
        }
    }

    /** 识别预处理：NCHW，归一化到 [-1,1] (x/127.5 - 1) */
    private fun preprocessRec(bitmap: Bitmap): java.nio.FloatBuffer {
        val w = REC_IMG_W; val h = REC_IMG_H
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (c in 0 until 3) {
            for (i in px.indices) {
                val raw = (px[i] shr (16 - 8 * c) and 0xFF)
                buf.put(raw / 127.5f - 1f)
            }
        }
        buf.rewind()
        return buf
    }

    /** CTC 贪心解码：argmax → 合并连续重复 → 去除 blank */
    private fun ctcDecode(timeSteps: Array<FloatArray>): String {
        if (charDict.isEmpty()) return ""
        val sb = StringBuilder()
        var prev = -1
        for (t in timeSteps) {
            var maxIdx = 0
            var maxVal = -Float.MAX_VALUE
            for (c in t.indices) {
                if (t[c] > maxVal) { maxVal = t[c]; maxIdx = c }
            }
            if (maxIdx != blankIndex && maxIdx != prev) {
                // blank 在 index 0，dict 字符位于 1..N → dictIndex = maxIdx - 1
                val dictIndex = maxIdx - 1
                if (dictIndex in charDict.indices) sb.append(charDict[dictIndex])
            }
            prev = maxIdx
        }
        return sb.toString()
    }

    private fun safeCrop(source: Bitmap, region: IntArray): Bitmap? {
        val x = region[0].coerceIn(0, source.width - 1)
        val y = region[1].coerceIn(0, source.height - 1)
        val w = (region[2] - region[0]).coerceAtLeast(1).coerceAtMost(source.width - x)
        val h = (region[3] - region[1]).coerceAtLeast(1).coerceAtMost(source.height - y)
        return if (w > 0 && h > 0) Bitmap.createBitmap(source, x, y, w, h) else null
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) { null }
    }
}
