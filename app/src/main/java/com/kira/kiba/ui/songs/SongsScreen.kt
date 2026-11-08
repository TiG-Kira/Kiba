package com.kira.kiba.ui.songs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.data.ChuniSongSync
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.model.SongSortMode
import com.kira.kiba.core.model.sortedBySongMode
import com.kira.kiba.core.repository.ChuniSongWithChartRepository
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.state.SongRefreshSession
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.ListSnapshotCache
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.songplayer.NOW_PLAYING_BAR_CLEARANCE
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.common.SectionTitle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.glass.*
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 曲目列表快照（进程级）：从歌曲详情返回时首帧即渲染上次的完整列表，
 * 否则 LazyColumn 空测量会把恢复出的滚动位置钳回 0（详见 [ListSnapshotCache]）。
 */
object SongsListCache : ListSnapshotCache<List<SongWithChartsEntity>>(emptyList())

/** 中二节奏曲目列表快照（进程级，同 [SongsListCache]）。 */
object ChuniSongsListCache : ListSnapshotCache<List<ChuniSongWithCharts>>(emptyList())

/*
 * 曲目页刷新进度卡的会话生命周期由 [SongRefreshSession] 的**任务计数**管理：
 * 谁发起刷新、发起几个任务，就登记几个待完成数，全部结束才收卡——不再依赖
 * 「舞萌与中二两边都进终态」，因为顶栏刷新在无更新时只跑舞萌一侧检查、中二侧永远
 * 停在 Idle，旧写法会让一张空卡片 + 一张「中二节奏曲库 等待同步…」长期挂在列表顶部。
 *
 * 只有曲目页的刷新入口（顶栏刷新按钮 / 更新对话框「立即更新」/ 空列表兜底按钮）传
 * `showProgress = true`；设置页的曲库更新选项不点亮。
 */

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
 * 游戏类型（[GameType]）：2.4.0 起切换开关移到**顶栏大标题行**（与大标题同频折叠），
 * 由 [KibaMainScreen] 持有状态并作为参数传入；切到中二节奏后列表立即换源
 * （舞萌 song_data / 中二节奏 chuni_song），持久化仍落 [SpUtil]。
 * 我页面 / 收藏 / 详情 / Best 也都按 SpUtil 里的当前游戏切换。
 *
 * @param searchTrigger 顶栏搜索按钮每次点击 +1，页面据此切换搜索栏展开 / 收起。
 */
