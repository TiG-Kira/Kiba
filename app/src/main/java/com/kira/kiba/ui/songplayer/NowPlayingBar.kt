package com.kira.kiba.ui.songplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.common.CoverFrame
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassShadows
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.Dp

/** 播放底栏悬浮在主屏幕时，页面列表底部需要额外让出的高度（胶囊 58dp + 与导航栏的间距 8dp）。 */
val NOW_PLAYING_BAR_CLEARANCE: Dp = 66.dp

/**
 * 曲目页「正在播放」悬浮底栏（miuix-glass 玻璃胶囊）。
 *
 * 由 KibaMainScreen 在 bottomBar 槽渲染（曲目 Tab 专属）：玻璃取景层记录的是
 * 内容 Box 子树，底栏在槽内渲染在取景层之外——既透出页面内容，又不会把自己
 * 采样进背景造成反射成环（与 GlassNavigationBar 同款布局约束）。
 *
 * 「真·高级材质」关闭时回落实色胶囊（与顶栏/弹层的回落口径一致）。
 */
@Composable
fun NowPlayingBar(
    state: SongPlaybackController.PlayerState,
    backdrop: Backdrop?,
    onOpenPlayer: () -> Unit,
    backgroundBoard: Color? = null,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    // 胶囊轮廓（与玻璃导航栏同款 percent-50 圆角）
    val shape = GlassShape(29.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(
                if (backdrop != null) {
                    // 与 GlassNavigationBar 完全同款参数：style 用同一常量
                    // （此前误用 style(isDark) 导致两栏透明度不一致）
                    Modifier.then(
                        if (backgroundBoard != null) {
                            Modifier.background(backgroundBoard, RoundedCornerShape(percent = 50))
                        } else Modifier,
                    )
                        .glassPanel(
                            backdrop = backdrop,
                            shape = shape,
                            style = GlassDefaults.Style,
                            material = GlassMaterials.puredThinGlass(isDark),
                            stroke = GlassStrokes.forTheme(isDark),
                            shadow = GlassShadows.Float,
                            shading = false,
                        )
                } else {
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MiuixTheme.colorScheme.surface)
                },
            )
            .clip(shape)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverFrame(
                songId = track.songId,
                imageUrl = track.imageUrl,
                genre = track.genre,
                size = 40.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
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
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable { SongPlaybackController.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.playing) MiuixGlassIcons.Regular.Pause else MiuixGlassIcons.Regular.Play,
                    contentDescription = if (state.playing) "暂停" else "播放",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}
