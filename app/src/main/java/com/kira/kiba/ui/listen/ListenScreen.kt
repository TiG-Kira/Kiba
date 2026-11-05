package com.kira.kiba.ui.listen

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.agent.KibaLlm
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.PlaylistEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.listen.AgentPlaylist
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
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Favorites
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.Playlist
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「听」页：Kiba 的音乐推荐与歌单中枢（仅舞萌——中二节奏无整轨音源）。
 *
 * - **智能推荐**：本地算法从 Best 40 + 收藏提炼口味画像，从曲库挑 15 首；
 *   点击即播，「详情」按钮跳曲目详情。
 * - **收藏连播**：一键把收藏列表灌进播放队列（playAll 整队替换）。
 * - **我的下载**：已下载乐曲的管理入口（听 / 删）。
 * - **我的歌单**：用户自建歌单（Room 持久化），点歌即播、整单播放。
 * - **Agent 专属歌单**：LLM 生成的个性化歌单（≤15 首），按设置页间隔自动刷新。
 */
@Composable
fun ListenScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (Int) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
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

    // ── Agent 专属歌单（仅 LLM 配置后；过期自动刷新）──
    val llmReady = remember { KibaLlm.isConfigured() }
    var agentSaved by remember { mutableStateOf(AgentPlaylist.load()) }
    var agentRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (llmReady && agentSaved == null || llmReady && AgentPlaylist.isStale()) {
            agentRefreshing = true
            val result = runCatching { AgentPlaylist.refreshWithProfile(songs, records, favoriteIds) }
            result.onSuccess { agentSaved = it }
                .onFailure { Log.w("Listen", "agent playlist refresh failed: ${it.message}") }
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
            // ══ 智能推荐 ══
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
                items(recommendedSongs.size, key = { "rec_${recommendedSongs[it].songData.id}" }) { index ->
                    ListenSongRow(
                        song = recommendedSongs[index],
                        onClick = {
                            SongPlaybackController.play(context, recommendedSongs[index].toTrack())
                        },
                        onDetail = { onOpenSong(recommendedSongs[index].songData.id) },
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenPlaylist(pl.id) },
                        pressFeedbackType = PressFeedbackType.Sink,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = MiuixGlassIcons.Regular.Playlist,
                                contentDescription = "歌单",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
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

            // ══ Agent 专属歌单 ══
            item(key = "agent_title") { SectionTitle("Agent 专属歌单") }
            if (!llmReady) {
                item(key = "agent_hint") {
                    EmptyStateCard(
                        text = "在「设置 → 模型配置」启用 LLM 后\nAgent 会按你的口味生成专属歌单（最多 15 首）",
                    )
                }
            } else {
                val saved = agentSaved
                if (agentRefreshing && saved == null) {
                    item(key = "agent_loading") {
                        EmptyStateCard(text = "Agent 正在品味你的歌单…")
                    }
                } else if (saved == null || saved.isEmpty) {
                    item(key = "agent_empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (!agentRefreshing) {
                                    agentRefreshing = true
                                    scope.launch {
                                        runCatching { AgentPlaylist.refreshWithProfile(songs, records, favoriteIds) }
                                            .onSuccess { agentSaved = it }
                                            .onFailure { e ->
                                                Toast.makeText(context, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        agentRefreshing = false
                                    }
                                }
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
                                    imageVector = MiuixGlassIcons.Regular.AiScan,
                                    contentDescription = "生成专属歌单",
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = if (agentRefreshing) "生成中…" else "让 Agent 生成专属歌单",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                } else {
                    item(key = "agent_summary") {
                        AgentPlaylistCard(
                            saved = saved,
                            refreshing = agentRefreshing,
                            songs = songs,
                            onRefresh = {
                                if (!agentRefreshing) {
                                    agentRefreshing = true
                                    scope.launch {
                                        runCatching { AgentPlaylist.refreshWithProfile(songs, records, favoriteIds) }
                                            .onSuccess { agentSaved = it }
                                            .onFailure { e ->
                                                Toast.makeText(context, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        agentRefreshing = false
                                    }
                                }
                            },
                            onPlayAll = {
                                val tracks = saved.ids.mapNotNull { id ->
                                    songs.firstOrNull { it.songData.id == id }
                                }.map { it.toTrack() }
                                if (tracks.isNotEmpty()) SongPlaybackController.playAll(context, tracks)
                            },
                        )
                    }
                    items(saved.ids.size, key = { "agent_${saved.ids[it]}" }) { index ->
                        val song = songs.firstOrNull { it.songData.id == saved.ids[index] }
                        if (song != null) {
                            ListenSongRow(
                                song = song,
                                subtitle = saved.reasons.getOrNull(index),
                                onClick = { SongPlaybackController.play(context, song.toTrack()) },
                                onDetail = { onOpenSong(song.songData.id) },
                            )
                        }
                    }
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

/** Agent 歌单头卡：总结语 + 整单播放 + 手动刷新。 */
@Composable
private fun AgentPlaylistCard(
    saved: AgentPlaylist.Saved,
    refreshing: Boolean,
    songs: List<SongWithChartsEntity>,
    onRefresh: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Agent 为你挑选了 ${saved.ids.size} 首",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (saved.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = saved.summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(text = "整单播放", onClick = onPlayAll)
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = if (refreshing) "刷新中…" else "重新生成",
                    onClick = onRefresh,
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
