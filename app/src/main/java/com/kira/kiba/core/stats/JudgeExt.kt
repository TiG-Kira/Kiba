package com.kira.kiba.core.stats

import com.kira.kiba.core.db.entity.RecordEntity

/**
 * 成绩判定维度（上游 prober 字段口径）。
 *
 * - 极：fc 非空（FC / AP / AP+ 都算全连）
 * - 将：SSS 及以上（rate = sss / sss+）
 * - 神：AP / AP+（fc = ap / app）
 * - FDX：FULL SYNC（fs 非空）
 */

/** 极：Full Combo。 */
val RecordEntity.isFc: Boolean
    get() = fc.isNotBlank()

/** 神：AP / AP+。 */
val RecordEntity.isAp: Boolean
    get() = fc == "ap" || fc == "app"

/** 将：SSS 及以上。 */
val RecordEntity.isSss: Boolean
    get() = rate.startsWith("sss", ignoreCase = true)

/** FDX：FULL SYNC。 */
val RecordEntity.isFdx: Boolean
    get() = fs.isNotBlank()
