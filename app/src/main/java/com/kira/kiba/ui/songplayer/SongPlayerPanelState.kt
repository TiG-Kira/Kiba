package com.kira.kiba.ui.songplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 乐曲播放页的页内 UI 面板状态。
 *
 * 为什么放在独立 object 而不是 [com.kira.kiba.core.playback.SongPlaybackController]：
 * 这是纯 UI 状态（「播放列表面板是否展开」），不参与播放、进程级单例即可；
 * 但它被两棵兄弟子树共享——页面主体（SongPlayerScreen 的列表卡）和
 * KibaMainScreen 提供的顶栏（收窄 + 改标题），CompositionLocal 跨不过兄弟树，
 * Controller 里混 UI 状态又会污染播放状态的语义。
 */
object SongPlayerPanelState {

    private val _queueVisible = MutableStateFlow(false)
    val queueVisible: StateFlow<Boolean> = _queueVisible.asStateFlow()

    /** 点播放列表按钮：切换面板显隐（顶栏随之前后折叠）。 */
    fun toggle() {
        _queueVisible.value = !_queueVisible.value
    }

    fun setVisible(visible: Boolean) {
        _queueVisible.value = visible
    }
}
