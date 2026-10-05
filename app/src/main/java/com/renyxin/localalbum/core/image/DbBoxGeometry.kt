package com.renyxin.localalbum.core.image

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * DB 文字检测后处理的纯几何部分（无 Android 依赖，可 JVM 单测）：
 * 凸包（Andrew 单调链）+ 旋转最小外接矩形（rotating calipers）+ 连通域行投影分裂。
 *
 * 对应 PaddleOCR DB 后处理（polygon_from_score → unclip → minAreaRect）的轴对齐实现替代：
 * 连通域外接框方案对粘连行（整片丢行）与倾斜文本（框内混入大量背景）无解，
 * 这里的旋转框 + 行分裂是根治手段。
 */
object DbBoxGeometry {

    /**
     * 旋转矩形。width ≥ height 恒成立，width 沿文本行方向，
     * angleDeg 为行方向与 x 轴的夹角（度，[-90, 90)），旋转 -angleDeg 即可把行转正。
     */
    data class RotatedRect(
        val cx: Double,
        val cy: Double,
        val width: Double,
        val height: Double,
        val angleDeg: Double,
    )

    /**
     * Andrew 单调链凸包。输入 xs/ys 的前 [count] 个点，返回凸包顶点的下标
     * （逆时针，共线点已去除）。点数 < 3 时原样返回下标，由 [minAreaRect] 处理退化。
     */
    fun convexHull(xs: IntArray, ys: IntArray, count: Int): IntArray {
        if (count <= 2) return IntArray(count) { it }
        val order = ArrayList<Int>(count)
        for (i in 0 until count) order.add(i)
        order.sortWith(compareBy({ xs[it] }, { ys[it] }))

        val lower = IntArray(count)
        val upper = IntArray(count)
        var nl = 0
        var nu = 0
        fun cross(o: Int, a: Int, b: Int): Long =
            (xs[a] - xs[o]).toLong() * (ys[b] - ys[o]) - (ys[a] - ys[o]).toLong() * (xs[b] - xs[o])

        for (i in order) {
            while (nl >= 2 && cross(lower[nl - 2], lower[nl - 1], i) <= 0L) nl--
            lower[nl++] = i
        }
        for (j in order.indices.reversed()) {
            val i = order[j]
            while (nu >= 2 && cross(upper[nu - 2], upper[nu - 1], i) <= 0L) nu--
            upper[nu++] = i
        }
        // 拼接 lower 去尾 + upper 去尾（首尾顶点在两条链中各出现一次）
        val hull = IntArray(nl + nu - 2)
        var k = 0
        for (i in 0 until nl - 1) hull[k++] = lower[i]
        for (i in 0 until nu - 1) hull[k++] = upper[i]
        return hull
    }

