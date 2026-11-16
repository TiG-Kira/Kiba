package com.kira.kiba.ui.songs

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.KibaIconTint
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.chuniDifficultyLabel
import com.kira.kiba.ui.common.copyTextToClipboard
import com.kira.kiba.ui.common.kibaIconTint
import com.kira.kiba.ui.common.kibaPressScale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
// 通配符导入：Regular.Info / Regular.Copy 等为同包扩展属性，单导入 MiuixGlassIcons 无法解析
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 中二节奏歌曲卡片（对标舞萌 [SongCard]）：封面 + 标题 + 副标题（曲师 · 流派）+
 * 各难度等级（BASIC/ADVANCED/EXPERT/MASTER/ULTIMA）。
 *
 * 长按弹出小菜单（与舞萌卡片同款 miuix [OverlayListPopup]）：只有
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
    val context = LocalContext.current
    var menuVisible by remember { mutableStateOf(false) }

    fun copyTitle() {
        val ok = context.copyTextToClipboard(song.song.title, label = "kiba_chuni_song")
        Toast.makeText(
            context,
            if (ok) "已复制「${song.song.title}」" else "复制失败",
            Toast.LENGTH_SHORT,
        ).show()
    }

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
                    onLongClick = { menuVisible = true },
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

        // ── 长按小菜单（miuix OverlayListPopup：经根 Scaffold 弹层宿主渲染，不被列表裁剪）──
        OverlayListPopup(
            show = menuVisible,
            onDismissRequest = { menuVisible = false },
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
        ) {
            // 中二节奏无歌单 / 本地播放：只保留详情与复制两项
            val menuActions = listOf(
                SongCardMenuAction(
                    text = "显示歌曲详情",
                    icon = MiuixGlassIcons.Regular.Info,
                    tint = KibaIconTint.BLUE,
                ) {
                    menuVisible = false
                    onClick()
                },
                SongCardMenuAction(
                    text = "复制歌曲名",
                    icon = MiuixGlassIcons.Regular.Copy,
                    tint = KibaIconTint.CYAN,
                ) {
                    menuVisible = false
                    copyTitle()
                },
            )
            ListPopupColumn {
                menuActions.forEachIndexed { index, action ->
                    DropdownImpl(
                        item = DropdownItem(
                            text = action.text,
                            icon = { m ->
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    tint = kibaIconTint(action.tint),
                                    modifier = m,
                                )
                            },
                        ),
                        optionSize = menuActions.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = { action.onClick() },
                    )
                }
            }
        }
    }
}
