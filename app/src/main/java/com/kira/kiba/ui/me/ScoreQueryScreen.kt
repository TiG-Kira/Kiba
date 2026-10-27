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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.ui.common.ComboBadge
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.InfoPill
import com.kira.kiba.ui.common.RankBadge
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.SyncBadge
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 成绩查询页（移植自 EasyMai player-records，替代旧「成绩记录 / 版本进度 / 等级进度」三页）：
 *
 * - 统计头：玩家昵称 + DX Rating + 当前筛选下的评级 / FC / FS 统计（EasyMai 口径）；
 * - 筛选：排序方式（ra / 达成率 / 定数）、版本（替代版本进度）、等级（替代等级进度）、
 *   难度、定数范围、达成率范围、FC/FS；
 * - 视图：列表（封面 + 达成率 + RA + 评级徽章）/ 网格（封面格子 + 评级/FC/FS/RA 角标，
 *   网格必须显示封面）；
 * - 点击任意成绩卡 → 歌曲详情页（原有逻辑不变）。
 *
 * 排版与筛选逻辑对齐 EasyMai，控件与页面骨架全部使用本项目 miuix 规范。
 */
/**
 * 成绩查询页状态快照（进程级）：从歌曲详情返回时完整还原筛选 / 视图 / 滚动位置。
 *
 * 筛选状态影响列表内容与排序，丢失会导致返回后列表错位/回顶；
 * 与 SongsListCache 同思路，与 KibaMainScreen 的 SaveableStateProvider 解耦，双保险。
 */
object ScoreQueryStateCache {
    var sortByOrdinal = 0
    var filterVersion: String? = null
    var filterLevel: String? = null
    var filterDifficulty: Int? = null
    var dsMin = 0.0
    var dsMax = 15.1
    var achMin = 0.0
    var achMax = 101.0
    var filterFc: String? = null
    var filterFs: String? = null
    var viewMode = "list"
    var gridColumns = 3
    var iconDisplay = "rate"
    var filtersExpanded = false
    var scrollIndex = 0
    var scrollOffset = 0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreQueryScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    val account by DivingFishAccount.state.collectAsState()
    val cache = ScoreQueryStateCache

    // ── 数据：成绩 + 曲库（版本 from / 封面 / 流派）──
    val records by produceState(initialValue = RecordsListCache.current, account.recordCount) {
        value = runCatching {
            AppDataBase.getInstance().recordDao().getAllRecordList()
        }.getOrDefault(emptyList())
    }
    // 数据到达后发布快照，供从歌曲详情页返回时首帧恢复（同旧 RecordsScreen）
    LaunchedEffect(records) { RecordsListCache.publish(records) }
    val songsById by produceState<Map<Int, SongDataEntity>>(emptyMap()) {
        value = runCatching {
            AppDataBase.getInstance().songDao().getAllSongsList().associateBy { it.id }
        }.getOrDefault(emptyMap())
    }
    // 全谱面（不含宴曲目）：未游玩的谱面也要出现在查询列表里
    val allCharts by produceState<List<SongWithChartsEntity>>(emptyList()) {
        value = runCatching {
            AppDataBase.getInstance().songWithChartDao().getAllSongsWithChartsOnce()
        }.getOrDefault(emptyList())
    }
    // ── 合并未游玩谱面：无成绩的谱面视为 D（0.0000%，无 FC/FS、ra=0），
    //    合成 RecordEntity 后筛选 / 排序 / 渲染全链路复用，排序时自然沉底；
    //    unplayedKeys 记录合成记录的键，渲染时显示「未游玩」，与真 D 0.0000% 区分 ──
    val (fullRecords, unplayedKeys) = remember(records, allCharts) {
        if (allCharts.isEmpty()) {
            records to emptySet<Pair<Int, Int>>()
        } else {
            val played = records.asSequence().map { it.songId to it.levelIndex }.toHashSet()
            val unplayed = mutableSetOf<Pair<Int, Int>>()
            val merged = buildList {
                addAll(records)
                allCharts.forEach { song ->
                    song.charts.forEach { chart ->
                        val key = song.songData.id to chart.difficultyType.ordinal
                        if (key !in played) {
                            unplayed.add(key)
                            add(
                                RecordEntity(
                                    achievements = 0.0,
                                    ds = chart.ds,
                                    dxScore = 0,
                                    fc = "",
                                    fs = "",
                                    level = chart.level,
                                    levelIndex = chart.difficultyType.ordinal,
                                    levelLabel = "",
                                    ra = 0,
                                    rate = "d",
                                    songId = song.songData.id,
                                    title = song.songData.title,
                                    type = chart.type,
                                )
                            )
                        }
                    }
                }
            }
            merged to unplayed
        }
    }
    val versions by produceState<List<String>>(emptyList(), songsById) {
        value = songsById.values.map { it.from }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val levels by produceState<List<String>>(emptyList(), fullRecords) {
        value = fullRecords.map { it.level }.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy({ it.replace("?", "").replace("+", "").toIntOrNull() ?: 0 }, { it }))
    }

