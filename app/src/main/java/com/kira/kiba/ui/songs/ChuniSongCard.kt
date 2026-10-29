package com.kira.kiba.ui.songs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.chuniDifficultyLabel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 中二节奏歌曲卡片（对标舞萌 [SongCard]）：封面 + 标题 + 副标题（曲师 · 流派）+
 * 各难度等级（BASIC/ADVANCED/EXPERT/MASTER/ULTIMA）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChuniSongCard(
    song: ChuniSongWithCharts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = (modifier.fillMaxWidth()),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
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
}
