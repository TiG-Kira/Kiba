package com.kira.kiba.core.stats

import com.kira.kiba.core.db.entity.RecordEntity

/**
 * 成绩判定维度（上游 prober 的 `rate` 字段口径）。
 *
 * rate 取值（舞萌官方评级）：
 * `d` `c` `b` `bb` `bbb` `a` `aa` `aaa` `s` `sp` `ss` `ssp` `sss` `sssp`
 * 对应显示名：D C B BB BBB A AA AAA S S+ SS SS+ SSS SSS+
 *
 * 传统三档（对应原版 UI）：
 * - 极：fc 非空（FC / AP / AP+ 都算全连）
 * - 将：**SSS 及以上**（rate = sss / sssp）——注意不是只有 SSS+
 * - 神：AP / AP+（fc = ap / app）
 * - FDX：FULL SYNC（fs 非空）
 *
 * 细档（>= 某档）：该档及所有更高档都算达成。
 */

/** 舞萌评级档位（从低到高）。 */
enum class JudgeTier(val label: String, val rates: Set<String>) {
    D("D", setOf("d")),
    C("C", setOf("c")),
    B("B", setOf("b")),
    BB("BB", setOf("bb")),
    BBB("BBB", setOf("bbb")),
    A("A", setOf("a")),
    AA("AA", setOf("aa")),
    AAA("AAA", setOf("aaa")),
    S("S", setOf("s")),
    SP("S+", setOf("sp")),
    SS("SS", setOf("ss")),
    SSP("SS+", setOf("ssp")),
    SSS("SSS", setOf("sss")),
    SSSP("SSS+", setOf("sssp")),
    ;

    /** 本档及所有更高档的 rate 集合。 */
    val atLeastRates: Set<String>
        get() = entries.dropWhile { it != this }.flatMap { it.rates }.toSet()

    companion object {
        /** 由 rate 字符串取档位（未知返回 null）。 */
        fun of(rate: String): JudgeTier? {
            val r = rate.lowercase()
            return entries.firstOrNull { r in it.rates }
        }
    }
}

/** 极：Full Combo。 */
val RecordEntity.isFc: Boolean
    get() = fc.isNotBlank()

/** 神：AP / AP+。 */
val RecordEntity.isAp: Boolean
    get() = fc == "ap" || fc == "app"

/** 将：SSS 及以上（rate = sss 或 sssp）。 */
val RecordEntity.isSss: Boolean
    get() = rate.lowercase() in JudgeTier.SSS.atLeastRates

/** FDX：FULL SYNC。 */
val RecordEntity.isFdx: Boolean
    get() = fs.isNotBlank()

/** 本条成绩的评级档位。 */
val RecordEntity.judgeTier: JudgeTier?
    get() = JudgeTier.of(rate)

/** 是否达到指定档位（含该档及以上）。 */
fun RecordEntity.reaches(tier: JudgeTier): Boolean =
    rate.lowercase() in tier.atLeastRates
