package com.kira.kiba.core.stats

import com.kira.kiba.core.data.ChuniRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChuniPushRecommender] 的单元测试。
 *
 * 两条铁律（都是真实体验问题）：
 * 1. **SSS+ 大幅降权**：线是 1009000，满分 1010000，余量只有 **1000 分**（漏一个 note 就掉线），
 *    却只比 SSS（1007500）多 0.15 ra —— 沉底，但 rating 17+ 推无可推时保留；
 * 2. **高定数优先、低 rank 优先**：同样收益下，换高定数的谱永远比在同一定数上硬顶 rank 省力。
 *
 * 旧实现把档位表按**降序**排列后取 `firstOrNull`，结果对每个谱面都命中最高档 SSS+，
 * 推荐列表全是「差几万分到 SSS+」——这两条全违反。
 */
class ChuniPushRecommenderTest {

    private val empty20 = ChuniPushRecommender.Pool(floor = null, size = 0, capacity = 20)

    private fun chart(
        id: Int,
        ds: Double,
        isCurrent: Boolean = false,
        levelIndex: Int = 3,
    ) = ChuniPushRecommender.Chart(
        songId = id,
        levelIndex = levelIndex,
        level = "14+",
        ds = ds,
        isCurrent = isCurrent,
    )

    private fun score(id: Int, score: Int, ra: Double, levelIndex: Int = 3) =
        ChuniPushRecommender.Score(id, levelIndex, score, ra)

    /** 非当前枠地板固定成 floor 的便捷构造。 */
    private fun pool(floor: Double, size: Int = 30, capacity: Int = 30) =
        ChuniPushRecommender.Pool(floor, size, capacity)

    @Test
    fun `档位表升序 SSS+ 保留但排在最后`() {
        val scores = ChuniPushRecommender.TIERS.map { it.score }
        assertEquals(scores.sorted(), scores)
        assertEquals("SSS+", ChuniPushRecommender.TIERS.last().name)
        assertEquals(1009000, ChuniPushRecommender.TIERS.last().score)
    }

    @Test
    fun `档位分数线与满分口径`() {
        // 官方分档：SSS 1007500 / SSS+ 1009000，满分 1010000 → SSS+ 余量只有 1000 分
        assertEquals(1007500, ChuniPushRecommender.SSS_SCORE)
        assertEquals(1009000, ChuniPushRecommender.SSS_PLUS_SCORE)
        assertEquals(1010000, ChuniPushRecommender.MAX_SCORE)
        assertEquals(1000, ChuniPushRecommender.SSS_PLUS_MARGIN)
        assertEquals(
            listOf(950000, 975000, 990000, 1000000, 1005000, 1007500, 1009000),
            ChuniPushRecommender.TIERS.map { it.score },
        )
    }

