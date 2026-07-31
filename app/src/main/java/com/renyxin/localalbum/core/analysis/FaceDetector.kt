package com.renyxin.localalbum.core.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 人脸检测器（Phase 3.3 人脸聚类）。
 *
 * 基于 ML Kit Face Detection 实现端侧人脸检测，并提取可用于聚类的特征向量。
 *
 * 特征提取策略：
 * ML Kit 免费版不直接输出 128D/512D 嵌入向量，因此采用**几何特征 + 归一化坐标**方案：
 * - 5 个关键点（眼·鼻·嘴·耳）的归一化坐标（相对人脸框，10 维）
 * - 关键点间距离比例（眼距/框宽、嘴鼻距/框高 等，6 维）
 * - 人脸框宽高比（1 维）
 * - 轮廓点统计特征（均值·方差，4 维）
 *
 * 合计 21 维特征向量，经 L2 归一化后用于 DBSCAN 聚类。
 * 该方案对同一人不同表情/角度的鲁棒性有限，但完全离线、零额外模型体积，
 * 适合作为 v1 基线；后续可替换为 TFLite FaceNet 模型以提升精度。
 */
class FaceDetector(
    private val context: Context,
) {
    companion object {
        private const val TAG = "FaceDetector"

        /** 特征向量维度 */
        const val EMBEDDING_DIM = 21

        /** 输入图像最大边长（控制内存与精度平衡） */
        private const val MAX_INPUT_DIM = 640
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
    )

    /**
     * 单张图片的人脸检测结果。
     *
     * @param filePath 图片路径
     * @param faces 检测到的人脸列表（含边界框与特征向量）
     */
    data class DetectionResult(
        val filePath: String,
        val faces: List<DetectedFace>,
    )

    /**
     * 检测到的单个人脸。
     *
     * @param box 归一化边界框（相对图片宽高，0~1）
     * @param embedding 特征向量（L2 归一化）
     */
    data class DetectedFace(
        val box: RectF,
        val embedding: FloatArray,
    )

    /**
     * 检测单张图片中的人脸。
     *
     * @param file 图片文件
     * @return 检测结果，若文件无法解码或无脸则返回空 faces 列表
     */
    suspend fun detect(file: File): DetectionResult = withContext(Dispatchers.IO) {
        val filePath = file.absolutePath
        if (!file.exists()) {
            return@withContext DetectionResult(filePath, emptyList())
        }

        val bitmap = decodeBitmap(file) ?: run {
            Log.w(TAG, "无法解码图片: $filePath")
            return@withContext DetectionResult(filePath, emptyList())
        }

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val mlFaces = detectAsync(image)

            val imgW = bitmap.width.toFloat()
            val imgH = bitmap.height.toFloat()

            val detected = mlFaces.map { face ->
                val bb = face.boundingBox
                val box = RectF(
                    bb.left / imgW,
                    bb.top / imgH,
                    bb.right / imgW,
                    bb.bottom / imgH,
                )
                val embedding = extractEmbedding(face, box)
                DetectedFace(box, embedding)
            }

            DetectionResult(filePath, detected)
        } catch (e: Exception) {
            Log.w(TAG, "人脸检测失败: $filePath", e)
            DetectionResult(filePath, emptyList())
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 批量检测多张图片中的人脸。
     *
     * @param files 图片文件列表
     * @return 每张图片的检测结果列表
     */
    suspend fun detectBatch(files: List<File>): List<DetectionResult> = withContext(Dispatchers.IO) {
        files.map { detect(it) }
    }

    /**
     * 从 ML Kit [Face] 对象提取特征向量。
     */
    private fun extractEmbedding(face: Face, box: RectF): FloatArray {
        val boxW = (box.right - box.left).coerceAtLeast(1e-6f)
        val boxH = (box.bottom - box.top).coerceAtLeast(1e-6f)

        // 归一化关键点坐标（相对人脸框）
        val le = normalizeLandmark(face.getLandmark(FaceLandmark.LEFT_EYE), box, boxW, boxH)
        val re = normalizeLandmark(face.getLandmark(FaceLandmark.RIGHT_EYE), box, boxW, boxH)
        val n = normalizeLandmark(face.getLandmark(FaceLandmark.NOSE_BASE), box, boxW, boxH)
        val ml = normalizeLandmark(face.getLandmark(FaceLandmark.MOUTH_LEFT), box, boxW, boxH)
        val mr = normalizeLandmark(face.getLandmark(FaceLandmark.MOUTH_RIGHT), box, boxW, boxH)
        val lear = normalizeLandmark(face.getLandmark(FaceLandmark.LEFT_EAR), box, boxW, boxH)
        val rear = normalizeLandmark(face.getLandmark(FaceLandmark.RIGHT_EAR), box, boxW, boxH)

        // 关键点间距离比例
        val eyeDist = distance(le, re)
        val mouthWidth = distance(ml, mr)
        val noseToMouth = distance(n, midpoint(ml, mr))
        val earDist = distance(lear, rear)
        val eyeToNose = (distance(le, n) + distance(re, n)) / 2f
        val boxAspect = boxW / boxH

        // 轮廓统计特征
        val faceContour = face.getContour(FaceContour.FACE)?.points
        val contourStats = computeContourStats(faceContour, box, boxW, boxH)

        val embedding = FloatArray(EMBEDDING_DIM)
        var idx = 0
        // 5 个关键点归一化坐标（左眼·右眼·鼻·嘴左·嘴右 = 10 维）
        idx = put(embedding, idx, le)
        idx = put(embedding, idx, re)
        idx = put(embedding, idx, n)
        idx = put(embedding, idx, ml)
        idx = put(embedding, idx, mr)
        // 距离比例特征（6 维）
        embedding[idx++] = eyeDist
        embedding[idx++] = mouthWidth
        embedding[idx++] = noseToMouth
        embedding[idx++] = earDist
        embedding[idx++] = eyeToNose
        embedding[idx++] = boxAspect
        // 轮廓统计（4 维）
        embedding[idx++] = contourStats[0]
        embedding[idx++] = contourStats[1]
        embedding[idx++] = contourStats[2]
        embedding[idx] = contourStats[3]

        // L2 归一化
        return l2Normalize(embedding)
    }

    /**
     * 归一化关键点坐标。使用类型推断避免直接引用 ML Kit Point 类。
     */
    private fun normalizeLandmark(
        landmark: FaceLandmark?,
        box: RectF,
        boxW: Float,
        boxH: Float,
    ): FloatArray {
        val pos = landmark?.position ?: return floatArrayOf(0.5f, 0.5f)
        val x = ((pos.x - box.left) / boxW).coerceIn(0f, 1f)
        val y = ((pos.y - box.top) / boxH).coerceIn(0f, 1f)
        return floatArrayOf(x, y)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midpoint(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f)
    }

    private fun put(target: FloatArray, offset: Int, point: FloatArray): Int {
        target[offset] = point[0]
        target[offset + 1] = point[1]
        return offset + 2
    }

    /**
     * 计算轮廓点统计特征。使用通配类型避免直接引用 ML Kit Point 类。
     */
    private fun computeContourStats(
        points: List<*>?,
        box: RectF,
        boxW: Float,
        boxH: Float,
    ): FloatArray {
        if (points.isNullOrEmpty()) return floatArrayOf(0.5f, 0.5f, 0.1f, 0.1f)
        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        for (i in points.indices) {
            val p = points[i]
            if (p != null) {
                // 使用反射式访问 x/y 属性，避免直接引用 Point 类
                val px = p.javaClass.getMethod("getX").invoke(p) as? Float ?: 0f
                val py = p.javaClass.getMethod("getY").invoke(p) as? Float ?: 0f
                xs[i] = ((px - box.left) / boxW).coerceIn(0f, 1f)
                ys[i] = ((py - box.top) / boxH).coerceIn(0f, 1f)
            } else {
                xs[i] = 0.5f
                ys[i] = 0.5f
            }
        }
        val meanX = xs.average().toFloat()
        val meanY = ys.average().toFloat()
        val varX = xs.map { (it - meanX) * (it - meanX) }.average().toFloat()
        val varY = ys.map { (it - meanY) * (it - meanY) }.average().toFloat()
        return floatArrayOf(meanX, meanY, varX, varY)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm < 1e-6f) return vec
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private suspend fun detectAsync(image: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit 人脸检测失败", e)
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    private fun decodeBitmap(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        while (srcW / sample > MAX_INPUT_DIM || srcH / sample > MAX_INPUT_DIM) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }

    /**
     * 释放检测器资源。
     */
    fun close() {
        detector.close()
    }
}
