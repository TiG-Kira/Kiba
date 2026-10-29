package com.kira.kiba.ui.songdetail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.ui.common.ChuniDifficultyChip
import com.kira.kiba.ui.common.ChuniFcBadge
import com.kira.kiba.ui.common.ChuniMetaChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.InfoRow
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
 * 中二节奏歌曲详情页（对标舞萌 [SongDetailScreen]，复用结构换中二节奏数据）。
 *
 * 内容：
 * - 信息列表：ID / BPM / 流派 / 版本（中二节奏无 SD/DX 区分、无 catcode）；
 * - 各难度谱面卡（可点击进谱面详情）：定数 / 谱师 / 连击（替代舞萌的 notes 构成）/
 *   本地成绩（分数 / 连击 / 本谱面 Rating 贡献）；
 * - 中二节奏不支持分享，由 MainScreen 顶栏逻辑统一控制（详情顶栏不提供分享入口）。
 */
@Composable
fun ChuniSongDetailScreen(
    songId: Int,
    scrollBehavior: ScrollBehavior,
    onOpenChart: (songId: Int, difficultyOrdinal: Int) -> Unit,
) {
    val db = remember { AppDataBase.getInstance() }

    // 歌曲 + 谱面：一次性挂载加载（详情页粒度）
    val song by produceState<ChuniSongEntity?>(initialValue = null) {
        value = runCatching { db.chuniSongWithChartDao().getChuniSongById(songId) }.getOrNull()
    }
    val charts by produceState<List<ChuniChartEntity>>(initialValue = emptyList()) {
        value = runCatching { db.chuniSongWithChartDao().getChuniChartsBySongId(songId) }
            .getOrDefault(emptyList())
    }
    // 本地成绩：LiveData → Flow 响应式
    val records by remember(songId) {
        db.chuniSongWithChartDao().getChuniRecordsBySongId(songId).asFlow()
    }.collectAsState(initial = emptyList())

    val currentSong = song
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (currentSong == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = pageContentPadding(withNavBar = false),
            ) {
                item {
                    EmptyStateCard(
                        text = "歌曲加载中…\n若持续无数据，请先到「曲目页」同步中二节奏曲库",
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 信息列表：ID / BPM / 流派 / 版本 ──
            item(key = "tags") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        InfoRow("ID", "$songId")
                        InfoRow("BPM", "${currentSong.bpm}")
                        InfoRow("流派", currentSong.genre)
                        InfoRow("版本", currentSong.from)
                    }
                }
            }

            // ── 谱面 ──
            item(key = "charts_title") { SectionTitle("谱面（${charts.size}）· 点击查看详情") }
            for (chart in charts.sortedBy { it.difficultyIndex }) {
                item(key = "chart_${chart.difficultyIndex}") {
                    ChuniChartCard(
                        chart = chart,
                        record = records.firstOrNull { it.levelIndex == chart.difficultyIndex },
                        onClick = { onOpenChart(currentSong.id, chart.difficultyIndex) },
                    )
                }
            }

            item(key = "footer") {
                Text(
                    text = "song id $songId · 数据来源：水鱼曲库（diving-fish）",
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

/** 单谱面卡（简略）：定数 / 谱师 / 连击 / 本地成绩。点击进谱面详情。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChuniChartCard(
    chart: ChuniChartEntity,
    record: ChuniRecordEntity?,
    onClick: () -> Unit,
) {
    val diffColor = chuniDifficultyColor(chart.difficultyIndex)
    Card(
        modifier = (Modifier.fillMaxWidth()),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 定数行
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChuniDifficultyChip(
                    text = "${chuniDifficultyLabel(chart.difficultyIndex)} ${chart.level}",
                    color = diffColor,
                )
                HGap()
                Text(
                    text = if (chart.ds > 0.0) "定数 %.1f".format(chart.ds) else "定数 -",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
            }
            VGap(8.dp)

            // 谱师
            Text(
                text = "谱师：${chart.charter}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            VGap(8.dp)

            // 等级 / 连击
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChuniMetaChip("等级 ${chart.level}")
                ChuniMetaChip("连击 ${chart.combo}")
            }

            // 本地成绩
            record?.let { r ->
                VGap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chuniScoreText(r.score),
                        style = MiuixTheme.textStyles.title1,
                        color = diffColor,
                    )
                }
                // 成绩的下一行：连击徽章 + 本谱面 Rating 贡献
                VGap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    chuniFcDisplay(r.fc)?.let { label ->
                        ChuniFcBadge(label)
                        HGap(6.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Rating ${r.ra}",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
