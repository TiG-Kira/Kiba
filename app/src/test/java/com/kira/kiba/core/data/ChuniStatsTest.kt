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
        isCurrentVersion: Boolean = false,
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
        isCurrentVersion = isCurrentVersion,
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
    // ── 落雪官方分池（new_bests）──────────────────────────────────────────────

    @Test
    fun `落雪官方分池在没有版本标记时也能正确分池`() {
        // 真实场景：水鱼源永远拿不到当前版本新曲（只回 Top30），切落雪后
        // `new_bests` 直接带 isCurrentVersion 标记，此时曲库有没有 version 都不影响。
        val records = (1..5).map { record(100 + it, 15.00, isCurrentVersion = true) } +
            (10..20).map { record(it, 14.00) }

        val stats = ChuniStats.build(records, emptyList())

        assertTrue("官方分池可用", stats.hasOfficialSplit)
        assertTrue("有官方分池就视为具备版本信息", stats.hasVersionInfo)
        assertEquals(5, stats.current.size)
        assertEquals(11, stats.nonCurrent.size)
        // (5*15.00 + 11*14.00) / 50 = 229 / 50 = 4.58
        assertEquals(4.58, stats.rating, 1e-9)
        assertEquals(15.00, stats.currentAverage, 1e-9)
        assertEquals(14.00, stats.nonCurrentAverage, 1e-9)
    }

    @Test
    fun `官方分池优先于曲库版本推断`() {
        // 曲库把 900 标成旧版本，但落雪 new_bests 明确把它算作当前版本 → 以官方分池为准
        val records = listOf(
            record(900, 15.50, isCurrentVersion = true),
            record(901, 14.30),
        )
        val ver = listOf(version(900, OLD), version(901, OLD))

        val stats = ChuniStats.build(records, ver)

        assertTrue(stats.hasOfficialSplit)
        assertEquals(listOf(15.50), stats.current.map { it.ra })
        assertEquals(listOf(14.30), stats.nonCurrent.map { it.ra })
        // 官方分池命中时也必须排除 World's End（这里另测）
        assertEquals(0.59, stats.rating, 1e-9) // (15.50 + 14.30) / 50 = 0.596 → 截断 0.59
    }

    @Test
    fun `官方分池同样排除WorldsEnd与Recent`() {
        val records = (1..2).map { record(200 + it, 15.00, isCurrentVersion = true) } +
            record(300, 20.00, levelIndex = WE, isCurrentVersion = true) +
            record(301, 19.00, isRecent = true)

        val stats = ChuniStats.build(records, emptyList())

        assertEquals(2, stats.current.size)
        assertEquals(0, stats.nonCurrent.size)
        assertEquals(0.60, stats.rating, 1e-9) // 30.00 / 50
    }
}
