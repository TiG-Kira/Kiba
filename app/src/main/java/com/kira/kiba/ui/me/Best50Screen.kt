package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
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
 * 歌曲卡与「生成 Best 50 图片」的单曲贴图同一套视觉（见 [Best50Tile]），
 * 按屏幕宽度自适应一行列数；点击进入该歌曲的歌曲详情页。
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

    // DX 满分表（notes 总数 × 3）：贴图右下的 DX 分/满分 与 星标 都要用
    val charts by produceState<List<ChartEntity>>(initialValue = emptyList()) {
        value = runCatching {
            AppDataBase.getInstance().chartDao().getAllChartsList()
        }.getOrDefault(emptyList())
    }
    val fullScoreByKey = remember(charts) {
        charts.associate { (it.songId to it.difficultyType.ordinal) to it.notesTotal * 3 }
    }

    // ── 按屏幕宽度决定一行几列（贴图等比缩放，放大不糊）──
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidthDp >= 1000 -> 4
        screenWidthDp >= 640 -> 3
        screenWidthDp >= 300 -> 2
        else -> 1
    }

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (b50.old.isEmpty() && b50.new.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        text = if (account.bound) {
                            "还没有成绩数据\n可在「我」页面下拉刷新，或稍后重试"
                        } else {
                            "查看 Best 50 需要先绑定查分器\n请到「我」页面通过水鱼 OAuth 完成授权"
                        },
                    )
                }
            } else {
                item(key = "save", span = { GridItemSpan(maxLineSpan) }) {
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
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
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
                    item(key = "new_title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "新曲 ${new.size}（当前版本，Best15）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    itemsIndexed(
                        items = new,
                        key = { _, r -> "b50_new_${r.songId}_${r.levelIndex}" },
                    ) { index, r ->
                        Best50TileCard(
                            rank = index + 1,
                            listSize = new.size,
                            record = r,
                            fullScore = fullScoreByKey[r.songId to r.levelIndex] ?: 0,
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
                if (old.isNotEmpty()) {
                    item(key = "old_title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "旧曲 ${old.size}（Best35）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    itemsIndexed(
                        items = old,
                        key = { _, r -> "b50_old_${r.songId}_${r.levelIndex}" },
                    ) { index, r ->
                        Best50TileCard(
                            rank = index + 1,
                            listSize = old.size,
                            record = r,
                            fullScore = fullScoreByKey[r.songId to r.levelIndex] ?: 0,
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Best 50 贴图卡：单曲贴图（[Best50Tile]）包在可点击卡片里。
 * 点击进入歌曲详情页（与旧版行卡行为一致）；贴图随卡片宽度等比缩放。
 */
@Composable
private fun Best50TileCard(
    rank: Int,
    listSize: Int,
    record: RecordEntity,
    fullScore: Int,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Box(modifier = Modifier.padding(4.dp)) {
            Best50Tile(
                record = record,
                fullScore = fullScore,
                rankNum = rank,
                listSize = listSize,
            )
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
            list.sortedByDescending { it.ra }.forEachIndexed { index, r ->
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
