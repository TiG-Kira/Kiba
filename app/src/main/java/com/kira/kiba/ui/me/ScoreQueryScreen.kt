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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.ui.common.ComboBadge
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.InfoPill
import com.kira.kiba.ui.common.RankBadge
import com.kira.kiba.ui.common.SyncBadge
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreQueryScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    val account by DivingFishAccount.state.collectAsState()

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
    val versions by produceState<List<String>>(emptyList(), songsById) {
        value = songsById.values.map { it.from }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val levels by produceState<List<String>>(emptyList(), records) {
        value = records.map { it.level }.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy({ it.replace("+", "").toIntOrNull() ?: 0 }, { it }))
    }

    // ── 筛选 / 视图状态 ──
    var sortBy by remember { mutableStateOf(SortBy.RA) }
    var filterVersion by remember { mutableStateOf<String?>(null) }
    var filterLevel by remember { mutableStateOf<String?>(null) }
    var filterDifficulty by remember { mutableStateOf<Int?>(null) }
    var dsRange by remember { mutableStateOf(0.0 to 15.1) }
    var achRange by remember { mutableStateOf(0.0 to 101.0) }
    var filterFc by remember { mutableStateOf<String?>(null) }
    var filterFs by remember { mutableStateOf<String?>(null) }

    var viewMode by remember { mutableStateOf("list") }
    var gridColumns by remember { mutableStateOf(3) }
    var iconDisplay by remember { mutableStateOf("rate") }

    var showSortDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showLevelDialog by remember { mutableStateOf(false) }
    var showDifficultyDialog by remember { mutableStateOf(false) }
    var showDsDialog by remember { mutableStateOf(false) }
    var showAchDialog by remember { mutableStateOf(false) }
    var showFcFsDialog by remember { mutableStateOf(false) }

    // ── 过滤 + 排序（playerRecordService.filterRecordsByMultipleConditions 口径）──
    val filtered = remember(records, songsById, sortBy, filterVersion, filterLevel,
        filterDifficulty, dsRange, achRange, filterFc, filterFs) {
        records.asSequence()
            .filter { r ->
                val song = songsById[r.songId]
                (filterVersion == null || song?.from == filterVersion) &&
                    (filterLevel == null || r.level == filterLevel) &&
                    (filterDifficulty == null || r.levelIndex == filterDifficulty) &&
                    r.ds >= dsRange.first && r.ds <= dsRange.second &&
                    r.achievements >= achRange.first && r.achievements <= achRange.second &&
                    (filterFc == null || r.fc == filterFc) &&
                    (filterFs == null || r.fs == filterFs)
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
                Card(modifier = Modifier.fillMaxWidth()) {
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

            // ── 筛选按钮（EasyMai 两行筛选按钮 → FlowRow 自适应换行）──
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            "排序：" + when (sortBy) {
                                SortBy.RA -> "Rating"
                                SortBy.ACHIEVEMENTS -> "达成率"
                                SortBy.DS -> "难度"
                            },
                            onClick = { showSortDialog = true },
                        )
                        FilterChip(
                            if (filterVersion == null) "版本筛选" else filterVersion!!,
                            active = filterVersion != null,
                            onClick = { showVersionDialog = true },
                        )
                        FilterChip(
                            if (filterLevel == null) "等级筛选" else "等级 ${filterLevel!!}",
                            active = filterLevel != null,
                            onClick = { showLevelDialog = true },
                        )
                        FilterChip(
                            if (filterDifficulty == null) "难度筛选"
                            else difficultyLabel(filterDifficulty!!),
                            active = filterDifficulty != null,
                            onClick = { showDifficultyDialog = true },
                        )
                        FilterChip(
                            if (dsRange == (0.0 to 15.1)) "定数筛选"
                            else "定数 ${dsRange.first}~${dsRange.second}",
                            active = dsRange != (0.0 to 15.1),
                            onClick = { showDsDialog = true },
                        )
                        FilterChip(
                            if (achRange == (0.0 to 101.0)) "达成率筛选"
                            else "达成率 ${achRange.first}~${achRange.second}",
                            active = achRange != (0.0 to 101.0),
                            onClick = { showAchDialog = true },
                        )
                        FilterChip(
                            when {
                                filterFc == null && filterFs == null -> "FC/FS筛选"
                                filterFs == null -> fcLabel(filterFc!!)
                                filterFc == null -> "FS ${fsLabel(filterFs!!)}"
                                else -> "${fcLabel(filterFc!!)}/${fsLabel(filterFs!!)}"
                            },
                            active = filterFc != null || filterFs != null,
                            onClick = { showFcFsDialog = true },
                        )
                        FilterChip(
                            if (viewMode == "grid") "网格 ${gridColumns}列" else "列表",
                            onClick = {
                                if (viewMode == "grid") {
                                    gridColumns = if (gridColumns >= 5) 3 else gridColumns + 1
                                } else {
                                    viewMode = "grid"
                                }
                            },
                        )
                        FilterChip(
                            when (iconDisplay) {
                                "rate" -> "角标：评级"
                                "fc" -> "角标：FC"
                                "fs" -> "角标：FS"
                                else -> "角标：RA"
                            },
                            onClick = {
                                iconDisplay = when (iconDisplay) {
                                    "rate" -> "fc"
                                    "fc" -> "fs"
                                    "fs" -> "ra"
                                    else -> "rate"
                                }
                            },
                        )
                    }
                }
            }

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
                    if (viewMode == "grid") {
                        GridCoverCell(
                            record = r,
                            song = song,
                            iconDisplay = iconDisplay,
                            onClick = { onOpenSong(r.songId) },
                        )
                    } else {
                        RecordListCard(
                            record = r,
                            songTitle = song?.title,
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
            }
        }
    }

    // ── 筛选弹层 ──
    if (showSortDialog) {
        SingleOptionDialog(
            title = "排序方式",
            options = listOf("Rating", "达成率", "难度"),
            selectedIndex = when (sortBy) {
                SortBy.RA -> 0
                SortBy.ACHIEVEMENTS -> 1
                SortBy.DS -> 2
            },
            onDismiss = { showSortDialog = false },
            onSelect = { index ->
                sortBy = when (index) {
                    1 -> SortBy.ACHIEVEMENTS
                    2 -> SortBy.DS
                    else -> SortBy.RA
                }
                showSortDialog = false
            },
        )
    }
    if (showVersionDialog) {
        SingleOptionDialog(
            title = "版本筛选",
            options = listOf("全部版本") + versions,
            selectedIndex = if (filterVersion == null) 0 else versions.indexOf(filterVersion) + 1,
            onDismiss = { showVersionDialog = false },
            onSelect = { index ->
                filterVersion = if (index == 0) null else versions[index - 1]
                showVersionDialog = false
            },
        )
    }
    if (showLevelDialog) {
        SingleOptionDialog(
            title = "等级筛选",
            options = listOf("全部等级") + levels,
            selectedIndex = if (filterLevel == null) 0 else levels.indexOf(filterLevel) + 1,
            onDismiss = { showLevelDialog = false },
            onSelect = { index ->
                filterLevel = if (index == 0) null else levels[index - 1]
                showLevelDialog = false
            },
        )
    }
    if (showDifficultyDialog) {
        SingleOptionDialog(
            title = "难度筛选",
            options = listOf("全部难度") + (0..4).map { difficultyLabel(it) },
            selectedIndex = if (filterDifficulty == null) 0 else filterDifficulty!! + 1,
            onDismiss = { showDifficultyDialog = false },
            onSelect = { index ->
                filterDifficulty = if (index == 0) null else index - 1
                showDifficultyDialog = false
            },
        )
    }
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
                "SSS 100~100.49" to (100.0 to 100.49),
                "SS+ 99.5~99.99" to (99.5 to 99.99),
                "SS 99~99.49" to (99.0 to 99.49),
                "S+ 98~98.99" to (98.0 to 98.99),
                "S 97~97.99" to (97.0 to 97.99),
                "未SSS 0~99.99" to (0.0 to 99.99),
            ),
            onDismiss = { showAchDialog = false },
            onApply = { min, max ->
                achRange = min to max
                showAchDialog = false
            },
        )
    }
    if (showFcFsDialog) {
        FcFsDialog(
            selectedFc = filterFc,
            selectedFs = filterFs,
            onDismiss = { showFcFsDialog = false },
            onApply = { fc, fs ->
                filterFc = fc
                filterFs = fs
                showFcFsDialog = false
            },
        )
    }
}

