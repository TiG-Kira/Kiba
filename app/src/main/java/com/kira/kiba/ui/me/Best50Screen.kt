package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.core.model.DifficultyType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Best 50 页（官方口径 sd 35 + dx 15，ra 之和即 DX Rating）。
 * 数据源：绑定查分器后水鱼服务端下发的官方 B50；未绑定时引导去「我」页面授权。
 */
@Composable
fun Best50Screen(scrollBehavior: ScrollBehavior) {
    val records by produceState(initialValue = emptyList<RecordEntity>()) {
        value = DivingFishAccount.best50()
    }
    val account by DivingFishAccount.state.collectAsState()
    val sd = remember(records) { records.take(35) }
    val dx = remember(records) { records.drop(35).take(15) }
    val total = records.sumOf { it.ra }

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
            if (records.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = if (account.bound) {
                            "还没有成绩数据\n可在「我」页面下拉刷新，或稍后重试"
                        } else {
                            "查看 Best 50 需要先绑定查分器\n请到「我」页面通过水鱼 OAuth 完成授权"
                        },
                    )
                }
            } else {
                item(key = "summary") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "DX Rating $total",
                                    style = MiuixTheme.textStyles.headline1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "新曲 35（ra ${sd.sumOf { it.ra }}） + 旧曲 15（ra ${dx.sumOf { it.ra }}）",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "sd ${sd.size} / dx ${dx.size}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                items@ records.forEachIndexed { index, r ->
                    item(key = "b50_$index") {
                        Best50Row(rank = index + 1, record = r, isDx = index >= 35)
                    }
                }
            }
        }
    }
}

@Composable
private fun Best50Row(rank: Int, record: RecordEntity, isDx: Boolean) {
    val diffColor = difficultyColor(
        DifficultyType.entries.getOrElse(record.levelIndex) { DifficultyType.UTAGE }
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "[${difficultyLabel(record.levelIndex)} ${record.level}] " +
                        "%.4f%%".format(record.achievements) +
                        if (isDx) "　· 旧曲" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            HGap()
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${record.ra}",
                    style = MiuixTheme.textStyles.subtitle,
                    color = diffColor,
                )
                Text(
                    text = "ra",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 成绩记录页（查分器更新）：全量成绩 + 顶栏刷新按钮。
 * 刷新 = [com.kira.kiba.core.state.KibaState.refreshRecords]（水鱼服务端重新拉取）。
 */
@Composable
fun RecordsScreen(scrollBehavior: ScrollBehavior) {
    val account by DivingFishAccount.state.collectAsState()
    val records by produceState(initialValue = emptyList<RecordEntity>()) {
        value = runCatching {
            com.kira.kiba.core.db.AppDataBase.getInstance().recordDao().getAllRecordList()
        }.getOrDefault(emptyList())
    }
    // 账号刷新成功后重新读库（rating / recordCount 变化即触发）
    val refreshKey = account.recordCount
    val refreshed by produceState(initialValue = emptyList<RecordEntity>(), refreshKey) {
        value = runCatching {
            com.kira.kiba.core.db.AppDataBase.getInstance().recordDao().getAllRecordList()
        }.getOrDefault(emptyList())
    }
    val list = refreshed.ifEmpty { records }

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
                            text = if (account.bound) {
                                "成绩 ${list.size} 条 · DX Rating ${account.rating}"
                            } else {
                                "未绑定查分器"
                            },
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (account.loading) {
                                "正在从水鱼服务端刷新…"
                            } else {
                                "点击顶栏刷新按钮可从查分器更新成绩"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "暂无成绩数据\n绑定查分器后点击顶栏刷新即可拉取",
                        action = null,
                    )
                }
            }
            items@ list.sortedByDescending { it.ra }.forEachIndexed { index, r ->
                item(key = "rec_${r.songId}_${r.levelIndex}") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.width(32.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.title,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "[${difficultyLabel(r.levelIndex)} ${r.level}] " +
                                        "%.4f%%".format(r.achievements) +
                                        "　DX ${r.dxScore}" +
                                        if (r.fc.isNotBlank()) "　FC ${r.fc}" else "",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            HGap()
                            Text(
                                text = "${r.ra}",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
