package com.kira.kiba.ui.navigation

import androidx.compose.ui.geometry.Rect

/**
 * 曲目卡片展开式转场的来源矩形（窗口坐标，px）。
 *
 * 歌曲卡片（[com.kira.kiba.ui.songs.SongCard] / [com.kira.kiba.ui.songs.ChuniSongCard]）
 * 在 onClick 里把自己的 boundsInWindow 「offer」进来；[KibaMainScreen.pushPage] 用
 * 「take」一次性取走并清空——只有卡片点击路径紧跟一次 pushPage，所以：
 * - 卡片点击 → take() 返回卡片矩形 → 详情页做展开式转场；
 * - 其他任何入口 push（详情页内播歌卡、Best50 卡、顶栏按钮…）→ take() 返回 null，
 *   同时把上次残留的矩形清掉，不会误用陈旧位置。
 *
 * popPage 不 take：从详情页返回（收缩动画）还需要这份矩形，直到下一次 push 才覆盖。
 */
object PageTransitionSource {

    @Volatile
    private var offered: Rect? = null

    /** 卡片在点击时上报自己的窗口矩形（下一次 pushPage 前有效）。 */
    fun offer(bounds: Rect) {
        offered = bounds
    }

    /** pushPage 取走并清空；没有待消费的矩形时返回 null。 */
    fun take(): Rect? {
        val r = offered
        offered = null
        return r
    }
}
