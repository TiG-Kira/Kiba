package com.kira.kiba.core.data

import com.kira.kiba.core.db.dao.ChartVersionRow
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.model.ChuniDiffType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChuniStats] 分池与 Rating 的单元测试。
 *
 * 覆盖曾经的真实故障：
 * - 「当前版本 TOP20 均值」长期显示 0.00 → 不是算错，而是**分类错**：
 *   曲库没有 version 标记时，[ChuniRating.Split.current] 恒空，旧实现把空池的 0.0
 *   当成均值写进 SharedPreferences，之后再没人重算；
 * - World's End / Recent 枠必须排除在 Rating 与列表之外。
 */
class ChuniStatsTest {

    private val CURRENT = 24000
    private val OLD = 23000
    private val WE = ChuniDiffType.WORLD_END.ordinal

    private fun record(
        songId: Int,
        ra: Double,
        levelIndex: Int = 3,
        isRecent: Boolean = false,
    ): ChuniRecordEntity = ChuniRecordEntity(
        songId = songId,
        cid = songId,
        title = "song-$songId",
        levelIndex = levelIndex,
        levelLabel = "MASTER",
        level = "14",
        score = 1_009_000,
        fc = "",
        ra = ra,
        ds = 14.0,
        isRecent = isRecent,
    )

    private fun versions(vararg rows: ChartVersionRow): List<ChartVersionRow> = rows.toList()

    private fun version(songId: Int, v: Int, levelIndex: Int = 3) =
        ChartVersionRow(songId = songId, difficultyIndex = levelIndex, version = v)

    @Test
    fun `按版本分池 非当前30 与 当前20 各取各的`() {
        val records = (1..30).map { record(it, 14.60) } + (100..119).map { record(it, 15.00) }
        val ver = (1..30).map { version(it, OLD) } + (100..119).map { version(it, CURRENT) }

        val stats = ChuniStats.build(records, ver)

        assertTrue(stats.hasVersionInfo)
        assertEquals(30, stats.nonCurrent.size)
        assertEquals(20, stats.current.size)
        assertEquals(14.60, stats.nonCurrentAverage, 1e-9)
        assertEquals(15.00, stats.currentAverage, 1e-9)
        // (30 * 14.60 + 20 * 15.00) / 50 = (438 + 300) / 50 = 14.76
        assertEquals(14.76, stats.rating, 1e-9)
    }

    @Test
    fun `曲库无版本标记时当前枠为空 不能把0点00当均值`() {
        val records = (1..5).map { record(it, 14.60) }

        val stats = ChuniStats.build(records, emptyList())

        assertFalse(stats.hasVersionInfo)
        assertTrue(stats.current.isEmpty())
        assertEquals(5, stats.nonCurrent.size)
        // 退化为 best30 均值兜底，而不是 438/50 那种被分母砍过的值
        assertEquals(14.60, stats.rating, 1e-9)
        assertEquals(14.60, stats.nonCurrentAverage, 1e-9)
        // 空枠的均值 0.0 只是「没有数据」，展示层据此显示「—」
        assertEquals(0.0, stats.currentAverage, 1e-9)
    }

    @Test
    fun `当前版本暂未游玩时非当前枠照常 分母仍50`() {
        val records = (1..30).map { record(it, 14.60) }
        val ver = (1..30).map { version(it, OLD) } + version(999, CURRENT) // 曲库有当前版本曲目，但玩家没打

        val stats = ChuniStats.build(records, ver)

        assertTrue(stats.hasVersionInfo)
        assertTrue(stats.current.isEmpty())
        // 30 * 14.60 / 50 = 8.76
        assertEquals(8.76, stats.rating, 1e-9)
    }

    @Test
    fun `WorldsEnd 与 Recent 枠不参与统计`() {
        val records = (1..3).map { record(it, 14.00) } +
            record(50, 20.00, levelIndex = WE) +
            record(60, 20.00, isRecent = true)
        // 曲库里有当前版本曲目（玩家没打），否则库内最大版本号会被当成「当前版本」
        val ver = (1..3).map { version(it, OLD) } +
            version(50, OLD, WE) +
            version(60, OLD) +
            version(999, CURRENT)

        val stats = ChuniStats.build(records, ver)

        assertEquals(3, stats.recordCount)
        assertEquals(3, stats.nonCurrent.size)
        assertTrue(stats.nonCurrent.none { it.songId == 50 || it.songId == 60 })
        // (3 * 14.00) / 50 = 0.84
        assertEquals(0.84, stats.rating, 1e-9)
    }

    @Test
    fun `同一首歌不同难度按各自版本分池`() {
        val records = listOf(
            record(1, 15.00, levelIndex = 3), // MASTER，旧版
            record(1, 15.50, levelIndex = 4), // ULTIMA，当前版
        )
        val ver = listOf(version(1, OLD, 3), version(1, CURRENT, 4))

        val stats = ChuniStats.build(records, ver)

        assertEquals(listOf(15.00), stats.nonCurrent.map { it.ra })
        assertEquals(listOf(15.50), stats.current.map { it.ra })
        assertEquals(0.61, stats.rating, 1e-9) // (15.00 + 15.50) / 50
    }

    @Test
    fun `图表键在同一id不同难度上唯一`() {
        assertEquals(
            ChuniRating.chartKey(1, 3),
            ChuniRating.chartKey(1, 3),
        )
        assertTrue(ChuniRating.chartKey(1, 4) != ChuniRating.chartKey(1, 3))
        assertTrue(ChuniRating.chartKey(2, 0) != ChuniRating.chartKey(1, 0))
        // 高位左移：不同 id 之间不会因为难度索引而串号
        assertTrue(ChuniRating.chartKey(256, 0) != ChuniRating.chartKey(255, 255))
    }
}
