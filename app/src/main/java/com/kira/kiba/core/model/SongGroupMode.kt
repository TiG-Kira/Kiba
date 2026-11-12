package com.kira.kiba.core.model

/**
 * 曲目列表归类方式。
 *
 * - [NONE] 不归类（默认）：按现有排序方式平铺展示；
 * - [VERSION] 按版本：显示版本大类（如「舞萌DX 2024」「CHUNITHM SUN」），
 *   **两个游戏都支持**，分组按版本时间轴从新到旧排列（见 [SongVersionOrder]）；
 * - [CHARTER] 按 MASTER 谱师：显示该曲 MASTER 难度谱面的谱师名（仅舞萌）；
 * - [ARTIST] 按曲师：显示歌曲艺术家（仅舞萌）。
 */
enum class SongGroupMode(val key: String, val label: String) {
    NONE("none", "不归类"),
    VERSION("version", "按版本"),
    CHARTER("charter", "按 MASTER 谱师"),
    ARTIST("artist", "按曲师"),
    ;

    companion object {
        fun fromKey(key: String?): SongGroupMode =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}
