package com.kira.kiba.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.ListSnapshotCache
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 曲目列表快照（进程级）：从歌曲详情返回时首帧即渲染上次的完整列表，
 * 否则 LazyColumn 空测量会把恢复出的滚动位置钳回 0（详见 [ListSnapshotCache]）。
 */
object SongsListCache : ListSnapshotCache<List<SongWithChartsEntity>>(emptyList())

/**
 * 曲目页：真实曲库数据（SongWithChartRepository → Room → LiveData 响应式刷新）。
 *
 * 曲库更新链路（复刻 MaimaiData）：
 * - 启动 / 手动「检查更新」→ [SongDataSync.checkForUpdate] 拉 update.json 比对版本；
 * - 有新版本 → [SongDataSync.updateAvailable] 非空 → 弹出更新对话框（稍后 / 立即更新）；
 * - 立即更新 → [KibaState.refreshSongData] 下载 + 入库，Room LiveData 自动推送，列表即刷新；
 * - 顶部「曲库数据」卡常驻展示：当前版本 / 歌曲数 / 同步状态 / 下载进度。
 *
 * 搜索栏默认隐藏：点顶栏搜索按钮展开，再点收起（搜索逻辑不变）。
 *
 * @param searchTrigger 顶栏搜索按钮每次点击 +1，页面据此切换搜索栏展开 / 收起。
 */