    @Test
    fun `目标档位取能过地板的最低一档而不是SSS+`() {
        // 定数 15.0 / 地板 15.5：S=15.0 不够，S+=15.6 刚好压过 → 目标必须是 S+
        // （旧实现会给出 SSS+：17.15，要打 1009000 分，纯属劝退）
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 15.0)),
            scores = emptyList(),
            nonCurrent = pool(15.5),
            current = empty20,
        )
        val c = result.single()
        assertEquals("S+", c.tierName)
        assertEquals(990000, c.tierScore)
        assertEquals(2, c.tierRank)
        assertEquals(15.6, c.potential, 1e-9)
        assertEquals(0.1, c.gain, 1e-9)
    }

    @Test
    fun `按定数从高到低排 即使低定数收益更高`() {
        // 地板 16.00：
        //   15.4 → SS(16.4)  收益 0.40
        //   15.0 → SS+(16.5) 收益 0.50（收益更高，但定数低一档、rank 还高一档）
        // 玩家愿意走的是前者：多赚 0.1 ra 不值一个档位。
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(2, 15.0), chart(1, 15.4)),
            scores = emptyList(),
            nonCurrent = pool(16.0),
            current = empty20,
        )
        assertEquals(listOf(15.4, 15.0), result.map { it.ds })
        assertTrue(result[0].gain < result[1].gain)
    }

    @Test
    fun `同定数优先更低档位`() {
        // 两首都是 15.2（S 档 = 定数本身）：当前枠地板 15.0 → S 就够；非当前枠地板 15.55 → 得 S+
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(2, 15.2), chart(1, 15.2, isCurrent = true)),
            scores = emptyList(),
            nonCurrent = pool(15.55),
            current = pool(15.0, size = 20, capacity = 20),
        )
        assertEquals(listOf("S", "S+"), result.map { it.tierName })
        assertEquals(1, result[0].songId)
    }

    @Test
    fun `只能靠 SSS+ 时保留候选 但排在 SSS 之后`() {
        // 地板 17.0（rating 17+ 的典型盘面）：
        //   15.05 → SSS(17.05) 收益 0.05
        //   15.00 → SSS+(17.15) 收益 0.15（收益更高，但要顶着 1000 分容错去拼）
        // 仍然先给 SSS —— SSS+ 降权，不是靠收益排。
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 15.05), chart(2, 15.0)),
            scores = emptyList(),
            nonCurrent = pool(17.0),
            current = empty20,
        )
        assertEquals(listOf(15.05, 15.0), result.map { it.ds })
        assertEquals(listOf("SSS", "SSS+"), result.map { it.tierName })
        assertTrue(result[1].gain > result[0].gain)
    }

    @Test
    fun `rating 17+ 推无可推时 SSS+ 仍然给建议`() {
        // 地板 17.2：15.2 是唯一还能压过地板的谱面，且只能 SSS+（17.35）
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 15.2), chart(2, 15.0), chart(3, 14.8)),
            scores = emptyList(),
            nonCurrent = pool(17.2),
            current = empty20,
        )
        val c = result.single()
        assertEquals("SSS+", c.tierName)
        assertEquals(15.2, c.ds, 1e-9)
        assertEquals(0.15, c.gain, 1e-9)
    }

    @Test
    fun `已经打到的档位自动跳到下一档`() {
        // 当前 980000（ra 14.42），地板 14.2：S（975000）已经打过 → 目标 S+
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 14.0)),
            scores = listOf(score(1, 980000, 14.42)),
            nonCurrent = pool(14.2),
            current = empty20,
        )
        val c = result.single()
        assertEquals("S+", c.tierName)
        assertEquals(10000, c.gap)
        assertTrue(c.inPool)
        // 已在榜内：增益按「潜在 ra − 当前 ra」算，不是 − 地板
        assertEquals(14.6 - 14.42, c.gain, 1e-9)
    }

    @Test
    fun `未上榜成绩按顶替地板计算 不按当前ra虚高`() {
        // 当前 960000（ra 14.1）低于地板 14.2 → S 只有 14.0 压不过，得打到 S+（14.6）顶掉地板那首
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 14.0)),
            scores = listOf(score(1, 960000, 14.1)),
            nonCurrent = pool(14.2),
            current = empty20,
        )
        val c = result.single()
        assertEquals("S+", c.tierName)
        assertEquals(14.6 - 14.2, c.gain, 1e-9)
        assertFalse(c.inPool)
        assertTrue(c.played)
    }

    @Test
    fun `枠没攒满时新曲目全额计入`() {
        val chart = chart(1, 14.0)
        val notFull = ChuniPushRecommender.recommend(
            charts = listOf(chart),
            scores = emptyList(),
            nonCurrent = pool(14.2, size = 5, capacity = 30),
            current = empty20,
        ).single()
        val full = ChuniPushRecommender.recommend(
            charts = listOf(chart),
            scores = emptyList(),
            nonCurrent = pool(14.2, size = 30, capacity = 30),
            current = empty20,
        ).single()
        assertEquals(14.6, notFull.gain, 1e-9)
        assertEquals(0.4, full.gain, 1e-9)
    }

    @Test
    fun `压不过地板的谱面不进候选`() {
        // 定数 14.0 的谱面打到 SSS+ 也只有 16.15，压不过 17.0 的地板
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 14.0)),
            scores = emptyList(),
            nonCurrent = pool(17.0),
            current = empty20,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `当前版本曲对标当前枠地板`() {
        // 当前枠地板 15.0，非当前枠地板 16.5：当前版本曲应按 15.0 判定
        // S 档 = 定数（15.0，压不过地板）→ 目标 S+（15.6）
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 15.0, isCurrent = true)),
            scores = emptyList(),
            nonCurrent = pool(16.5),
            current = pool(15.0, size = 20, capacity = 20),
        )
        val c = result.single()
        assertTrue(c.isCurrent)
        assertEquals("S+", c.tierName)
        assertEquals(15.6, c.potential, 1e-9)
    }

    @Test
    fun `BASIC_ADVANCED 与 WorldsEnd 一律不推荐`() {
        val result = ChuniPushRecommender.recommend(
            charts = listOf(
                chart(1, 15.0, levelIndex = 0),
                chart(2, 15.0, levelIndex = 1),
                chart(3, 15.0, levelIndex = 5),
                chart(4, 15.0, levelIndex = 2),
            ),
            scores = emptyList(),
            nonCurrent = pool(14.5),
            current = empty20,
        )
        assertEquals(listOf(4), result.map { it.songId })
    }

    @Test
    fun `候选数量按 limit 截断`() {
        val charts = (1..20).map { chart(it, 15.0 + it * 0.01) }
        val result = ChuniPushRecommender.recommend(
            charts = charts,
            scores = emptyList(),
            nonCurrent = pool(16.2),
            current = empty20,
            limit = 5,
        )
        assertEquals(5, result.size)
    }

    @Test
    fun `潜在ra与官方公式一致`() {
        val result = ChuniPushRecommender.recommend(
            charts = listOf(chart(1, 14.5)),
            scores = emptyList(),
            nonCurrent = pool(16.4),
            current = empty20,
        )
        val c = result.single()
        assertEquals("SSS", c.tierName)
        assertEquals(ChuniRating.raOf(1007500, 14.5), c.potential, 1e-9)
    }

    @Test
    fun `真实场景 地板16_0 列表定数递减且档位非降`() {
        // 典型中二玩家：非当前 TOP30 地板 16.00，曲库定数 13.5~15.4
        val result = ChuniPushRecommender.recommend(
            charts = listOf(13.5, 14.0, 14.5, 15.0, 15.2, 15.4)
                .mapIndexed { index, ds -> chart(index + 1, ds) },
            scores = emptyList(),
            nonCurrent = pool(16.0),
            current = empty20,
        )
        // 14.0 只能靠 SSS+（16.15）→ 保留但沉到最后；13.5 连 SSS+ 都不够
        assertEquals(listOf(15.4, 15.2, 15.0, 14.5, 14.0), result.map { it.ds })
        assertEquals(listOf("SS", "SS", "SS+", "SSS", "SSS+"), result.map { it.tierName })
        // 下一条永远不比上一条强：定数递减、档位非降
        result.zipWithNext().forEach { (a, b) ->
            assertTrue(a.ds >= b.ds)
            assertTrue(a.tierRank <= b.tierRank)
        }
        // SSS+ 只允许出现在末尾
        assertTrue(result.dropLast(1).none { it.tierName == "SSS+" })
        assertEquals("SSS+", result.last().tierName)
    }
}
