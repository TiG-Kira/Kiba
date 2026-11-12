package com.kira.kiba.ui.listen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import coil.compose.AsyncImage
import com.kira.kiba.core.agent.KibaLlm
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.PlaylistEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.listen.AgentPlaylist
import com.kira.kiba.core.listen.AgentPlaylistStore
import com.kira.kiba.core.listen.SongDownloader
import com.kira.kiba.core.listen.SongRecommender
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.songplayer.NOW_PLAYING_BAR_CLEARANCE
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.KibaGlassDialog
import com.kira.kiba.ui.songs.SongsListCache
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.CoverFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.AiScan
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Favorites
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Playlist
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.core.spring
import com.kira.kiba.ui.theme.KibaMotion

/**
 * 「畅听」页（2.6.0 由「听」更名）：Kiba 的音乐推荐与歌单中枢（仅舞萌——中二无整轨音源）。
 *
 * - **为你推荐**：本地算法从 Best 40 + 收藏提炼口味画像，从曲库挑 15 首。
 *   2.6.0 起改成**横向分栏**：每栏竖排 5 首，栏宽只占屏幕 ~84%，
 *   下一栏露一条边提示「还能往左滑」——比原来一长条竖列表更省滚动，一屏看全 5 首。
 * - **收藏连播** / **我的下载** / **我的歌单**：沿用原有能力。
 * - **Agent 专属歌单**：每次生成都存档（含模板封面），逐个入口展示，可单删 / 全清。
 */
