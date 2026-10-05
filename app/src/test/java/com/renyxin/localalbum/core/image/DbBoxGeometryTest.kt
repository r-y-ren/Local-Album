package com.renyxin.localalbum.core.image

import com.renyxin.localalbum.core.image.DbBoxGeometry.RotatedRect
import com.renyxin.localalbum.core.image.DbBoxGeometry.minAreaRect
import com.renyxin.localalbum.core.image.DbBoxGeometry.splitRowBands
import com.renyxin.localalbum.core.image.DbBoxGeometry.unclip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DbBoxGeometryTest {

    // ---- minAreaRect ----

    @Test
    fun `axis aligned rectangle gives zero angle and exact center`() {
        val xs = intArrayOf(10, 50, 10, 50, 30, 10, 50)
        val ys = intArrayOf(10, 10, 40, 40, 25, 25, 40)
        val rect = minAreaRect(xs, ys, xs.size)!!
        assertEquals(30.0, rect.cx, 0.01)
        assertEquals(25.0, rect.cy, 0.01)
        assertEquals(40.0, rect.width, 0.01)
        assertEquals(30.0, rect.height, 0.01)
        assertTrue("angle must be near 0, was ${rect.angleDeg}", abs(rect.angleDeg) < 1.0)
        assertTrue("width must be the long side", rect.width >= rect.height)
    }

    @Test
    fun `45 degree diagonal line yields 45 degree angle`() {
        val xs = IntArray(101) { it }
        val ys = IntArray(101) { it }
        val rect = minAreaRect(xs, ys, xs.size)!!
        assertEquals(141.0, rect.width, 2.0)
        assertTrue("angle should be ~45, was ${rect.angleDeg}", abs(rect.angleDeg - 45.0) < 1.0)
    }

    @Test
    fun `rotated long rectangle recovers its tilt`() {
        // 中心 (100,100)，长边 80 沿 30° 方向，短边 20；采样四个角
        val ang = Math.toRadians(30.0)
        val cos = Math.cos(ang)
        val sin = Math.sin(ang)
        fun pt(halfL: Double, halfS: Double): Pair<Int, Int> {
            val x = 100.0 + halfL * cos - halfS * sin
            val y = 100.0 + halfL * sin + halfS * cos
            return x.toInt() to y.toInt()
        }
        val corners = listOf(pt(40.0, 10.0), pt(40.0, -10.0), pt(-40.0, 10.0), pt(-40.0, -10.0))
        val xs = IntArray(corners.size) { corners[it].first }
        val ys = IntArray(corners.size) { corners[it].second }
        val rect = minAreaRect(xs, ys, xs.size)!!
        assertEquals(100.0, rect.cx, 1.0)
        assertEquals(100.0, rect.cy, 1.0)
        assertEquals(80.0, rect.width, 2.5)
        assertEquals(20.0, rect.height, 2.5)
        assertEquals(30.0, rect.angleDeg, 2.0)
    }

    @Test
    fun `degenerate inputs return zero-area rects instead of throwing`() {
        assertNull(minAreaRect(IntArray(0), IntArray(0), 0))
        val point = minAreaRect(intArrayOf(5), intArrayOf(7), 1)!!
        assertEquals(5.0, point.cx, 0.0)
        assertEquals(0.0, point.width, 0.0)
        val line = minAreaRect(intArrayOf(0, 30), intArrayOf(0, 0), 2)!!
        assertEquals(30.0, line.width, 0.01)
        assertEquals(0.0, line.height, 0.01)
    }

    // ---- splitRowBands ----

    @Test
    fun `true gaps split stacked lines`() {
        // 两行文本，中间有空行
        val rows = intArrayOf(0, 40, 40, 40, 0, 0, 35, 35, 35, 0)
        val bands = splitRowBands(rows, 0, rows.size - 1)
        assertEquals(listOf(1..3, 6..8), bands)
    }

    @Test
    fun `thin bridge valley splits bridged lines`() {
        // 两行被 1px 细桥粘连：行间行计数 1（峰值 50 的 2%）
        val rows = intArrayOf(50, 50, 50, 1, 50, 50, 50)
        val bands = splitRowBands(rows, 0, rows.size - 1)
        assertEquals(listOf(0..2, 4..6), bands)
    }

    @Test
    fun `single line with ascender band does not split`() {
        // 英文小写行的 ascender 段（峰值的 30%）不是谷
        val rows = intArrayOf(15, 15, 50, 50, 50, 50, 50, 10, 10)
        val bands = splitRowBands(rows, 0, rows.size - 1)
        assertEquals(listOf(0..8), bands)
    }

    @Test
    fun `structurally sparse strokes are not torn apart`() {
        // "十"形字符：竖笔行计数 1 贯穿全高 → 低段过厚不算谷，整个字符仍是单个连通段
        //（顶部两行细尖被裁掉由 unclip 外扩补回，重点是竖笔没有把字符拆开）
        val rows = intArrayOf(1, 1, 20, 1, 1, 1, 1, 1)
        val bands = splitRowBands(rows, 0, rows.size - 1)
        assertEquals(listOf(2..7), bands)
    }

    @Test
    fun `short slivers between valleys are dropped`() {
        // 谷行之间的 1 行小凸起（20 < 峰值 40 的 12%×...）不足 minSegmentRows，整段丢弃
        val rows = intArrayOf(40, 40, 40, 0, 20, 0, 30, 30, 30)
        val bands = splitRowBands(rows, 0, rows.size - 1)
        assertEquals(listOf(0..2, 6..8), bands)
    }

    // ---- unclip ----

    @Test
    fun `unclip expands by area times ratio over perimeter`() {
        val rect = RotatedRect(cx = 50.0, cy = 40.0, width = 20.0, height = 10.0, angleDeg = 0.0)
        val expanded = unclip(rect, 1.6)
        // offset = (20*10*1.6) / (2*(20+10)) = 320/60 ≈ 5.333
        assertEquals(30.67, expanded.width, 0.01)
        assertEquals(20.67, expanded.height, 0.01)
        assertEquals(50.0, expanded.cx, 0.0)
        assertEquals(0.0, expanded.angleDeg, 0.0)
    }
}
