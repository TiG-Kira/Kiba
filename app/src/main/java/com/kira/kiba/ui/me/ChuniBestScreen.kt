package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.ChuniFcBadge
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.VGap
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.chuniDifficultyLabel
import com.kira.kiba.ui.common.chuniFcDisplay
import com.kira.kiba.ui.common.chuniScoreText
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 中二节奏 Best 页（按单曲 ra 降序取前 30；**不支持分享**）。
 *
 * 数据源：本地 chuni_record（由 [com.kira.kiba.core.data.ChuniRecordSync] 从水鱼
 * chunithmprober/player/records 拉取）。头部卡展示官方 Rating / 昵称 / 成绩条数。
 * 中二 2026 版本后 r10（最近游玩）为空数组，Rating 由服务端官方口径给出，本地不再自行计算。
 */
@Composable
fun ChuniBestScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit,
) {
    val db = remember { AppDataBase.getInstance() }
    val records by produceState<List<ChuniRecordEntity>>(initialValue = emptyList()) {
        value = runCatching { db.chuniRecordDao().getAllChuniRecordList() }
            .getOrDefault(emptyList())
    }
    val rating = remember { SpUtil.getChuniRating() }
    val nickname = remember { SpUtil.getChuniNickname() }
    val sorted = remember(records) { records.sortedByDescending { it.ra } }
    val best30 = remember(sorted) { sorted.take(30) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "$rating",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        VGap(4.dp)
                        Text(
                            text = buildString {
                                if (nickname.isNotBlank()) {
                                    append(nickname)
                                    append(" · ")
                                }
                                append("官方 Rating · 本地成绩 ${records.size} 条")
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (best30.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "还没有中二成绩数据\n请在「我」页面同步中二成绩后重试",
                    )
                }
            } else {
                item(key = "title") { SectionTitle("Best 30 · 点击查看歌曲详情") }
                best30.forEachIndexed { index, record ->
                    item(key = "best_${record.songId}_${record.levelIndex}") {
                        ChuniBestRow(
                            rank = index + 1,
                            record = record,
                            onClick = { onOpenSong(record.songId) },
                        )
                    }
                }
            }

            item(key = "footer") {
                Text(
                    text = "Rating 取自水鱼官方口径（chunithmprober）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Best 30 单条：排名 + 封面 + 歌名 + 难度 + 分数 / ra / FC 徽章。 */
@Composable
private fun ChuniBestRow(
    rank: Int,
    record: ChuniRecordEntity,
    onClick: () -> Unit,
) {
    val diffColor = chuniDifficultyColor(record.levelIndex)
    Card(
        modifier = (Modifier.fillMaxWidth()),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.Center,
            )
            ChuniCoverFrame(songId = record.songId, genre = "", size = 52.dp)
            HGap(12.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                VGap(4.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChuniDifficultyChip(
                        text = "${chuniDifficultyLabel(record.levelIndex)} ${record.level}",
                        color = diffColor,
                    )
                    chuniFcDisplay(record.fc)?.let { label ->
                        HGap(6.dp)
                        ChuniFcBadge(label)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = chuniScoreText(record.score),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                VGap(2.dp)
                Box(
                    modifier = Modifier
                        .background(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "ra ${record.ra}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
