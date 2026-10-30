package com.kira.kiba.core.data

import kotlin.math.floor

/**
 * 中二节奏 Rating 的官方口径计算（纯算法，不依赖 Android，可直接单测）。
 *
 * ## 官方公式（CHUNITHM VERSE 起，当前 X-VERSE-X 适用）
 * ```
 * playerRate = ( 非当前版本谱面 ra 最高的 30 个之和
 *              + 当前版本常规谱面 ra 最高的 20 个之和 ) / 50
 * ```
 * - World's End 谱面不参与 Rating。
 * - 分母固定 50（非当前 30 + 当前 20）。某一边凑不满时取实际有的，分母仍是 50。
 * - 显示保留两位小数且**截断**（非四舍五入）。
 *
 * ## 「当前版本」的判定
 * 查分器（水鱼 / 落雪）把临近两版都归为「最新曲」，故统一取曲库里最大的有效 version
 * 作为 currentVersion（落雪数据里为 24000，即 X-VERSE 档，已包含 X-VERSE-X 新曲）。
 * 曲库未同步落雪、没有任何 version 信息时，退化为旧 best30 均值口径（分母 30），
 * 避免把 Rating 砍没。
 *
 * ## 关于水鱼数据源
 * 2026 版本后 `records.r10`（最近游玩）恒为空数组，水鱼服务端的 rating 会直接腰斩
 * （实测玩家机台 14.x，接口只给 7.x）—— 所以本 App 不取服务端 rating，一律用本地
 * 成绩按官方公式自算。
 */
object ChuniRating {

    /** 非当前版本枠曲目数。 */
    const val NON_CURRENT_SIZE = 30

    /** 当前版本枠曲目数。 */
    const val CURRENT_SIZE = 20

    /** 分母（非当前 30 + 当前 20）。 */
    const val DENOMINATOR = 50.0

    /**
     * 单曲 Rating 输入。
     * @param ra 该谱面的 Rating 贡献值（浮点，如 14.53）。
     * @param version 谱面版本编码（落雪提供，0 = 未打标）。
     * @param isWorldEnd 是否为 World's End 谱面（不参与 Rating）。
     */
    data class Entry(val ra: Double, val version: Int, val isWorldEnd: Boolean)

    /**
     * 按官方 VERSE 起公式算玩家 Rating。
     *
     * @param entries 全部历史最佳成绩（调用方需保证已按谱面去重）。
     * @param currentVersion 当前版本编码（曲库最大有效 version）；<= 0 表示无版本信息，退化为 best30 均值。
     * @return 两位小数的 Rating；无成绩时返回 0.0。
     */
    fun calculate(entries: List<Entry>, currentVersion: Int): Double {
        if (currentVersion <= 0) {
            return bestAverage(entries.filter { !it.isWorldEnd }.map { it.ra })
        }
        val nonCurrent = entries
            .filter { !it.isWorldEnd && it.version < currentVersion }
            .sortedByDescending { it.ra }
            .take(NON_CURRENT_SIZE)
        val current = entries
            .filter { !it.isWorldEnd && it.version == currentVersion }
            .sortedByDescending { it.ra }
            .take(CURRENT_SIZE)
        val sum = nonCurrent.sumOf { it.ra } + current.sumOf { it.ra }
        return truncate2(sum / DENOMINATOR)
    }

    /** 非当前版本 TOP30 均值（Best 页展示用，两位小数截断）。 */
    fun nonCurrentAverage(entries: List<Entry>, currentVersion: Int): Double {
        if (currentVersion <= 0) return bestAverage(entries.filter { !it.isWorldEnd }.map { it.ra })
        val list = entries
            .filter { !it.isWorldEnd && it.version < currentVersion }
            .sortedByDescending { it.ra }
            .take(NON_CURRENT_SIZE)
        if (list.isEmpty()) return 0.0
        return truncate2(list.map { it.ra }.average())
    }

    /** 当前版本 TOP20 均值（Best 页展示用，两位小数截断）；无当前版本数据返回 0.0。 */
    fun currentAverage(entries: List<Entry>, currentVersion: Int): Double {
        if (currentVersion <= 0) return 0.0
        val list = entries
            .filter { !it.isWorldEnd && it.version == currentVersion }
            .sortedByDescending { it.ra }
            .take(CURRENT_SIZE)
        if (list.isEmpty()) return 0.0
        return truncate2(list.map { it.ra }.average())
    }

    /** 旧口径兜底（无 version 信息）：历史最佳 ra 最高的 30 首均值，两位小数截断。 */
    fun bestAverage(best: List<Double>): Double {
        val top = best.sortedDescending().take(NON_CURRENT_SIZE)
        if (top.isEmpty()) return 0.0
        return truncate2(top.average())
    }

    /** 官方口径两位小数截断（不是四舍五入）。 */
    fun truncate2(value: Double): Double = floor(value * 100 + 1e-9) / 100
}
