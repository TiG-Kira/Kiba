package com.kira.kiba.ui.songs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.SongCardMenuHost
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.chuniDifficultyLabel
import com.kira.kiba.ui.common.kibaPressScale
import com.kira.kiba.ui.common.rememberSongCardMenuState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 中二节奏歌曲卡片（对标舞萌 [SongCard]）：封面 + 标题 + 副标题（曲师 · 流派）+
 * 各难度等级（BASIC/ADVANCED/EXPERT/MASTER/ULTIMA）。
 *
 * 长按弹出小菜单（共享 [SongCardMenuHost]，与舞萌卡片同款）：只有
 * 「显示歌曲详情 / 复制歌曲名」两项——中二节奏没有歌单与本地播放能力，
 * 「播放歌曲 / 加入歌单」不提供。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChuniSongCard(
    song: ChuniSongWithCharts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menu = rememberSongCardMenuState()

    Box(modifier = modifier.fillMaxWidth()) {
        // 按压缩放 + 单击 / 长按共用一个 interactionSource（miuix Card 的 onClick
        // 只有单击语义，长按必须自带 combinedClickable；Sink 反馈用 kibaPressScale 补）
        val interaction = remember { MutableInteractionSource() }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .kibaPressScale(interaction)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        menu.show(song.song.id, song.song.title) { onClick() }
                    },
                ),
        ) {
            BasicComponent(
                startAction = {
                    ChuniCoverFrame(
                        songId = song.song.id,
                        genre = song.song.genre,
                        size = 64.dp,
                    )
                },
                bottomAction = {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        song.charts.sortedBy { it.difficultyIndex }.forEach { chart ->
                            ChuniDifficultyChip(
                                text = "${chuniDifficultyLabel(chart.difficultyIndex)} ${chart.level}",
                                color = chuniDifficultyColor(chart.difficultyIndex),
                            )
                        }
                    }
                },
            ) {
                Text(
                    text = song.song.title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                // 副标题：曲师 · 流派
                Text(
                    text = buildString {
                        val artist = song.song.artist.takeIf { it.isNotBlank() && it != "-" }
                        if (artist != null) {
                            append(artist)
                            append(" · ")
                        }
                        append(song.song.genre)
                    },
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 长按小菜单（共享宿主；中二无歌单 / 本地播放，只保留详情与复制两项）──
        SongCardMenuHost(menu, includePlayAndPlaylist = false)
    }
}
