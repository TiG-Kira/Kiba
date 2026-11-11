package com.kira.kiba.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局动画闸门（设备性能评分驱动）。
 *
 * ## 为什么要有它
 * 真·高级材质那条 blur + 多重采样的渲染链在低端机上本来就吃力，再叠一层转场动画
 * 就是「点一下卡两下」。设备评分低于 [ANIMATION_FLOOR_SCORE] 时直接关掉 App 侧的
 * 转场 / 按压反馈动画 —— 功能一个不少，只是不再有位移与缩放。
 *
 * ## 口径
 * - 只关**装饰性**动画：页面转场、顶栏下沉、按压缩放、程序化折叠。
 * - 不关**功能性**动画：进度条、加载圈的转圈（那是「正在干活」的唯一反馈，
 *   关掉会让人以为卡死）、以及 miuix 控件内部的涟漪/开关位移动画。
 *
 * 用 `mutableStateOf` 而不是 `@Volatile`：重新评分后用户可能立刻返回上一页，
 * 读 state 的调用方（转场 spec、按压修饰符）会在下一帧拿到新值。
 */
object KibaMotion {

    /** 动画下限分：低于此分彻底关掉装饰性动画（与材质门槛 PASS_SCORE=55 分开）。 */
    const val ANIMATION_FLOOR_SCORE = 15

    /** 当前是否允许装饰性动画。 */
    var animationsEnabled by mutableStateOf(true)
        private set

    /** 用设备评分同步闸门（MainActivity 启动 / 设置页重新评分后调用）。 */
    fun syncFromScore(score: Int) {
        animationsEnabled = score >= ANIMATION_FLOOR_SCORE
    }

    /** 动画时长：关闭时返回 0（tween(0) 会在下一帧直接落终值）。 */
    fun millis(base: Int): Int = if (animationsEnabled) base else 0

    /**
     * 弹簧 / 自定义 spec 的闸门：关闭时换成 [snap] 直接落终值。
     * 弹簧没有时长可调，只能整条换掉。
     */
    fun <T> snapOr(spec: AnimationSpec<T>): AnimationSpec<T> =
        if (animationsEnabled) spec else snap()
}
