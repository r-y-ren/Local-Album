package com.renyxin.localalbum.core.plugin.extension

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

/**
 * 人脸仿射对齐工具（换脸 Reactor 流水线核心组件）。
 *
 * 基于 SCRFD 检测的 5 关键点（左眼、右眼、鼻尖、嘴左、嘴右），
 * 使用 OpenCV 5.0 [Geometry.estimateAffinePartial2D] 计算**相似变换**矩阵
 * （4 自由度：平移+旋转+各向同性缩放，5 点最小二乘），与 InsightFace/ReActor
 * 原生对齐方式一致；将原图中的人脸对齐到标准模板尺寸：
 * - **ArcFace**（w600k_r50）：112×112，使用 insightface 官方 5 点模板
 * - **Inswapper**（inswapper_128）：128×128，ratio=1.0 无缩放，
 *   仅 X 轴整体偏移 +8（InsightFace C++ 原生居中：仅 X 偏移，Y 不动）。
 *
 * 同时支持逆变换（[invertAffine]），用于将换脸结果贴回原图坐标。
 *
 * ## OpenCV 5.0 API 说明
 * - `estimateAffinePartial2D` 在 `org.opencv.geometry.Geometry` 类（相似变换）
 * - `invertAffineTransform` 在 `org.opencv.geometry.Geometry` 类
 * - `warpAffine`/`GaussianBlur`/`ellipse`/`cvtColor` 在 `org.opencv.imgproc.Imgproc` 类
 */
object FaceAligner {

    private const val TAG = "FaceAligner"

    /**
     * ArcFace 官方 112×112 标准 5 点模板（左眼、右眼、鼻、嘴左、嘴右）。
     * 来源：insightface/python-package/insightface/utils/face_align.py
     */
    private val ARCFACE_TEMPLATE_112 = arrayOf(
        Point(38.2946, 51.6963),  // 左眼
        Point(73.5318, 51.5014),  // 右眼
        Point(56.0252, 71.7366),  // 鼻尖
        Point(41.5493, 92.3655),  // 嘴左
        Point(70.7299, 92.2041),  // 嘴右
    )

    /**
     * Inswapper 128×128 模板：参照 ReActor inswap.py:INSwapper.get，
     * `ratio = input_size[0]/128.0 = 1.0`，故 src_pts = ARCFACE_STD_POINTS * 1.0 + [8,0]。
     * 即 112 模板【不缩放】，仅 X 轴整体偏移 8（InsightFace C++ 原生居中：仅 X 偏移，Y 不动）。
     */
    private val INSWAPPER_TEMPLATE_128: Array<Point> by lazy {
        ARCFACE_TEMPLATE_112.map { Point(it.x + 8.0, it.y) }.toTypedArray()
    }

    /**
     * 将归一化关键点 [landmarks]（[x0,y0,...,x4,y4]，∈[0,1]）转换为原图像素坐标。
     */
    fun denormalizeLandmarks(landmarks: FloatArray, imgW: Int, imgH: Int): Array<Point> {
        return Array(5) { i ->
            Point((landmarks[i * 2] * imgW).toDouble(), (landmarks[i * 2 + 1] * imgH).toDouble())
        }
    }

    /**
     * 计算相似变换矩阵（2×3，4 自由度），将原图 5 关键点映射到标准模板。
     * 使用 [Geometry.estimateAffinePartial2D]（5 点最小二乘，相似变换），
     * 与 InsightFace/ReActor 原生 `cv2.estimateAffinePartial2D` 一致。
     * 相比全仿射 `getAffineTransform`（6 自由度，仅 3 点），相似变换更稳定、
     * 不会因过约束导致姿态失真。
     *
     * @return 2×3 仿射矩阵；若估计失败返回 empty Mat（调用方需判空）。
     */
    fun computeAffineMatrix(srcPoints: Array<Point>, targetSize: Int): Mat {
        val template = if (targetSize == 112) ARCFACE_TEMPLATE_112 else INSWAPPER_TEMPLATE_128
        val srcMat = MatOfPoint2f(*srcPoints)
        val dstMat = MatOfPoint2f(*template)
        val affine = Geometry.estimateAffinePartial2D(srcMat, dstMat)
        srcMat.release()
        dstMat.release()
        return affine
    }

    /**
     * 对 Bitmap 执行仿射变换，输出指定尺寸的对齐人脸。
     * borderMode 使用 [Imgproc.BORDER_CONSTANT]（值 0），与 ReActor `borderValue=0.0` 一致，
     * 避免 BORDER_REFLECT 在 crop 边缘引入镜像伪影。
     */
    fun warpAffine(bitmap: Bitmap, affineMatrix: Mat, targetSize: Int): Bitmap {
        val dst = warpAffineMat(bitmap, affineMatrix, targetSize)
        val result = matBGRToBitmap(dst)
        dst.release()
        return result
    }

    /**
     * 对 Bitmap 执行仿射变换，输出指定尺寸的 BGR [Mat]（不转 Bitmap）。
     * 供换脸 diff-mask 贴回使用：需要对齐底图 Mat 与换脸 Mat 做逐像素差。
     */
    fun warpAffineMat(bitmap: Bitmap, affineMatrix: Mat, targetSize: Int): Mat {
        val src = bitmapToMatBGR(bitmap)
        val dst = Mat()
        Imgproc.warpAffine(src, dst, affineMatrix, Size(targetSize.toDouble(), targetSize.toDouble()),
            Imgproc.INTER_LINEAR, 0 /* BORDER_CONSTANT */, org.opencv.core.Scalar.all(0.0))
        src.release()
        return dst
    }

    /**
     * 计算仿射矩阵的逆矩阵（2×3），用于将换脸结果贴回原图坐标。
     * OpenCV 5.0: [Geometry.invertAffineTransform] 直接计算 2×3 仿射矩阵的逆。
     */
    fun invertAffine(affineMatrix: Mat): Mat {
        val inverted = Mat()
        Geometry.invertAffineTransform(affineMatrix, inverted)
        return inverted
    }

    // ---- Bitmap ↔ Mat 转换 ----

    /** Bitmap(ARGB) → Mat(BGR) */
    fun bitmapToMatBGR(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    /** Mat(BGR) → Bitmap(ARGB) */
    fun matBGRToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }
}
