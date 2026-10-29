package com.kira.kiba.core.model

/**
 * 游戏类型开关：舞萌 DX（MAIMAI）与中二节奏（CHUNITHM）。
 *
 * 用作全应用级的「当前游戏」状态：曲目页顶部分段开关、我页面 Rating、
 * 收藏页、歌曲/谱面详情、Best 页、VPN 上传都据此切换数据源与渲染。
 * 持久化在 [com.kira.kiba.core.utils.SpUtil]（KEY_GAME_TYPE）。
 */
enum class GameType(val key: String) {
    MAIMAI("maimai"),
    CHUNITHM("chunithm");

    companion object {
        fun fromKey(key: String?): GameType = when (key) {
            "chunithm" -> CHUNITHM
            else -> MAIMAI
        }
    }
}
