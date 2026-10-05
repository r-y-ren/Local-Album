package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.image.DbBoxGeometry
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.runtime.NativeAiRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PaddleOCR Provider — 使用 PP-OCRv6 (检测) + PP-OCRv5 (识别) ONNX 模型。
 *
 * 双模型串联：
 * 1. PP-OCRv6_small_det_infer/inference.onnx → DB 检测文字区域（二值化 + 连通域 + 行分裂
 *    + 旋转最小外接矩形 + 均分过滤 + unclip 外扩，见 [DbBoxGeometry]）
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
        /**
         * 检测输入长边上限（官方 DetResizeForTest limit_type=max 的取值）。
         * 检测模型 H/W 均为动态维度，输入按等比缩放到长边 960、宽高取 32 倍数后直接推理，
         * 不做方形填充。原 640 方案把整图压进 640²，照片/截图里的小字在检测图上只剩
         * 5~10px 高，DB 检不出来——是"画面文字大量漏检"的主因。
         */
        private const val DET_LIMIT_SIDE = 960
        // 修复：PP-OCRv5_mobile_rec 模型要求输入高度为 48（见 inference.yml RecResizeImg.image_shape: [3, 48, 320]），
        // 原值 32 导致 ONNX Runtime 报错 ORT_INVALID_ARGUMENT (Got: 32 Expected: 48)。
        private const val REC_IMG_H = 48
        /**
         * 识别输入宽度上限。宽度维度为动态（模型导出为 dynamic），按行宽等比缩放、
         * 无需填充；原固定 320 宽在行宽超过 6.7:1 后水平压扁，长文本行字形失真丢字。
         */
        private const val REC_IMG_MAX_W = 1280
        private const val DET_THRESH = 0.3f
        private const val MIN_BOX_AREA = 100
        /** 概率图坐标下检测框的最短边（官方 det_db_min_size≈3），滤表格线/下划线。 */
        private const val MIN_BOX_SIDE = 3
        private const val MIN_REC_BOX_WIDTH = 12
        private const val MIN_REC_BOX_HEIGHT = 10
        /**
         * DB unclip 外扩系数（官方 det_db_unclip_ratio=1.6）：DB 概率图是收缩过的
         * 文本区，按 offset = 面积×ratio/周长 外扩才能覆盖完整字形。
         */
        private const val UNCLIP_RATIO = 1.6f
        /** 框内平均概率阈值（官方 det_db_box_thresh=0.6），过滤背景纹理误检。 */
        private const val BOX_SCORE_THRESH = 0.6f
        // 预处理等比缩放后短边/右侧的填充色 = ImageNet 均值（与官方"归一化后补 0"等价）
        /** OCR 解码源图的最长边上限：识别 crop 取自源图，上限直接决定小字可读性。 */
        private const val DECODE_MAX_DIM = 2048
        /**
         * 每图参与识别的文字区域上限。长截图/文档/海报常有上百个文字行，按面积
         * 截断会系统性丢小字行；mobile 级识别单次推理 ~20ms，64 个区域仍在秒级。
         */
        private const val MAX_TEXT_REGIONS = 64

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
                inputShape = listOf(1, 3, DET_LIMIT_SIDE, DET_LIMIT_SIDE),
                outputShape = intArrayOf(1, 1, DET_LIMIT_SIDE, DET_LIMIT_SIDE),
                fileSizeBytes = 8_000_000,
                memoryFootprintBytes = 60_000_000,
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
                inputShape = listOf(1, 3, REC_IMG_H, REC_IMG_MAX_W),
                outputShape = intArrayOf(1, 0),
                fileSizeBytes = 14_000_000,
                memoryFootprintBytes = 30_000_000,
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
                .filter { it.w >= MIN_REC_BOX_WIDTH && it.h >= MIN_REC_BOX_HEIGHT }
                .sortedByDescending { it.w * it.h }
                .take(MAX_TEXT_REGIONS)
                .sortedWith(compareBy({ it.cy }, { it.cx }))
            val lines = mutableListOf<String>()
            for (region in recognitionRegions) {
                val crop = cropRegion(bitmap, region) ?: continue
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

    private fun detectTextRegions(bitmap: Bitmap, session: OrtSession): List<DetRegion> {
        val env = NativeAiRuntime.getOrtEnvironment()
        return try {
            // 官方 DB 预处理：等比缩放到长边 DET_LIMIT_SIDE（小图放大，利于检出），
            // 宽高取 32 倍数。模型 H/W 为动态维度，按实际尺寸推理，不做方形填充。
            val scale = min(
                DET_LIMIT_SIDE.toFloat() / bitmap.width,
                DET_LIMIT_SIDE.toFloat() / bitmap.height,
            )
            val rw = roundToMultipleOf32(bitmap.width * scale)
            val rh = roundToMultipleOf32(bitmap.height * scale)
            val resized = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
            val buf = preprocessDet(resized)

            val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, rh.toLong(), rw.toLong()))
            val result = session.run(mapOf(session.inputNames.iterator().next() to input))
            input.close()

            // 输出契约为 [1, 1, H, W]。逐层检查而非强制转换，避免模型文件或
            // ONNX Runtime 输出类型改变时产生 ClassCastException / 未检查泛型转换。
            val firstOutput = result.iterator().let { iterator ->
                if (iterator.hasNext()) iterator.next() else null
            }
            val probMap = firstOutput?.value?.value?.let(::asDetectionProbabilityMap)
            result.close()
            if (probMap == null || probMap.isEmpty()) return emptyList()

            val regions = findTextRegions(probMap)
            // 按概率图实际尺寸做按轴映射（不假设输出与输入同尺寸），夹紧到源图内
            val scaleX = bitmap.width.toFloat() / probMap[0].size
            val scaleY = bitmap.height.toFloat() / probMap.size
            regions.map { r ->
                DetRegion(
                    cx = (r.cx * scaleX).toFloat().coerceIn(0f, bitmap.width.toFloat()),
                    cy = (r.cy * scaleY).toFloat().coerceIn(0f, bitmap.height.toFloat()),
                    w = (r.width * scaleX).toFloat().coerceAtMost(bitmap.width.toFloat()),
                    h = (r.height * scaleY).toFloat().coerceAtMost(bitmap.height.toFloat()),
                    angleDeg = r.angleDeg.toFloat(),
                )
            }.filter { it.w > 0f && it.h > 0f }
                .sortedWith(compareBy({ it.cy }, { it.cx }))
        } catch (e: Exception) {
            Log.w(TAG, "文字检测失败", e)
            emptyList()
        }
    }

    private fun roundToMultipleOf32(value: Float): Int =
        (((value + 16f) / 32f).toInt() * 32).coerceIn(32, DET_LIMIT_SIDE)

    /**
     * 检测框（源图坐标）：中心 + 沿行方向的长宽 + 行方向角度（度）。
     */
    private class DetRegion(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val angleDeg: Float,
    )

    /**
     * DB 后处理（全部在概率图坐标系）：二值化 → 连通域 → 行投影分裂（拆粘连行）
     * → 旋转最小外接矩形 → 框内均分过滤 → unclip 外扩。
     *
     * 相比旧版连通域 AABB 的两处根治：①相邻行被细桥粘连时不再并成一个大框
     * （多行压进一条 48px 识别条带 → 整片废文本）；②倾斜文本拿到贴行的旋转框，
     * 不再把大片背景混进识别 crop。
     */
    private fun findTextRegions(probMap: Array<FloatArray>): List<DbBoxGeometry.RotatedRect> {
        val h = probMap.size
        val w = if (h > 0) probMap[0].size else 0
        if (h == 0 || w == 0) return emptyList()

        val binary = BooleanArray(h * w) { probMap[it / w][it % w] > DET_THRESH }
        val visited = BooleanArray(h * w)
        // 原始 Int 栈/缓冲避免装箱：960 长边下概率图最大 ~92 万格
        val stack = IntArray(h * w)
        val compPixels = IntArray(h * w)
        val xs = IntArray(h * w)
        val ys = IntArray(h * w)
        val rowCounts = IntArray(h)
        val regions = mutableListOf<DbBoxGeometry.RotatedRect>()

        for (startIdx in binary.indices) {
            if (!binary[startIdx] || visited[startIdx]) continue
            // BFS 连通域
            var sp = 0
            var np = 0
            var minY = Int.MAX_VALUE
            var maxY = -1
            stack[sp++] = startIdx
            visited[startIdx] = true
            while (sp > 0) {
                val idx = stack[--sp]
                compPixels[np++] = idx
                val x = idx % w
                val y = idx / w
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                rowCounts[y]++
                // 4 邻接
                if (x > 0) { val n = idx - 1; if (binary[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (x < w - 1) { val n = idx + 1; if (binary[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (y > 0) { val n = idx - w; if (binary[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
                if (y < h - 1) { val n = idx + w; if (binary[n] && !visited[n]) { visited[n] = true; stack[sp++] = n } }
            }

            // 行分裂 + 逐段旋转框
            for (band in DbBoxGeometry.splitRowBands(rowCounts, minY, maxY)) {
                var n = 0
                var scoreSum = 0.0
                for (i in 0 until np) {
                    val idx = compPixels[i]
                    val y = idx / w
                    if (y < band.first || y > band.last) continue
                    xs[n] = idx % w
                    ys[n] = y
                    scoreSum += probMap[y][xs[n]].toDouble()
                    n++
                }
                if (n == 0) continue
                // 框内均分过滤（官方 box_thresh）：连通域只看阈值化形状，
                // 背景纹理误检在均分上过不了 0.6
                if (scoreSum / n < BOX_SCORE_THRESH) continue
                val rect = DbBoxGeometry.minAreaRect(xs, ys, n) ?: continue
                if (min(rect.width, rect.height) < MIN_BOX_SIDE) continue
                if (rect.width * rect.height < MIN_BOX_AREA) continue
                regions.add(DbBoxGeometry.unclip(rect, UNCLIP_RATIO.toDouble()))
            }
            for (y in minY..maxY) rowCounts[y] = 0
        }
        return regions
    }

    /** 检测预处理：NCHW，ImageNet 归一化（输入为等比缩放后的非方形位图） */
    private fun preprocessDet(bitmap: Bitmap): java.nio.FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
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
        val env = NativeAiRuntime.getOrtEnvironment()
        return try {
            // 官方识别预处理：高度等比缩放到 48，宽度按行宽等比（模型宽度维度为动态，
            // 单条推理无需填充）。原实现固定 320 宽填充，长文本行被水平压扁 2~3 倍，
            // 字形失真是丢字/错字的直接原因；仅行宽超过 26.7:1 时才截断式压缩。
            val ratio = REC_IMG_H.toFloat() / crop.height
            val targetW = (crop.width * ratio).toInt().coerceIn(1, REC_IMG_MAX_W)
            val resized = Bitmap.createScaledBitmap(crop, targetW, REC_IMG_H, true)
            val buf = preprocessRec(resized)

            val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, REC_IMG_H.toLong(), targetW.toLong()))
            val result = session.run(mapOf(session.inputNames.iterator().next() to input))
            input.close()

            // 输出契约为 [1, W, C]。逐层验证数组层级和元素类型。
            val firstOutput = result.iterator().let { iterator ->
                if (iterator.hasNext()) iterator.next() else null
            }
            val timeSteps = firstOutput?.value?.value?.let(::asRecognitionTimeSteps)
            result.close()
            if (timeSteps.isNullOrEmpty()) return ""

            ctcDecode(timeSteps)
        } catch (e: Exception) {
            Log.w(TAG, "文字识别失败", e)
            ""
        }
    }

    /** 将 ONNX 检测输出严格解析为 [H, W] 概率图。 */
    private fun asDetectionProbabilityMap(value: Any): Array<FloatArray>? {
        val batch = value as? Array<*> ?: return null
        val channels = batch.singleOrNull() as? Array<*> ?: return null
        val rows = channels.singleOrNull() as? Array<*> ?: return null
        return rows.mapOrNull { it as? FloatArray }
    }

    /** 将 ONNX 识别输出严格解析为 batch=1 的 [W, C] logits。 */
    private fun asRecognitionTimeSteps(value: Any): Array<FloatArray>? {
        val batch = value as? Array<*> ?: return null
        val rows = batch.singleOrNull() as? Array<*> ?: return null
        return rows.mapOrNull { it as? FloatArray }
    }

    /** 当且仅当所有元素都是 FloatArray 时生成数组，避免部分损坏输出进入后处理。 */
    private fun Array<*>.mapOrNull(transform: (Any?) -> FloatArray?): Array<FloatArray>? {
        val mapped = ArrayList<FloatArray>(size)
        for (element in this) {
            mapped += transform(element) ?: return null
        }
        return mapped.toTypedArray()
    }

    /** 识别预处理：NCHW，归一化到 [-1,1] (x/127.5 - 1)，输入为 48×行宽 的位图 */
    private fun preprocessRec(bitmap: Bitmap): java.nio.FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
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

    /**
     * 从源图裁出检测框内容。近水平（|角|<5°）直接轴对齐裁剪；倾斜文本用矩阵
     * 把行方向转正后绘制到 w×h 输出（等价官方 get_rotate_crop_image）。
     */
    private fun cropRegion(source: Bitmap, r: DetRegion): Bitmap? {
        if (r.w < 1f || r.h < 1f) return null
        val outW = r.w.toInt().coerceAtMost(source.width)
        val outH = r.h.toInt().coerceAtMost(source.height)
        if (outW < 1 || outH < 1) return null
        return if (abs(r.angleDeg) < 5f) {
            val x = (r.cx - outW / 2f).toInt().coerceIn(0, source.width - 1)
            val y = (r.cy - outH / 2f).toInt().coerceIn(0, source.height - 1)
            val cw = outW.coerceAtMost(source.width - x)
            val ch = outH.coerceAtMost(source.height - y)
            if (cw < 1 || ch < 1) null else Bitmap.createBitmap(source, x, y, cw, ch)
        } else {
            val cx = r.cx.coerceIn(0f, source.width.toFloat())
            val cy = r.cy.coerceIn(0f, source.height.toFloat())
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val matrix = android.graphics.Matrix()
            matrix.postRotate(-r.angleDeg, cx, cy)
            matrix.postTranslate(outW / 2f - cx, outH / 2f - cy)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, matrix, paint)
            out
        }
    }

    private fun decodeBitmap(file: File): Bitmap? =
        // RobustImageDecode 内含 ImageDecoder 兜底（16-bit PNG 等）。OCR 专用链路：
        // 精确缩放到最长边 2048——2 的幂采样最多浪费一倍线性分辨率（1080×2400 截图
        // 在 1280 上限下只得 540×1200）。识别 crop 取自解码后的源图，上限决定小字可读性。
        com.renyxin.localalbum.core.image.RobustImageDecode.decodeFileScaled(file, maxDim = DECODE_MAX_DIM)
}
