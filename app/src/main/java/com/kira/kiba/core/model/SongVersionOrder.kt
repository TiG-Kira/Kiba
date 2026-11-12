package com.kira.kiba.core.model

import java.util.Locale

/**
 * 版本时间轴（「按版本归类」的分组排序依据）。
 *
 * ## 为什么改成「版本倒序」
 * 归类入口的语义是「找某个版本」，而版本本身有时间轴：用户翻列表时预期的是
 * 「最新版本在最上面，往下越来越旧」。以前按**组内歌曲数降序**排，结果「舞萌DX 2012」
 * 这类大版本被顶到最前，每次找新曲都要先滑过一屏老版本，和直觉相反。
 *
 * ## 为什么手写顺序表，而不是拿字符串比大小
 * 版本名跨代混用两套命名（`maimai GreeN` 老街机 / `舞萌DX 2024` 国服年度版 /
 * `maimai でらっくす BUDDiES` 日版），大小写（`MURASAKi`、`FiNALE`）、全角半角都不统一，
 * 任何字典序都排不出时间轴（`maimai GreeN` > `舞萌DX 2024` 纯属编码巧合）。
 *
 * 表按「旧 → 新」排列，两个游戏各自独立：
 * - 表中存在的版本 → 表内序号（越大越新）；
 * - 表里没有但名字带 4 位年份（如以后新增「舞萌DX 2027」）→ [YEAR_BASE] + 年份，
 *   自动排到所有已列版本之后，不必改代码；
 * - 其余未知（含空版本名「未知版本」）→ -1，沉到列表最底部。
 */
object SongVersionOrder {

    /** 带年份的未知版本排到已列版本之后，留出足够大的间隔。 */
    private const val YEAR_BASE = 10_000

    /**
     * 舞萌：老街机世代 → DX 世代。
     *
     * DX 之后国服改用年度命名（舞萌DX 2021…），日版仍是副标题命名（Splash / UNiVERSE …），
     * 同一代的两个名字放在相邻位置 —— 它们本来就是同一次更新。
     * 全部**小写**书写，匹配时统一 lowercase（`MURASAKi` → `murasaki`）。
     */
    private val MAIMAI: List<String> = listOf(
        // ── 老街机 ──
        "maimai",
        "maimai plus",
        "maimai green",
        "maimai green plus",
        "maimai orange",
        "maimai orange plus",
        "maimai pink",
        "maimai pink plus",
        "maimai murasaki",
        "maimai murasaki plus",
        "maimai milk",
        "milk plus",
        "maimai milk plus",
        "maimai finale",
        "all finale",
        // ── DX ──
        "maimai dx",
        "maimai でらっくす",
        "舞萌dx",
        "maimai dx splash",
        "maimai dx splash plus",
        "maimai でらっくす splash",
        "maimai でらっくす splash plus",
        "舞萌dx 2021",
        "maimai dx universe",
        "maimai dx universe plus",
        "maimai でらっくす universe",
        "maimai でらっくす universe plus",
        "舞萌dx 2022",
        "maimai dx festival",
        "maimai dx festival plus",
        "maimai でらっくす festival",
        "maimai でらっくす festival plus",
        "舞萌dx 2023",
        "maimai dx buddies",
        "maimai dx buddies plus",
        "maimai でらっくす buddies",
        "maimai でらっくす buddies plus",
        "舞萌dx 2024",
        "maimai dx prism",
        "maimai dx prism plus",
        "maimai でらっくす prism",
        "maimai でらっくす prism plus",
        "舞萌dx 2025",
        "maimai dx circle",
        "maimai でらっくす circle",
        "舞萌dx 2026",
    )

    /** 中二节奏：`CHUNITHM` 初代 → 现在。国服年度版（中二节奏 2024…）与日版副标题并排。 */
    private val CHUNITHM: List<String> = listOf(
        "chunithm",
        "chunithm plus",
        "chunithm air",
        "chunithm air plus",
        "chunithm star",
        "chunithm star plus",
        "chunithm amazon",
        "chunithm amazon plus",
        "chunithm crystal",
        "chunithm crystal plus",
        "chunithm paradise",
        "chunithm paradise lost",
        "chunithm new",
        "中二节奏 new",
        "chunithm new plus",
        "chunithm sun",
        "chunithm sun plus",
        "chunithm luminous",
        "中二节奏 2024",
        "chunithm luminous plus",
        "chunithm verse",
        "中二节奏 2025",
        "chunithm x-verse",
        "chunithm x-verse plus",
    )

    private val maimaiRank: Map<String, Int> = indexOf(MAIMAI)
    private val chuniRank: Map<String, Int> = indexOf(CHUNITHM)

    /** 同名只取第一次出现的位置（表中故意留了几个别名）。 */
    private fun indexOf(names: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>(names.size * 2)
        names.forEachIndexed { index, name ->
            if (!map.containsKey(name)) map[name] = index
        }
        return map
    }

    private val YEAR = Regex("(20\\d{2})")

    private fun normalize(version: String): String = version.trim().lowercase(Locale.ROOT)

    /**
     * 版本的新旧分值：**数值越大越新**。
     *
     * @return 表内版本 = 表内序号；带年份的未知版本 = [YEAR_BASE] + 年份；未知 / 空 = -1
     */
    fun rank(game: GameType, version: String): Int {
        val key = normalize(version)
        if (key.isEmpty()) return -1
        val table = if (game == GameType.CHUNITHM) chuniRank else maimaiRank
        table[key]?.let { return it }
        val year = YEAR.find(key)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return -1
        return YEAR_BASE + year
    }

    /**
     * 版本名比较器：新版本在前。
     *
     * 同分值（理论上只有两个同年的未知版本）按名字升序，保证排序稳定可预期。
     */
    fun newestFirst(game: GameType, a: String, b: String): Int {
        val ra = rank(game, a)
        val rb = rank(game, b)
        return if (ra != rb) rb.compareTo(ra) else a.compareTo(b)
    }
}
