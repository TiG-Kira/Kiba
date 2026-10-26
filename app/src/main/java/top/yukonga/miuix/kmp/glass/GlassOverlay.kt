// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop

/** Default values for [GlassDialog]. */
object GlassOverlayDefaults {

    /** Corner radius of a dialog. */
    val DialogCornerRadius: Dp = 28.dp

    /** Opacity of the scrim behind a dialog in a light theme. */
    val ScrimAlphaLight: Float = 0.3f

    /** Opacity of the scrim behind a dialog in a dark theme. */
    val ScrimAlphaDark: Float = 0.6f
}

/**
 * A dialog on glass, over a scrim.
 *
 * @param visible Whether the dialog is showing.
 * @param onDismissRequest Called when the scrim is tapped.
 * @param backdrop The [Backdrop] supplying the content behind the glass.
 * @param modifier The modifier applied to the box holding the scrim and the panel.
 * @param scrimColor Colour of the scrim behind the panel.
 * @param scrimAlpha Opacity of the scrim once the dialog has settled.
 * @param style The glass material.
 * @param shape The dialog's silhouette.
 * @param alpha Opacity multiplier for the material.
 * @param shadow The shadow under the dialog. `null` removes it.
 * @param stroke Optional bloom stroke along the rim.
 * @param content The dialog's content.
 */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter,
    scrimColor: Color = Color.Black,
    scrimAlpha: Float = GlassOverlayDefaults.ScrimAlphaLight,
    style: GlassStyle = GlassDefaults.Style,
    shape: GlassShape = GlassShape(GlassOverlayDefaults.DialogCornerRadius),
    alpha: Float = 1f,
    stroke: GlassStroke? = null,
    shadow: GlassShadow? = GlassShadows.ExtraHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 延迟一帧的可见态：保证 AnimatedVisibility 始终经历 false→true 过渡，进入动画才会播放。
    // 若直接以 visible 驱动，弹层首次组合时 visible 已为 true，AnimatedVisibility 判定为「首帧即可见」、不播 enter。
    val animVisible = remember { mutableStateOf(false) }
    LaunchedEffect(visible) { animVisible.value = visible }
    val scrim = animateFloatAsState(
        targetValue = if (animVisible.value) scrimAlpha else 0f,
        animationSpec = if (animVisible.value) GlassMotion.dialogEnter() else GlassMotion.dialogExit(),
        label = "glassDialogScrim",
    )
    val active by remember(visible, scrim) {
        derivedStateOf { visible || scrim.value > 0.001f }
    }
    rememberGlassPopupBackProgress(
        show = visible,
        active = active,
        enabled = visible,
        retainWhenInactive = false,
        resetSpec = GlassMotion.dialogExit(),
        onDismissRequest = onDismissRequest,
    )

    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrimColor.copy(alpha = scrim.value)) }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        // 与 miuix 原生 OverlayDialog 对齐：手机（非大屏）走底部滑入，大屏传 Center 时退化为中心缩放。
        val isBottomAttached = alignment == Alignment.BottomCenter
        AnimatedVisibility(
            visible = animVisible.value,
            modifier = Modifier
                .align(alignment)
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = if (isBottomAttached) 12.dp else 0.dp,
                )
                .let { m ->
                    val withIme = m.imePadding()
                    if (isBottomAttached) withIme.navigationBarsPadding() else withIme
                },
            enter = if (isBottomAttached) {
                slideInVertically(
                    animationSpec = GlassMotion.dialogEnter(),
                    initialOffsetY = { it },
                ) + fadeIn(animationSpec = GlassMotion.fadeIn())
            } else {
                scaleIn(
                    animationSpec = GlassMotion.dialogEnter(),
                    initialScale = 0.86f,
                ) + fadeIn(animationSpec = GlassMotion.fadeIn())
            },
            exit = if (isBottomAttached) {
                slideOutVertically(
                    animationSpec = GlassMotion.dialogExit(),
                    targetOffsetY = { it },
                ) + fadeOut(animationSpec = GlassMotion.fadeOut())
            } else {
                scaleOut(
                    animationSpec = GlassMotion.dialogExit(),
                    targetScale = 0.9f,
                ) + fadeOut(animationSpec = GlassMotion.fadeOut())
            },
        ) {
            Column(
                modifier = Modifier
                    .glassPanel(
                        backdrop = backdrop,
                        shape = shape,
                        style = style,
                        alpha = alpha,
                        stroke = stroke,
                        shadow = shadow,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { /* Consume taps inside the dialog. */ }
                    }
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
