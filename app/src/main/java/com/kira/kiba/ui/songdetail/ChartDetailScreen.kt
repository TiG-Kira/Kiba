package com.kira.kiba.ui.songdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.flow.flowOf
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.ChartStatsEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.stats.RelativeDifficulty
import com.kira.kiba.core.stats.SongRating
import com.kira.kiba.core.stats.statsLevelIndex
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.VGap
import com.kira.kiba.ui.common.RankBadge
import com.kira.kiba.ui.common.ComboBadge
import com.kira.kiba.ui.common.SyncBadge
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 谱面详情页（对标 MaimaiData SongLevelFragment 的完整版）。
 *
 * 内容：
 * - 谱面头：难度 / 定数（旧定数对比）/ 谱师 / 歌曲名；
 * - notes 构成与判定权重；
 * - **失分表**：Tap / Hold / Slide / Touch 四类 × Perfect(50/100 落) / Great / Good / Miss，
 *   BREAK 单独成表（含 4x / 3x / 2.5x 大 G 分档与 50/100 落保底），
 *   公式与上游 SongLevelFragment 完全一致；
 * - 单曲 Rating 档位表（SSS+ ~ D，公式：round(定数 × 完成度 × 乘数)）；
 * - 本地成绩（达成率 / 单曲 Rating / ra / DX 分 / FC / FS）。
 */
