package com.kira.kiba.ui.songs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 归类分组子页面：展示某个版本 / MASTER 谱师 / 曲师下的全部歌曲。
 *
 * 数据来源与曲目页同源（Room LiveData → Flow），仅按分组键过滤；
 * 歌曲卡复用 [SongCard]（与曲目页平铺视图一致）。
 */
@Composable
fun SongGroupScreen(
    mode: SongGroupMode,
    groupKey: String,
    scrollBehavior: ScrollBehavior,
    onSongClick: (songId: Int) -> Unit,
) {
    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    val songsFlow = remember {
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
    val songs by songsFlow.collectAsStateCached(SongsListCache)
    val filtered = songs.filter { songInGroup(it, mode, groupKey) }

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
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(text = "这个分组下还没有歌曲")
                }
            }
            items(items = filtered, key = { it.songData.id }) { song ->
                SongCard(song = song, onClick = { onSongClick(song.songData.id) })
            }
        }
    }
}
