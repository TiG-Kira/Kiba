package com.kira.kiba.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.stats.SongRating
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单曲 Rating 计算器（工具箱，移植自 EasyMai rank-calculator）：
 * 输入定数 + 达成率 → 评级 / 乘数 / 单曲 Rating。
 *
 * 口径用本项目 [SongRating]（官方档位表，round 计算口径，与 Best 50 一致），
 * 不用 EasyMai 页面自带的系数表（其中 SSS/SS+ 两档乘数与水鱼口径有出入）。
 * 附完整档位系数表，当前命中的档位高亮。
 */
@Composable
fun RatingCalcScreen(scrollBehavior: ScrollBehavior) {
    var dsInput by remember { mutableStateOf("") }
    var achInput by remember { mutableStateOf("") }

    val ds = dsInput.trim().toDoubleOrNull()
    val achievement = achInput.trim().toDoubleOrNull()
    val valid = ds != null && achievement != null && ds in 1.0..15.0 && achievement in 0.0..101.0

    fun validate(): String? = when {
        ds == null || achievement == null -> null
        ds < 1.0 -> "定数 < 1，rating 要变负数啦"
        ds > 15.0 -> "定数 > 15，那是中二定数"
        achievement > 101.0 -> "达成率溢出了（上限 101%）"
        else -> null
    }
    val error = validate()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "input") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = dsInput,
                            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) dsInput = it },
                            label = "歌曲定数（1.0 ~ 15.0）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = achInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() || c == '.' }) achInput = it
                            },
                            label = "达成率（%，0 ~ 101）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "result_title") { SectionTitle("计算结果") }
            item(key = "result") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            error != null && ds != null && achievement != null -> {
                                Text(
                                    text = error,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                            valid && ds != null && achievement != null -> {
                                val a = achievement.coerceAtMost(100.5)
                                ResultRow("RANK 等级", SongRating.rankOf(a))
                                ResultRow("乘数", "%.3f".format(SongRating.multiplierOf(a) * 100))
                                ResultRow(
                                    "单曲 Rating",
                                    "${SongRating.songRating(ds, achievement)}",
                                    highlight = true,
                                )
                            }
                            else -> {
                                Text(
                                    text = "输入定数与达成率即可计算",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "table_title") { SectionTitle("RANK 系数表") }
            item(key = "table") {
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "单曲 Rating = 定数 × 达成率 × 乘数（SSS+ 封顶 100.5%）",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))
                        TierTableHeader()
                        Spacer(Modifier.height(4.dp))
                        val currentRank = if (valid && achievement != null) {
                            SongRating.rankOf(achievement.coerceAtMost(100.5))
                        } else null
                        SongRating.TIERS.forEach { tier ->
                            TierTableRow(
                                rank = tier.rank,
                                achievement = tier.minAchievement,
                                multiplier = tier.multiplier * 100,
                                highlight = tier.rank == currentRank,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = if (highlight) MiuixTheme.textStyles.title3 else MiuixTheme.textStyles.body1,
            color = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TierTableHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        TableCell("RANK", modifier = Modifier.weight(2f), bold = true)
        TableCell("达成率", modifier = Modifier.weight(3f), bold = true)
        TableCell("乘数", modifier = Modifier.weight(3f), bold = true)
    }
}

@Composable
private fun TierTableRow(rank: String, achievement: Double, multiplier: Double, highlight: Boolean) {
    val color = if (highlight) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        TableCell(rank, modifier = Modifier.weight(2f), color = color, bold = highlight)
        TableCell("%.1f%%".format(achievement), modifier = Modifier.weight(3f), color = color)
        TableCell("%.1f".format(multiplier), modifier = Modifier.weight(3f), color = color)
    }
}

@Composable
private fun TableCell(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.onSurface,
    bold: Boolean = false,
) {
    Text(
        text = text,
        style = if (bold) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote1,
        color = color,
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}