    /**
     * 旋转最小外接矩形：以每条凸包边为候选轴向（rotating calipers 的矩形版），
     * 取面积最小的轴向包围盒。点数 < 1 返回 null；单点/共线等退化输入返回
     * 零面积矩形，由调用方的尺寸过滤兜底。
     */
    fun minAreaRect(xs: IntArray, ys: IntArray, count: Int): RotatedRect? {
        if (count == 0) return null
        if (count == 1) return RotatedRect(xs[0].toDouble(), ys[0].toDouble(), 0.0, 0.0, 0.0)

        val hull = convexHull(xs, ys, count)
        if (hull.isEmpty()) return RotatedRect(xs[0].toDouble(), ys[0].toDouble(), 0.0, 0.0, 0.0)

        var bestArea = Double.MAX_VALUE
        var bestCx = 0.0; var bestCy = 0.0
        var bestW = 0.0; var bestH = 0.0
        var bestAngle = 0.0

        for (ei in hull.indices) {
            val p = hull[ei]
            val q = hull[(ei + 1) % hull.size]
            val dx = (xs[q] - xs[p]).toDouble()
            val dy = (ys[q] - ys[p]).toDouble()
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val ux = dx / len
            val uy = dy / len
            val vx = -uy
            val vy = ux

            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (hi in hull.indices) {
                val k = hull[hi]
                val px = xs[k].toDouble()
                val py = ys[k].toDouble()
                val u = px * ux + py * uy
                val v = px * vx + py * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val w = maxU - minU
            val h = maxV - minV
            val area = w * h
            if (area < bestArea) {
                bestArea = area
                // 中心 = U/V 区间中点逆变换回世界坐标
                val midU = (minU + maxU) / 2.0
                val midV = (minV + maxV) / 2.0
                bestCx = midU * ux + midV * vx
                bestCy = midU * uy + midV * vy
                bestW = w
                bestH = h
                bestAngle = atan2(dy, dx)
            }
        }
        if (bestArea == Double.MAX_VALUE) {
            return RotatedRect(xs[0].toDouble(), ys[0].toDouble(), 0.0, 0.0, 0.0)
        }

        // 归一化：width 取两边中的长者（沿文本行方向），角度随之换轴
        var w = bestW
        var h = bestH
        var angleDeg = Math.toDegrees(bestAngle)
        if (h > w) {
            val t = w; w = h; h = t
            angleDeg += 90.0
        }
        // 折到 [-90, 90)
        angleDeg %= 180.0
        if (angleDeg < -90.0) angleDeg += 180.0
        if (angleDeg >= 90.0) angleDeg -= 180.0
        return RotatedRect(bestCx, bestCy, w, h, angleDeg)
    }

    /**
     * 行投影分裂：rowCounts[y] 是连通域在第 y 行的像素数。
     *
     * 相邻文本行在概率图上被细桥粘连成一个连通域时（连通域/轮廓法的固有盲区），
     * 行间桥只有 1~2 行像素，投影上是深谷。谷行 = 计数 ≤ 行峰值 12% 的行；
     * 过厚的低段（> 总高 1/4，如笔画间隙这种结构性稀疏）不算谷，防止把
     * 单个字符按笔画拆开。返回各段行号区间（含端点），不足 [minSegmentRows]
     * 行的碎片段丢弃（交给上游面积过滤）。
     */
    fun splitRowBands(rowCounts: IntArray, from: Int, to: Int, minSegmentRows: Int = 3): List<IntRange> {
        if (to <= from) return emptyList()
        var maxRow = 0
        for (y in from..to) if (rowCounts[y] > maxRow) maxRow = rowCounts[y]
        if (maxRow <= 0) return emptyList()

        val limit = max(1, (maxRow * 0.12f).toInt())
        val totalH = to - from + 1
        val maxBandH = max(2, totalH / 4)
        val isLow = BooleanArray(totalH) { rowCounts[from + it] <= limit }

        // 过厚的低段转回普通行：是结构性稀疏（笔画间隙）而非行间细桥
        var runStart = -1
        for (i in 0..totalH) {
            val low = i < totalH && isLow[i]
            if (low && runStart < 0) runStart = i
            if (!low && runStart >= 0) {
                if (i - runStart > maxBandH) {
                    for (k in runStart until i) isLow[k] = false
                }
                runStart = -1
            }
        }

        val bands = mutableListOf<IntRange>()
        var segStart = -1
        for (i in 0..totalH) {
            val solid = i < totalH && !isLow[i]
            if (solid) {
                if (segStart < 0) segStart = i
            } else if (segStart >= 0) {
                if (i - segStart >= minSegmentRows) bands.add((from + segStart)..(from + i - 1))
                segStart = -1
            }
        }
        return bands
    }

    /** 旋转矩形外扩（官方 DB unclip 的矩形近似）：offset = 面积×ratio/周长。 */
    fun unclip(rect: RotatedRect, unclipRatio: Double): RotatedRect {
        val area = rect.width * rect.height
        val perimeter = 2.0 * (rect.width + rect.height)
        val offset = if (perimeter <= 1e-9) 0.0 else area * unclipRatio / perimeter
        return RotatedRect(
            cx = rect.cx,
            cy = rect.cy,
            width = rect.width + 2.0 * offset,
            height = rect.height + 2.0 * offset,
            angleDeg = rect.angleDeg,
        )
    }
}
