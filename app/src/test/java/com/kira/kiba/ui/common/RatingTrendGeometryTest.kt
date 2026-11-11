package com.kira.kiba.ui.common

import com.kira.kiba.core.account.RatingHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildPoints] 纯几何单测：X 均分、Y 自适应、同值假窗、单点居中。
 * 点位错一截，曲线的分割符与点击命中就全错位，所以钉死。
 */
class RatingTrendGeometryTest {

    private fun entry(rating: Int, ts: Long = 0L, dropped: Boolean = false) =
        RatingHistory.Entry(ts = ts, rating = rating.toDouble(), dropped = dropped)

    private val W = 800f
    private val H = 400f
    private val PAD_H = 20f
    private val PAD_TOP = 40f
    private val PAD_BOTTOM = 30f

    @Test
    fun `空列表返回空`() {
        assertTrue(buildPoints(emptyList(), W, H, PAD_H, PAD_TOP, PAD_BOTTOM).isEmpty())
    }

    @Test
    fun `单点水平居中`() {
        val pts = buildPoints(listOf(entry(15000)), W, H, PAD_H, PAD_TOP, PAD_BOTTOM)
        assertEquals(1, pts.size)
        assertEquals(W / 2f, pts[0].x, 0.01f)
    }

    @Test
    fun `多点X均分且覆盖左右内边距`() {
        val pts = buildPoints(
            (1..5).map { entry(14000 + it) },
            W, H, PAD_H, PAD_TOP, PAD_BOTTOM,
        )
        assertEquals(5, pts.size)
        assertEquals(PAD_H, pts.first().x, 0.01f)
        assertEquals(W - PAD_H, pts.last().x, 0.01f)
        // 等间距
        val gaps = pts.zipWithNext { a, b -> b.x - a.x }
        gaps.forEach { assertEquals(gaps[0], it, 0.01f) }
    }

    @Test
    fun `Y方向min在下max在上且各留15pct余量`() {
        val pts = buildPoints(
            listOf(entry(14000), entry(15000)),
            W, H, PAD_H, PAD_TOP, PAD_BOTTOM,
        )
        val plotH = H - PAD_TOP - PAD_BOTTOM
        // y 窗口两端各留 span 的 15%（span=1000）→ 点离 pad 边缘 15/130 ≈ 11.54% plotH
        val margin = plotH * 15f / 130f
        assertEquals(H - PAD_BOTTOM - margin, pts[0].y, 0.01f)
        assertEquals(PAD_TOP + margin, pts[1].y, 0.01f)
    }

    @Test
    fun `全部同值时给假窗不贴边`() {
        val pts = buildPoints(
            listOf(entry(15400), entry(15400), entry(15400)),
            W, H, PAD_H, PAD_TOP, PAD_BOTTOM,
        )
        // 三个点同一条水平线，且不在画布上下边缘
        assertEquals(pts[0].y, pts[1].y, 0.01f)
        assertTrue(pts[0].y > PAD_TOP + 5f)
        assertTrue(pts[0].y < H - PAD_BOTTOM - 5f)
    }

    @Test
    fun `下降点保留dropped标记供分割与命中使用`() {
        val entries = listOf(entry(15400), entry(15200, dropped = true), entry(15300))
        val pts = buildPoints(entries, W, H, PAD_H, PAD_TOP, PAD_BOTTOM)
        assertEquals(entries, pts.map { it.entry })
        assertTrue(pts[1].entry.dropped)
    }
}