@Composable
fun ListenScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (Int) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAgentPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 数据源：全量曲库 + 成绩 + 收藏 ──
    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    val songs by remember {
        repo.searchSongsWithCharts(
            searchText = "", genreList = emptyList(), versionList = emptyList(),
            selectLevel = null, sequencing = null, ds = null,
            isFavor = false, isMatchAlias = true, isMatchCharter = false, isMatchSongId = true,
        ).asFlow()
    }.collectAsStateCached(SongsListCache)

    val records by produceState<List<com.kira.kiba.core.db.entity.RecordEntity>>(
        initialValue = emptyList(),
    ) {
        value = withContext(Dispatchers.IO) {
            AppDataBase.getInstance().recordDao().getAllRecordList()
        }
    }
    val favoriteIds by produceState<List<String>>(initialValue = SpUtil.getFavIds()) {
        value = SpUtil.getFavIds()
    }

    // ── 本地智能推荐（曲库/成绩/收藏任一变化即重算）──
    // 成绩没有 genre 字段，从曲库 join；version 是字符串，能转数字才当新曲信号
    val recommended by remember(songs, records, favoriteIds) {
        val genreOf = songs.associate { it.songData.id to it.songData.genre }
        mutableStateOf(
            SongRecommender.recommend(
                songs = songs.map {
                    SongRecommender.SongLite(
                        it.songData.id,
                        it.songData.genre,
                        it.songData.version.toIntOrNull(),
                    )
                },
                records = records.map {
                    SongRecommender.RecordLite(it.songId, it.ra, genreOf[it.songId].orEmpty())
                },
                favoriteIds = favoriteIds.mapNotNull { it.toIntOrNull() }.toSet(),
            ),
        )
    }
    val recommendedSongs = remember(recommended, songs) {
        recommended.mapNotNull { id -> songs.firstOrNull { it.songData.id == id } }
    }

    // ── 我的歌单（Room Flow 实时刷新）──
    val playlists by AppDataBase.getInstance().playlistDao().observeAll()
        .collectAsState(initial = emptyList())
    val playlistSongCount by AppDataBase.getInstance().playlistDao().observeAllSongs()
        .collectAsState(initial = emptyList())

    // ── 我的下载 ──
    val downloads by produceState<List<SongDownloader.DownloadedSong>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { SongDownloader.listAll(context) }
    }

    // ── Agent 专属歌单：历史存档（每张一个入口）──
    val llmReady = remember { KibaLlm.isConfigured() }
    // 初值给空、读盘放到 LaunchedEffect：SP + JSON 解析不该占组合期的第一帧
    var agentHistory by remember { mutableStateOf<List<AgentPlaylistStore.Item>>(emptyList()) }
    var agentRefreshing by remember { mutableStateOf(false) }

    // 旧版「只存最新一份」的歌单迁移进历史（按版本号只跑一次，内部幂等 + 全程兜底）
    LaunchedEffect(Unit) {
        runCatching { AgentPlaylistStore.migrateLegacy(context) }
            .onFailure { android.util.Log.w("Listen", "legacy migrate failed: ${it.message}") }
        agentHistory = AgentPlaylistStore.load()
    }

    // 过期自动续一张（间隔在设置页可调）；已有历史时只追加，不覆盖旧的
    LaunchedEffect(llmReady, songs.size) {
        if (llmReady && songs.isNotEmpty() && AgentPlaylist.isStale()) {
            agentRefreshing = true
            runCatching { AgentPlaylist.refreshWithProfile(context, songs, records, favoriteIds) }
                .onFailure { android.util.Log.w("Listen", "agent playlist refresh failed: ${it.message}") }
            // 自动生成事件 = 一次天然的打扫时机：此刻不会有人在画封面，
            // 顺手把「没有任何歌单引用」的历史封面（含上次生成失败留下的半成品）全清掉。
            runCatching { AgentPlaylistStore.cleanupCovers(context) }
                .onFailure { android.util.Log.w("Listen", "cover cleanup failed: ${it.message}") }
            agentHistory = AgentPlaylistStore.load()
            agentRefreshing = false
        }
    }

    fun generateAgentPlaylist() {
        if (agentRefreshing) return
        agentRefreshing = true
        scope.launch {
            runCatching { AgentPlaylist.refreshWithProfile(context, songs, records, favoriteIds) }
                .onSuccess {
                    agentHistory = AgentPlaylistStore.load()
                    Toast.makeText(context, "已生成并加入「Agent 专属歌单」", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            agentRefreshing = false
        }
    }

    // ── 新建歌单弹层
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    // 底部垫高两阶段：无播放底栏 = 悬浮导航栏一档；正在播放 = 再让出 NowPlayingBar
    // （66dp 胶囊 + 间距），否则列表尾部被底栏+导航栏双层遮挡。播放态切换时平滑过渡。
    val playerState by SongPlaybackController.state.collectAsState()
    val listenBottomExtra by animateDpAsState(
        targetValue = if (playerState.track != null) {
            NOW_PLAYING_BAR_CLEARANCE + 16.dp
        } else 16.dp,
        animationSpec = KibaMotion.snapOr(spring()),
        label = "listenBottomExtra",
    )

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
            contentPadding = pageContentPadding(bottomExtra = listenBottomExtra),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ══ 智能推荐（横向分栏：每栏 5 首）══
            item(key = "rec_title") {
                SectionTitle(
                    if (records.isEmpty() && favoriteIds.isEmpty()) "随便听听"
                    else "为你推荐",
                )
            }
            if (recommendedSongs.isEmpty()) {
                item(key = "rec_empty") {
                    EmptyStateCard(text = "曲库还没同步\n先去「曲目」页右上角刷新曲库数据")
                }
            } else {
                item(key = "rec_row") {
                    RecommendColumns(
                        songs = recommendedSongs,
                        onPlay = { song -> SongPlaybackController.play(context, song.toTrack()) },
                        onDetail = { song -> onOpenSong(song.songData.id) },
                    )
                }
            }

            // ══ 收藏连播 ══
            if (favoriteIds.isNotEmpty()) {
                item(key = "fav_play") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val tracks = favoriteIds.mapNotNull { id ->
                                id.toIntOrNull()?.let { sid -> songs.firstOrNull { it.songData.id == sid } }
                            }.map { it.toTrack() }
                            if (tracks.isEmpty()) {
                                Toast.makeText(context, "收藏的曲目不在当前曲库中", Toast.LENGTH_SHORT).show()
                                return@Card
                            }
                            SongPlaybackController.playAll(context, tracks)
                        },
                        pressFeedbackType = PressFeedbackType.Sink,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = MiuixGlassIcons.Regular.Favorites,
                                contentDescription = "收藏连播",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "收藏连播",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "一键连播收藏的 ${favoriteIds.size} 首乐曲",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            // ══ 我的下载 ══
            item(key = "downloads") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenDownloads,
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = MiuixGlassIcons.Regular.Download,
                            contentDescription = "我的下载",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "我的下载",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (downloads.isEmpty()) "播放页里可以下载正在听的乐曲"
                                else "已下载 ${downloads.size} 首 · 点击试听或删除",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            // ══ 我的歌单 ══
            item(key = "pl_title") { SectionTitle("我的歌单") }
            item(key = "pl_create") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        newPlaylistName = ""
                        showCreatePlaylist = true
                    },
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = MiuixGlassIcons.Regular.Add,
                            contentDescription = "新建歌单",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "新建歌单",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (playlists.isEmpty()) {
                item(key = "pl_empty") {
                    EmptyStateCard(text = "还没有歌单\n点上面的「新建歌单」，再从曲目详情加入歌曲")
                }
            } else {
                items(playlists.size, key = { "pl_${playlists[it].id}" }) { index ->
                    val pl = playlists[index]
                    val count = playlistSongCount.count { it.playlistId == pl.id }
                    val firstSong = playlistSongCount.firstOrNull { it.playlistId == pl.id }
                        ?.songId?.let { sid -> songs.firstOrNull { it.songData.id == sid } }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenPlaylist(pl.id) },
                        pressFeedbackType = PressFeedbackType.Sink,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 歌单缩略图：首曲封面（与详情页大封面同源）
                            if (firstSong != null) {
                                CoverFrame(
                                    songId = firstSong.songData.id,
                                    imageUrl = firstSong.songData.imageUrl,
                                    genre = firstSong.songData.genre,
                                    size = 52.dp,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = MiuixGlassIcons.Regular.Playlist,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = pl.name,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$count 首",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            // ══ Agent 专属歌单（历史存档，每张一个入口）══
            item(key = "agent_title") { SectionTitle("Agent 专属歌单") }
            // 历史歌单先摆出来 —— 没配 LLM 也要能看到以前生成的（迁移进来的也在其中），
            // 否则「关掉模型配置」等于把已生成的歌单一起藏了
            if (llmReady) {
                item(key = "agent_generate") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (!agentRefreshing) generateAgentPlaylist() },
                        pressFeedbackType = PressFeedbackType.Sink,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = MiuixGlassIcons.Regular.AiScan,
                                contentDescription = "生成专属歌单",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (agentRefreshing) "正在生成…" else "让 Agent 生成一张新歌单",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "每张歌单都会按封面模板生成专属封面，历史歌单保留可回看",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            if (agentHistory.isNotEmpty()) {
                item(key = "agent_clear") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${agentHistory.size} 张歌单",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "清空全部",
                            onClick = {
                                AgentPlaylistStore.clear()
                                agentHistory = AgentPlaylistStore.load()
                                Toast.makeText(context, "已清空全部 Agent 歌单", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
                items(agentHistory.size, key = { "agent_${agentHistory[it].id}" }) { index ->
                    val item = agentHistory[index]
                    AgentPlaylistEntry(
                        item = item,
                        onClick = { onOpenAgentPlaylist(item.id) },
                        onDelete = {
                            AgentPlaylistStore.delete(item)
                            agentHistory = AgentPlaylistStore.load()
                            Toast.makeText(context, "已删除「${item.name}」", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            } else if (llmReady) {
                item(key = "agent_empty") {
                    EmptyStateCard(text = "还没有生成过歌单\n点上面那一行，Agent 会按你的口味挑 5~15 首")
                }
            } else {
                item(key = "agent_hint") {
                    EmptyStateCard(
                        text = "在「设置 → 模型配置」启用 LLM 后\nAgent 会按你的口味生成专属歌单（最多 15 首）",
                    )
                }
            }
        }
    }

    // ── 新建歌单弹层 ──
    KibaGlassDialog(
        show = showCreatePlaylist,
        title = "新建歌单",
        summary = "创建后从曲目详情页把歌曲加进来",
        onDismissRequest = { showCreatePlaylist = false },
    ) {
        Column {
            TextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = "歌单名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showCreatePlaylist = false },
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "创建",
                    onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isEmpty()) {
                            Toast.makeText(context, "先给歌单起个名字", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppDataBase.getInstance().playlistDao().createWithSongs(
                                    PlaylistEntity(name = name, createdAt = System.currentTimeMillis()),
                                    emptyList(),
                                )
                            }
                            showCreatePlaylist = false
                            Toast.makeText(context, "歌单「$name」已创建", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 推荐区：横向分栏，每栏竖排 5 首。
 *
 * 栏宽 = 屏宽 × 0.84，**故意留出 16% 给下一栏露头**——用户一眼就能看出「左边还有」，
 * 不用试探性滑动。每栏内 5 首紧凑行（封面 44dp + 单行歌名），一屏正好一栏看完。
 */
@Composable
private fun RecommendColumns(
    songs: List<SongWithChartsEntity>,
    onPlay: (SongWithChartsEntity) -> Unit,
    onDetail: (SongWithChartsEntity) -> Unit,
) {
    val perColumn = 5
    val columns = songs.chunked(perColumn)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = maxWidth * 0.84f
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp),
        ) {
            items(columns.size, key = { "col_$it" }) { colIndex ->
                Column(
                    modifier = Modifier.width(columnWidth),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    columns[colIndex].forEach { song ->
                        RecommendMiniCard(
                            song = song,
                            onClick = { onPlay(song) },
                            onDetail = { onDetail(song) },
                        )
                    }
                }
            }
        }
    }
}

/** 推荐区紧凑卡：封面 44dp + 单行歌名 + 流派；整卡点播放，右侧小 ⓘ 进详情。 */
@Composable
private fun RecommendMiniCard(
    song: SongWithChartsEntity,
    onClick: () -> Unit,
    onDetail: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverFrame(
                songId = song.songData.id,
                imageUrl = song.songData.imageUrl,
                genre = song.songData.genre,
                size = 44.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.songData.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.songData.genre,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDetail)
                    .padding(6.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = MiuixGlassIcons.Regular.Info,
                    contentDescription = "曲目详情",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Agent 歌单入口：模板封面缩略图 + 名称 + 曲目数/生成时间 + 删除。 */
@Composable
private fun AgentPlaylistEntry(
    item: AgentPlaylistStore.Item,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面：模板生成的 PNG；文件被清理时回落占位块（不显示破图）
            val cover = item.coverPath.takeIf { it.isNotBlank() }
            if (cover != null) {
                AsyncImage(
                    model = java.io.File(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = MiuixGlassIcons.Regular.Playlist,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // createdAt=0 是「从旧版本迁移进来」的条目（当时没存时间），
                    // 格式化出来会是 1970-01-01，不如直接不显示时间
                    text = if (item.createdAt > 0L) {
                        "${item.ids.size} 首 · ${timeFmt.format(Date(item.createdAt))}"
                    } else {
                        "${item.ids.size} 首 · 旧版迁移"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete)
                    .padding(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = MiuixGlassIcons.Regular.Delete,
                    contentDescription = "删除歌单",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 听页歌曲行：封面 + 标题 + 副标题 + 播放 / 详情（可定制尾部按钮）。
 *
 * 整行点击 = 播放（需求口径：「正常点击即播放歌曲」）；尾部按钮区默认「详情」跳曲目详情，
 * 歌单内可传入「移除 + 详情」组合。
 */
@Composable
fun ListenSongRow(
    song: SongWithChartsEntity,
    onClick: () -> Unit,
    onDetail: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverFrame(
                songId = song.songData.id,
                imageUrl = song.songData.imageUrl,
                genre = song.songData.genre,
                size = 56.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.songData.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle ?: buildString {
                        val artist = song.songData.artist.takeIf { it.isNotBlank() && it != "-" }
                        if (artist != null) {
                            append(artist)
                            append(" · ")
                        }
                        append(song.songData.genre)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                // 「详情」按钮：跳曲目详情（独立 clickable 子元素，不吞整行点击）
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDetail)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = MiuixGlassIcons.Regular.Info,
                        contentDescription = "曲目详情",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
