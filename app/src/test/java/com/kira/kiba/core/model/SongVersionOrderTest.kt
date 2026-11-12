package com.kira.kiba.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本时间轴单测。
 *
 * 重点不是「每个版本名都背下来」，而是三条口径：
 * 1. 同一代日版名 / 国服年度名排在一起；
 * 2. 越新的版本 rank 越大（`newestFirst` 排出来就是新 → 旧）；
 * 3. 未知版本有确定的兜底位置（带年份的排最前、没年份的沉底）。
 */
class SongVersionOrderTest {

    private fun rankMaimai(v: String) = SongVersionOrder.rank(GameType.MAIMAI, v)
    private fun rankChuni(v: String) = SongVersionOrder.rank(GameType.CHUNITHM, v)

    @Test
    fun `国服年度版从旧到新单调递增`() {
        val years = listOf(
            "舞萌DX", "舞萌DX 2021", "舞萌DX 2022", "舞萌DX 2023", "舞萌DX 2024", "舞萌DX 2025", "舞萌DX 2026",
        ).map(::rankMaimai)
        assertEquals(years.sorted(), years)
        assertEquals(years.size, years.distinct().size)
    }

    @Test
    fun `老街机世代在新版之前`() {
        assertTrue(rankMaimai("maimai") < rankMaimai("maimai FiNALE"))
        assertTrue(rankMaimai("maimai FiNALE") < rankMaimai("舞萌DX"))
        assertTrue(rankMaimai("maimai GreeN") < rankMaimai("maimai ORANGE"))
    }

    @Test
    fun `大小写不影响匹配`() {
        assertEquals(rankMaimai("maimai MURASAKi"), rankMaimai("maimai murasaki"))
        assertEquals(rankMaimai("maimai FiNALE"), rankMaimai("maimai finale"))
        assertEquals(rankMaimai("舞萌DX 2024"), rankMaimai("舞萌dx 2024"))
    }

    @Test
    fun `中二节奏同样遵循从旧到新`() {
        val list = listOf(
            "CHUNITHM", "CHUNITHM AIR", "CHUNITHM STAR", "CHUNITHM AMAZON", "CHUNITHM CRYSTAL",
            "CHUNITHM PARADISE", "CHUNITHM NEW", "CHUNITHM SUN", "CHUNITHM LUMINOUS",
            "CHUNITHM VERSE", "CHUNITHM X-VERSE",
        ).map(::rankChuni)
        assertEquals(list.sorted(), list)
    }

    @Test
    fun `中二节奏国服年度版与日版副标题同代对齐`() {
        // 国服年度版与日版副标题是同一代，允许相等或紧邻，但不能前后颠倒
        assertTrue(rankChuni("CHUNITHM NEW") <= rankChuni("中二节奏 NEW"))
        assertTrue(rankChuni("中二节奏 NEW") < rankChuni("CHUNITHM SUN"))
        assertTrue(rankChuni("中二节奏 2024") < rankChuni("CHUNITHM X-VERSE"))
    }

    @Test
    fun `未知版本兜底位置`() {
        // 空版本名沉到最底（比任何已列版本都旧）
        assertEquals(-1, rankMaimai(""))
        assertEquals(-1, rankChuni("   "))
        // 带年份的未知版本排到所有已列版本之后（将来出新版本不必改代码）
        assertTrue(rankMaimai("舞萌DX 2027") > rankMaimai("舞萌DX 2026"))
        assertTrue(rankChuni("CHUNITHM 2027") > rankChuni("CHUNITHM X-VERSE PLUS"))
    }

    @Test
    fun `newestFirst 排出来是新到旧`() {
        val names = listOf("舞萌DX 2022", "maimai GreeN", "舞萌DX 2025", "maimai FiNALE")
        val sorted = names.sortedWith { a, b -> SongVersionOrder.newestFirst(GameType.MAIMAI, a, b) }
        assertEquals(listOf("舞萌DX 2025", "舞萌DX 2022", "maimai FiNALE", "maimai GreeN"), sorted)
    }
}
