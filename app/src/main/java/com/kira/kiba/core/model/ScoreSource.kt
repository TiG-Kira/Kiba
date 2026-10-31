package com.kira.kiba.core.model

/**
 * 成绩查分器数据源。
 *
 * **两个游戏各自独立选择**（舞萌选水鱼、中二选落雪完全合法），因此偏好键也分开存，
 * 见 [com.kira.kiba.core.utils.SpUtil.getScoreSource] / [com.kira.kiba.core.utils.SpUtil.setScoreSource]。
 *
 * ## 两源的本质差异（决定了为什么必须能切）
 * - **水鱼 DivingFish**：舞萌侧最全（全量 records、机台更新链路）；中二侧只有账号密码 jwt
 *   通道，且 `player/records` 只回约 30 条去重 Best —— **当前版本 B20 根本不在响应里**，
 *   没挤进 Top30 的新曲本地无从得知。
 * - **落雪 LXNS**：中二侧 `player/bests` 直接返回官方分池 `bests` / `selections` /
 *   `new_bests`（当前版本 B20），是全量成绩 + 当前版本枠唯一可靠来源；舞萌侧同样是全量。
 *
 * 所以「当前版本 B20 空数据」不是本地算错，是水鱼源给不出这份数据 —— 切落雪即可解决。
 */
enum class ScoreSource(
    /** 持久化键（写进 SharedPreferences，改动即等于让老用户掉回默认值，别乱改）。 */
    val key: String,
    /** 设置页显示名。 */
    val label: String,
    /** 紧凑场景（徽章 / 卡片副标题）用的短名。 */
    val shortLabel: String,
) {
    WATER_FISH("waterfish", "水鱼查分器", "水鱼"),
    LXNS("lxns", "落雪查分器", "落雪"),
    ;

    companion object {
        /** 未知 / 空值一律回落水鱼，保证老用户升级后行为不变。 */
        fun fromKey(key: String?): ScoreSource =
            entries.firstOrNull { it.key == key } ?: WATER_FISH
    }
}
