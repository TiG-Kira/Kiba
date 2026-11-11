package com.kira.kiba.ui.listen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.PlaylistSongEntity
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.common.CoverFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Remove
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.firstOrNull

/**
 * 歌单详情子页：歌单内歌曲管理（点歌即播 / 整单播放 / 移除 / 跳详情）。
 *
 * 歌单数据走 Room Flow 实时刷新——移除一首立刻少一行，不用手动刷新。
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    scrollBehavior: ScrollBehavior,
    onOpenSong: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDataBase.getInstance().playlistDao() }

    val playlist by produceState<com.kira.kiba.core.db.entity.PlaylistEntity?>(initialValue = null, playlistId) {
        value = withContext(Dispatchers.IO) { dao.getById(playlistId) }
    }
    val songRefs by dao.observeSongs(playlistId).collectAsState(initial = emptyList())
    // 关联曲库实体（封面/流派/可播放 Track 都要从 SongWithChartsEntity 拿）
    val songs by produceState<Map<Int, com.kira.kiba.core.db.entity.SongWithChartsEntity>>(
        initialValue = emptyMap(),
    ) {
        value = withContext(Dispatchers.IO) {
            SongWithChartRepository.getInstance(
                AppDataBase.getInstance().songWithChartDao(),
            ).searchSongsWithCharts(
                searchText = "", genreList = emptyList(), versionList = emptyList(),
                selectLevel = null, sequencing = null, ds = null,
                isFavor = false, isMatchAlias = true, isMatchCharter = false, isMatchSongId = true,
            ).asFlow().firstOrNull().orEmpty().associateBy { it.songData.id }
        }
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
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                val pl = playlist
                if (pl != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = pl.name,
                                style = MiuixTheme.textStyles.headline2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${songRefs.size} 首 · 点击歌曲即播放",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row {
                                TextButton(
                                    text = "整单播放",
                                    onClick = {
                                        val tracks = songRefs.mapNotNull { ref ->
                                            songs[ref.songId]?.toTrack()
                                        }
                                        if (tracks.isEmpty()) {
                                            Toast.makeText(context, "歌单还没有可播放的歌曲", Toast.LENGTH_SHORT).show()
                                        } else {
                                            SongPlaybackController.playAll(context, tracks)
                                        }
                                    },
                                )
                                Spacer(Modifier.width(12.dp))
                                TextButton(
                                    text = "删除歌单",
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { dao.delete(playlistId) }
                                            Toast.makeText(context, "歌单已删除", Toast.LENGTH_SHORT).show()
                                            onBack()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (songRefs.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(text = "歌单还是空的\n去歌曲详情页点「加入歌单」把歌加进来")
                }
            } else {
                items(songRefs.size, key = { "s_${songRefs[it].songId}" }) { index ->
                    val ref = songRefs[index]
                    val swc = songs[ref.songId]
                    if (swc != null) {
                        ListenSongRow(
                            song = swc,
                            onClick = { SongPlaybackController.play(context, swc.toTrack()) },
                            onDetail = { onOpenSong(swc.songData.id) },
                            // 「移除 + 详情」双按钮（覆盖默认详情按钮）
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .smallButton {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        dao.removeSong(playlistId, ref.songId)
                                                    }
                                                    Toast.makeText(context, "已从歌单移除", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.Icon(
                                            imageVector = MiuixGlassIcons.Regular.Remove,
                                            contentDescription = "移出歌单",
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .smallButton { onOpenSong(swc.songData.id) }
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
                            },
                        )
                    } else {
                        // 曲库里找不到这首歌（理论上不会）：显示占位行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "未知曲目 #${ref.songId}",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "已失效",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 无涟漪小按钮统一走标准 clickable（行内独立子元素不会拦截整行卡片点击）。 */
private fun Modifier.smallButton(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
