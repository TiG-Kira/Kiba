// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AndroidLiquidGlass demo（`catalog/utils/DampedDragAnimation.kt`）的等价实现。
 *
 * 底栏胶囊的动画状态机：
 * - [value]：胶囊位置（单位 = tab 下标），阻尼临界弹簧驱动（dampingRatio = 1，无过冲——
 *   "液体"的过冲感来自拉伸与 [InteractiveHighlight]，而不是位置弹跳）；
 * - [velocity]：暴露给拉伸公式（`scaleX /= 1 - v*0.75`，速度越快拉得越长）；
 * - [pressProgress]：按压进度 0..1，玻璃折射强度、高光、内阴影、放大全部乘它——
 *   **静止时玻璃效果为 0，只有灰色提示**，动起来才"活"；
 * - [scaleX] / [scaleY]：`lerp(initialScale, pressedScale, pressProgress)`，
 *   demo 的 pressedScale = 78f/56f ≈ 1.39，按住时胶囊明显鼓起；
 * - [modifier]：拖动手势（`detectDragGestures`），拖动中 `updateValue` 跟手。
 *
 * 点击/程序化切换走 [animateToValue]：先把 pressProgress 拉到 1（玻璃浮现），
 * 位置动画到位后再落回 0（玻璃淡出、凝回灰色提示）——酷安点击切换有完整玻璃
 * 特效而 demo 只有拖动有，这一步是把两者行为对齐的关键。
 */
class DampedDragAnimation(
    animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    val onDragStarted: () -> Unit = {},
    val onDragStopped: () -> Unit = {},
    val onDrag: (position: Offset, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val scope = animationScope

    private val position = Animatable(initialValue)
    private val press = Animatable(0f)

    /** 胶囊当前位置（tab 下标，可为小数 = 正在两 tab 之间）。 */
    val value: Float get() = position.value

    /** 动画目标位置。 */
    val targetValue: Float get() = position.targetValue

    /** 当前速度（tab/秒），拉伸公式用。 */
    val velocity: Float get() = position.velocity

    /** 按压进度 0..1。 */
    val pressProgress: Float get() = press.value

    /** 按压缩放 X（= Y，等比鼓起）。 */
    val scaleX: Float get() = lerp(initialScale, pressedScale, press.value)
    val scaleY: Float get() = lerp(initialScale, pressedScale, press.value)

    /** 拖动手势：按下鼓起、拖动跟手、松手触发 [onDragStopped]（吸附逻辑由调用方做）。 */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { offset ->
                onDragStarted()
                scope.launch { press.animateTo(1f, spring(1f, Spring.StiffnessMediumLow)) }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                onDrag(change.position, dragAmount)
            },
            onDragEnd = {
                onDragStopped()
                scope.launch { press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow)) }
            },
            onDragCancel = {
                onDragStopped()
                scope.launch { press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow)) }
            },
        )
    }

    /** 拖动中直接跟手（不播动画）。 */
    fun updateValue(target: Float) {
        val coerced = target.coerceIn(valueRange.start, valueRange.endInclusive)
        scope.launch {
            position.snapTo(coerced)
        }
    }

    /**
     * 程序化切换：玻璃浮现（press→1）→ 位置到位 → 玻璃淡出（press→0）。
     * visibilityThreshold 很小（demo 用 0.001f），跨一个 tab 就算"动了"。
     */
    suspend fun animateToValue(target: Float) {
        val coerced = target.coerceIn(valueRange.start, valueRange.endInclusive)
        if (abs(position.value - coerced) < visibilityThreshold) {
            position.snapTo(coerced)
            return
        }
        press.animateTo(1f, spring(1f, Spring.StiffnessMediumLow))
        position.animateTo(
            coerced,
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        )
        press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow))
    }
}