@Composable
fun SongsScreen(
    scrollBehavior: ScrollBehavior,
    searchTrigger: Int = 0,
    // 当前游戏由顶栏大标题行的切换开关驱动（2.4.0 从列表首项迁到顶栏），
    // 页面只读这份状态，不再自己持有一份 rememberSaveable 副本。
    gameType: GameType = SpUtil.getGameType(),
    // 归类方式选择弹层：由顶栏归类按钮控制（曲目页不再放独立归类卡）
    showGroupPicker: Boolean = false,
    onDismissGroupPicker: () -> Unit = {},
    onSongClick: (SongWithChartsEntity) -> Unit = {},
    onChuniSongClick: (ChuniSongWithCharts) -> Unit = {},
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

    // 换游戏后列表整体换源：回到顶部，避免停在上一个曲库的中段位置
    var lastGameType by remember { mutableStateOf(gameType) }
    LaunchedEffect(gameType) {
        if (lastGameType != gameType) {
            lastGameType = gameType
            listState.scrollToItem(0)
        }
    }

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
    val chuniRepo = remember {
        ChuniSongWithChartRepository.getInstance(AppDataBase.getInstance().chuniSongWithChartDao())
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

    // 中二节奏列表（按搜索文本；收藏由收藏页单独承载，这里 isFavor=false）
    val chuniSongsFlow = remember(query) {
        chuniRepo.searchChuniSongsWithCharts(searchText = query, isFavor = false).asFlow()
    }
    val chuniSongs by chuniSongsFlow.collectAsStateCached(ChuniSongsListCache)

    // ── 排序（舞萌 / 中二节奏通用）──
    // 旧实现里顶栏 Sort 按钮只做「归类」，而归类被 gameType == MAIMAI 门控；
    // 中二节奏走 ChuniDao 写死的 ORDER BY id DESC，从头到尾没有任何排序环节，
    // 所以切到中二节奏后点排序选什么都毫无反应。这里两套曲库各自映射成
    // SongSortKey 后共用同一套比较器，排序对两个游戏同时生效。
    var sortMode by remember {
        mutableStateOf(SongSortMode.fromKey(SpUtil.getSongSortMode()))
    }
    val sortedSongs = remember(songs, sortMode) {
        songs.sortedBySongMode(sortMode, ::maimaiSongSortKey)
    }
    val sortedChuniSongs = remember(chuniSongs, sortMode) {
        chuniSongs.sortedBySongMode(sortMode, ::chuniSongSortKey)
    }

    // World's End 谱面（等级为汉字的特殊谱面，标题形如 [X]歌名）单独归类，不混入常规列表
    val chuniNormalSongs = remember(sortedChuniSongs) {
        sortedChuniSongs.filter { s -> s.charts.none { it.difficultyIndex == ChuniDiffType.WORLD_END.ordinal } }
    }
    val chuniWeSongs = remember(sortedChuniSongs) {
        sortedChuniSongs.filter { s -> s.charts.any { it.difficultyIndex == ChuniDiffType.WORLD_END.ordinal } }
    }

    // ── 归类：不归类（默认）/ 按版本 / 按 MASTER 谱师 / 按曲师 ──
    // 归类是舞萌专用（分组子页只查舞萌曲库）；中二节奏用上面的排序，不参与归类。
    // 搜索进行中强制平铺（搜索结果跨分组，归类入口暂隐），搜索清空后恢复。
    // 注意：groupMode 必须先于 grouping 声明（Kotlin 局部属性初始化顺序）。
    var groupMode by remember {
        mutableStateOf(SongGroupMode.fromKey(SpUtil.getSongGroupMode()))
    }
    val grouping = gameType == GameType.MAIMAI &&
        groupMode != SongGroupMode.NONE && query.isBlank()
    val groups = remember(sortedSongs, groupMode, grouping) {
        if (grouping) buildSongGroups(sortedSongs, groupMode) else emptyList()
    }

    val syncState by SongDataSync.state.collectAsState()
    val chuniSyncState by ChuniSongSync.state.collectAsState()
    val updateAvailable by SongDataSync.updateAvailable.collectAsState()
    val localVersion = remember { SpUtil.getDataVersion() }
    val chuniLocalVersion = remember { ChuniSongSync.getLocalVersion() }
    val syncing = syncState is SongDataSync.SyncState.Checking ||
        syncState is SongDataSync.SyncState.Downloading
    val chuniSyncing = chuniSyncState is ChuniSongSync.SyncState.Syncing
    // 刷新进度卡：是否处于刷新会话中（见 [SongRefreshSession]）
    val refreshCardVisible by SongRefreshSession.visible.collectAsState()
    // 刷新收尾提示（成功 / 失败），可手动关闭：进度卡在终态不渲染，
    // 没有这张卡时「舞萌已是最新」这类秒完成的刷新用户完全感知不到。
    val refreshNotices by SongRefreshSession.notices.collectAsState()

    // 冷启动兜底：bootstrap 未跑（异常进程恢复等）时补一次检查
    // （后台检查不点亮进度卡，避免一进页面就闪一张「正在检查曲库更新」）
    LaunchedEffect(Unit) {
        if (SongDataSync.state.value is SongDataSync.SyncState.Idle) {
            KibaState.checkSongUpdate()
        }
    }

    // 每张进度卡只在自己的状态确实在跑（或刚失败）时渲染：
    // Done / Idle 一律不画——否则 Done 会渲染成一张空卡片、
    // 中二 Done 会渲染成「中二节奏曲库 等待同步…」，看着像卡死。
    val maimaiCardVisible = refreshCardVisible && syncState.isProgressCardState()
    val chuniCardVisible = refreshCardVisible && chuniSyncState.isProgressCardState()

    // 卡片出现时：列表立刻重置到顶 + 顶栏动画恢复展开。
    // 用 snapshotFlow 而非以 visible 为 key 的 LaunchedEffect：
    // 检查完成卡片消失会重启 effect 并打断滚动。列表用瞬移而非 animateScrollToItem——
    // 长列表（1300+ 首）的 spring 回顶又慢又易被打断；
    // 且歌曲 item 带 key，后插入的进度卡（key="sync_card"）会被锚定在视口外，
    // 必须等卡片进入组合（等一帧）后才能 scrollToItem(0)。
    LaunchedEffect(Unit) {
        snapshotFlow { refreshCardVisible }
            .filter { it }
            .collect {
                val topBarState = scrollBehavior.state
                topBarState.contentOffset = 0f
                launch {
                    if (topBarState.heightOffset < 0f) {
                        Animatable(topBarState.heightOffset).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 300),
                        ) {
                            topBarState.heightOffset = value
                        }
                    }
                }
                withFrameNanos { }
                listState.scrollToItem(0)
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
                // 弹窗要立刻收起，updateAvailable 已被清空，必须显式要求下载否则只会重新检查
                KibaState.refreshAllSongData(
                    context,
                    forceMaimaiDownload = true,
                    showProgress = true,
                )
            },
        )
    }

    // 底部垫高两阶段：无播放底栏 = 悬浮导航栏一档；正在播放 = 再让出 NowPlayingBar
    // （66dp 胶囊 + 间距），否则列表尾部被底栏+导航栏双层遮挡。播放态切换时平滑过渡。
    val bottomBarPlayerState by SongPlaybackController.state.collectAsState()
    val songsBottomExtra by animateDpAsState(
        targetValue = if (bottomBarPlayerState.track != null) {
            NOW_PLAYING_BAR_CLEARANCE + 16.dp
        } else 16.dp,
        label = "songsBottomExtra",
    )

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
            contentPadding = pageContentPadding(bottomExtra = songsBottomExtra),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 刷新进度卡（置顶）：按各自状态独立渲染。
            // 曲目页刷新会**同时刷新舞萌与中二两边**，两张卡各展示一边的进度；
            // 只刷新一边（顶栏「无更新时」仅检查舞萌）时就只有一张卡。
            if (maimaiCardVisible) {
                item(key = "sync_card_maimai") {
                    SongsSyncProgressCard(state = syncState)
                }
            }
            if (chuniCardVisible) {
                item(key = "sync_card_chuni") {
                    ChuniSongsSyncProgressCard(state = chuniSyncState)
                }
            }

            // 刷新收尾提示（舞萌 / 中二各一张，右上角可关闭）。
            // 会话结束后进度卡立刻收走，靠这里把「已是最新 / 已更新 N 首」留给用户看。
            items(items = refreshNotices, key = { "refresh_notice_${it.id}" }) { notice ->
                RefreshNoticeCard(
                    notice = notice,
                    onDismiss = { SongRefreshSession.dismissNotice(notice.id) },
                )
            }

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

            // 曲库数据卡与归类方式卡已移到顶栏按钮（刷新 / 归类），
            // 列表直接从搜索栏 / 分组入口 / 歌曲卡片开始。

            if (gameType == GameType.MAIMAI) {
                // ── 舞萌 ──
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
                                        colors = ButtonDefaults.buttonColorsPrimary(),
                                        onClick = {
                                            // 空曲库必须真下载，不能退化成「只检查」
                                            KibaState.refreshAllSongData(
                                                context,
                                                forceMaimaiDownload = true,
                                                showProgress = true,
                                            )
                                        },
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
                            modifier = (Modifier.fillMaxWidth()),
                            onClick = { onOpenGroup(groupMode, group.key) },
                            pressFeedbackType = PressFeedbackType.Sink,
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
                    items(items = sortedSongs, key = { it.songData.id }) { song ->
                        SongCard(song = song, onClick = { onSongClick(song) })
                    }
                }
            } else {
                // ── 中二节奏（World's End 单独归类在列表尾部）──
                if (chuniNormalSongs.isEmpty() && chuniWeSongs.isEmpty()) {
                    item(key = "chuni_state") {
                        EmptyStateCard(
                            text = when {
                                chuniSyncing -> "正在同步中二节奏曲库…"
                                query.isNotBlank() -> "没有找到「$query」相关的中二节奏歌曲"
                                else -> "中二节奏曲库还是空的\n同步后即可浏览全部歌曲"
                            },
                            action = if (chuniSyncing) {
                                null
                            } else {
                                {
                                    Button(
                                        colors = ButtonDefaults.buttonColorsPrimary(),
                                        onClick = {
                                            KibaState.refreshAllSongData(context, showProgress = true)
                                        },
                                    ) {
                                        Text(text = "同步中二节奏曲库")
                                    }
                                }
                            },
                        )
                    }
                } else {
                    items(items = chuniNormalSongs, key = { it.song.id }) { song ->
                        ChuniSongCard(song = song, onClick = { onChuniSongClick(song) })
                    }
                    if (chuniWeSongs.isNotEmpty()) {
                        item(key = "we_title") {
                            SectionTitle("World's End（${chuniWeSongs.size}）")
                        }
                        items(items = chuniWeSongs, key = { it.song.id }) { song ->
                            ChuniSongCard(song = song, onClick = { onChuniSongClick(song) })
                        }
                    }
                }
            }
        }
    }

    // 排序 / 归类选择弹层（单选圆点指示；由顶栏 Sort 按钮唤起）
    // 排序对舞萌和中二节奏同时生效；归类是舞萌专用，中二节奏给明确说明，
    // 不再出现「选了没有任何反应」的假选项。
    if (showGroupPicker) {
        KibaGlassDialog(
            show = true,
            title = "排序与归类",
            summary = "排序对舞萌 / 中二节奏同时生效；归类仅舞萌可用",
            onDismissRequest = onDismissGroupPicker,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "排序方式（两游戏通用）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                SongSortMode.entries.forEach { mode ->
                    SongListOptionRow(
                        label = mode.label,
                        selected = sortMode == mode,
                        onClick = {
                            sortMode = mode
                            SpUtil.setSongSortMode(mode.key)
                            // 换排序后回到顶部，否则用户停留在旧列表的中段看不出变化
                            scope.launch { listState.scrollToItem(0) }
                            onDismissGroupPicker()
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (gameType == GameType.MAIMAI) {
                        "归类方式（仅舞萌）"
                    } else {
                        "归类方式（中二节奏暂不支持）"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                if (gameType == GameType.MAIMAI) {
                    SongGroupMode.entries.forEach { mode ->
                        SongListOptionRow(
                            label = mode.label,
                            selected = groupMode == mode,
                            onClick = {
                                groupMode = mode
                                SpUtil.setSongGroupMode(mode.key)
                                scope.launch { listState.scrollToItem(0) }
                                onDismissGroupPicker()
                            },
                        )
                    }
                } else {
                    Text(
                        text = "中二节奏曲库的分组语义与舞萌不同，请用上方排序方式重排列表。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** 排序 / 归类弹层里的单选项行（选中圆点 + 文案）。 */
@Composable
private fun SongListOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
                )
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
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
    KibaGlassDialog(
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
 * 舞萌侧进度卡是否该出现：只有「进行中」与「刚失败」两种状态有内容可画。
 * `Done` 画出来是一张空卡片、`Idle` 什么都没有——这两种都不渲染。
 */
private fun SongDataSync.SyncState.isProgressCardState(): Boolean = when (this) {
    is SongDataSync.SyncState.Checking,
    is SongDataSync.SyncState.Downloading,
    is SongDataSync.SyncState.Failed,
    -> true
    else -> false
}

/** 中二侧进度卡是否该出现（同上：`Done` / `Idle` 不渲染，避免「等待同步…」常驻）。 */
private fun ChuniSongSync.SyncState.isProgressCardState(): Boolean = when (this) {
    is ChuniSongSync.SyncState.Syncing,
    is ChuniSongSync.SyncState.Failed,
    -> true
    else -> false
}

/**
 * 舞萌曲库刷新进度卡（曲目页置顶）：
 * - Checking → 不定态动画条「正在检查曲库更新」；
 * - Downloading → 真实进度条（received/total，total 未知时退化为动画条）「正在下载最新曲目库」；
 * - Failed → 红色错误提示（外层 3 秒后自动隐藏卡片）；
 * - Done → 外层立即隐藏，不会渲染到这里。
 */
@Composable
private fun SongsSyncProgressCard(state: SongDataSync.SyncState) {
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is SongDataSync.SyncState.Checking -> {
                        Text(
                            text = "正在检查曲库更新…",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    is SongDataSync.SyncState.Downloading -> {
                        val totalMb = state.total / (1024f * 1024f)
                        val receivedMb = state.received / (1024f * 1024f)
                        Text(
                            text = if (state.total > 0) {
                                "正在下载最新曲目库  %.1f / %.1f MB".format(receivedMb, totalMb)
                            } else {
                                "正在下载最新曲目库  %.1f MB".format(receivedMb)
                            },
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    is SongDataSync.SyncState.Failed -> {
                        Text(
                            text = "曲库刷新失败",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    else -> Unit
                }
            }
            if (state is SongDataSync.SyncState.Failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                when (state) {
                    is SongDataSync.SyncState.Downloading, is SongDataSync.SyncState.Checking ->
                        SyncProgressBar(
                            progress = (state as? SongDataSync.SyncState.Downloading)
                                ?.takeIf { it.total > 0 }
                                ?.let { it.received.toFloat() / it.total },
                        )
                    else -> Unit
                }
            }
        }
    }
}

/**
 * 中二节奏曲库刷新进度卡（与舞萌 [SongsSyncProgressCard] 并列展示，曲目页刷新时两边同时跑）。
 *
 * 三个阶段各有一条文案，下载阶段拿到字节数就走定态进度条，
 * 入库 / 补物量阶段（无总量可算）走不定态动画条。
 */
@Composable
private fun ChuniSongsSyncProgressCard(state: ChuniSongSync.SyncState) {
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            val syncing = state as? ChuniSongSync.SyncState.Syncing
            Text(
                text = when {
                    syncing != null -> chuniStageText(syncing)
                    state is ChuniSongSync.SyncState.Failed -> "中二节奏曲库刷新失败"
                    else -> "中二节奏曲库 等待同步…"
                },
                style = MiuixTheme.textStyles.body1,
                color = if (state is ChuniSongSync.SyncState.Failed) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            if (state is ChuniSongSync.SyncState.Failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else if (syncing != null) {
                Spacer(Modifier.height(10.dp))
                SyncProgressBar(
                    progress = syncing
                        .takeIf { it.stage == ChuniSongSync.STAGE_DOWNLOAD && it.total > 0 }
                        ?.let { it.received.toFloat() / it.total },
                )
            }
        }
    }
}

/** 中二曲库同步各阶段的展示文案。 */
private fun chuniStageText(state: ChuniSongSync.SyncState.Syncing): String {
    if (state.stage != ChuniSongSync.STAGE_DOWNLOAD) {
        return when (state.stage) {
            ChuniSongSync.STAGE_IMPORT -> "正在写入中二节奏曲库…"
            ChuniSongSync.STAGE_NOTES -> "正在补齐中二谱面物量…"
            else -> "正在同步中二节奏曲库…"
        }
    }
    val receivedMb = state.received / (1024f * 1024f)
    val totalMb = state.total / (1024f * 1024f)
    return if (state.total > 0) {
        "正在下载中二节奏曲库  %.1f / %.1f MB".format(receivedMb, totalMb)
    } else {
        "正在下载中二节奏曲库  %.1f MB".format(receivedMb)
    }
}

/**
 * 同步进度条：progress 非空时为定态进度条（带平滑动画），
 * 为空时为不定态来回滑动动画条（「无法获取进度」的兜底形态）。
 */
@Composable
private fun SyncProgressBar(progress: Float?) {
    if (progress != null) {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 200),
            label = "syncProgress",
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
    } else {
        val transition = rememberInfiniteTransition(label = "syncIndeterminate")
        val offset by transition.animateFloat(
            initialValue = -1.2f,
            targetValue = 3.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1300, easing = LinearEasing),
            ),
            label = "syncIndeterminateOffset",
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
                    .fillMaxWidth(0.3f)
                    .height(6.dp)
                    .graphicsLayer { translationX = offset * size.width }
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.primary),
            )
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
        modifier = (modifier.fillMaxWidth()),
        onClick = onClick,
        // HyperOS 风格按压缩放反馈（miuix Card 默认无反馈）
        pressFeedbackType = PressFeedbackType.Sink,
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

/**
 * 刷新收尾提示卡（舞萌 / 中二各一张，右上角 × 可关闭）。
 *
 * 进度卡在终态一律不渲染，而「舞萌无新版本」时刷新只是一次版本比较，几十毫秒就结束——
 * 用户点了刷新却什么都看不到，观感就是「按钮没反应」。这张卡把结果留在列表顶部，
 * 直到用户主动关闭；失败也走同一张卡（标题走主题色 + 具体原因在副标题）。
 */
@Composable
private fun RefreshNoticeCard(
    notice: SongRefreshSession.RefreshNotice,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notice.title,
                    style = MiuixTheme.textStyles.body1,
                    color = if (notice.success) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
                if (notice.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = notice.detail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixGlassIcons.Regular.Close,
                    contentDescription = "关闭提示",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
