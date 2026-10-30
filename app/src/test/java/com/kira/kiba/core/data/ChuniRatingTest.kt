package com.kira.kiba.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ChuniRating] 的单元测试。
 *
 * 官方 VERSE 起公式：非当前版本 TOP30 之和 + 当前版本 TOP20 之和，分母 50，两位小数截断。
 * 真实故障场景：
 * - 玩家机台 14.x，App 旧版显示 7.x → 服务端 r10 腰斩，本地必须自算；
 * - 单曲 ra 是浮点，整数截断会让 Rating 系统性偏低；
 * - 曲库未同步落雪（无 version）时退化为 best30 均值，不能把值砍没。
 */
class ChuniRatingTest {

    private val CURRENT = 24000 // 查分器语境下的「当前版本」（X-VERSE 档，含 X-VERSE-X 新曲）
    private val OLD = 23000 // 非当前版本

    private fun entry(ra: Double, version: Int = OLD, we: Boolean = false): ChuniRating.Entry =
        ChuniRating.Entry(ra, version, we)

    @Test
    fun `非当前TOP30 + 当前TOP20 等于均值(各14_6)`() {
        val entries = List(30) { entry(14.60, OLD) } + List(20) { entry(14.60, CURRENT) }
        assertEquals(14.60, ChuniRating.calculate(entries, CURRENT), 1e-9)
    }

    @Test
    fun `全部非当前版本时只取TOP30 分母仍50`() {
        // 40 首全 23000（非当前），仅 TOP30 计入，/50 → 438/50 = 8.76
        val entries = List(40) { entry(14.60, OLD) }
        assertEquals(8.76, ChuniRating.calculate(entries, CURRENT), 1e-9)
    }

    @Test
    fun `当前版本不足20首时取实际有的 分母仍50`() {
        // 非当前 30*14.0 + 当前仅 5*20.0 → (420 + 100) / 50 = 10.40
        val entries = List(30) { entry(14.00, OLD) } + List(5) { entry(20.00, CURRENT) }
        assertEquals(10.40, ChuniRating.calculate(entries, CURRENT), 1e-9)
    }

    @Test
    fun `无version信息时退化为best30均值 不砍值`() {
        // 全部 version=0 → currentVersion<=0 → best30 均值 14.60（不 /50）
        val entries = List(30) { entry(14.60, version = 0) }
        assertEquals(14.60, ChuniRating.calculate(entries, currentVersion = 0), 1e-9)
    }

    @Test
    fun `WorldsEnd 不参与Rating`() {
        // 30 首 WE（非当前）全 14.6 + 20 首当前非 WE 14.6 → 仅当前组计入 → 292/50 = 5.84
        val entries = List(30) { entry(14.60, OLD, we = true) } +
            List(20) { entry(14.60, CURRENT, we = false) }
        assertEquals(5.84, ChuniRating.calculate(entries, CURRENT), 1e-9)
    }

    @Test
    fun `两位小数是截断不是四舍五入`() {
        // 非当前 30*14.0 + 当前 20*15.999 → 420 + 319.98 = 739.98 / 50 = 14.7996 → 截断 14.79
        val entries = List(30) { entry(14.00, OLD) } + List(20) { entry(15.999, CURRENT) }
        assertEquals(14.79, ChuniRating.calculate(entries, CURRENT), 1e-9)
    }

    @Test
    fun `非当前与当前均值辅助函数`() {
        val entries = List(30) { entry(14.00, OLD) } + List(20) { entry(20.00, CURRENT) }
        // 非当前 TOP30 均值 = 14.00；当前 TOP20 均值 = 20.00
        assertEquals(14.00, ChuniRating.nonCurrentAverage(entries, CURRENT), 1e-9)
        assertEquals(20.00, ChuniRating.currentAverage(entries, CURRENT), 1e-9)
    }

    @Test
    fun `无成绩返回0`() {
        assertEquals(0.0, ChuniRating.calculate(emptyList(), CURRENT), 1e-9)
        assertEquals(0.0, ChuniRating.nonCurrentAverage(emptyList(), CURRENT), 1e-9)
        assertEquals(0.0, ChuniRating.currentAverage(emptyList(), CURRENT), 1e-9)
        assertEquals(0.0, ChuniRating.bestAverage(emptyList()), 1e-9)
    }
}
