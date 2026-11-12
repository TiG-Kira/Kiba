package com.kira.kiba.ui.songs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.model.SongSortMode
import com.kira.kiba.core.model.sortedBySongMode
import com.kira.kiba.core.repository.ChuniSongWithChartRepository
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 归类分组子页面：展示某个版本（舞萌还有 MASTER 谱师 / 曲师）下的全部歌曲。
 *
 * 数据来源与曲目页同源（Room LiveData → Flow），仅按分组键过滤；歌曲卡复用
 * [SongCard] / [ChuniSongCard]（与曲目页平铺视图一致）。
 *
 * 游戏类型在**进页面那一刻**读一次：分组页只能从当前游戏的曲目列表点进来，
 * 不存在「人在子页里切游戏」的路径，所以不需要响应式；也正因为如此，只查
 * 当前游戏那套曲库，不做双份查询。
 */
@Composable
fun SongGroupScreen(
    mode: SongGroupMode,
    groupKey: String,
    scrollBehavior: ScrollBehavior,
    onSongClick: (songId: Int) -> Unit,
    onChuniSongClick: (ChuniSongWithCharts) -> Unit = {},
) {
    val gameType = remember { SpUtil.getGameType() }
    // 与曲目页保持同一套排序方式（分组内也按用户选的定数 / 等级 / 曲名排序）
    val sortMode = remember { SongSortMode.fromKey(SpUtil.getSongSortMode()) }
    val isChuni = gameType == GameType.CHUNITHM

    // 条件组合是可接受的：isChuni 在本页生命周期内恒定（见函数注释）
    val maimaiSongs = if (isChuni) emptyList() else rememberMaimaiSongs()
    val chuniSongs = if (isChuni) rememberChuniSongs() else emptyList()

    val filteredMaimai = remember(maimaiSongs, sortMode, mode, groupKey) {
        maimaiSongs.filter { songInGroup(it, mode, groupKey) }
            .sortedBySongMode(sortMode, ::maimaiSongSortKey)
    }
    val filteredChuni = remember(chuniSongs, sortMode, mode, groupKey) {
        chuniSongs.filter { chuniSongInGroup(it, mode, groupKey) }
            .sortedBySongMode(sortMode, ::chuniSongSortKey)
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
            if (isChuni) {
                if (filteredChuni.isEmpty()) {
                    item(key = "empty") { EmptyStateCard(text = "这个分组下还没有歌曲") }
                } else {
                    items(items = filteredChuni, key = { it.song.id }) { song ->
                        ChuniSongCard(song = song, onClick = { onChuniSongClick(song) })
                    }
                }
            } else {
                if (filteredMaimai.isEmpty()) {
                    item(key = "empty") { EmptyStateCard(text = "这个分组下还没有歌曲") }
                } else {
                    items(items = filteredMaimai, key = { it.songData.id }) { song ->
                        SongCard(song = song, onClick = { onSongClick(song.songData.id) })
                    }
                }
            }
        }
    }
}

/** 舞萌全量曲库（分组页只需要一份完整列表，过滤在调用方做）。 */
@Composable
private fun rememberMaimaiSongs(): List<SongWithChartsEntity> {
    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    val flow = remember {
        repo.searchSongsWithCharts(
            searchText = "",
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
    val songs by flow.collectAsStateCached(SongsListCache)
    return songs
}

/** 中二节奏全量曲库。 */
@Composable
private fun rememberChuniSongs(): List<ChuniSongWithCharts> {
    val repo = remember {
        ChuniSongWithChartRepository.getInstance(AppDataBase.getInstance().chuniSongWithChartDao())
    }
    val flow = remember {
        repo.searchChuniSongsWithCharts(searchText = "", isFavor = false).asFlow()
    }
    val songs by flow.collectAsStateCached(ChuniSongsListCache)
    return songs
}