// ── 数据模型 ──

/** 排序方式（EasyMai：ra / achievements / ds）。 */
private enum class SortBy { RA, ACHIEVEMENTS, DS }

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
    onClick: () -> Unit,
) {
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
                    if (record.fc.isNotBlank() && record.fc != "none") {
                        InfoPill(fcLabel(record.fc))
                    }
                    if (record.fs.isNotBlank() && record.fs != "none") {
                        InfoPill("FS ${fsLabel(record.fs)}")
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 官方评级徽章（与 B50 贴图同一套贴图资源）
            RankBadge(rate = record.rate, height = 30.dp)
        }
    }
}

// ── 网格封面格子（EasyMai grid 视图：封面为格子本体 + 角标，封面必须显示）──

@Composable
private fun GridCoverCell(
    record: RecordEntity,
    song: SongDataEntity?,
    iconDisplay: String,
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
            when (iconDisplay) {
                "fc" -> ComboBadge(fc = record.fc, height = 20.dp)
                "fs" -> SyncBadge(fs = record.fs, height = 20.dp)
                "ra" -> Text(
                    text = if (record.ra > 0) "${record.ra}" else "-",
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.White,
                )
                else -> RankBadge(rate = record.rate, height = 22.dp)
            }
        }
    }
}

// ── 筛选弹层 ──

