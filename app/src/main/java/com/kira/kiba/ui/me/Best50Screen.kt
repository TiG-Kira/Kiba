package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.launch
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.InfoPill
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Best 50 页（官方口径 sd 35 + dx 15，ra 之和即 DX Rating）。
 * 数据源：绑定查分器后水鱼服务端下发的官方 B50；未绑定时引导去「我」页面授权。
 *
 * 每行是可点击的歌曲卡：点击进入该歌曲的歌曲详情页。
 */
@Composable
fun Best50Screen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
    onSaveImage: suspend () -> String? = { null },
) {
    val b50 by produceState(initialValue = DivingFishAccount.Best50()) {
        value = DivingFishAccount.best50()
    }
    val account by DivingFishAccount.state.collectAsState()
    val old = b50.old
    val new = b50.new
    val total = b50.rating

    // ── 保存图片到相册 ──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }

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
            if (b50.old.isEmpty() && b50.new.isEmpty()) {
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
                item(key = "save") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    saving -> "正在生成图片…"
                                    saveMsg != null -> saveMsg!!
                                    else -> "生成 Best 50 图片"
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = if (saveMsg?.contains("失败") == true) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (saving) "生成中" else "保存到相册",
                                enabled = !saving && (old.isNotEmpty() || new.isNotEmpty()),
                                onClick = {
                                    if (!saving) {
                                        saving = true
                                        saveMsg = null
                                        scope.launch {
                                            val msg = onSaveImage()
                                            saveMsg = msg ?: "生成失败：缺少成绩数据"
                                            saving = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
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
                                    text = "旧曲 ${old.size}（ra ${b50.oldRa}） + 新曲 ${new.size}（ra ${b50.newRa}）",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "旧 ${old.size} / 新 ${new.size}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                if (new.isNotEmpty()) {
                    item(key = "new_title") {
                        Text(
                            text = "新曲 ${new.size}（当前版本，Best15）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    new.forEachIndexed { index, r ->
                        item(key = "b50_new_$index") {
                            Best50Row(
                                rank = index + 1,
                                record = r,
                                tag = "新",
                                onClick = { onOpenSong(r.songId) },
                            )
                        }
                    }
                }
                if (old.isNotEmpty()) {
                    item(key = "old_title") {
                        Text(
                            text = "旧曲 ${old.size}（Best35）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    old.forEachIndexed { index, r ->
                        item(key = "b50_old_$index") {
                            Best50Row(
                                rank = index + 1,
                                record = r,
                                tag = "旧",
                                onClick = { onOpenSong(r.songId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Best 50 行：序号 + 歌名 + 徽标行（难度 / 达成率 / 新旧 tag）+ ra。
 * 点击进入歌曲详情；徽标用 FlowRow，宽度不足自动换行不挤到卡片边缘。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Best50Row(rank: Int, record: RecordEntity, tag: String, onClick: () -> Unit) {
    val diffColor = difficultyColor(
        DifficultyType.entries.getOrElse(record.levelIndex) { DifficultyType.UTAGE }
    )
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
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
                    maxLines = 2,
                )
                Spacer(Modifier.height(3.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.kira.kiba.ui.common.DifficultyChip(
                        text = "${difficultyLabel(record.levelIndex)} ${record.level}",
                        color = diffColor,
                    )
                    InfoPill("%.2f%%".format(record.achievements))
                    InfoPill(tag)
                }
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
 *
 * 每行是可点击的歌曲卡：点击进入该歌曲的歌曲详情页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordsScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenSong(r.songId) },
                    ) {
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
                                    maxLines = 2,
                                )
                                Spacer(Modifier.height(3.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    com.kira.kiba.ui.common.DifficultyChip(
                                        text = "${difficultyLabel(r.levelIndex)} ${r.level}",
                                        color = difficultyColor(
                                            DifficultyType.entries.getOrElse(r.levelIndex) {
                                                DifficultyType.UTAGE
                                            }
                                        ),
                                    )
                                    InfoPill("%.2f%%".format(r.achievements))
                                    InfoPill("DX ${r.dxScore}")
                                    if (r.fc.isNotBlank()) InfoPill("FC ${r.fc}")
                                }
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
