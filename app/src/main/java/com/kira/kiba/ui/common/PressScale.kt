package com.kira.kiba.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kira.kiba.ui.theme.KibaMotion

/**
 * 按压缩放反馈（HyperOS / miuix Card 同款手感）：按下轻缩、抬手弹簧回位。
 *
 * miuix 的 [top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink] 只对 `Card` 生效；
 * 网格封面这类自己用 `clickable` 拼的格子拿不到，就用这个修饰符补上——
 * 两处观感统一（缩放幅度、时长一致），点下去有「按到了」的反馈而不是干点一下。
 *
 * 用法：`Modifier.kibaPressScale(interactionSource)`，同一个 `interactionSource`
 * 必须传给 `clickable(interactionSource = ...)`，否则读不到按下态。
 */
@Composable
fun Modifier.kibaPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.95f,
): Modifier {
    // 低分设备（<15 分）连按压缩放都关掉：缩放每帧都要 relayout，
    // 在本来就吃力的机器上是纯负担
    if (!KibaMotion.animationsEnabled) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // 按下要跟手（快），回弹略慢一点才不显得弹跳
        animationSpec = tween(durationMillis = if (pressed) 90 else 180),
        label = "kibaPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