/** 单选弹层（与曲目页归类弹层同一套 miuix OverlayDialog + 圆点样式）。 */
@Composable
private fun SingleOptionDialog(
    title: String,
    summary: String = "点击选项立即生效",
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (index == selectedIndex) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                }
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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

    OverlayDialog(
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

/** FC/FS 筛选弹层（EasyMai：选 FS 会清掉 FC，反之亦然）。 */
@Composable
private fun FcFsDialog(
    selectedFc: String?,
    selectedFs: String?,
    onDismiss: () -> Unit,
    onApply: (String?, String?) -> Unit,
) {
    var tempFc by remember { mutableStateOf(selectedFc) }
    var tempFs by remember { mutableStateOf(selectedFs) }

    OverlayDialog(
        show = true,
        title = "FC/FS 筛选",
        summary = "FC 与 FS 二选一生效",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "FC",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            listOf(
                "不限" to null,
                "未 FC" to "none",
                "FC/FC+" to "fc",
                "仅 FC+" to "fcp",
                "AP/AP+" to "ap",
                "仅 AP+" to "app",
            ).forEach { (label, value) ->
                DialogOptionRow(
                    label = label,
                    selected = tempFc == value,
                    onClick = { tempFc = value; tempFs = null },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "FS",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            listOf(
                "不限" to null,
                "未 FS" to "none",
                "FS/FS+" to "fs",
                "仅 FS+" to "fsp",
                "FSD/FSD+" to "fsd",
                "仅 FSD+" to "fsdp",
            ).forEach { (label, value) ->
                DialogOptionRow(
                    label = label,
                    selected = tempFs == value,
                    onClick = { tempFs = value; tempFc = null },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "应用", onClick = { onApply(tempFc, tempFs) })
            }
        }
    }
}

@Composable
private fun DialogOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
        )
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