    // ── 筛选 / 视图状态（初始值来自进程级快照，返回时完整还原）──
    var sortBy by remember { mutableStateOf(SortBy.entries.getOrElse(cache.sortByOrdinal) { SortBy.RA }) }
    var filterVersion by remember { mutableStateOf(cache.filterVersion) }
    var filterLevel by remember { mutableStateOf(cache.filterLevel) }
    var filterDifficulty by remember { mutableStateOf(cache.filterDifficulty) }
    var dsRange by remember { mutableStateOf(cache.dsMin to cache.dsMax) }
    var achRange by remember { mutableStateOf(cache.achMin to cache.achMax) }
    var filterFc by remember { mutableStateOf(cache.filterFc) }
    var filterFs by remember { mutableStateOf(cache.filterFs) }

    var viewMode by remember { mutableStateOf(cache.viewMode) }
    var gridColumns by remember { mutableStateOf(cache.gridColumns) }
    var iconDisplay by remember { mutableStateOf(cache.iconDisplay) }
    // 筛选 / 显示两组设置默认折叠，点「展开筛选器」展开
    var filtersExpanded by remember { mutableStateOf(cache.filtersExpanded) }

    var showDsDialog by remember { mutableStateOf(false) }
    var showAchDialog by remember { mutableStateOf(false) }

    // 滚动位置：初始值来自快照；离开页面时把全部状态写回快照（push 详情即触发）
    val gridState = rememberLazyGridState(cache.scrollIndex, cache.scrollOffset)
    DisposableEffect(Unit) {
        onDispose {
            cache.sortByOrdinal = sortBy.ordinal
            cache.filterVersion = filterVersion
            cache.filterLevel = filterLevel
            cache.filterDifficulty = filterDifficulty
            cache.dsMin = dsRange.first
            cache.dsMax = dsRange.second
            cache.achMin = achRange.first
            cache.achMax = achRange.second
            cache.filterFc = filterFc
            cache.filterFs = filterFs
            cache.viewMode = viewMode
            cache.gridColumns = gridColumns
            cache.iconDisplay = iconDisplay
            cache.filtersExpanded = filtersExpanded
            cache.scrollIndex = gridState.firstVisibleItemIndex
            cache.scrollOffset = gridState.firstVisibleItemScrollOffset
        }
    }

    // ── 过滤 + 排序（playerRecordService.filterRecordsByMultipleConditions 口径）──
    val filtered = remember(fullRecords, songsById, sortBy, filterVersion, filterLevel,
        filterDifficulty, dsRange, achRange, filterFc, filterFs) {
        fullRecords.asSequence()
            .filter { r ->
                val song = songsById[r.songId]
                (filterVersion == null || song?.from == filterVersion) &&
                    (filterLevel == null || r.level == filterLevel) &&
                    (filterDifficulty == null || r.levelIndex == filterDifficulty) &&
                    r.ds >= dsRange.first && r.ds <= dsRange.second &&
                    r.achievements >= achRange.first && r.achievements <= achRange.second &&
                    // 「未FC/未FS」(sentinel "none") 要同时命中空串与 "none"：
                    // 水鱼 API 无 FC/FS 时 fc/fs 字段缺省为空串，精确 == "none" 会筛出 0 条
                    (filterFc == null || (if (filterFc == "none") r.fc.isBlank() || r.fc == "none" else r.fc == filterFc)) &&
                    (filterFs == null || (if (filterFs == "none") r.fs.isBlank() || r.fs == "none" else r.fs == filterFs))
            }
            .sortedWith(
                when (sortBy) {
                    SortBy.RA -> compareByDescending<RecordEntity> { it.ra }
                        .thenByDescending { it.achievements }
                    SortBy.ACHIEVEMENTS -> compareByDescending<RecordEntity> { it.achievements }
                        .thenByDescending { it.ra }
                    SortBy.DS -> compareByDescending<RecordEntity> { it.ds }
                        .thenByDescending { it.achievements }
                }
            )
            .toList()
    }

