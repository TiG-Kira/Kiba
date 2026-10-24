package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.stats.MaimaiStats
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 版本进度页（对标 MaimaiData VersionCheckActivity）：
 * 各版本（maimai / DELUXE / …）歌曲的收集 / 游玩比例。
 * 需要曲库数据；绑定查分器后「已游玩」按真实成绩统计。
 */
@Composable
fun VersionProgressScreen(scrollBehavior: ScrollBehavior) {
    ProgressScreen(
        scrollBehavior = scrollBehavior,
        title = "版本进度",
        load = { MaimaiStats.versionProgress() },
        emptyHint = "曲库还是空的\n请先到「设置 → 曲库更新」同步曲库",
    )
}

/**
 * 等级进度页（对标 MaimaiData LevelCheckActivity）：
 * 各难度等级（7 / 7+ / 8 … 14+ / 15）的谱面游玩情况。
 */
@Composable
fun LevelProgressScreen(scrollBehavior: ScrollBehavior) {
    ProgressScreen(
        scrollBehavior = scrollBehavior,
        title = "等级进度",
        load = { MaimaiStats.levelProgress() },
        emptyHint = "曲库还是空的\n请先到「设置 → 曲库更新」同步曲库",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressScreen(
    scrollBehavior: ScrollBehavior,
    title: String,
    load: suspend () -> List<MaimaiStats.ProgressItem>,
    emptyHint: String,
) {
    val state = produceState(initialValue = emptyList<MaimaiStats.ProgressItem>()) {
        value = load()
    }
    val items = state.value
    val account by DivingFishAccount.state.collectAsState()
    val totalPlayed = items.sumOf { it.played }
    val totalCharts = items.sumOf { it.total }

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
            item(key = "summary") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = title,
                            style = MiuixTheme.textStyles.headline1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (totalCharts > 0) {
                                "已游玩 $totalPlayed / $totalCharts 张谱面" +
                                    if (account.bound) "" else "（未绑定查分器，仅统计曲库）"
                            } else {
                                "暂无统计数据"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            JudgePill("极 FC", items.sumOf { it.fc })
                            JudgePill("将 SSS+", items.sumOf { it.sss })
                            JudgePill("神 AP", items.sumOf { it.ap })
                            JudgePill("FDX", items.sumOf { it.fdx })
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") { EmptyStateCard(text = emptyHint) }
            }
            items.forEach { item ->
                item(key = "${title}_${item.key}") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = item.key,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${item.played} / ${item.total}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            ProgressBar(ratio = item.ratio)
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                JudgePill("已游玩", item.played)
                                JudgePill("极 FC", item.fc)
                                JudgePill("将 SSS+", item.sss)
                                JudgePill("神 AP", item.ap)
                                JudgePill("FDX", item.fdx)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** miuix 主色进度条。 */
@Composable
private fun ProgressBar(ratio: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
    ) {
        if (ratio > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

/** 判定维度小胶囊（极 / 将 / 神 / FDX）。 */
@Composable
private fun JudgePill(label: String, count: Int) {
    Box(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$label $count",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
