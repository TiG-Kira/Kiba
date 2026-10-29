package com.kira.kiba.core.model

/**
 * 中二节奏难度类型（5 个标准难度，索引与 water 鱼 music_data 的 charts 数组顺序一致）。
 * World's End（世界终曲）单独成类，不在标准 5 难度序列内。
 *
 * 索引约定（对应 chunithmprober records 的 level_index）：
 * 0 = BASIC / 1 = ADVANCED / 2 = EXPERT / 3 = MASTER / 4 = ULTIMA。
 */
enum class ChuniDiffType {
    BASIC,
    ADVANCED,
    EXPERT,
    MASTER,
    ULTIMA,
    WORLD_END,
    UNKNOWN;

    /** 短标签（UI 展示）。 */
    val label: String
        get() = when (this) {
            BASIC -> "BASIC"
            ADVANCED -> "ADVANCED"
            EXPERT -> "EXPERT"
            MASTER -> "MASTER"
            ULTIMA -> "ULTIMA"
            WORLD_END -> "WORLD'S END"
            UNKNOWN -> "?"
        }

    companion object {
        /** 由 level_index（0..4 标准难度，5 = World's End）映射到难度类型。 */
        fun fromIndex(index: Int): ChuniDiffType = when (index) {
            0 -> BASIC
            1 -> ADVANCED
            2 -> EXPERT
            3 -> MASTER
            4 -> ULTIMA
            5 -> WORLD_END
            else -> UNKNOWN
        }
    }
}
