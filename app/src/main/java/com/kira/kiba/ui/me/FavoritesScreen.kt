package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.songs.SongCard
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 我的收藏：列出在歌曲详情页标记过收藏的歌曲。
 *
 * 收藏状态存在 SpUtil（songInfoPrefs，key = songId）；
 * 列表通过 [SongWithChartRepository.searchSongsWithCharts] 的 isFavor 通道取，
 * 曲库更新后自动刷新。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (SongWithChartsEntity) -> Unit,
) {
    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    // 收藏 id 变化时重建查询（收藏是 SP 存储，不是 Room）
    val favIds by produceState(initialValue = SpUtil.getFavIds(), key1 = Unit) {
        value = SpUtil.getFavIds()
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
    }.collectAsState(initial = emptyList())

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
