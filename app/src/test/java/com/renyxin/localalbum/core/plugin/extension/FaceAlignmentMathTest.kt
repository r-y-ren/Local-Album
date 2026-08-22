package com.renyxin.localalbum.core.plugin.extension

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import nu.pattern.OpenCV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point

/**
 * FaceAlignmentMath 与 OpenCV estimateAffinePartial2D 的数值对拍（JVM，桌面版 OpenCV）。
 *
 * 对拍策略：OpenCV 的 RANSAC 估计在无噪声对应点上收敛到精确解，与最小二乘闭式解一致；
 * 因此用"构造已知相似变换 → 反解源点 → 两侧各自估计 → 逐元素比对"覆盖任意姿态/尺度/平移。
 */
class FaceAlignmentMathTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeOpenCV() {
            OpenCV.loadLocally()
        }
    }

    private fun opencvEstimateAffinePartial2D(src: FloatArray, dst: FloatArray, method: Int = Calib3d.RANSAC, threshold: Double = 3.0): FloatArray? {
        val srcMat = MatOfPoint2f(*src.toList().chunked(2).map { Point(it[0].toDouble(), it[1].toDouble()) }.toTypedArray())
        val dstMat = MatOfPoint2f(*dst.toList().chunked(2).map { Point(it[0].toDouble(), it[1].toDouble()) }.toTypedArray())
        val affine = Calib3d.estimateAffinePartial2D(srcMat, dstMat, Mat(), method, threshold, 2000, 0.99, 10)
        srcMat.release()
        dstMat.release()
        if (affine.empty()) {
            affine.release()
            return null
        }
        val result = floatArrayOf(
            affine.get(0, 0)[0].toFloat(),
            affine.get(1, 0)[0].toFloat(),
            affine.get(0, 2)[0].toFloat(),
            affine.get(1, 2)[0].toFloat(),
        )
        affine.release()
        return result
    }

    @Test
    fun `恒等对应点恢复恒等变换`() {
        val identity = FaceAlignmentMath.estimateSimilarity(
            FaceAlignmentMath.ARCFACE_TEMPLATE_112,
            FaceAlignmentMath.ARCFACE_TEMPLATE_112,
        )
        assertNotNull(identity)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), identity!!, 1e-4f)
    }

    @Test
    fun `退化的重合点返回null供调用方回退`() {
        val degenerate = FloatArray(10) { 42f }
        assertNull(FaceAlignmentMath.estimateSimilarity(degenerate, FaceAlignmentMath.ARCFACE_TEMPLATE_112))
    }

    @Test
    fun `精确恢复构造的相似变换`() {
        val random = Random(20260822)
        repeat(200) {
            val theta = random.nextDouble() * 2 * Math.PI
            val scale = 0.2 + random.nextDouble() * 5.0
            val tx = -500.0 + random.nextDouble() * 1000
            val ty = -500.0 + random.nextDouble() * 1000
            val a = scale * cos(theta)
            val b = scale * sin(theta)
            // dst = T(src)：src 由模板经逆变换反解生成，保证存在精确解
            val src = FloatArray(10)
            for (i in 0 until 5) {
                val u = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i].toDouble()
                val v = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i + 1].toDouble()
                // 逆变换：src = T^-1(dst)
                src[2 * i] = ((u - tx) * a + (v - ty) * b).toFloat() / (a * a + b * b).toFloat()
                src[2 * i + 1] = ((v - ty) * a - (u - tx) * b).toFloat() / (a * a + b * b).toFloat()
            }
            val estimated = FaceAlignmentMath.estimateSimilarity(src, FaceAlignmentMath.ARCFACE_TEMPLATE_112)
            assertNotNull(estimated)
            assertArrayEquals(floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat()), estimated!!, 1e-2f)
        }
    }

    @Test
    fun `与 OpenCV estimateAffinePartial2D 数值一致`() {
        val random = Random(42)
        repeat(100) { iteration ->
            val theta = random.nextDouble() * 2 * Math.PI
            val scale = 0.3 + random.nextDouble() * 3.0
            val tx = -300.0 + random.nextDouble() * 600
            val ty = -300.0 + random.nextDouble() * 600
            val a = scale * cos(theta)
            val b = scale * sin(theta)
            val src = FloatArray(10)
            for (i in 0 until 5) {
                val u = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i].toDouble()
                val v = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i + 1].toDouble()
                src[2 * i] = ((u - tx) * a + (v - ty) * b).toFloat() / (a * a + b * b).toFloat()
                src[2 * i + 1] = ((v - ty) * a - (u - tx) * b).toFloat() / (a * a + b * b).toFloat()
            }
            val ours = FaceAlignmentMath.estimateSimilarity(src, FaceAlignmentMath.ARCFACE_TEMPLATE_112)!!
            val opencv = opencvEstimateAffinePartial2D(src, FaceAlignmentMath.ARCFACE_TEMPLATE_112)
            assertNotNull("OpenCV 应能估计（迭代 $iteration）", opencv)
            // [a, b, tx, ty]：a=s·cosθ, b=s·sinθ；OpenCV 返回 [α, β, tx, ty] 同约定
            assertTrue(
                "迭代 $iteration: ours=${ours.toList()} opencv=${opencv!!.toList()}",
                abs(ours[0] - opencv[0]) < 1e-2 &&
                    abs(ours[1] - opencv[1]) < 1e-2 &&
                    abs(ours[2] - opencv[2]) < 1e-1 &&
                    abs(ours[3] - opencv[3]) < 1e-1,
            )
        }
    }

    @Test
    fun `真实尺度关键点与 OpenCV 全点最小二乘数值一致`() {
        // 模拟 960×1280 照片中检测到的人脸关键点（归一化坐标反归一化后的典型量级）。
        // 真实关键点不存在精确相似变换解：OpenCV 默认 RANSAC 会剔点拟合成不同结果，
        // 而 insightface 官方 norm_crop 用 skimage SimilarityTransform（全点最小二乘），
        // 与本实现一致。OpenCV 不支持全点 LSQ 模式，用超大阈值使全部点成为内点，
        // 其 LSQ 精修阶段即收敛到全点最小二乘解。
        val normalized = floatArrayOf(
            0.312f, 0.452f,
            0.418f, 0.448f,
            0.367f, 0.541f,
            0.322f, 0.612f,
            0.405f, 0.608f,
        )
        val src = FaceAlignmentMath.denormalizeLandmarks(normalized, 960, 1280)
        val ours = FaceAlignmentMath.estimateSimilarity(src, FaceAlignmentMath.ARCFACE_TEMPLATE_112)!!
        val opencv = opencvEstimateAffinePartial2D(
            src,
            FaceAlignmentMath.ARCFACE_TEMPLATE_112,
            threshold = 1e9,
        )!!
        assertTrue("ours=${ours.toList()} opencv=${opencv.toList()}", abs(ours[0] - opencv[0]) < 1e-2)
        assertTrue(abs(ours[1] - opencv[1]) < 1e-2)
        assertTrue(abs(ours[2] - opencv[2]) < 1e-1)
        assertTrue(abs(ours[3] - opencv[3]) < 1e-1)
    }

    @Test
    fun `噪声输入满足最小二乘正交性条件`() {
        // 加噪后不存在精确解，验证解满足法方程（梯度为零），即确为 LSQ 最优解
        val random = Random(7)
        val noisy = FloatArray(10)
        for (i in noisy.indices) {
            noisy[i] = FaceAlignmentMath.ARCFACE_TEMPLATE_112[i] + random.nextFloat() * 8f - 4f
        }
        val solution = FaceAlignmentMath.estimateSimilarity(noisy, FaceAlignmentMath.ARCFACE_TEMPLATE_112)!!
        val (a, b, tx, ty) = listOf(solution[0], solution[1], solution[2], solution[3])
        var gradA = 0.0
        var gradB = 0.0
        var gradTx = 0.0
        var gradTy = 0.0
        for (i in 0 until 5) {
            val x = noisy[2 * i].toDouble()
            val y = noisy[2 * i + 1].toDouble()
            val u = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i].toDouble()
            val v = FaceAlignmentMath.ARCFACE_TEMPLATE_112[2 * i + 1].toDouble()
            val r1 = a * x - b * y + tx - u
            val r2 = b * x + a * y + ty - v
            gradA += x * r1 + y * r2
            gradB += -y * r1 + x * r2
            gradTx += r1
            gradTy += r2
        }
        assertTrue(abs(gradA) < 1e-2)
        assertTrue(abs(gradB) < 1e-2)
        assertTrue(abs(gradTx) < 1e-2)
        assertTrue(abs(gradTy) < 1e-2)
    }
}
