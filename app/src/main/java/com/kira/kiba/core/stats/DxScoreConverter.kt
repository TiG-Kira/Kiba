package com.kira.kiba.core.stats

import kotlin.math.roundToInt

/**
 * DX 分数 / 达成率换算（对齐上游 MaimaiData `FinaleToDxActivity`）。
 *
 * 上游口径：
 * - 判定权重：TAP ×10、HOLD ×20、SLIDE ×30、BREAK ×50；
 * - 判定得分：Perfect 全权重、Great 80%（BREAK 大 G 取 40% 或 25% 两档）、Good 50%、Miss 0；
 * - BREAK 额外达成加成：Perfect 1.0/0.5、Great 0.4、Good 0.3（按 break 总数占比）；
 * - 传入 breakScore（BREAK 实得分，0~2600/个）时反推 50 落 ~ 100 落之间的精确达成率。
 */
object DxScoreConverter {

    /** 某类 note 的判定数量。 */
    data class Counts(
        val perfect: Int = 0,
        val great: Int = 0,
        val good: Int = 0,
        val miss: Int = 0,
    ) {
        val total: Int get() = perfect + great + good + miss
    }

    /**
     * 换算结果。
     * @param minAchievement 达成率下界（BREAK 50 落 / 大 G 取低档）
     * @param maxAchievement 达成率上界（BREAK 100 落 / 大 G 取高档）
     * @param exact 是否为 breakScore 校正后的精确值
     * @param note 提示（校验失败原因等）
     */
    data class Conversion(
        val minAchievement: Double,
        val maxAchievement: Double,
        val exact: Boolean = false,
        val note: String? = null,
    ) {
        /** 用于算 Rating 的达成率（精确值优先，否则取上界）。 */
        val ratingAchievement: Double get() = if (exact) maxAchievement else maxAchievement
    }

    /**
     * 换算达成率。
     * @param breakScore BREAK 实得分总和（0 表示不校正）
     * @return 成功为结果，失败为校验错误信息
     */
    fun convert(
        tap: Counts,
        hold: Counts,
        slide: Counts,
        brk: Counts,
        breakScore: Int = 0,
    ): Result<Conversion> {
        val tapCount = tap.total
        val holdCount = hold.total
        val slideCount = slide.total
        val breakCount = brk.total

        if (tapCount + holdCount + slideCount + breakCount == 0) {
            return kotlin.Result.failure(IllegalArgumentException("请至少填写一类 note 的判定数量"))
        }
        if (breakCount == 0 && breakScore != 0) {
            return kotlin.Result.failure(IllegalArgumentException("填了 BREAK 实得分，但没有 BREAK 判定数量"))
        }

        val dxTotalScore = tapCount * 10 + holdCount * 20 + slideCount * 30 + breakCount * 50
        if (dxTotalScore == 0) {
            return kotlin.Result.failure(IllegalArgumentException("判定数量异常"))
        }

        val playMax = tap.perfect * 10 + tap.great * 8 + tap.good * 5 +
            hold.perfect * 20 + hold.great * 16 + hold.good * 10 +
            slide.perfect * 30 + slide.great * 24 + slide.good * 15 +
            brk.perfect * 50 + brk.great * 40 + brk.good * 20
        val playMin = playMax - brk.great * 15 // 大 G 取 25 档（40 - 15）

        if (breakScore == 0) {
            // 无 BREAK 时加成为 0（上游未处理除零，这里补上）
            val bc = if (breakCount > 0) breakCount.toFloat() else 1f
            val bPerfect = if (breakCount > 0) brk.perfect.toFloat() / bc else 0f
            val bGreat = if (breakCount > 0) brk.great.toFloat() / bc else 0f
            val bGood = if (breakCount > 0) brk.good.toFloat() / bc else 0f
            val max = playMax.toFloat() / dxTotalScore * 100 +
                bPerfect * 1 + bGreat * 0.4 + bGood * 0.3
            val min = playMin.toFloat() / dxTotalScore * 100 +
                bPerfect * 0.5 + bGreat * 0.4 + bGood * 0.3
            return Result.success(Conversion(min.toDouble(), max.toDouble()))
        }

        // breakScore 模式：必须全是 Perfect，且落在 [50落, 100落] 对应的分数区间
        if (brk.great > 0 || brk.good > 0 || brk.miss > 0 ||
            brk.perfect * 2600 < breakScore || brk.perfect * 2500 > breakScore
        ) {
            return kotlin.Result.failure(
                IllegalArgumentException("数据错误：填 BREAK 实得分时，BREAK 只能是 Perfect，且总分需落在 50 落 ~ 100 落区间"),
            )
        }
        val breakPart = 1 - 0.25 * (breakCount * 2600 - breakScore) / 50 / breakCount
        val current = playMax.toFloat() / dxTotalScore * 100 + breakPart
        return Result.success(Conversion(current.toDouble(), current.toDouble(), exact = true))
    }

    /** 按达成率算单曲 Rating（定数为空时返回 0）。 */
    fun ratingOf(ds: Double?, achievement: Double): Int =
        if (ds == null || ds <= 0.0) 0 else SongRating.songRating(ds, achievement)

    /** 达成率 → 单曲 ra（上游口径：⌊定数 × min(达成率, 100.5) / 100⌋）。 */
    fun raOf(ds: Double?, achievement: Double): Int {
        if (ds == null || ds <= 0.0) return 0
        val capped = minOf(achievement, 100.5)
        return (ds * capped / 100).roundToInt()
    }
}
