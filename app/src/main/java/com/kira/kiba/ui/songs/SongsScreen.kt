package com.kira.kiba.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 曲目页：真实曲库数据（SongWithChartRepository → Room → LiveData 响应式刷新）。
 *
 * 曲库更新链路（复刻 MaimaiData）：
 * - 启动 / 手动「检查更新」→ [SongDataSync.checkForUpdate] 拉 update.json 比对版本；
 * - 有新版本 → [SongDataSync.updateAvailable] 非空 → 弹出更新对话框（稍后 / 立即更新）；
 * - 立即更新 → [KibaState.refreshSongData] 下载 + 入库，Room LiveData 自动推送，列表即刷新；
 * - 顶部「曲库数据」卡常驻展示：当前版本 / 歌曲数 / 同步状态 / 下载进度。
 *
 * @param searchTrigger 顶栏搜索按钮每次点击 +1，页面据此聚焦搜索框（0 不聚焦）。
 */
@Composable
fun SongsScreen(
    scrollBehavior: ScrollBehavior,
    searchTrigger: Int = 0,
    onSongClick: (SongWithChartsEntity) -> Unit = {},
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(searchTrigger) {
        if (searchTrigger > 0) searchFocusRequester.requestFocus()
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
    val songs by songsFlow.collectAsState(initial = emptyList())

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            items(items = songs, key = { it.songData.id }) { song ->
                SongCard(song = song, onClick = { onSongClick(song) })
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

/** 歌曲卡片：封面框 + 标题/艺术家 + 最高难度分类 + ds。 */
@Composable
fun SongCard(
    song: SongWithChartsEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topChart: ChartEntity? = song.charts.maxByOrNull { it.ds }
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        BasicComponent(
            title = song.songData.title,
            summary = buildString {
                append(song.songData.artist)
                append(" · ")
                append(song.songData.genre)
                if (topChart != null) {
                    append(" · ds ")
                    append(topChart.ds)
                }
            },
            startAction = {
                CoverFrame(songId = song.songData.id, genre = song.songData.genre)
            },
            endActions = {
                Column(horizontalAlignment = Alignment.End) {
                    if (topChart != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChartTypeChip(type = song.songData.type)
                            HGap()
                            DifficultyChip(
                                text = "${difficultyLabel(topChart.difficultyType)} ${topChart.level}",
                                color = difficultyColor(topChart.difficultyType),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 各难度等级一览（BASIC 12 / ADVANCED 12+ …）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            song.charts.sortedBy { it.difficultyType.ordinal }.forEach { chart ->
                                DifficultyChip(
                                    text = chart.level,
                                    color = difficultyColor(chart.difficultyType).copy(alpha = 0.85f),
                                    modifier = Modifier.padding(start = 3.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