    // ── 统计（EasyMai getVersionPlayStats 口径，作用于当前筛选结果）──
    val stats = remember(filtered) {
        ScoreStats(
            total = filtered.size,
            sssp = filtered.count { it.rate == "sssp" },
            sss = filtered.count { it.rate == "sss" },
            fc = filtered.count { it.fc == "fc" || it.fc == "fcp" },
            ap = filtered.count { it.fc == "ap" || it.fc == "app" },
            fs = filtered.count { it.fs == "fs" || it.fs == "fsp" },
            fsd = filtered.count { it.fs == "fsd" || it.fs == "fsdp" },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (viewMode == "grid") gridColumns else 1),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 统计头 ──
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (account.bound) {
                                "${account.nickname.ifBlank { "查分器用户" }} · DX Rating ${account.rating}"
                            } else {
                                "未绑定查分器"
                            },
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell("${stats.total}", "总曲目数", Modifier.weight(1f))
                            StatCell("${stats.sssp}", "SSS+", Modifier.weight(1f))
                            StatCell("${stats.sss}", "SSS", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell("${stats.fc}", "FC/FC+", Modifier.weight(1f))
                            StatCell("${stats.ap}", "AP/AP+", Modifier.weight(1f))
                            StatCell("${stats.fs}", "FS/FS+", Modifier.weight(1f))
                            StatCell("${stats.fsd}", "FSD/FSD+", Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── 折叠开关：筛选 + 显示两组默认折叠，点按钮展开 / 收起 ──
            item(key = "filter_toggle", span = { GridItemSpan(maxLineSpan) }) {
                Button(
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (filtersExpanded) "收起筛选器" else "展开筛选器")
                }
            }

            // ── 筛选（设置页同款 miuix preference 控件：OverlaySpinner / ArrowPreference）──
            if (filtersExpanded) {
            item(key = "filters_title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("筛选") }
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    OverlaySpinnerPreference(
                        items = listOf(
                            DropdownItem(text = "Rating"),
                            DropdownItem(text = "达成率"),
                            DropdownItem(text = "难度"),
                        ),
                        selectedIndex = sortBy.ordinal,
                        title = "排序方式",
                        summary = when (sortBy) {
                            SortBy.RA -> "Rating"
                            SortBy.ACHIEVEMENTS -> "达成率"
                            SortBy.DS -> "难度"
                        },
                        onSelectedIndexChange = { sortBy = SortBy.entries[it] },
                    )
                    OverlaySpinnerPreference(
                        items = remember(versions) {
                            (listOf("全部版本") + versions).map { DropdownItem(text = it) }
                        },
                        selectedIndex = if (filterVersion == null) 0 else versions.indexOf(filterVersion) + 1,
                        title = "版本筛选",
                        summary = filterVersion ?: "全部版本",
                        onSelectedIndexChange = { index ->
                            filterVersion = if (index == 0) null else versions.getOrNull(index - 1)
                        },
                    )
                    OverlaySpinnerPreference(
                        items = remember(levels) {
                            (listOf("全部等级") + levels).map { DropdownItem(text = it) }
                        },
                        selectedIndex = if (filterLevel == null) 0 else levels.indexOf(filterLevel) + 1,
                        title = "等级筛选",
                        summary = if (filterLevel == null) "全部等级" else "等级 ${filterLevel}",
                        onSelectedIndexChange = { index ->
                            filterLevel = if (index == 0) null else levels.getOrNull(index - 1)
                        },
                    )
                    OverlaySpinnerPreference(
                        items = (listOf("全部难度") + (0..4).map { difficultyLabel(it) })
                            .map { DropdownItem(text = it) },
                        selectedIndex = (filterDifficulty ?: -1) + 1,
                        title = "难度筛选",
                        summary = if (filterDifficulty == null) "全部难度"
                        else difficultyLabel(filterDifficulty!!),
                        onSelectedIndexChange = { index ->
                            filterDifficulty = if (index == 0) null else index - 1
                        },
                    )
                    ArrowPreference(
                        title = "定数筛选",
                        summary = if (dsRange == (0.0 to 15.1)) "不限制"
                        else "${dsRange.first} ~ ${dsRange.second}",
                        onClick = { showDsDialog = true },
                    )
                    ArrowPreference(
                        title = "达成率筛选",
                        summary = if (achRange == (0.0 to 101.0)) "不限制"
                        else "${achRange.first}% ~ ${achRange.second}%",
                        onClick = { showAchDialog = true },
                    )
                    OverlaySpinnerPreference(
                        items = listOf("不限", "未FC", "FC/FC+", "仅FC+", "AP/AP+", "仅AP+")
                            .map { DropdownItem(text = it) },
                        selectedIndex = FC_OPTIONS.indexOfFirst { it.second == filterFc }.coerceAtLeast(0),
                        title = "FC 筛选",
                        summary = filterFc?.let { fcLabel(it) } ?: "不限",
                        onSelectedIndexChange = { index -> filterFc = FC_OPTIONS[index].second },
                    )
                    OverlaySpinnerPreference(
                        items = listOf("不限", "未FS", "FS/FS+", "仅FS+", "FSD/FSD+", "仅FSD+")
                            .map { DropdownItem(text = it) },
                        selectedIndex = FS_OPTIONS.indexOfFirst { it.second == filterFs }.coerceAtLeast(0),
                        title = "FS 筛选",
                        summary = filterFs?.let { fsLabel(it) } ?: "不限",
                        onSelectedIndexChange = { index -> filterFs = FS_OPTIONS[index].second },
                    )
                }
            }

            // ── 显示方式（列表 / 封面网格 + 网格角标）──
            item(key = "display_title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("显示") }
            item(key = "display", span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = (Modifier.fillMaxWidth())) {
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
                        },
                    )
                    OverlaySpinnerPreference(
                        items = listOf("评级", "FC", "FS", "RA").map { DropdownItem(text = it) },
                        selectedIndex = when (iconDisplay) {
                            "fc" -> 1
                            "fs" -> 2
                            "ra" -> 3
                            else -> 0
                        },
                        title = "网格角标",
                        summary = when (iconDisplay) {
                            "rate" -> "评级"
                            "fc" -> "FC"
                            "fs" -> "FS"
                            else -> "RA"
                        },
                        onSelectedIndexChange = { index ->
                            iconDisplay = when (index) {
                                1 -> "fc"
                                2 -> "fs"
                                3 -> "ra"
                                else -> "rate"
                            }
                        },
                    )
                }
            }
            } // filtersExpanded

            // ── 结果区 ──
            if (filtered.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        text = if (records.isEmpty()) {
                            "暂无成绩数据\n绑定查分器后点击顶栏刷新即可拉取"
                        } else {
                            "当前筛选条件下没有成绩\n试试放宽筛选"
                        },
                        action = null,
                    )
                }
            } else {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "共 ${filtered.size} 条成绩",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                items(
                    count = filtered.size,
                    key = { filtered[it].let { r -> "${r.songId}_${r.levelIndex}" } },
                ) { index ->
                    val r = filtered[index]
                    val song = songsById[r.songId]
                    val unplayed = (r.songId to r.levelIndex) in unplayedKeys
                    if (viewMode == "grid") {
                        GridCoverCell(
                            record = r,
                            song = song,
                            iconDisplay = iconDisplay,
                            unplayed = unplayed,
                            onClick = { onOpenSong(r.songId) },
                        )
                    } else {
                        RecordListCard(
                            record = r,
                            songTitle = song?.title,
                            unplayed = unplayed,
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
            }
        }
    }

    // ── 范围筛选弹层（定数 / 达成率；其余筛选已改为 miuix OverlaySpinner）──
    if (showDsDialog) {
        RangeFilterDialog(
            title = "定数筛选",
            minLabel = "最小定数",
            maxLabel = "最大定数",
            initialMin = dsRange.first,
            initialMax = dsRange.second,
            allowDecimal = true,
            onDismiss = { showDsDialog = false },
            onApply = { min, max ->
                dsRange = min to max
                showDsDialog = false
            },
        )
    }
    if (showAchDialog) {
        RangeFilterDialog(
            title = "达成率筛选",
            minLabel = "最小达成率(%)",
            maxLabel = "最大达成率(%)",
            initialMin = achRange.first,
            initialMax = achRange.second,
            allowDecimal = true,
            quickRanges = listOf(
                "SSS+ 100.5~101" to (100.5 to 101.0),
                // 达成率四位小数：SSS 上边界是 100.4999 而不是 100.49，其余档位同理
                "SSS 100~100.4999" to (100.0 to 100.4999),
                "SS+ 99.5~99.9999" to (99.5 to 99.9999),
                "SS 99~99.4999" to (99.0 to 99.4999),
                "S+ 98~98.9999" to (98.0 to 98.9999),
                "S 97~97.9999" to (97.0 to 97.9999),
                "未SSS 0~99.9999" to (0.0 to 99.9999),
            ),
            onDismiss = { showAchDialog = false },
            onApply = { min, max ->
                achRange = min to max
                showAchDialog = false
            },
        )
    }
}

