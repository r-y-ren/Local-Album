package com.renyxin.localalbum.core.plugin.extension

/**
 * 纯 Kotlin 的人脸 5 点对齐数学（不依赖 OpenCV 与 android.framework，可在 JVM 单测验证）。
 *
 * 背景：OpenCV 5.0 的 Utils/Geometry 原生路径在部分机型（OnePlus PLC110/Android 16）
 * 批量分析时破坏原生堆（SIGSEGV/SIGBUS，崩溃点漂移于 Bitmap 生命周期），因此对齐的
 * 数学部分改为纯 Kotlin 实现，重采样交给 android.graphics.Matrix/Canvas（见
 * InsightFaceProvider.alignBitmap）。
 */
object FaceAlignmentMath {

    /**
     * ArcFace 官方 112×112 标准 5 点模板（左眼、右眼、鼻尖、嘴左、嘴右，x0,y0,...,x4,y4）。
     * 来源：insightface/python-package/insightface/utils/face_align.py。
     * 单一事实源：[FaceAligner] 的 Point 数组由此派生。
     */
    val ARCFACE_TEMPLATE_112: FloatArray = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0250f, 71.7366f,
        41.5493f, 92.3655f,
        70.7290f, 92.2041f,
    )

    /** 归一化关键点 [x0,y0,...]（∈[0,1]）转原图像素坐标。 */
    fun denormalizeLandmarks(landmarks: FloatArray, imgW: Int, imgH: Int): FloatArray =
        FloatArray(landmarks.size) { i ->
            if (i % 2 == 0) landmarks[i] * imgW else landmarks[i] * imgH
        }

    /**
     * 最小二乘估计 2D 相似变换（旋转 + 各向同性缩放 + 平移，4 自由度）。
     *
     * 与 insightface 官方 `norm_crop` 所用的 skimage `SimilarityTransform`（最小二乘）
     * 同法；OpenCV `estimateAffinePartial2D` 在无噪声输入下收敛到同一解。
     *
     * 求解 dst ≈ (a·x − b·y + tx, b·x + a·y + ty)，其中 a = s·cosθ、b = s·sinθ。
     *
     * @return [a, b, tx, ty]；退化输入（所有源点重合）返回 null，调用方回退到裁剪。
     */
    fun estimateSimilarity(src: FloatArray, dst: FloatArray): FloatArray? {
        require(src.size == dst.size && src.size >= 6 && src.size % 2 == 0) {
            "关键点数组必须等长、成对且至少 3 个点"
        }
        val n = src.size / 2
        var meanSx = 0f
        var meanSy = 0f
        var meanDx = 0f
        var meanDy = 0f
        for (i in 0 until n) {
            meanSx += src[2 * i]
            meanSy += src[2 * i + 1]
            meanDx += dst[2 * i]
            meanDy += dst[2 * i + 1]
        }
        meanSx /= n
        meanSy /= n
        meanDx /= n
        meanDy /= n

        var denominator = 0f
        var numeratorA = 0f
        var numeratorB = 0f
        for (i in 0 until n) {
            val xc = src[2 * i] - meanSx
            val yc = src[2 * i + 1] - meanSy
            val uc = dst[2 * i] - meanDx
            val vc = dst[2 * i + 1] - meanDy
            denominator += xc * xc + yc * yc
            numeratorA += xc * uc + yc * vc
            numeratorB += xc * vc - yc * uc
        }
        if (denominator < 1e-12f) return null
        val a = numeratorA / denominator
        val b = numeratorB / denominator
        val tx = meanDx - a * meanSx + b * meanSy
        val ty = meanDy - b * meanSx - a * meanSy
        return floatArrayOf(a, b, tx, ty)
    }
}
