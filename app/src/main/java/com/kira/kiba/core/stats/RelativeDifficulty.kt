package com.kira.kiba.core.stats

import com.kira.kiba.core.model.DifficultyType
import java.util.Locale

/**
 * 相对难度（相对拟合评级）：
 *
 * 数据来源优先级：
 * 1. 曲库自带 tag（songdata 的 stats.tag）——当前数据集（music_data_20260428）不含 stats，
 *    此分支保留为未来接入点；
 * 2. 自算：拟合定数（水鱼 chart_stats.fit_diff）与真实定数（ds）对比——
 *    差值 ≤ -0.2 简单 / ≥ +0.2 偏难 / 其余（±0.2 内浮动）正常；
 *    （例：真实 15.0，拟合 14.9 / 15.1 → 正常；拟合 14.8 及以下 → 简单；15.2 及以上 → 偏难）
 * 3. 拟合定数也缺失 → [MISSING]（暂时缺少数据）。
 */
object RelativeDifficulty {

    /** 数据缺失时的占位文案。 */
    const val MISSING = "暂时缺少数据"

    /** 浮点比较容差：14.8 - 15.0 在二进制浮点下是 -0.19999…，裸比较会漏掉 ±0.2 边界。 */
    private const val FLOAT_EPS = 1e-9

    /** 相对难度评级（不含拟合定数括号）。 */
    fun label(ds: Double, fitDiff: Double?, tag: String? = null): String {
        // 首选：曲库自带 tag（上游 KibaBot 口径；"Insufficient"/"--" 视为无效）
        if (!tag.isNullOrBlank() && tag != "Insufficient" && tag != "--") return tag
        // 其次：拟合定数 vs 真实定数
        if (fitDiff != null && fitDiff > 0.0) {
            val delta = fitDiff - ds
            return when {
                delta <= -0.2 + FLOAT_EPS -> "简单"
                delta >= 0.2 - FLOAT_EPS -> "偏难"
                else -> "正常"
            }
        }
        // 垫底
        return MISSING
    }

    /** 拟合定数文本（1 位小数，与官标定数格式一致）。 */
    fun fittedText(fitDiff: Double?): String =
        fitDiff?.let { String.format(Locale.US, "%.1f", it) } ?: MISSING

    /** 组合展示：`正常 (14.9)`；缺数据时仅「暂时缺少数据」。 */
    fun display(ds: Double, fitDiff: Double?, tag: String? = null): String {
        val l = label(ds, fitDiff, tag)
        if (l == MISSING) return MISSING
        return if (fitDiff != null && fitDiff > 0.0) "$l (${fittedText(fitDiff)})" else l
    }
}

/**
 * [DifficultyType] → 水鱼 chart_stats 的 level_index。
 * 普通谱面 0-4 对应 BASIC~REMASTER；宴会场 0 = 单人 / 1P，1 = 2P。
 */
fun DifficultyType.statsLevelIndex(): Int = when (this) {
    DifficultyType.BASIC -> 0
    DifficultyType.ADVANCED -> 1
    DifficultyType.EXPERT -> 2
    DifficultyType.MASTER -> 3
    DifficultyType.REMASTER -> 4
    DifficultyType.UTAGE -> 0
    DifficultyType.UTAGE_PLAYER2 -> 1
    DifficultyType.UNKNOWN -> -1
}
