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
        val split = split(
            items = entries,
            currentVersion = currentVersion,
            raOf = { it.ra },
            versionOf = { it.version },
            isWorldEndOf = { it.isWorldEnd },
        )
        return calculate(split.nonCurrent, split.current)
    }

    /** 官方 /50 公式本体：(非当前 TOP30 之和 + 当前 TOP20 之和) / 50，两位小数截断。 */
    fun calculate(nonCurrent: List<Entry>, current: List<Entry>): Double =
        ratingOf(nonCurrent.map { it.ra }, current.map { it.ra })

    /** 同上，直接吃两个枠的 ra 列表（Best 页 / 统计页只需要 ra，不必构造 [Entry]）。 */
    fun ratingOf(nonCurrentRa: List<Double>, currentRa: List<Double>): Double =
        truncate2((nonCurrentRa.sum() + currentRa.sum()) / DENOMINATOR)

    /**
     * 官方口径分池：非当前版本 TOP30 与当前版本 TOP20。
     *
     * 与 [calculate] 用的是同一套判定，Best 页据此分组展示，避免「页面分组」与
     * 「头部 Rating」各算一套导致对不上。
     *
     * @param currentVersion 当前版本编码；<= 0（曲库无版本标记）时无法区分当前 / 非当前，
     *   全部按非当前枠处理（取 ra 最高 30），此时 [Split.current] 为空——UI 需据此提示
     *   「暂缺版本信息」而不是把空池的 0.00 当成真实均值展示。
     */
    fun <T> split(
        items: List<T>,
        currentVersion: Int,
        raOf: (T) -> Double,
        versionOf: (T) -> Int,
        isWorldEndOf: (T) -> Boolean,
    ): Split<T> {
        val usable = items.filter { !isWorldEndOf(it) }
        if (currentVersion <= 0) {
            return Split(usable.sortedByDescending(raOf).take(NON_CURRENT_SIZE), emptyList())
        }
        val nonCurrent = usable
            .filter { versionOf(it) < currentVersion }
            .sortedByDescending(raOf)
            .take(NON_CURRENT_SIZE)
        val current = usable
            .filter { versionOf(it) == currentVersion }
            .sortedByDescending(raOf)
            .take(CURRENT_SIZE)
        return Split(nonCurrent, current)
    }

    /** 一组单曲 ra 的均值（空集返回 0.0），两位小数截断。 */
    fun averageOf(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else truncate2(values.average())

    /**
     * 分池结果。
     * @param nonCurrent 非当前版本 TOP30（ra 降序）。
     * @param current 当前版本 TOP20（ra 降序）。
     */
    data class Split<T>(val nonCurrent: List<T>, val current: List<T>) {
        val isEmpty: Boolean get() = nonCurrent.isEmpty() && current.isEmpty()
        val size: Int get() = nonCurrent.size + current.size
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

    /**
     * 由分数 + 谱面定数反推单曲 ra（官方评分表）。
     *
     * ## 什么时候才会用到
     * 只在数据源**没给单曲 rating** 时兜底：落雪 `player/scores` 在部分实现里返回的是
     * `SimpleScore`（无 rating 字段），而 `player/bests` 只有 50 条。为了详情页 / 收藏页
     * 也能显示所有谱面的 ra，这里按官方分段公式本地补算。
     *
     * 接口只要给了 `rating`（`bests` / `new_bests` 一定给），就**不**走这里 —— 以接口值为准，
     * 避免公式某段临界点与服务器差 0.01 影响 B50 排序。
     *
     * 分段（CHUNITHM 现行）：
     * ```
     * 1009000+  ds + 2.15
     * 1007500+  ds + 2.0  + (score - 1007500) / 10000 * 0.15
     * 1005000+  ds + 1.5  + (score - 1005000) / 5000  * 0.5
     * 1000000+  ds + 1.0  + (score - 1000000) / 10000 * 0.5
     *  990000+  ds + 0.6  + (score -  990000) / 10000 * 0.4
     *  950000+  ds + (score - 950000) / 50000 * 0.6
     *  900000+  (score - 900000) / 100000 * ds * 0.5
     * ```
     * 低于 900000 记 0（这些分数本来也进不了 B50）。
     */
    fun raOf(score: Int, ds: Double): Double {
        if (ds <= 0.0 || score <= 0) return 0.0
        return when {
            score >= 1009000 -> ds + 2.15
            score >= 1007500 -> ds + 2.0 + (score - 1007500) / 10000.0 * 0.15
            score >= 1005000 -> ds + 1.5 + (score - 1005000) / 5000.0 * 0.5
            score >= 1000000 -> ds + 1.0 + (score - 1000000) / 10000.0 * 0.5
            score >= 990000 -> ds + 0.6 + (score - 990000) / 10000.0 * 0.4
            score >= 950000 -> ds + (score - 950000) / 50000.0 * 0.6
            score >= 900000 -> (score - 900000) / 100000.0 * ds * 0.5
            else -> 0.0
        }
    }

    /**
     * 谱面复合键（歌曲 id + 难度索引）：成绩表与谱面表靠它关联版本。
     *
     * 用 Long 打包而不用 `kotlin.Pair` —— 后者在 R8 优化下有被裁掉的风险（本项目已知坑），
     * 且 `songId * 10 + levelIndex` 这种乘法拼接在 id 变大后不够直观。
     */
    fun chartKey(songId: Int, difficultyIndex: Int): Long =
        (songId.toLong() shl 8) or (difficultyIndex.toLong() and 0xFF)
}
