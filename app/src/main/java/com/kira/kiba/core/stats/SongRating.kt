package com.kira.kiba.core.stats

import kotlin.math.roundToInt

/**
 * 单曲 Rating（舞萌 DX 现行口径，Best50 = Best15 + Best35）。
 *
 * 公式：**Rating = round(定数 × 完成度 × 乘数)**（最后一步四舍五入）。
 * 乘数由完成度档位决定（完成度越高乘数越高，收益递增）；
 * 完成度封顶 100.5%（SSS+），超过按 100.5% 计。
 *
 * 口径来源：https://luch4736.github.io/post/2GwvWgY6N/ （Festival 后 Best50 制）。
 */
object SongRating {

    /** 完成度(%) → (档位, 乘数)。从高到低匹配，命中即返回。 */
    data class Tier(val minAchievement: Double, val rank: String, val multiplier: Double)

    /** 官方档位表（公开给 Rating 计算器等展示场景）。 */
    val TIERS = listOf(
        Tier(100.5, "SSS+", 0.224),
        Tier(100.0, "SSS", 0.216),
        Tier(99.5, "SS+", 0.211),
        Tier(99.0, "SS", 0.208),
        Tier(98.0, "S+", 0.203),
        Tier(97.0, "S", 0.200),
        Tier(94.0, "AAA", 0.168),
        Tier(90.0, "AA", 0.152),
        Tier(80.0, "A", 0.136),
        Tier(75.0, "BBB", 0.120),
        Tier(70.0, "BB", 0.112),
        Tier(60.0, "B", 0.096),
        Tier(50.0, "C", 0.080),
        Tier(40.0, "D", 0.064),
        Tier(30.0, "D", 0.048),
        Tier(20.0, "D", 0.032),
        Tier(10.0, "D", 0.016),
    )

    /** 完成度(%) → 档位名。 */
    fun rankOf(achievement: Double): String {
        val a = achievement.coerceAtMost(100.5)
        return TIERS.firstOrNull { a >= it.minAchievement - 1e-9 }?.rank ?: "D"
    }

    /** 完成度(%) → 乘数。 */
    fun multiplierOf(achievement: Double): Double {
        val a = achievement.coerceAtMost(100.5)
        return TIERS.firstOrNull { a >= it.minAchievement - 1e-9 }?.multiplier ?: 0.0
    }

    /**
     * 单曲 Rating：round(定数 × min(完成度, 100.5) × 乘数)。
     * @param ds 谱面定数（内部定数，x.7~x.9 显示为 x+ 但计算用真值）
     * @param achievement 完成度（百分数，如 100.5）
     */
    fun songRating(ds: Double, achievement: Double): Int {
        val a = achievement.coerceIn(0.0, 100.5)
        if (a <= 0.0) return 0
        return (ds * a * multiplierOf(a)).roundToInt()
    }

    /** 理论最大 Rating（SSS+ 100.5%）。 */
    fun maxSongRating(ds: Double): Int = songRating(ds, 100.5)
}