// ── 数据模型 ──

/** 排序方式（EasyMai：ra / achievements / ds）。 */
private enum class SortBy { RA, ACHIEVEMENTS, DS }

/** FC 筛选选项（label, 水鱼 fc 值；null = 不限）。 */
private val FC_OPTIONS = listOf(
    "不限" to null,
    "未FC" to "none",
    "FC/FC+" to "fc",
    "仅FC+" to "fcp",
    "AP/AP+" to "ap",
    "仅AP+" to "app",
)

/** FS 筛选选项（label, 水鱼 fs 值；null = 不限）。 */
private val FS_OPTIONS = listOf(
    "不限" to null,
    "未FS" to "none",
    "FS/FS+" to "fs",
    "仅FS+" to "fsp",
    "FSD/FSD+" to "fsd",
    "仅FSD+" to "fsdp",
)

/** 统计头数据（EasyMai getVersionPlayStats 口径）。 */
private data class ScoreStats(
    val total: Int,
    val sssp: Int,
    val sss: Int,
    val fc: Int,
    val ap: Int,
    val fs: Int,
    val fsd: Int,
)

// ── 统计格子 ──

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── 筛选按钮 ──

@Composable
private fun FilterChip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 列表成绩卡（EasyMai song-card 排版：封面 + ds 角标 / 标题 / 达成率+RA+FC-FS / 评级徽章）──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordListCard(
    record: RecordEntity,
    songTitle: String?,
    unplayed: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = (Modifier.fillMaxWidth()),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面 + 左下角定数角标（难度色）
            Box {
                CoverFrame(
                    songId = record.songId,
                    imageUrl = "",
                    genre = "",
                    size = 64.dp,
                )
                // 定数角标：覆盖在封面左下（EasyMai difficulty-badge）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .clip(RoundedCornerShape(5.dp))
                        .background(difficultyColor(difficultyOf(record.levelIndex)))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${record.ds}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songTitle ?: record.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InfoPill("%.4f%%".format(record.achievements))
                    // 水鱼对未定数新曲（level 带「?」）返回 ra = 0，显示为「-」更优雅
                    InfoPill(if (record.ra > 0) "RA ${record.ra}" else "RA -")
                    InfoPill(difficultyLabel(difficultyOf(record.levelIndex)) + " ${record.level}")
                    // FC/FS 状态贴图（与 B50 / 网格角标同一套官方贴图资源）
                    if (record.fc.isNotBlank() && record.fc != "none") {
                        Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                            ComboBadge(fc = record.fc, height = 18.dp)
                        }
                    }
                    if (record.fs.isNotBlank() && record.fs != "none") {
                        Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                            SyncBadge(fs = record.fs, height = 18.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右侧评级：未游玩显示「未游玩」标记（与真 D 0.0000% 的 D 贴图区分）
            if (unplayed) {
                UnplayedBadge()
            } else {
                RankBadge(rate = record.rate, height = 30.dp)
            }
        }
    }
}

