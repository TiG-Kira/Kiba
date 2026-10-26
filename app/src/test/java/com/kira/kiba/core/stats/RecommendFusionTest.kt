package com.kira.kiba.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecommendFusion（EasyMai 推荐算法移植 + 融合）单元测试。
 */
class RecommendFusionTest {

    // ── 平均定数 / 推荐范围（ratingUtils.js 口径）──

    @Test
    fun `averageDs matches EasyMai formula`() {
        // 12500 / (22.4*50) = 11.1607 → 11.2
        assertEquals(11.2, RecommendFusion.averageDs(12500), 1e-9)
        // 15323 / 1120 = 13.681 → 13.7
        assertEquals(13.7, RecommendFusion.averageDs(15323), 1e-9)
        // 11200 / 1120 = 10.0
        assertEquals(10.0, RecommendFusion.averageDs(11200), 1e-9)
        assertEquals(0.0, RecommendFusion.averageDs(0), 1e-9)
    }

    @Test
    fun `recommendedRange is avg to avg+half`() {
        val (min, max) = RecommendFusion.recommendedRange(11.2)
        assertEquals(11.2, min, 1e-9)
        assertEquals(11.7, max, 1e-9)
        // 下限不低于 1
        val (low, _) = RecommendFusion.recommendedRange(0.3)
        assertEquals(1.0, low, 1e-9)
        // 上限不超过 15
        val (_, high) = RecommendFusion.recommendedRange(14.9)
        assertEquals(15.0, high, 1e-9)
        // 非法输入
        assertEquals(0.0, RecommendFusion.recommendedRange(0.0).first, 1e-9)
    }

    // ── fitScore（recommendationUtils.js 加权公式）──

    @Test
    fun `fitScore matches EasyMai weighting`() {
        // avg=100 → avgScore=5；cnt=20000 → cntScore=1 → 5*(0.7+0.3)=5.0
        assertEquals(5.0, RecommendFusion.fitScore(100.0, 20000.0), 1e-9)
        // avg=97 → 2；cnt=5000 → 0.5 → 2*(0.7+0.15)=1.7
        assertEquals(1.7, RecommendFusion.fitScore(97.0, 5000.0), 1e-9)
        // avg 高但没人玩：5*(0.7)=3.5 < 5.0
        assertTrue(RecommendFusion.fitScore(100.0, 0.0) < RecommendFusion.fitScore(100.0, 99999.0))
        // avg < 95 → 负分，沉底
        assertTrue(RecommendFusion.fitScore(94.0, 100.0) < 0.0)
    }

    // ── recommend：fit 路线 ──

    private fun fixture(): Pair<List<RecommendFusion.StatChart>, Map<Pair<Int, Int>, Double>> {
        val stats = listOf(
            RecommendFusion.StatChart(songId = 1, levelIndex = 3, cnt = 9000.0, avg = 99.0, fitDiff = 11.0),
            RecommendFusion.StatChart(songId = 2, levelIndex = 3, cnt = 100.0, avg = 99.5, fitDiff = 11.4),
            RecommendFusion.StatChart(songId = 3, levelIndex = 3, cnt = 12000.0, avg = 98.0, fitDiff = 12.0),
            // 定数范围外（ds 14.0 > max 11.7）
            RecommendFusion.StatChart(songId = 4, levelIndex = 3, cnt = 50000.0, avg = 99.9, fitDiff = 13.9),
            // 无官标定数（dsByChart 不含）→ 应被跳过
            RecommendFusion.StatChart(songId = 5, levelIndex = 3, cnt = 8000.0, avg = 99.0, fitDiff = 11.2),
        )
        val ds = mapOf(
            (1 to 3) to 11.5,
            (2 to 3) to 11.6,
            (3 to 3) to 11.3,
            (4 to 3) to 14.0,
            // songId=5 的官标定数缺失
        )
        return stats to ds
    }

    @Test
    fun `fit route filters by ds range and sorts by score`() {
        val (stats, ds) = fixture()
        val result = RecommendFusion.recommend(stats, ds, rating = 12400)

        assertEquals(11.1, result.averageDs, 1e-9)
        // songId 4（范围外）与 5（无官标定数）都不进 fit 列表
        assertTrue(result.fitCandidates.all { it.songId != 4 && it.songId != 5 })
        // songId 1：avgScore 4 × (0.7+0.3×0.9)=4.0×0.97=3.88 → 最高
        assertEquals(1, result.fitCandidates.first().songId)
        assertEquals(3.88, result.fitCandidates.first().score!!, 1e-9)
    }

    @Test
    fun `diff route keeps only positive official-minus-fit within range`() {
        val (stats, ds) = fixture()
        val result = RecommendFusion.recommend(stats, ds, rating = 12400)

        // 全部 diff > 0 且在定数范围内
        assertTrue(result.diffCandidates.all { (it.dsDifference ?: 0.0) > 0.0 })
        // songId 1: 11.5-11.0=0.5；songId 3: 11.3-12.0=-0.7（拟合比官标难，排除）；
        // songId 2: 11.6-11.4=0.2
        assertTrue(result.diffCandidates.none { it.songId == 3 })
        // 按差值降序：songId 1 在前
        assertEquals(1, result.diffCandidates.first().songId)
        assertEquals(0.5, result.diffCandidates.first().dsDifference!!, 1e-9)
    }

    @Test
    fun `rangeOverride overrides rating-derived range`() {
        val (stats, ds) = fixture()
        val result = RecommendFusion.recommend(stats, ds, rating = 0, rangeOverride = 13.5 to 14.5)
        // songId 4（ds 14.0）此刻在范围内
        assertTrue(result.fitCandidates.any { it.songId == 4 })
    }

    @Test
    fun `empty inputs yield empty candidates`() {
        val result = RecommendFusion.recommend(emptyList(), emptyMap(), rating = 12000)
        assertTrue(result.fitCandidates.isEmpty() && result.diffCandidates.isEmpty())
    }

    // ── 融合辅助 ──

    @Test
    fun `fusedTieBreaker clamps to 0-1`() {
        assertEquals(0.0, RecommendFusion.fusedTieBreaker(null), 1e-9)
        assertEquals(1.0, RecommendFusion.fusedTieBreaker(99.0), 1e-9)
        assertEquals(0.5, RecommendFusion.fusedTieBreaker(2.5), 1e-9)
    }

    @Test
    fun `singleRa matches diving-fish computeRa floor semantics`() {
        // 13.5 × 1.005 × 22.4 = 303.912 → floor 303
        assertEquals(303, RecommendFusion.singleRa(13.5, 100.5, 22.4))
        // 达成率超过 100.5 按 100.5 封顶
        assertEquals(
            RecommendFusion.singleRa(13.5, 101.0, 22.4),
            RecommendFusion.singleRa(13.5, 100.5, 22.4),
        )
    }
}
