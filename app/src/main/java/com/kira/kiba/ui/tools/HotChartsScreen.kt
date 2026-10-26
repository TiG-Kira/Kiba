package com.kira.kiba.ui.tools

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartStatsEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.core.model.DifficultyType
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 热门乐曲排行（工具箱，移植自 EasyMai chart-stats / chartStatsUtils）：
 * 按水鱼谱面统计的游玩样本数（cnt）降序排列，展示全服均分与拟合定数。
 * 点击条目进入歌曲详情页。数据来自本地 chart_stats 表（每 5 天自动同步）。
 */
@Composable
fun HotChartsScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    var ready by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<List<ChartStatsEntity>>(emptyList()) }
    var songsById by remember { mutableStateOf<Map<Int, SongDataEntity>>(emptyMap()) }
    var dsByChart by remember { mutableStateOf<Map<Pair<Int, Int>, Double>>(emptyMap()) }
    var levelByChart by remember { mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val db = AppDataBase.getInstance()
        val st = runCatching { db.chartStatsDao().getAllChartStatsList() }.getOrDefault(emptyList())
        val songs = runCatching { db.songDao().getAllSongsList() }.getOrDefault(emptyList())
        val charts = runCatching { db.chartDao().getAllChartsList() }.getOrDefault(emptyList())
        // 宴会场谱面没有谱面统计意义，排除
        val utageSongIds = songs.filter { it.genre == "宴会場" || it.id >= 100000 }.map { it.id }.toSet()
        stats = st
            .filter { it.songId !in utageSongIds && (it.cnt ?: 0.0) > 0.0 }
            .sortedByDescending { it.cnt ?: 0.0 }
            .take(100)
        songsById = songs.associateBy { it.id }
        dsByChart = charts.associate { it.songId to it.difficultyType.ordinal to it.ds }
        levelByChart = charts.associate { it.songId to it.difficultyType.ordinal to it.level }
        ready = true
    }

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
            item(key = "intro") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "按水鱼查分器谱面游玩样本数排序（每 5 天自动同步）。" +
                                "均分越高说明这张谱面大众打得越好，拟合定数则是水鱼回归出的实际难度。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (ready && stats.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "暂无谱面统计数据\n启动后会自动从水鱼服务端同步（约 1.5MB）",
                        action = null,
                    )
                }
            }
            item(key = "rank_title") { SectionTitle("TOP 100") }
            items(count = stats.size, key = { stats[it].let { c -> "${c.songId}_${c.levelIndex}" } }) { index ->
                val c = stats[index]
                val song = songsById[c.songId]
                val key = c.songId to c.levelIndex
                val ds = dsByChart[key]
                val level = levelByChart[key]
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    onClick = { onOpenSong(c.songId) },
                ) {
                    BasicComponent(
                        startAction = {
                            Text(
                                text = "${index + 1}",
                                style = MiuixTheme.textStyles.title3,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.width(40.dp),
                            )
                            CoverFrame(
                                songId = c.songId,
                                imageUrl = song?.imageUrl ?: "",
                                genre = song?.genre ?: "",
                                size = 52.dp,
                            )
                        },
                        title = song?.title ?: "未知歌曲 (id ${c.songId})",
                        summary = buildString {
                            if (ds != null) append("定数 $ds")
                            c.fitDiff?.let { append(if (isEmpty()) "拟合 $it" else " · 拟合 ${"%.2f".format(it)}") }
                            c.avg?.let { append(if (isEmpty()) "均分 ${"%.2f%%".format(it)}" else " · 均分 ${"%.2f%%".format(it)}") }
                            append(if (isEmpty()) "样本 ${(c.cnt ?: 0.0).toInt()}" else " · 样本 ${(c.cnt ?: 0.0).toInt()}")
                        },
                        endActions = {
                            DifficultyChip(
                                text = difficultyLabel(
                                    DifficultyType.entries.getOrElse(c.levelIndex) { DifficultyType.UTAGE }
                                ),
                                color = difficultyColor(
                                    DifficultyType.entries.getOrElse(c.levelIndex) { DifficultyType.UTAGE }
                                ),
                            )
                        },
                    )
                }
            }
            if (stats.isNotEmpty()) {
                item(key = "footer") {
                    Text(
                        text = "样本数截至最近一次同步 · 数据来源 水鱼查分器",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
