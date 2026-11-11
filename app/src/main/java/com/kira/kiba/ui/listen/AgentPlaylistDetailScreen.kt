package com.kira.kiba.ui.listen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import coil.compose.AsyncImage
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.listen.AgentPlaylistStore
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.KibaGlassDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Agent 歌单详情页（2.6.0 新增）：一张存档歌单的完整内容。
 *
 * 与「我的歌单」详情页的区别：这里的曲目**不可增删**（是 Agent 当时挑的那套），
 * 但保留每首歌的推荐理由，并能整单播放 / 删除整张 / 重新设计封面（走 Agent 的封面模板）。
 *
 * 大封面顶栏由 MainScreen 的 CollectionTopBarSlot 提供（同歌曲详情页的封面顶栏），
 * 本页只负责内容：封面卡 + 推荐语 + 曲目列表。
 */
@Composable
fun AgentPlaylistDetailScreen(
    playlistId: String,
    scrollBehavior: ScrollBehavior,
    onOpenSong: (Int) -> Unit,
) {
    val context = LocalContext.current
    var item by remember(playlistId) { mutableStateOf(AgentPlaylistStore.get(playlistId)) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 曲库实体（封面 / 播放 Track 都需要）
    val songs by produceState<Map<Int, SongWithChartsEntity>>(initialValue = emptyMap()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
                    .searchSongsWithCharts(
                        searchText = "", genreList = emptyList(), versionList = emptyList(),
                        selectLevel = null, sequencing = null, ds = null,
                        isFavor = false, isMatchAlias = true, isMatchCharter = false,
                        isMatchSongId = true,
                    ).asFlow().firstOrNull().orEmpty().associateBy { it.songData.id }
            }.getOrDefault(emptyMap())
        }
    }

    val current = item
    val tracks = remember(current, songs) {
        current?.ids?.mapNotNull { id -> songs[id] }?.map { it.toTrack() }.orEmpty()
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
            if (current == null) {
                item(key = "empty") {
                    EmptyStateCard(text = "这张歌单已经不在了\n可能已被删除，返回上一页看看别的")
                }
            } else {
                item(key = "head") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cover = current.coverPath.takeIf { it.isNotBlank() }
                            if (cover != null) {
                                AsyncImage(
                                    model = File(cover),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Agent",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = current.name,
                                    style = MiuixTheme.textStyles.headline2,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${current.ids.size} 首 · Agent 专属歌单",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (current.summary.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = current.summary,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        ) {
                            TextButton(
                                text = "整单播放",
                                onClick = {
                                    if (tracks.isEmpty()) {
                                        Toast.makeText(context, "歌单里的曲子不在当前曲库中", Toast.LENGTH_SHORT).show()
                                    } else {
                                        SongPlaybackController.playAll(context, tracks)
                                    }
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                text = "删除歌单",
                                onClick = { showDeleteConfirm = true },
                            )
                        }
                    }
                }

                item(key = "songs_title") { SectionTitle("曲目（${current.ids.size}）") }

                if (current.ids.isEmpty()) {
                    item(key = "songs_empty") {
                        EmptyStateCard(text = "这张歌单没有曲目")
                    }
                } else {
                    items(current.ids.size, key = { "s_${current.ids[it]}" }) { index ->
                        val songId = current.ids[index]
                        val song = songs[songId]
                        if (song != null) {
                            ListenSongRow(
                                song = song,
                                subtitle = current.reasons.getOrNull(index)?.takeIf { it.isNotBlank() },
                                onClick = { SongPlaybackController.play(context, song.toTrack()) },
                                onDetail = { onOpenSong(songId) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && current != null) {
        KibaGlassDialog(
            show = true,
            title = "删除歌单",
            summary = "「${current.name}」将从历史歌单中移除，封面文件一并删除。不可撤销。",
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { showDeleteConfirm = false })
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        AgentPlaylistStore.delete(current)
                        item = null
                        showDeleteConfirm = false
                    },
                )
            }
        }
    }
}
