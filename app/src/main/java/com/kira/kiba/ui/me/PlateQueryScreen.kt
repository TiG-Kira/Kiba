package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.kira.kiba.ui.common.ListSnapshotCache
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.kibaPressScale
import com.kira.kiba.ui.common.SongCardMenuHost
import com.kira.kiba.ui.common.rememberSongCardMenuState
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 名牌进度查询页：
 *
 * - **筛选器置顶**：与成绩查询页同款「展开 / 收起筛选器」开关，默认折叠；
 *   版本（舞代 / 真代 / 各 DX 版本，新版本在前）、牌子（按所选版本给出可选项：
 *   真代无将、霸者仅舞代）、难度、隐藏已达成，以及列表 / 网格显示方式；
 * - **版本进度**：只展示当前选中版本的各牌总进度（大字代号 + 分牌进度条，
 *   留白更宽敞）；真代 = maimai + maimai plus 合并口径；
 * - **明细**：当前筛选下的进度 + 歌曲（按难度降序分组，组内按定数降序），
 *   已达成显示官方贴图徽章，未达成显示「未达成」标记；网格视图为封面格子 + 角标。
 *
 * 判定口径见 [PlateProgress]（极 = FC 及以上、将 = SSS 及以上、神 = AP、
 * 舞舞 = FSD+、霸者 = 达成率 > 80% 仅旧框）。
 */