// ── 网格封面格子（EasyMai grid 视图：封面为格子本体 + 角标，封面必须显示）──

@Composable
private fun GridCoverCell(
    record: RecordEntity,
    song: SongDataEntity?,
    iconDisplay: String,
    unplayed: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        // 封面铺满整个格子（难度色描边由 CoverFrame 自带）
        CoverFrame(
            songId = record.songId,
            imageUrl = song?.imageUrl ?: "",
            genre = song?.genre ?: "",
            size = maxWidth,
        )
        // 底部轻 scrim，衬出角标
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    )
                ),
        )
        // 角标（评级 / FC / FS / RA，EasyMai iconDisplay 四态）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        ) {
            when {
                // 未游玩：无论角标显示模式都显示「未游玩」标记
                unplayed -> UnplayedBadge()
                iconDisplay == "fc" -> ComboBadge(fc = record.fc, height = 20.dp)
                iconDisplay == "fs" -> SyncBadge(fs = record.fs, height = 20.dp)
                iconDisplay == "ra" -> Text(
                    text = if (record.ra > 0) "${record.ra}" else "-",
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.White,
                )
                else -> RankBadge(rate = record.rate, height = 22.dp)
            }
        }
    }
}

/** 「未游玩」标记：无成绩谱面按 D（0.0000%）参与筛选排序，但显示上与真 D 区分。 */
@Composable
private fun UnplayedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = "未游玩",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 范围筛选弹层（min/max 输入 + 快捷区间），定数与达成率共用。 */
@Composable
private fun RangeFilterDialog(
    title: String,
    minLabel: String,
    maxLabel: String,
    initialMin: Double,
    initialMax: Double,
    allowDecimal: Boolean,
    quickRanges: List<Pair<String, Pair<Double, Double>>> = emptyList(),
    onDismiss: () -> Unit,
    onApply: (Double, Double) -> Unit,
) {
    var minText by remember { mutableStateOf(formatRange(initialMin)) }
    var maxText by remember { mutableStateOf(formatRange(initialMax)) }

    KibaGlassDialog(
        show = true,
        title = title,
        summary = "留空表示不限制",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (quickRanges.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickRanges.forEach { (label, range) ->
                        FilterChip(
                            text = label,
                            active = false,
                            onClick = {
                                minText = formatRange(range.first)
                                maxText = formatRange(range.second)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = minText,
                    onValueChange = { v ->
                        minText = v.filter { c -> c.isDigit() || (allowDecimal && c == '.') }
                    },
                    label = minLabel,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextField(
                    value = maxText,
                    onValueChange = { v ->
                        maxText = v.filter { c -> c.isDigit() || (allowDecimal && c == '.') }
                    },
                    label = maxLabel,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "应用",
                    onClick = {
                        val min = minText.trim().toDoubleOrNull() ?: 0.0
                        val max = maxText.trim().toDoubleOrNull() ?: (if (title.contains("定数")) 15.1 else 101.0)
                        onApply(if (min > max) max else min, if (min > max) min else max)
                    },
                )
            }
        }
    }
}

// ── 工具函数 ──

private fun difficultyOf(levelIndex: Int): DifficultyType =
    DifficultyType.entries.getOrElse(levelIndex) { DifficultyType.UTAGE }

private fun formatRange(v: Double): String =
    if (v == 0.0 || v == 101.0 || v == 15.1) "" else v.toString()

private fun fcLabel(fc: String): String = when (fc) {
    "none" -> "未FC"
    "fc" -> "FC/FC+"
    "fcp" -> "仅FC+"
    "ap" -> "AP/AP+"
    "app" -> "仅AP+"
    else -> fc.uppercase()
}

private fun fsLabel(fs: String): String = when (fs) {
    "none" -> "未FS"
    "fs" -> "FS/FS+"
    "fsp" -> "仅FS+"
    "fsd" -> "FSD/FSD+"
    "fsdp" -> "仅FSD+"
    else -> fs.uppercase()
}
