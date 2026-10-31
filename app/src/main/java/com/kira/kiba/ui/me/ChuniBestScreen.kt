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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.data.ChuniStats
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
 * 中二节奏 Best 50（官方 VERSE 起口径：**非当前版本 TOP30 + 当前版本 TOP20**，分母 50；不支持分享）。
 *
 * ## 数据与口径
 * - 成绩来自本地 `chuni_record`（[com.kira.kiba.core.data.ChuniRecordSync] 从水鱼
 *   chunithmprober/player/records 拉取）；谱面版本来自 `chuni_chart.version`
 *   （曲库同步时由落雪打标）。
 * - Rating / 两个枠的均值一律由 [ChuniStats] **从 Room 现算**，不再读同步时写死的
 *   SharedPreferences 缓存 —— 缓存值会在「成绩先同步、曲库后打标」或「曲库重导入」后
 *   永久失真（表现就是「当前 TOP20 均值 0.00」）。
 * - 两个枠**分开成组展示**：非当前版本 TOP30 一组、当前版本 TOP20 一组，各自按 ra 降序，
 *   与官方 Rating 的取数完全一致。
 * - World's End 不参与官方 Rating，列表与头部数字一并排除。
 */
@Composable
fun ChuniBestScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit,
) {
    // 成绩 + 谱面版本 → 统计结果（IO 线程读库）。以成绩同步状态为 key：
    // 页面停留期间同步完成（Syncing → Done）会自动重算，不用退出重进。
    val syncState by ChuniRecordSync.state.collectAsState()
    val loaded by produceState<ChuniStats.Result?>(initialValue = null, syncState) {
        val result = runCatching { ChuniStats.compute() }.getOrNull()
        // 顺手回写 SpUtil：我页面的 Rating 徽章读的是缓存，保持与这里同一套数
        if (result != null) runCatching { ChuniStats.persist(result) }
        value = result
    }
    val nickname = SpUtil.getChuniNickname()

    val stats = loaded
    val rating = stats?.rating ?: 0.0

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
                                append("官方 Rating 口径 · 本地成绩 ${stats?.recordCount ?: 0} 条")
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        VGap(4.dp)
                        // 两个枠各报各的均值与条数：空枠显示「—」，不再用 0.00 冒充均值
                        Text(
                            text = "非当前版本 TOP30 均值 " +
                                poolAverageText(stats?.nonCurrentAverage, stats?.nonCurrent?.size ?: 0) +
                                " · 当前版本 TOP20 均值 " +
                                poolAverageText(stats?.currentAverage, stats?.current?.size ?: 0),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (stats != null && !stats.hasVersionInfo) {
                            VGap(6.dp)
                            Text(
                                text = "曲库暂时没有谱面版本标记，无法区分当前 / 非当前版本，" +
                                    "Rating 先按历史最佳 TOP30 均值展示。更新一次中二曲库后会自动恢复官方口径。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            if (stats == null || stats.isEmpty) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = if (stats == null) {
                            "正在读取中二节奏成绩…"
                        } else {
                            "还没有中二成绩数据\n请在「我」页面同步中二成绩后重试"
                        },
                    )
                }
            } else {
                // ── 枠一：非当前版本 TOP30 ──
                if (stats.nonCurrent.isNotEmpty()) {
                    item(key = "title_non_current") {
                        SectionTitle("非当前版本 TOP30（${stats.nonCurrent.size}）")
                    }
                    stats.nonCurrent.forEachIndexed { index, record ->
                        item(key = "non_current_${record.songId}_${record.levelIndex}") {
                            ChuniBestRow(
                                rank = index + 1,
                                record = record,
                                onClick = { onOpenSong(record.songId) },
                            )
                        }
                    }
                }
                // ── 枠二：当前版本 TOP20 ──
                if (stats.current.isNotEmpty()) {
                    item(key = "title_current") {
                        SectionTitle("当前版本 TOP20（${stats.current.size}）")
                    }
                    stats.current.forEachIndexed { index, record ->
                        item(key = "current_${record.songId}_${record.levelIndex}") {
                            ChuniBestRow(
                                rank = index + 1,
                                record = record,
                                onClick = { onOpenSong(record.songId) },
                            )
                        }
                    }
                } else if (stats.hasVersionInfo) {
                    item(key = "current_hint") {
                        EmptyStateCard(
                            text = "当前版本还没有成绩\n新版本曲目打出成绩后会自动进入这一枠（20 个名额）",
                        )
                    }
                }
            }

            item(key = "footer") {
                Text(
                    text = "Rating 按官方 VERSE 起口径本地计算：非当前版本 TOP30 + 当前版本 TOP20，分母 50",
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

/** 枠均值文案：有成绩才显示两位小数，空枠显示「—」（避免把「没有数据」误读成 0.00）。 */
private fun poolAverageText(average: Double?, count: Int): String =
    if (average == null || count <= 0) "—" else "${chuniRatingText(average)}（$count 首）"

/** Best 单条：排名 + 封面 + 歌名 + 难度 + 分数 / ra / FC 徽章。 */
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
