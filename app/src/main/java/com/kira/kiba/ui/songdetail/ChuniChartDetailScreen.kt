package com.kira.kiba.ui.songdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * 中二节奏谱面详情页（精简版，对标舞萌 [ChartDetailScreen] 但仅保留中二节奏关心的信息）：
 * - 谱面头：难度 / 定数 / 歌曲名 / 谱师 / 最大连击；
 * - 我的成绩（若有）：分数（0–1009000）/ 连击 / 本谱面 Rating 贡献；
 * - 谱面信息：定数 / 等级 / 谱师 / 最大连击。
 *
 * 中二节奏 music_data 不提供 notes 细分，故不展示音符构成 / 失分表 / 单曲 Rating 档位表。
 * 中二节奏不支持分享，本页不提供分享入口（由 MainScreen 顶栏逻辑统一控制）。
 */
@Composable
fun ChuniChartDetailScreen(
    songId: Int,
    difficultyOrdinal: Int,
    scrollBehavior: ScrollBehavior,
) {
    val db = remember { AppDataBase.getInstance() }

    val song by produceState<ChuniSongEntity?>(initialValue = null) {
        value = runCatching { db.chuniSongWithChartDao().getChuniSongById(songId) }.getOrNull()
    }
    val chart by produceState<ChuniChartEntity?>(initialValue = null) {
        value = runCatching {
            db.chuniSongWithChartDao().getChuniChartsBySongId(songId)
                .firstOrNull { it.difficultyIndex == difficultyOrdinal }
        }.getOrNull()
    }
    val records by remember(songId) {
        db.chuniSongWithChartDao().getChuniRecordsBySongId(songId).asFlow()
    }.collectAsState(initial = emptyList())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val currentChart = chart

        if (currentChart == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = pageContentPadding(withNavBar = false),
            ) {
                item { EmptyStateCard(text = "谱面加载中…") }
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
            // ── 谱面头 ──
            item(key = "head") {
                ChuniChartHeaderCard(chart = currentChart, song = song)
            }

            // ── 我的成绩（紧跟谱面信息）──
            val record = records.firstOrNull { it.levelIndex == difficultyOrdinal }
            record?.let { r ->
                item(key = "record_title") { SectionTitle("我的成绩") }
                item(key = "record") { ChuniRecordCard(chart = currentChart, record = r) }
            }

            // ── 谱面信息 ──
            item(key = "info_title") { SectionTitle("谱面信息") }
            item(key = "info") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        InfoRow("定数", if (currentChart.ds > 0.0) "%.1f".format(currentChart.ds) else "-")
                        InfoRow("等级", currentChart.level)
                        InfoRow("谱师", currentChart.charter)
                        InfoRow("最大连击", "${currentChart.combo}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChuniChartHeaderCard(chart: ChuniChartEntity, song: ChuniSongEntity?) {
    val diffColor = chuniDifficultyColor(chart.difficultyIndex)
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChuniDifficultyChip(
                    text = "${chuniDifficultyLabel(chart.difficultyIndex)} ${chart.level}",
                    color = diffColor,
                )
                HGap()
                Text(
                    text = if (chart.ds > 0.0) "定数 %.1f".format(chart.ds) else "定数 -",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            VGap(6.dp)
            song?.let {
                Text(
                    text = "${it.title}　${it.artist}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                VGap(2.dp)
            }
            Text(
                text = "谱师：${chart.charter}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            VGap(2.dp)
            Text(
                text = "最大连击：${chart.combo}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ChuniRecordCard(chart: ChuniChartEntity, record: ChuniRecordEntity) {
    val diffColor = chuniDifficultyColor(chart.difficultyIndex)
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chuniScoreText(record.score),
                    style = MiuixTheme.textStyles.title1,
                    color = diffColor,
                )
            }
            // 成绩的下一行：连击徽章 + 本谱面 Rating 贡献
            VGap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                chuniFcDisplay(record.fc)?.let { label ->
                    ChuniFcBadge(label)
                    HGap(6.dp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Rating ${record.ra}",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            VGap(4.dp)
            Text(
                text = "本谱面 Rating 贡献 ${record.ra}　定数 %.1f".format(chart.ds),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
