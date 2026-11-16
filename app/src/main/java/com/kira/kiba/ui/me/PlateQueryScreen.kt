package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.stats.PlateProgress
import com.kira.kiba.core.stats.PlateProgress.PlateKind
import com.kira.kiba.ui.common.ComboBadge
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.InfoPill
import com.kira.kiba.ui.common.RankBadge
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.SyncBadge
import com.kira.kiba.ui.common.UnplayedPill
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 名牌进度查询页（参考 EasyMai achievement-progress「牌子进度」）：
 *
 * - **版本总览**：舞代（旧框聚合）+ 各版本的四牌总进度（极 / 将 / 神，旧框另含霸者）；
 * - **筛选**：版本（舞代 / 各版本，新版本在前）、牌子（极 / 将 / 神 / 舞舞 / 霸者，
 *   霸者仅舞代可选）、难度（所有难度 / Basic…Re:Master，EasyMai 口径）；
 * - **明细**：当前筛选下的进度 + 歌曲（按难度降序分组，组内按定数降序），
 *   已达成显示官方贴图徽章，未达成显示「未达成」标记；可开关「隐藏已达成」。
 *
 * 判定口径与 [PlateProgress]（极 = FC 及以上、将 = SSS 及以上、神 = AP、
 * 舞舞 = FSD+、霸者 = 达成率 > 80% 仅旧框）。
 */
