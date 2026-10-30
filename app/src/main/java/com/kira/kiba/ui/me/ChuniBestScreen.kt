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
import com.kira.kiba.core.data.ChuniRating
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.ChuniFcBadge
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.chuniRatingText
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
 * 中二节奏 Best 页（按官方 VERSE 起口径：非当前版本 TOP30 + 当前版本 TOP20，分母 50；**不支持分享**）。
 *
 * 数据源：本地 chuni_record（由 [com.kira.kiba.core.data.ChuniRecordSync] 从水鱼
 * chunithmprober/player/records 拉取，谱面版本来自曲库同步时的落雪打标）。头部卡展示 Rating / 昵称 / 成绩条数。
 *
 * **Rating 由本地按官方 VERSE 起公式自算**（[ChuniRating]）：(非当前 TOP30 之和 + 当前 TOP20 之和) / 50，
 * 两位小数截断。水鱼服务端 rating 把 r10 当 0 会整体腰斩（机台 14.x 只给 7.x），故不取服务端值；
 * 曲库未同步落雪（无 version）时退化为 best30 均值托底。
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
    val recentCount = remember { SpUtil.getChuniRecentCount() }
    val nonCurrentAvg = remember { SpUtil.getChuniNonCurrentAvg() }
    val currentAvg = remember { SpUtil.getChuniCurrentAvg() }
    // Best 枠与 Recent 枠在库里用 is_recent 区分：列表只展示历史最佳（Best），
    // 否则最近一次手滑的低分会混进 Best。水鱼 2026 后 r10 恒空 → 全量即 Best。
    // World's End 不参与官方 Rating，列表与头部数字保持一致一并排除。
    val pool = remember(records) {
        val best = records.filter { !it.isRecent && it.levelIndex != ChuniDiffType.WORLD_END.ordinal }
        if (best.isNotEmpty()) best else records.filter { it.levelIndex != ChuniDiffType.WORLD_END.ordinal }
    }
    val sorted = remember(pool) { pool.sortedByDescending { it.ra } }
    val best50 = remember(sorted) { sorted.take(50) }

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
                            text = chuniRatingText(rating),
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
                                append("官方 Rating 口径 · 本地成绩 ${pool.size} 条")
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        VGap(4.dp)
                        Text(
                            text = "非当前 TOP30 均值 ${chuniRatingText(nonCurrentAvg)}" +
                                " · 当前 TOP20 均值 ${chuniRatingText(currentAvg)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (best50.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "还没有中二成绩数据\n请在「我」页面同步中二成绩后重试",
                    )
                }
            } else {
                item(key = "title") { SectionTitle("Best 50 · 官方口径 TOP50（非当前30 + 当前20）") }
                best50.forEachIndexed { index, record ->
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
                    text = "Rating 按官方 VERSE 起口径本地计算：非当前 TOP30 + 当前 TOP20，分母 50",
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
                        text = "ra ${chuniRatingText(record.ra)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
