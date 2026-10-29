package com.kira.kiba.core.vpn

import androidx.core.content.edit
import com.kira.kiba.core.utils.SpUtil

/**
 * 机台成绩更新（VPN 方案）设置。
 *
 * 原版 MaimaiData 用 `widgets.Settings` 存"要更新哪些难度"，这里独立成轻量实现，
 * 默认全难度（BASIC/ADVANCED/EXPERT/MASTER/Re:MASTER）。
 */
object VpnSettings {

    private const val KEY_SELECT_DIFFICULTIES = "vpn_select_difficulties"

    /** 难度索引：0=BASIC 1=ADVANCED 2=EXPERT 3=MASTER 4=Re:MASTER */
    private val DEFAULT_SELECT = setOf(0, 1, 2, 3, 4)

    /** 爬虫要更新的难度集合。 */
    fun getUpdateDifficulty(): Set<Int> {
        val stored = SpUtil.getVpnDifficulties()
        return if (stored.isEmpty()) DEFAULT_SELECT else stored
    }

    /** 设置要更新的难度集合。 */
    fun setUpdateDifficulty(difficulties: Set<Int>) {
        SpUtil.setVpnDifficulties(difficulties)
    }

    /** 中二节奏难度索引：0=BASIC 1=ADVANCED 2=EXPERT 3=MASTER 4=ULTIMA 5=WORLD'S END 6=最近游玩 */
    private val DEFAULT_CHUNI_SELECT = setOf(0, 1, 2, 3, 4)

    /** 中二节奏要更新的难度集合。 */
    fun getUpdateChuniDifficulty(): Set<Int> {
        val stored = SpUtil.getVpnChuniDifficulties()
        return if (stored.isEmpty()) DEFAULT_CHUNI_SELECT else stored
    }

    /** 设置中二节奏要更新的难度集合。 */
    fun setUpdateChuniDifficulty(difficulties: Set<Int>) {
        SpUtil.setVpnChuniDifficulties(difficulties)
    }
}