@Composable
fun ChartDetailScreen(
    songId: Int,
    difficultyOrdinal: Int,
    scrollBehavior: ScrollBehavior,
) {
    val db = remember { AppDataBase.getInstance() }

    val song by produceState<SongDataEntity?>(initialValue = null) {
        value = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }
            .getOrNull()
    }
    val chart by produceState<ChartEntity?>(initialValue = null) {
        value = runCatching {
            db.chartDao().getChartsBySongId(songId)
                .firstOrNull { it.difficultyType.ordinal == difficultyOrdinal }
        }.getOrNull()
    }
    val records by remember(songId) {
        db.recordDao().getRecordsBySongId(songId).asFlow()
    }.collectAsState(initial = emptyList())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val currentChart = chart

        // 拟合定数（水鱼 chart_stats）：相对难度数据源；未同步时为 null → 显示「暂时缺少数据」
        val chartStats by remember(currentChart) {
            val idx = currentChart?.difficultyType?.statsLevelIndex() ?: -1
            if (idx < 0) {
                flowOf<ChartStatsEntity?>(null)
            } else {
                db.chartStatsDao()
                    .getChartStatsBySongIdAndDifficultyIndex(songId, idx)
                    .asFlow()
            }
        }.collectAsState(initial = null)

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
                ChartHeaderCard(chart = currentChart, song = song, fitDiff = chartStats?.fitDiff)
            }

            // ── notes 构成 ──
            item(key = "notes_title") { SectionTitle("谱面构成") }
            item(key = "notes") { NotesCard(chart = currentChart) }

            // ── 失分表（Tap/Hold/Slide/Touch）──
            item(key = "loss_title") { SectionTitle("判定失分表（Perfect / Great / Good / Miss）") }
            item(key = "loss") { LossTableCard(chart = currentChart) }

            // ── BREAK 专用表 ──
            if (currentChart.notesBreak > 0) {
                item(key = "break_title") { SectionTitle("BREAK 判定表") }
                item(key = "break") { BreakLossCard(chart = currentChart) }
            }

            // ── 单曲 Rating 档位 ──
            item(key = "rating_title") { SectionTitle("单曲 Rating（定数 × 完成度 × 乘数）") }
            item(key = "rating") { RatingTableCard(chart = currentChart) }

            // ── 本地成绩 ──
            val record = records.firstOrNull { it.levelIndex == difficultyOrdinal }
            record?.let { r ->
                item(key = "record_title") { SectionTitle("我的成绩") }
                item(key = "record") { RecordCard(chart = currentChart, record = r) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartHeaderCard(chart: ChartEntity, song: SongDataEntity?, fitDiff: Double? = null) {
    val diffColor = difficultyColor(chart.difficultyType)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DifficultyChip(
                    text = "${difficultyLabel(chart.difficultyType)} ${chart.level}",
                    color = diffColor,
                )
                HGap()
                Text(
                    text = "定数 ${chart.ds}",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                chart.oldDs?.let { old ->
                    val (arrow, color) = when {
                        old < chart.ds -> "↑ 上调" to MiuixTheme.colorScheme.primary
                        old > chart.ds -> "↓ 下调" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else -> "· 持平" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Text(
                        text = "$arrow（原 $old）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = color,
                    )
                }
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
            VGap(2.dp)
            Text(
                text = "相对难度：${RelativeDifficulty.display(chart.ds, fitDiff)}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            VGap(2.dp)
            Text(
                text = "谱师：${chart.charter}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesCard(chart: ChartEntity) {
    val totalScore = totalScoreOf(chart)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPill("总 ${chart.notesTotal}")
                InfoPill("TAP ${chart.notesTap}")
                InfoPill("HOLD ${chart.notesHold}")
                InfoPill("SLIDE ${chart.notesSlide}")
                if (chart.notesTouch > 0) InfoPill("TOUCH ${chart.notesTouch}")
                InfoPill("BREAK ${chart.notesBreak}")
            }
            VGap(8.dp)
            Text(
                text = "判定权重总分 $totalScore（TAP/TOUCH×1 + HOLD×2 + SLIDE×3 + BREAK×5）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 普通 note 失分表：Perfect 无失分；Great = 权重/总分×0.2；Good = ×0.5；Miss = 全额。
 */
@Composable
private fun LossTableCard(chart: ChartEntity) {
    val ts = totalScoreOf(chart).toDouble()
    val rows = listOf(
        LossRow("TAP", chart.notesTap, 1.0, ts),
        LossRow("HOLD", chart.notesHold, 2.0, ts),
        LossRow("SLIDE", chart.notesSlide, 3.0, ts),
        LossRow("TOUCH", chart.notesTouch, 1.0, ts),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            LossTableHeader()
            rows.forEach { row ->
                VGap(6.dp)
                LossTableRow(
                    label = row.label,
                    count = row.count,
                    perfect = "0",
                    great = pct(row.weight / ts * 0.2),
                    good = pct(row.weight / ts * 0.5),
                    miss = pct(row.weight / ts),
                )
            }
            VGap(8.dp)
            Text(
                text = "单位为达成率失分百分比；Perfect（50/100 落）无失分。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * BREAK 判定表（上游口径）：
 * - 50 落 = 0.01/nb×0.25、100 落 = 0.01/nb×0.5（该 BREAK 仍保有的达成占比）
 * - 大 G 分档：4x = 5/ts×0.2 + 0.01/nb×0.6；3x = 5/ts×0.4 + …；2.5x = 5/ts×0.5 + …
 * - Good = 5/ts×0.6 + 0.01/nb×0.7；Miss = 5/ts + 0.01/nb
 */
@Composable
private fun BreakLossCard(chart: ChartEntity) {
    val ts = totalScoreOf(chart).toDouble()
    val nb = chart.notesBreak.toDouble()
    val fix = 0.01 / nb
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            LossRowLine(
                label = "50 落",
                value = pct(fix * 0.25),
                hint = "该 BREAK 保底达成占比",
            )
            LossRowLine(
                label = "100 落",
                value = pct(fix * 0.5),
                hint = "Perfect 满分达成占比",
            )
            LossRowLine(
                label = "大 G（4x / 3x / 2.5x）",
                value = "${pct(5.0 / ts * 0.2 + fix * 0.6)} / ${pct(5.0 / ts * 0.4 + fix * 0.6)} / ${pct(5.0 / ts * 0.5 + fix * 0.6)}",
                hint = "Great 三档",
            )
            LossRowLine(
                label = "Good",
                value = pct(5.0 / ts * 0.6 + fix * 0.7),
                hint = "",
            )
            LossRowLine(
                label = "Miss（断）",
                value = pct(5.0 / ts + fix),
                hint = "断 1 个 BREAK 的失分",
            )
            VGap(6.dp)
            Text(
                text = "BREAK 每个满判定额外给 1% 达成加成（0.01/n），断掉则该加成与权重一并损失。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 单曲 Rating 档位表：各档完成度下的单曲 Rating。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingTableCard(chart: ChartEntity) {
    val ds = chart.ds
    val tiers = listOf(
        "SSS+" to 100.5, "SSS" to 100.0, "SS+" to 99.5, "SS" to 99.0,
        "S+" to 98.0, "S" to 97.0, "AAA" to 94.0, "AA" to 90.0,
        "A" to 80.0, "BBB" to 75.0, "BB" to 70.0, "B" to 60.0,
        "C" to 50.0, "D" to 40.0,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tiers.forEach { (rank, achievement) ->
                    InfoPill("$rank ${SongRating.songRating(ds, achievement)}")
                }
            }
            VGap(8.dp)
            Text(
                text = "Rating = round(定数 × 完成度 × 乘数)，完成度封顶 100.5%（SSS+）。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun RecordCard(chart: ChartEntity, record: RecordEntity) {
    val diffColor = difficultyColor(chart.difficultyType)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%.4f%%".format(record.achievements),
                    style = MiuixTheme.textStyles.title1,
                    color = diffColor,
                )
            }
            // 成绩的下一行：评级 / 连击 / 同步贴图 + 本曲 Rating
            VGap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankBadge(rate = record.rate.ifBlank { SongRating.rankOf(record.achievements) })
                HGap(6.dp)
                ComboBadge(fc = record.fc)
                if (record.fs.isNotBlank()) HGap(6.dp)
                SyncBadge(fs = record.fs)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "获得的本曲 Rating ${record.ra}",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            VGap(4.dp)
            Text(
                text = "单曲 Rating ${SongRating.songRating(chart.ds, record.achievements)}" +
                    "　DX 分数 ${record.dxScore} / ${chart.notesTotal * 3}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

// ── 表格基础组件 ──

private data class LossRow(val label: String, val count: Int, val weight: Double, val totalScore: Double)

/** 判定权重总分：TAP/TOUCH×1 + HOLD×2 + SLIDE×3 + BREAK×5（上游口径）。 */
private fun totalScoreOf(chart: ChartEntity): Int =
    (chart.notesTap + chart.notesTouch) + chart.notesHold * 2 +
        chart.notesSlide * 3 + chart.notesBreak * 5

/** 百分比格式化（4 位小数）。 */
private fun pct(value: Double): String = "%.4f%%".format(value * 100)

@Composable
private fun LossTableHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "类型",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = "Perfect",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
        Text(
            text = "Great",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
        Text(
            text = "Good",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
        Text(
            text = "Miss",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun LossTableRow(
    label: String,
    count: Int,
    perfect: String,
    great: String,
    good: String,
    miss: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(52.dp)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (count > 0) {
                Text(
                    text = "×$count",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        CellText(perfect, MiuixTheme.colorScheme.onSurfaceVariantSummary)
        CellText(great, MiuixTheme.colorScheme.onSurface)
        CellText(good, MiuixTheme.colorScheme.onSurface)
        CellText(miss, MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun RowScope.CellText(value: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = value,
        style = MiuixTheme.textStyles.footnote1,
        color = color,
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.End,
    )
}

@Composable
private fun LossRowLine(label: String, value: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** 小信息胶囊。 */
@Composable
private fun InfoPill(text: String) {
    Box(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
