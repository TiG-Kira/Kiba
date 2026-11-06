// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.shapes.Capsule
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.songplayer.PlayerBarSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 液态玻璃播放底栏高度（比柔光玻璃+ 的 58dp 更厚一档，胶囊形体才对称）。 */
val LiquidNowPlayingBarHeight = 64.dp

/**
 * 液态玻璃版「正在播放」悬浮底栏（独立实现，不共用 miuix glass 的管线条）。
 *
 * 与柔光玻璃+ 版（`NowPlayingBar`）的差别：
 * - 形体：64dp 胶囊，封面用 12dp 圆角方块（不是胶囊），播放键是独立的半透明圆钮；
 * - 材质：`liquidPillEffects`（vibrancy + blur(6dp) + lens(18dp,20dp, chromaticAberration)），
 *   边缘能看到厚玻璃的色散分离，是液态玻璃最直观的识别点；
 * - 投影与高光沿用液态玻璃统一口径（24dp 投影 + 0.5dp 细高光）。
 */
@Composable
fun LiquidNowPlayingBar(
    state: PlayerBarSnapshot,
    backdrop: Backdrop?,
    onOpenPlayer: () -> Unit,
    backgroundBoard: Color? = null,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val container = remember(isDark, backgroundBoard) {
        backgroundBoard ?: if (isDark) {
            Color(0xFF121212).copy(alpha = 0.40f)
        } else {
            Color(0xFFFAFAFA).copy(alpha = 0.40f)
        }
    }
    val buttonSurface = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)
    }
    val shape: () -> Shape = remember { { Capsule() } }
    val effects: BackdropEffectScope.() -> Unit = remember { { liquidPillEffects() } }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val onDrawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(container) { { drawRect(container) } }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LiquidNowPlayingBarHeight)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface, Capsule())
                },
            )
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onOpenPlayer,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverFrame(
            songId = track.songId,
            imageUrl = track.imageUrl,
            genre = track.genre,
            size = 44.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    state.error != null -> state.error
                    state.ended -> "列表已播完 · 点播放重播"
                    !state.prepared -> "加载音源中…"
                    else -> track.artist
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(Capsule())
                .background(buttonSurface)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                ) { SongPlaybackController.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.playing) {
                    MiuixGlassIcons.Regular.Pause
                } else {
                    MiuixGlassIcons.Regular.Play
                },
                contentDescription = if (state.playing) "暂停" else "播放",
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
