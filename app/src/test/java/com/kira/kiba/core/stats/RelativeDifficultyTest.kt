package com.kira.kiba.core.stats

import com.kira.kiba.core.model.DifficultyType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RelativeDifficulty] 评级规则测试：
 * tag 首选 → 拟合定数 vs 真实定数自算（±0.2 阈值）→ 暂时缺少数据兜底。
 */
class RelativeDifficultyTest {

    // ---------- 自算规则（真实定数 15.0 为基准） ----------

    @Test
    fun `浮动范围内为正常`() {
        assertEquals("正常", RelativeDifficulty.label(15.0, 15.0))
        assertEquals("正常", RelativeDifficulty.label(15.0, 14.9))
        assertEquals("正常", RelativeDifficulty.label(15.0, 15.1))
    }

    @Test
    fun `拟合明显偏低为简单`() {
        assertEquals("简单", RelativeDifficulty.label(15.0, 14.8))
        assertEquals("简单", RelativeDifficulty.label(15.0, 14.7))
    }

    @Test
    fun `拟合明显偏高为偏难`() {
        assertEquals("偏难", RelativeDifficulty.label(15.0, 15.2))
        assertEquals("偏难", RelativeDifficulty.label(15.0, 15.3))
    }

    // ---------- 兜底 ----------

    @Test
    fun `拟合定数缺失显示暂时缺少数据`() {
        assertEquals("暂时缺少数据", RelativeDifficulty.label(15.0, null))
        // 0 / 负值视为无效数据
        assertEquals("暂时缺少数据", RelativeDifficulty.label(15.0, 0.0))
    }

    // ---------- tag 首选 ----------

    @Test
    fun `tag 有效时优先于自算`() {
        assertEquals("14.9", RelativeDifficulty.label(15.0, 14.0, tag = "14.9"))
        // 即使 tag 与自算结果不同也用 tag
        assertEquals("偏难", RelativeDifficulty.label(15.0, 14.0, tag = "偏难"))
    }

    @Test
    fun `tag 无效时回落自算`() {
        assertEquals("正常", RelativeDifficulty.label(15.0, 15.0, tag = "Insufficient"))
        assertEquals("正常", RelativeDifficulty.label(15.0, 15.0, tag = "--"))
        assertEquals("正常", RelativeDifficulty.label(15.0, 15.0, tag = ""))
        assertEquals("暂时缺少数据", RelativeDifficulty.label(15.0, null, tag = null))
    }

    // ---------- 组合展示 ----------

    @Test
    fun `组合文本带括号拟合定数`() {
        assertEquals("正常 (14.9)", RelativeDifficulty.display(15.0, 14.9))
        assertEquals("偏难 (15.2)", RelativeDifficulty.display(15.0, 15.2))
        // 缺数据时只有占位文案，不带空括号
        assertEquals("暂时缺少数据", RelativeDifficulty.display(15.0, null))
    }

    @Test
    fun `拟合定数保留一位小数`() {
        assertEquals("14.9", RelativeDifficulty.fittedText(14.9))
        assertEquals("14.6", RelativeDifficulty.fittedText(14.554297900860991))
        assertEquals("暂时缺少数据", RelativeDifficulty.fittedText(null))
    }

    // ---------- level_index 映射 ----------

    @Test
    fun `难度映射到 chart_stats 索引`() {
        assertEquals(0, DifficultyType.BASIC.statsLevelIndex())
        assertEquals(1, DifficultyType.ADVANCED.statsLevelIndex())
        assertEquals(2, DifficultyType.EXPERT.statsLevelIndex())
        assertEquals(3, DifficultyType.MASTER.statsLevelIndex())
        assertEquals(4, DifficultyType.REMASTER.statsLevelIndex())
        // 宴会场：0 = 单人/1P，1 = 2P
        assertEquals(0, DifficultyType.UTAGE.statsLevelIndex())
        assertEquals(1, DifficultyType.UTAGE_PLAYER2.statsLevelIndex())
    }
}
