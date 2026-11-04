package com.kira.kiba.ui.songplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.navigation.LocalSystemNavBarBottom
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 乐曲播放页（曲目列表悬浮底栏 / 曲目详情「播放乐曲」/ 媒体通知 共同的目的地）。
 *
 * 布局：大封面 → 曲名/艺术家 → 可拖进度条（真实时长，音源整轨 mp3）→
 * 上一首 / 播放-暂停 / 下一首（会话队列内移动）。加载中与加载失败各有明确态：
 * 落雪 CDN 部分曲目（宴谱等特殊 id）没有整轨音源，失败时提示而非静音悬挂。
 *
 * 进度条刻意手写（Box + pointerInput）而不是 miuix Slider：需要「拖动中暂停跟随、
 * 松手才 seekTo」的唱片 App 手感，Slider 的 onValueChange 语义贴不上 MediaPlayer 的
 * seek 成本（每次拖动都是一次网络 Range 请求）。
 */
@Composable
fun SongPlayerScreen(
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
) {
    val state by SongPlaybackController.state.collectAsState()
    val track = state.track
    val navBottom = LocalSystemNavBarBottom.current
    // 二级页面顶栏由 KibaMainScreen 提供，页面自己把内容压到 clearance 之下，
    // 否则封面会顶到大标题/返回键背后（首版实况：封面被顶栏盖住半截）。
    val clearance = LocalTopBarClearance.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (track == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = clearance + 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有正在播放的乐曲\n去曲目详情页点「播放乐曲」试试",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = clearance)
                .padding(horizontal = 28.dp)
                .padding(bottom = navBottom + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // ── 大封面（CoverFrame：候选链回退 + 流派描边，与列表/详情页同源）──
            CoverFrame(
                songId = track.songId,
                imageUrl = track.imageUrl,
                genre = track.genre,
                size = 264.dp,
            )

            Spacer(Modifier.height(24.dp))

            // ── 曲名 / 艺术家 ──
            Text(
                text = track.title,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = track.artist,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )

            Spacer(Modifier.height(20.dp))

            // ── 错误态 ──
            state.error?.let { err ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = err,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "音源：落雪 ${track.audioUrl.substringBeforeLast('/')}/",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.weight(1f))

            // ── 进度条 ──
            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                enabled = state.prepared,
                onSeek = { SongPlaybackController.seekTo(it) },
            )
            Spacer(Modifier.height(18.dp))

            // ── 控制区 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlIcon(
                    icon = MiuixGlassIcons.Regular.ChevronBackward,
                    contentDescription = "上一首",
                    enabled = state.queueIndex > 0,
                    size = 44.dp,
                ) { SongPlaybackController.prev() }
                Spacer(Modifier.width(28.dp))
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(RoundedCornerShape(37.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .clickable(enabled = state.prepared) { SongPlaybackController.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.playing) {
                        ControlIcon(
                            icon = MiuixGlassIcons.Regular.Pause,
                            contentDescription = "暂停",
                            enabled = true,
                            size = 40.dp,
                        )
                    } else {
                        ControlIcon(
                            icon = MiuixGlassIcons.Regular.Play,
                            contentDescription = "播放",
                            enabled = true,
                            size = 40.dp,
                        )
                    }
                }
                Spacer(Modifier.width(28.dp))
                ControlIcon(
                    icon = MiuixGlassIcons.Regular.ChevronForward,
                    contentDescription = "下一首",
                    enabled = state.queueIndex < state.queue.lastIndex,
                    size = 44.dp,
                ) { SongPlaybackController.next() }
            }

            if (!state.prepared && state.error == null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "加载音源中…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 控制图标（上一首/下一首；播放键内层复用）。 */
@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit = {},
) {
    val tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

/**
 * 可拖进度条：拖动中只更新本地预览位置（不打 seek），松手才真正 seekTo。
 * duration 未就绪（prepared 前）禁用。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragPos by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val progress = if (durationMs > 0) {
        if (dragging) dragPos / durationMs
        else positionMs.toFloat() / durationMs
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // 触控区（视觉条只有 5dp 高）
                .then(if (enabled) {
                    Modifier
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                            }
                        }
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragging = true
                                    dragPos = (offset.x / size.width * durationMs).coerceIn(0f, durationMs.toFloat())
                                },
                                onDragEnd = {
                                    dragging = false
                                    onSeek(dragPos.toLong().coerceIn(0, durationMs))
                                },
                                onDragCancel = { dragging = false },
                            ) { change, _ ->
                                change.consume()
                                dragPos = (change.position.x / size.width * durationMs)
                                    .coerceIn(0f, durationMs.toFloat())
                            }
                        }
                } else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 底槽
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            )
            // 已播放
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatMs(if (dragging) dragPos.toLong() else positionMs),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMs(durationMs),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