@Composable
fun PlateQueryScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    val cache = PlateQueryStateCache

    // ── 版本组 + 总览（曲库 / 成绩变化即重算）──
    val summaries by produceState<List<PlateProgress.GroupSummary>>(initialValue = emptyList()) {
        value = PlateProgress.loadOverview()
    }
    val groups = remember(summaries) { summaries.map { it.group } }

    // ── 筛选状态（进程级快照：返回不丢）──
    // 默认舞萌DX 2024（双宴）：EasyMai 默认最新已有版本，取曲库里默认存在的较新年度版
    var groupKey by rememberSaveable { mutableStateOf(cache.groupKey) }
    var plateOrdinal by rememberSaveable { mutableStateOf(cache.plateOrdinal) }
    var difficultyOrdinal by rememberSaveable { mutableStateOf(cache.difficultyOrdinal) }
    var hideAchieved by rememberSaveable { mutableStateOf(cache.hideAchieved) }

    val selectedGroup = remember(groups, groupKey) {
        groups.firstOrNull { it.key == groupKey } ?: groups.firstOrNull()
    }
    val plate = PlateKind.entries.getOrElse(plateOrdinal) { PlateKind.FC }
    val difficulty = if (difficultyOrdinal < 0) {
        null
    } else {
        DifficultyType.entries.getOrNull(difficultyOrdinal)
    }

    // ── 当前筛选下的明细 ──
    val detail by produceState<List<PlateProgress.Item>>(
        initialValue = emptyList(),
        selectedGroup, difficulty,
    ) {
        val g = selectedGroup
        value = if (g == null) emptyList() else PlateProgress.loadDetail(g, difficulty)
    }

    // 牌子筛选跟随组切换：非舞代组不支持霸者（EasyMai 强制切舞代口径，这里改为锁定不可选）
    val plateOptions = remember(selectedGroup) {
        if (selectedGroup?.isOldFrame == true) PlateKind.entries else PlateKind.entries - PlateKind.MASTER
    }
    val effectivePlate = if (plate in plateOptions) plate else PlateKind.FC

    // 当前进度（作用于明细，与列表口径一致）
    val currentProgress = remember(detail, effectivePlate) {
        PlateProgress.Progress(
            done = detail.count { it.isDone(effectivePlate) },
            total = detail.size,
        )
    }

    // 按等级分组（level 降序，[PlateProgress.levelRank] 保证 14+ > 14）
    val levelGroups = remember(detail, hideAchieved, effectivePlate) {
        detail.asSequence()
            .filter { !hideAchieved || !it.isDone(effectivePlate) }
            .groupBy { it.chart.level }
            .map { (level, items) -> level to items }
            .sortedByDescending { (level, _) -> PlateProgress.levelRank(level) }
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
            // ── 版本总览 ──
            item(key = "overview_title") { SectionTitle("版本总览 · 点击行查看该版本明细") }
            if (summaries.isEmpty()) {
                item(key = "overview_empty") {
                    EmptyStateCard(
                        text = "暂无数据\n先在曲目页同步曲库、并绑定查分器拉取成绩",
                    )
                }
            } else {
                item(key = "overview_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            summaries.forEachIndexed { index, summary ->
                                OverviewRow(
                                    summary = summary,
                                    selected = summary.group.key == selectedGroup?.key,
                                    onClick = {
                                        groupKey = summary.group.key
                                        PlateQueryStateCache.groupKey = summary.group.key
                                    },
                                )
                                if (index != summaries.lastIndex) {
                                    Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── 筛选 ──
            if (groups.isNotEmpty()) {
                item(key = "filter_title") { SectionTitle("筛选") }
                item(key = "filter_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        OverlaySpinnerPreference(
                            items = remember(groups) {
                                groups.map { DropdownItem(text = "${it.displayName}（${it.plateCode}）") }
                            },
                            selectedIndex = groups.indexOfFirst { it.key == selectedGroup?.key }
                                .coerceAtLeast(0),
                            title = "版本",
                            summary = selectedGroup?.let { "${it.displayName}（${it.plateCode}）" } ?: "全部",
                            onSelectedIndexChange = { index ->
                                groups.getOrNull(index)?.let {
                                    groupKey = it.key
                                    PlateQueryStateCache.groupKey = it.key
                                }
                            },
                        )
                        OverlaySpinnerPreference(
                            items = remember(plateOptions) {
                                plateOptions.map { DropdownItem(text = it.label) }
                            },
                            selectedIndex = plateOptions.indexOf(effectivePlate).coerceAtLeast(0),
                            title = "牌子",
                            summary = effectivePlate.label + if (effectivePlate == PlateKind.MASTER) {
                                "（仅旧框 · 达成率 > 80%）"
                            } else {
                                ""
                            },
                            onSelectedIndexChange = { index ->
                                plateOrdinal = (plateOptions.getOrNull(index) ?: PlateKind.FC).ordinal
                                PlateQueryStateCache.plateOrdinal = plateOrdinal
                            },
                        )
                        OverlaySpinnerPreference(
                            items = listOf("所有难度", "Basic", "Advanced", "Expert", "Master", "Re:Master")
                                .map { DropdownItem(text = it) },
                            selectedIndex = difficultyOrdinal + 1,
                            title = "难度",
                            summary = if (difficulty == null) "所有难度"
                            else difficultyLabel(difficulty),
                            onSelectedIndexChange = { index ->
                                difficultyOrdinal = index - 1
                                PlateQueryStateCache.difficultyOrdinal = difficultyOrdinal
                            },
                        )
                        SwitchPreference(
                            checked = hideAchieved,
                            onCheckedChange = {
                                hideAchieved = it
                                PlateQueryStateCache.hideAchieved = it
                            },
                            title = "隐藏已达成",
                            summary = "只看还没打满条件的谱面（EasyMai 同款开关）",
                        )
                    }
                }

                // ── 当前进度 ──
                item(key = "progress_title") { SectionTitle("当前进度") }
                item(key = "progress_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${selectedGroup?.displayName ?: "—"} · " +
                                        "${selectedGroup?.plateCode ?: ""} ${effectivePlate.label}牌",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${currentProgress.done} / ${currentProgress.total}",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            ProgressBar(fraction = currentProgress.fraction)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${currentProgress.percentText} · " +
                                    "共 ${currentProgress.total} 张谱面（" +
                                    (difficulty?.let { "${difficultyLabel(it)}" } ?: "所有难度") + "）",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                // ── 明细列表（按等级降序分组，组内定数降序）──
                if (levelGroups.isEmpty()) {
                    item(key = "detail_empty") {
                        EmptyStateCard(
                            text = if (hideAchieved && detail.isNotEmpty()) {
                                "该条件下全部谱面都已达成 🎉"
                            } else {
                                "当前筛选下没有谱面\n试试换个版本 / 难度"
                            },
                        )
                    }
                } else {
                    levelGroups.forEach { (level, rows) ->
                        item(key = "level_$level") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Lv.$level",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "(${rows.size} 首)",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        items(
                            count = rows.size,
                            key = { "song_${rows[it].song.id}_${rows[it].chart.difficultyType.ordinal}" },
                        ) { index ->
                            PlateItemRow(
                                item = rows[index],
                                plate = effectivePlate,
                                onClick = { onOpenSong(rows[index].song.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 版本总览行：版本名（代号）+ 极 / 将 / 神（旧框含霸者）四段迷你进度。 */
@Composable
private fun OverviewRow(
    summary: PlateProgress.GroupSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(132.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.group.plateCode,
                    style = MiuixTheme.textStyles.body1,
                    color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary.group.displayName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniProgress(PlateKind.FC.label, summary.fc, Modifier.weight(1f))
            MiniProgress(PlateKind.SSS.label, summary.sss, Modifier.weight(1f))
            MiniProgress(PlateKind.AP.label, summary.ap, Modifier.weight(1f))
            if (summary.group.isOldFrame) {
                MiniProgress(PlateKind.MASTER.label, summary.master, Modifier.weight(1f))
            }
        }
    }
}

/** 总览里的迷你进度：牌名 + 已完成数/总数 + 细进度条。 */
@Composable
private fun MiniProgress(label: String, progress: PlateProgress.Progress, modifier: Modifier = Modifier) {
    val accent = when (label) {
        PlateKind.FC.label -> Color(0xFF3D9BE9)
        PlateKind.SSS.label -> Color(0xFFF2B53C)
        PlateKind.AP.label -> Color(0xFFE8547E)
        else -> Color(0xFF9F51DC)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${progress.done}/${progress.total}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}

/** 明细条目：封面（难度描边）+ 歌名 + 难度/等级/定数 + 牌子达成徽章或「未达成」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlateItemRow(
    item: PlateProgress.Item,
    plate: PlateKind,
    onClick: () -> Unit,
) {
    val done = item.isDone(plate)
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverFrame(
                songId = item.song.id,
                imageUrl = item.song.imageUrl,
                genre = item.song.genre,
                size = 56.dp,
                strokeColor = difficultyColor(item.chart.difficultyType),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.song.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InfoPill(difficultyLabel(item.chart.difficultyType))
                    InfoPill("Lv.${item.chart.level}")
                    InfoPill("定数 ${item.chart.ds}")
                    if (plate == PlateKind.MASTER && item.record != null) {
                        InfoPill("%.4f%%".format(item.record.achievements))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右侧：已达成显示对应官方贴图，未达成显示灰色标记
            when {
                plate == PlateKind.MASTER -> if (done) {
                    RankBadge(rate = item.record?.rate ?: "d", height = 30.dp)
                } else {
                    UnplayedPill()
                }
                plate == PlateKind.FC -> if (done) {
                    ComboBadge(fc = item.record?.fc ?: "fc", height = 30.dp)
                } else {
                    UnplayedPill()
                }
                plate == PlateKind.AP -> if (done) {
                    ComboBadge(fc = item.record?.fc ?: "ap", height = 30.dp)
                } else {
                    UnplayedPill()
                }
                plate == PlateKind.FSD -> if (done) {
                    SyncBadge(fs = item.record?.fs ?: "fsd", height = 30.dp)
                } else {
                    UnplayedPill()
                }
                // 将：评级贴图（SSS / SSS+）
                else -> if (done) {
                    RankBadge(rate = item.record?.rate ?: "sss", height = 30.dp)
                } else {
                    UnplayedPill()
                }
            }
        }
    }
}

/** 细进度条（与曲目页同步进度条同款视觉）。 */
@Composable
private fun ProgressBar(fraction: Float) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 240),
        label = "plateProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

/**
 * 名牌进度页状态快照（进程级）：返回时还原筛选状态，行为对齐 ScoreQueryStateCache。
 */
object PlateQueryStateCache {
    var groupKey: String = "OLD"
    var plateOrdinal: Int = PlateKind.FC.ordinal
    var difficultyOrdinal: Int = 3 // 默认 Master（EasyMai currentDiffIndex=4 → Master）
    var hideAchieved: Boolean = false
}