@Composable
fun PlateQueryScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    val cache = PlateQueryStateCache

    // ── 版本组 + 总览（曲库 / 成绩变化即重算；首帧用快照兜底以恢复滚动位置）──
    val summaries by produceState<List<PlateProgress.GroupSummary>>(
        initialValue = PlateOverviewCache.current,
    ) {
        value = PlateProgress.loadOverview()
    }
    LaunchedEffect(summaries) { PlateOverviewCache.publish(summaries) }
    val groups = remember(summaries) { summaries.map { it.group } }

    // ── 筛选状态（进程级快照：返回不丢）──
    var groupKey by rememberSaveable { mutableStateOf(cache.groupKey) }
    var plateOrdinal by rememberSaveable { mutableStateOf(cache.plateOrdinal) }
    var difficultyOrdinal by rememberSaveable { mutableStateOf(cache.difficultyOrdinal) }
    var hideAchieved by rememberSaveable { mutableStateOf(cache.hideAchieved) }
    var filtersExpanded by rememberSaveable { mutableStateOf(cache.filtersExpanded) }
    var viewMode by rememberSaveable { mutableStateOf(cache.viewMode) }
    var gridColumns by rememberSaveable { mutableStateOf(cache.gridColumns) }

    val selectedGroup = remember(groups, groupKey) {
        groups.firstOrNull { it.key == groupKey } ?: groups.firstOrNull()
    }
    val plate = PlateKind.entries.getOrElse(plateOrdinal) { PlateKind.FC }
    // Re:Master（宗师）只计入舞代口径：非舞代组筛选器不提供宗师档，
    // 选中态回落到大师（数据层 loadDetail 也会再排除一遍，双保险）
    val allowsRemaster = selectedGroup?.isOldFrame != false
    val difficulty = if (difficultyOrdinal < 0) {
        null
    } else {
        DifficultyType.entries.getOrNull(difficultyOrdinal)
            ?.let { if (it == DifficultyType.REMASTER && !allowsRemaster) DifficultyType.MASTER else it }
    }

    // ── 当前筛选下的明细 ──
    // 首帧用进程级快照（键对齐才生效）：produceState 异步重载期间 value 保持快照，
    // LazyVerticalGrid 第一帧就是完整 item 数，SaveableStateProvider / 缓存恢复的
    // 滚动位置才不会被空表钳回 0（「从详情返回回顶」的根因）。
    val detail by produceState<List<PlateProgress.Item>>(
        initialValue = PlateDetailCache.items.takeIf {
            it.isNotEmpty() &&
                PlateDetailCache.groupKey == cache.groupKey &&
                PlateDetailCache.difficultyOrdinal == cache.difficultyOrdinal
        } ?: emptyList(),
        selectedGroup, difficulty,
    ) {
        val g = selectedGroup
        value = if (g == null) emptyList() else PlateProgress.loadDetail(g, difficulty)
    }
    // 数据稳定（组解析完成且明细非空）后发布快照，供从歌曲详情返回时首帧恢复
    LaunchedEffect(detail, groupKey, difficultyOrdinal) {
        if (detail.isNotEmpty() && selectedGroup?.key == groupKey) {
            PlateDetailCache.groupKey = groupKey
            PlateDetailCache.difficultyOrdinal = difficultyOrdinal
            PlateDetailCache.items = detail
        }
    }

    // 牌子筛选跟随组切换：真代无将、霸者仅舞代（直接取组给出的可用牌集合）
    val plateOptions = remember(selectedGroup) {
        selectedGroup?.availablePlates ?: PlateKind.entries.toList()
    }
    val effectivePlate = if (plate in plateOptions) plate else plateOptions.first()

    // 当前版本的各牌总览（只展示筛选器选中的那一个版本）
    val selectedSummary = remember(summaries, selectedGroup) {
        summaries.firstOrNull { it.group.key == selectedGroup?.key }
    }

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

    // 滚动位置：初始值来自进程级快照（与成绩查询页同款双保险）；离开页面时写回
    // （push 歌曲详情即触发 onDispose）
    val gridState = rememberLazyGridState(cache.scrollIndex, cache.scrollOffset)
    DisposableEffect(Unit) {
        onDispose {
            cache.scrollIndex = gridState.firstVisibleItemIndex
            cache.scrollOffset = gridState.firstVisibleItemScrollOffset
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (viewMode == "grid") gridColumns else 1),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 筛选器（置顶，与成绩查询页同款折叠开关）──
            item(key = "filter_toggle", span = { GridItemSpan(maxLineSpan) }) {
                Button(
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = {
                        filtersExpanded = !filtersExpanded
                        cache.filtersExpanded = filtersExpanded
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (filtersExpanded) "收起筛选器" else "展开筛选器")
                }
            }
            if (filtersExpanded) {
                item(key = "filter_title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("筛选") }
                item(key = "filter_card", span = { GridItemSpan(maxLineSpan) }) {
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
                                    cache.groupKey = it.key
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
                                "（达成率 > 80%）"
                            } else {
                                ""
                            },
                            onSelectedIndexChange = { index ->
                                plateOrdinal = (plateOptions.getOrNull(index) ?: PlateKind.FC).ordinal
                                cache.plateOrdinal = plateOrdinal
                            },
                        )
                        // 难度选项：非舞代组不提供 Re:Master（宗师不计入名牌统计），
                        // 选中索引按生效难度（回落后的）计算，两种选项集下映射一致：
                        // 0=所有难度(-1)，其后依次 = ordinal
                        OverlaySpinnerPreference(
                            items = remember(allowsRemaster) {
                                (listOf("所有难度", "Basic", "Advanced", "Expert", "Master") +
                                    if (allowsRemaster) listOf("Re:Master") else emptyList())
                                    .map { DropdownItem(text = it) }
                            },
                            selectedIndex = (difficulty?.ordinal ?: -1) + 1,
                            title = "难度",
                            summary = if (difficulty == null) "所有难度"
                            else difficultyLabel(difficulty),
                            onSelectedIndexChange = { index ->
                                difficultyOrdinal = index - 1
                                cache.difficultyOrdinal = difficultyOrdinal
                            },
                        )
                        if (!allowsRemaster) {
                            Text(
                                text = "非舞代 Re:Master 不计入名牌统计",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        OverlaySpinnerPreference(
                            items = listOf(
                                DropdownItem(text = "列表"),
                                DropdownItem(text = "网格 · 3 列"),
                                DropdownItem(text = "网格 · 4 列"),
                                DropdownItem(text = "网格 · 5 列"),
                            ),
                            selectedIndex = if (viewMode == "list") 0 else gridColumns - 2,
                            title = "显示方式",
                            summary = if (viewMode == "list") "列表" else "网格 · $gridColumns 列",
                            onSelectedIndexChange = { index ->
                                if (index == 0) {
                                    viewMode = "list"
                                } else {
                                    viewMode = "grid"
                                    gridColumns = index + 2
                                }
                                cache.viewMode = viewMode
                                cache.gridColumns = gridColumns
                            },
                        )
                        SwitchPreference(
                            checked = hideAchieved,
                            onCheckedChange = {
                                hideAchieved = it
                                cache.hideAchieved = it
                            },
                            title = "隐藏已达成",
                            summary = "只看还没达成当前牌子条件的谱面",
                        )
                    }
                }
            }

            // ── 版本进度总览（只显示当前选中的版本，排版更宽敞）──
            item(key = "overview_title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("版本进度") }
            if (selectedSummary == null) {
                item(key = "overview_empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        text = "暂无数据\n先在曲目页同步曲库、并绑定查分器拉取成绩",
                    )
                }
            } else {
                item(key = "overview_card", span = { GridItemSpan(maxLineSpan) }) {
                    OverviewCard(summary = selectedSummary)
                }
            }

            // ── 当前筛选进度 ──
            item(key = "progress_title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("当前进度") }
            item(key = "progress_card", span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${selectedGroup?.plateCode ?: ""} ${effectivePlate.label}牌 · " +
                                    (difficulty?.let { difficultyLabel(it) } ?: "所有难度"),
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
                            text = "${currentProgress.percentText} · 共 ${currentProgress.total} 张谱面",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ── 明细列表（按等级降序分组，组内定数降序；网格视图格子带角标）──
            if (levelGroups.isEmpty()) {
                item(key = "detail_empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        text = if (hideAchieved && detail.isNotEmpty()) {
                            "该条件下全部谱面都已达成 🎉"
                        } else {
                            "当前筛选下没有谱面\n试试换个版本 / 难度"
                        },
                    )
                }
            } else {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "共 ${detail.count { !hideAchieved || !it.isDone(effectivePlate) }} 张谱面",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                levelGroups.forEach { (level, rows) ->
                    item(key = "level_$level", span = { GridItemSpan(maxLineSpan) }) {
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
                        val item = rows[index]
                        if (viewMode == "grid") {
                            PlateGridCell(
                                item = item,
                                plate = effectivePlate,
                                onClick = { onOpenSong(item.song.id) },
                            )
                        } else {
                            PlateItemRow(
                                item = item,
                                plate = effectivePlate,
                                onClick = { onOpenSong(item.song.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 版本进度总览卡（只展示当前选中版本）：大字牌子代号 + 版本名 + 谱面总数，
 * 下方分牌横向进度条（极 / 将 / 神，舞代另含霸者；真代无将），留白宽敞。
 */
@Composable
private fun OverviewCard(summary: PlateProgress.GroupSummary) {
    val group = summary.group
    // 总览牌序：极 / 将 / 神（舞代另含霸者）；真代无将；舞舞不在总览内
    val overviewPlates = remember(group) {
        group.availablePlates.filter { it != PlateKind.FSD }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            // 头部：代号大字 + 版本名 / 谱面总数
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.plateCode,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.displayName,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "共 ${summary.total} 张谱面",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            overviewPlates.forEach { kind ->
                val progress = when (kind) {
                    PlateKind.FC -> summary.fc
                    PlateKind.SSS -> summary.sss
                    PlateKind.AP -> summary.ap
                    PlateKind.MASTER -> summary.master
                    PlateKind.FSD -> summary.fc // 总览不含舞舞，兜底分支
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${kind.label}牌",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (kind == PlateKind.MASTER) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(达成率 > 80%)",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${progress.done} / ${progress.total}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = progress.percentText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(7.dp))
                ProgressBar(fraction = progress.fraction, color = plateAccent(kind), height = 8.dp)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 各牌子在总览进度条上的强调色（极蓝 / 将金 / 神红 / 霸者紫）。 */
private fun plateAccent(kind: PlateKind): Color = when (kind) {
    PlateKind.FC -> Color(0xFF3D9BE9)
    PlateKind.SSS -> Color(0xFFF2B53C)
    PlateKind.AP -> Color(0xFFE8547E)
    PlateKind.FSD -> Color(0xFF34C7A0)
    PlateKind.MASTER -> Color(0xFF9F51DC)
}

/** 牌子达成官方贴图徽章（按牌子种类给出对应贴图）。 */
@Composable
private fun PlateBadge(item: PlateProgress.Item, plate: PlateKind, height: Dp) {
    when (plate) {
        PlateKind.MASTER -> RankBadge(rate = item.record?.rate ?: "d", height = height)
        PlateKind.FC -> ComboBadge(fc = item.record?.fc ?: "fc", height = height)
        PlateKind.AP -> ComboBadge(fc = item.record?.fc ?: "ap", height = height)
        PlateKind.FSD -> SyncBadge(fs = item.record?.fs ?: "fsd", height = height)
        // 将：评级贴图（SSS / SSS+）
        else -> RankBadge(rate = item.record?.rate ?: "sss", height = height)
    }
}

/** 明细条目（列表）：封面（难度描边）+ 歌名 + 难度/等级/定数 + 牌子达成徽章或「未达成」。 */
@OptIn(ExperimentalLayoutApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlateItemRow(
    item: PlateProgress.Item,
    plate: PlateKind,
    onClick: () -> Unit,
) {
    // 长按菜单（与曲目页 SongCard 同款：详情 / 复制歌名 / 播放 / 加入歌单）
    val menu = rememberSongCardMenuState()
    val interaction = remember { MutableInteractionSource() }
    val done = item.isDone(plate)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .kibaPressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    menu.show(item.song.id, item.song.title) { onClick() }
                },
            ),
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
            if (done) {
                PlateBadge(item = item, plate = plate, height = 30.dp)
            } else {
                UnplayedPill()
            }
        }
    }
    SongCardMenuHost(menu)
}

/** 明细条目（网格）：封面铺满格子（难度描边）+ 底部 scrim + 牌子达成角标或「未达成」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlateGridCell(
    item: PlateProgress.Item,
    plate: PlateKind,
    onClick: () -> Unit,
) {
    // 网格格子是自拼 clickable（不是 miuix Card），按压反馈靠 kibaPressScale 补；
    // 长按菜单与列表行同款（SongCardMenuHost）
    val menu = rememberSongCardMenuState()
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .kibaPressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    menu.show(item.song.id, item.song.title) { onClick() }
                },
            ),
    ) {
        // 封面铺满整个格子，描边按难度上色
        CoverFrame(
            songId = item.song.id,
            imageUrl = item.song.imageUrl,
            genre = item.song.genre,
            size = maxWidth,
            strokeColor = difficultyColor(item.chart.difficultyType),
        )
        // 底部轻 scrim，衬出角标
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    )
                ),
        )
        // 角标：已达成显示对应官方贴图，未达成显示灰色标记
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        ) {
            if (item.isDone(plate)) {
                PlateBadge(item = item, plate = plate, height = 20.dp)
            } else {
                UnplayedPill()
            }
        }
    }
    SongCardMenuHost(menu)
}

/** 细进度条（与曲目页同步进度条同款视觉；颜色 / 粗细可调）。 */
@Composable
private fun ProgressBar(fraction: Float, color: Color = MiuixTheme.colorScheme.primary, height: Dp = 6.dp) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 240),
        label = "plateProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/**
 * 名牌进度页状态快照（进程级）：返回时还原筛选状态，行为对齐 ScoreQueryStateCache。
 */
object PlateQueryStateCache {
    var groupKey: String = "OLD"
    var plateOrdinal: Int = PlateKind.FC.ordinal
    var difficultyOrdinal: Int = 3 // 默认 Master
    var hideAchieved: Boolean = false
    var filtersExpanded: Boolean = false
    var viewMode: String = "list"
    var gridColumns: Int = 3
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
}

/**
 * 版本总览快照：返回首帧即可解析出 selectedGroup，明细快照（[PlateDetailCache]）
 * 才能在第一帧生效——groups 异步加载时 selectedGroup 为 null，produceState 会在
 * null 键上跑一轮把明细清空，滚动位置随之被钳回 0（详见 [ListSnapshotCache]）。
 */
object PlateOverviewCache : ListSnapshotCache<List<PlateProgress.GroupSummary>>(emptyList())

/** 明细快照：键（版本 + 难度）对齐当前筛选时才允许首帧使用，避免版本切到一半返回错位。 */
object PlateDetailCache {
    @Volatile var groupKey: String = ""
    @Volatile var difficultyOrdinal: Int = -1
    var items: List<PlateProgress.Item> = emptyList()
}
