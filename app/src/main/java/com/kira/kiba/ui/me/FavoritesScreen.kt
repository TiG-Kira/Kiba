package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.repository.ChuniSongWithChartRepository
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.ListSnapshotCache
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.songs.ChuniSongCard
import com.kira.kiba.ui.songs.ChuniSongsListCache
import com.kira.kiba.ui.songs.SongCard
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 收藏列表快照（进程级）：从歌曲详情返回时首帧即渲染上次的收藏列表，
 * 否则 LazyColumn 空测量会把恢复出的滚动位置钳回 0（详见 [ListSnapshotCache]）。
 */
object FavoritesListCache : ListSnapshotCache<List<SongWithChartsEntity>>(emptyList())

/**
 * 我的收藏：列出在歌曲详情页标记过收藏的歌曲，**按曲目页游戏开关切换数据源**。
 *
 * - 舞萌：收藏键 = songId（SpUtil songInfoPrefs），列表走
 *   [SongWithChartRepository.searchSongsWithCharts] 的 isFavor 通道；
 * - 中二节奏：收藏键 = `chuni:` 前缀隔离，列表走
 *   [ChuniSongWithChartRepository.searchChuniSongsWithCharts] 的 isFavor 通道。
 */
@Composable
fun FavoritesScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (SongWithChartsEntity) -> Unit,
    onOpenChuniSong: (ChuniSongWithCharts) -> Unit,
) {
    val gameType = remember { SpUtil.getGameType() }
    val isChunithm = gameType == GameType.CHUNITHM

    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    val chuniRepo = remember {
        ChuniSongWithChartRepository.getInstance(AppDataBase.getInstance().chuniSongWithChartDao())
    }
    // 收藏 id 变化时重建查询（收藏是 SP 存储，不是 Room）
    val favIds by produceState(initialValue = SpUtil.getFavIds(), key1 = Unit) {
        value = SpUtil.getFavIds()
    }
    val chuniFavIds by produceState(
        initialValue = SpUtil.getFavIds(GameType.CHUNITHM), key1 = Unit,
    ) {
        value = SpUtil.getFavIds(GameType.CHUNITHM)
    }
    val songs by remember(favIds) {
        repo.searchSongsWithCharts(
            searchText = "",
            genreList = emptyList(),
            versionList = emptyList(),
            selectLevel = null,
            sequencing = null,
            ds = null,
            isFavor = true,
            isMatchAlias = true,
            isMatchCharter = false,
            isMatchSongId = true,
        ).asFlow()
    }.collectAsStateCached(FavoritesListCache)
    val chuniSongs by remember(chuniFavIds) {
        chuniRepo.searchChuniSongsWithCharts(searchText = "", isFavor = true).asFlow()
    }.collectAsStateCached(ChuniSongsListCache)

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
            if (isChunithm) {
                // ── 中二节奏收藏 ──
                item(key = "count") {
                    SectionTitle("已收藏 ${chuniSongs.size} 首")
                }
                if (chuniSongs.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateCard(
                            text = "还没有收藏任何中二节奏歌曲\n在中二歌曲详情页打开「收藏」开关即可加入这里",
                        )
                    }
                }
                items(chuniSongs.size, key = { chuniSongs[it].song.id }) { index ->
                    ChuniSongCard(
                        song = chuniSongs[index],
                        onClick = { onOpenChuniSong(chuniSongs[index]) },
                    )
                }
            } else {
                // ── 舞萌收藏 ──
                item(key = "count") {
                    SectionTitle("已收藏 ${songs.size} 首")
                }
                if (songs.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateCard(
                            text = "还没有收藏任何歌曲\n在歌曲详情页打开「收藏」开关即可加入这里",
                        )
                    }
                }
                items(songs.size, key = { songs[it].songData.id }) { index ->
                    SongCard(song = songs[index], onClick = { onOpenSong(songs[index]) })
                }
            }
        }
    }
}