@Composable
fun SongsScreen(
    scrollBehavior: ScrollBehavior,
    searchTrigger: Int = 0,
    onSongClick: (SongWithChartsEntity) -> Unit = {},
    onOpenGroup: (mode: SongGroupMode, key: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // lastTrigger 必须 rememberSaveable：普通 remember 会在页面重建时归零，
    // 而此时 searchTrigger（MainScreen 作用域）还保留旧值 → LaunchedEffect 误触发，
    // 表现为「用过搜索后从详情返回，搜索栏弹出且列表滚回顶部」。
    var lastTrigger by rememberSaveable { mutableIntStateOf(0) }
    // 列表滚动状态：搜索按钮点击时用它做「回到顶部」动画
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(searchTrigger) {
        if (searchTrigger > 0 && searchTrigger != lastTrigger) {
            lastTrigger = searchTrigger
            searchVisible = !searchVisible
            // 回到页面顶部（带动画）——搜索栏在列表首位，滚回顶部才看得见
            scope.launch {
                listState.animateScrollToItem(0)
            }
            if (searchVisible) {
                // 展开后聚焦输入框
                runCatching { searchFocusRequester.requestFocus() }
            } else {
                query = ""
            }
        }
    }

    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    // Room LiveData → Flow → Compose State（DB 更新后自动发射新列表）
    val songsFlow = remember(query) {
        repo.searchSongsWithCharts(
            searchText = query,
            genreList = emptyList(),
            versionList = emptyList(),
            selectLevel = null,
            sequencing = null,
            ds = null,
            isFavor = false,
            isMatchAlias = true,
            isMatchCharter = false,
            isMatchSongId = true,
        ).asFlow()
    }
    val songs by songsFlow.collectAsStateCached(SongsListCache)

    // ── 归类：不归类（默认）/ 按版本 / 按 MASTER 谱师 / 按曲师 ──
    // 搜索进行中强制平铺（搜索结果跨分组，归类入口暂隐），搜索清空后恢复。
    var groupMode by remember {
        mutableStateOf(SongGroupMode.fromKey(SpUtil.getSongGroupMode()))
    }
    var showGroupPicker by remember { mutableStateOf(false) }
    val grouping = groupMode != SongGroupMode.NONE && query.isBlank()
    val groups = remember(songs, groupMode, grouping) {
        if (grouping) buildSongGroups(songs, groupMode) else emptyList()
    }

    val syncState by SongDataSync.state.collectAsState()
    val updateAvailable by SongDataSync.updateAvailable.collectAsState()
    val localVersion = remember { SpUtil.getDataVersion() }
    val syncing = syncState is SongDataSync.SyncState.Checking ||
        syncState is SongDataSync.SyncState.Downloading

    // 冷启动兜底：bootstrap 未跑（异常进程恢复等）时补一次检查
    LaunchedEffect(Unit) {
        if (SongDataSync.state.value is SongDataSync.SyncState.Idle) {
            KibaState.checkSongUpdate()
        }
    }

    // 曲库数据更新提示（对齐 MaimaiData 的 MaterialDialog）
    if (updateAvailable != null) {
        SongDataUpdateDialog(
            localVersion = localVersion,
            remoteVersion = updateAvailable!!,
            onDismiss = { SongDataSync.dismissUpdatePrompt() },
            onConfirm = {
                SongDataSync.dismissUpdatePrompt()
                KibaState.refreshSongData(context)
            },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 搜索栏（默认隐藏，顶栏搜索按钮切换）
            if (searchVisible) {
                item(key = "search") {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索歌曲 / 别名 / ID",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                    )
                }
            }

            // 曲库数据卡：版本 / 歌曲数 / 检查更新 / 下载进度
            item(key = "song_data") {
                SongDataCard(
                    syncState = syncState,
                    localVersion = localVersion,
                    songCount = songs.size,
                    hasUpdate = updateAvailable != null,
                    syncing = syncing,
                    onCheckOrUpdate = {
                        if (updateAvailable != null) {
                            KibaState.refreshSongData(context)
                        } else {
                            KibaState.checkSongUpdate()
                        }
                    },
                )
            }

            // 归类方式选择（搜索中隐藏：搜索结果强制平铺）
            if (query.isBlank()) {
                item(key = "group_mode") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showGroupPicker = true },
                    ) {
                        BasicComponent(
                            title = "归类方式",
                            summary = "${groupMode.label} · 点击切换",
                            endActions = {
                                Icon(
                                    MiuixGlassIcons.Regular.ChevronForward,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                        )
                    }
                }
            }

            if (songs.isEmpty()) {
                item(key = "state") {
                    EmptyStateCard(
                        text = when {
                            syncing -> "正在同步曲库数据…"
                            query.isNotBlank() -> "没有找到「$query」相关的歌曲\n可换个关键词，或更新曲库后再试"
                            else -> "曲库还是空的\n更新曲库数据后即可浏览全部歌曲"
                        },
                        action = if (syncing) {
                            null
                        } else {
                            {
                                Button(
                                    onClick = { KibaState.refreshSongData(context) },
                                ) {
                                    Text(text = "立即更新曲库")
                                }
                            }
                        },
                    )
                }
            }

            // 归类视图：分组入口（组名 + 歌曲数），点击进子页面
            if (grouping) {
                items(items = groups, key = { it.key }) { group ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenGroup(groupMode, group.key) },
                    ) {
                        BasicComponent(
                            title = group.name,
                            summary = "${group.count} 首",
                            endActions = {
                                Icon(
                                    MiuixGlassIcons.Regular.ChevronForward,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                        )
                    }
                }
            } else {
                items(items = songs, key = { it.songData.id }) { song ->
                    SongCard(song = song, onClick = { onSongClick(song) })
                }
            }
        }
    }

    // 归类方式选择弹层（单选圆点指示）
    if (showGroupPicker) {
        OverlayDialog(
            show = true,
            title = "归类方式",
            summary = "选择曲目列表的归类维度，子页面展示对应谱面",
            onDismissRequest = { showGroupPicker = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SongGroupMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                groupMode = mode
                                SpUtil.setSongGroupMode(mode.key)
                                showGroupPicker = false
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (groupMode == mode) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (groupMode == mode) 0.dp else 1.5.dp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    shape = CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = mode.label,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 曲库数据卡：常驻展示当前数据版本 / 歌曲数，提供「检查更新 / 立即更新」入口，
 * 下载中展示进度条（miuix Card + BasicComponent + TextButton 全走 miuix 规范）。
 */
@Composable
private fun SongDataCard(
    syncState: SongDataSync.SyncState,
    localVersion: String,
    songCount: Int,
    hasUpdate: Boolean,
    syncing: Boolean,
    onCheckOrUpdate: () -> Unit,
) {
    val countSuffix = if (songCount > 0) " · 共 $songCount 首" else ""
    val summary = when (val s = syncState) {
        SongDataSync.SyncState.Idle -> "当前版本 $localVersion$countSuffix"
        SongDataSync.SyncState.Checking -> "正在检查更新…"
        is SongDataSync.SyncState.Downloading ->
            if (s.total > 0) {
                "正在下载 ${s.received / 1024} / ${s.total / 1024} KB"
            } else {
                "正在下载 ${s.received / 1024} KB…"
            }
        is SongDataSync.SyncState.Done -> "已是最新版本 ${s.version}$countSuffix"
        is SongDataSync.SyncState.Failed -> "更新失败：${s.message}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "曲库数据",
            summary = summary,
            endActions = {
                TextButton(
                    text = when {
                        syncing -> "更新中"
                        hasUpdate -> "立即更新"
                        else -> "检查更新"
                    },
                    onClick = onCheckOrUpdate,
                    enabled = !syncing,
                )
            },
        )
        if (syncState is SongDataSync.SyncState.Downloading) {
            SyncProgressBar(
                received = syncState.received,
                total = syncState.total,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** 细进度条（曲库下载进度），走 miuix 主色。 */
@Composable
private fun SyncProgressBar(
    received: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    if (total <= 0L) return
    val fraction = (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

/** 曲库更新对话框（对应上游 MainActivity 的数据更新 MaterialDialog）。 */
@Composable
private fun SongDataUpdateDialog(
    localVersion: String,
    remoteVersion: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "曲库数据更新",
        summary = "本地版本 $localVersion → 最新版本 $remoteVersion",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "下载并导入最新曲库数据，歌曲列表会自动刷新。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "稍后", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "立即更新", onClick = onConfirm)
            }
        }
    }
}

/**
 * 歌曲卡片：封面 + 标题（SD/DX 贴图跟在标题后，放不下自动换到下一行）+ 副标题（曲师 · 分类）+ 各难度等级。
 *
 * 标题行的换行策略：FlowRow 承载「歌名 + SD/DX 贴图」，歌名占满一行或剩余空间放不下贴图时，
 * 贴图自动落到标题下一行；贴图与歌名垂直居中对齐。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongCard(
    song: SongWithChartsEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        BasicComponent(
            startAction = {
                CoverFrame(
                    songId = song.songData.id,
                    imageUrl = song.songData.imageUrl,
                    genre = song.songData.genre,
                    size = 64.dp,
                )
            },
            bottomAction = {
                // 各难度等级，放在副标题下方；FlowRow 保证放不下时自动换行
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    song.charts.sortedBy { it.difficultyType.ordinal }.forEach { chart ->
                        DifficultyChip(
                            text = "${difficultyLabel(chart.difficultyType)} ${chart.level}",
                            color = difficultyColor(chart.difficultyType),
                        )
                    }
                }
            },
        ) {
            // ── 标题行：歌名 + SD/DX 贴图（样式对齐 miuix BasicComponent 默认 title）──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = song.songData.title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                ChartTypeChip(
                    type = song.songData.type,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            // 副标题：曲师 · 分类（样式对齐 miuix BasicComponent 默认 summary）
            Text(
                text = buildString {
                    // 曲师 = 歌曲艺术家（artist），不是谱师（charter）；直接展示名字不加前缀
                    val artist = song.songData.artist.takeIf { it.isNotBlank() && it != "-" }
                    if (artist != null) {
                        append(artist)
                        append(" · ")
                    }
                    append(song.songData.genre)
                },
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
