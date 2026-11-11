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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.listen.SongDownloader
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.common.CoverFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import androidx.lifecycle.asFlow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/**
 * 我的下载（「听」页入口）：已下载乐曲的管理页。
 *
 * - 点击整行 = 离线播放本地文件（MediaStore content uri，MediaPlayer 原生支持）；
 * - 「删除」按钮 = 从媒体库与存储中删除该文件（含索引摘除）；
 * - 「详情」按钮 = 跳曲目详情（元数据口径与下载时对齐）。
 */
@Composable
fun DownloadsScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (Int) -> Unit,
) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf<List<SongDownloader.DownloadedSong>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(refreshTick) {
        downloads = withContext(Dispatchers.IO) { SongDownloader.listAll(context) }
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
            if (downloads.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "还没有下载的乐曲\n播放页里点「下载」即可离线试听",
                    )
                }
            } else {
                items(downloads.size, key = { "dl_${downloads[it].songId}" }) { index ->
                    val item = downloads[index]
                    // 封面/流派从曲库补（MediaStore 只存了文本元数据）
                    val swc by produceState<com.kira.kiba.core.db.entity.SongWithChartsEntity?>(
                        initialValue = null, item.songId,
                    ) {
                        value = withContext(Dispatchers.IO) {
                            SongWithChartRepository.getInstance(
                                AppDataBase.getInstance().songWithChartDao(),
                            ).searchSongsWithCharts(
                                searchText = "", genreList = emptyList(), versionList = emptyList(),
                                selectLevel = null, sequencing = null, ds = null,
                                isFavor = false, isMatchAlias = true, isMatchCharter = false, isMatchSongId = true,
                            ).asFlow().firstOrNull().orEmpty()
                                .firstOrNull { it.songData.id == item.songId }
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val s = swc
                            val track = if (s != null) {
                                // 本地文件：audioUrl 换成 MediaStore uri，其余口径与在线播放一致
                                s.toTrack().copy(audioUrl = item.uri.toString())
                            } else {
                                SongPlaybackController.Track(
                                    songId = item.songId,
                                    title = item.title,
                                    artist = item.artist,
                                    imageUrl = "",
                                    genre = "",
                                    coverCandidates = emptyList(),
                                    audioUrl = item.uri.toString(),
                                )
                            }
                            SongPlaybackController.play(context, track)
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val s = swc
                            if (s != null) {
                                CoverFrame(
                                    songId = s.songData.id,
                                    imageUrl = s.songData.imageUrl,
                                    genre = s.songData.genre,
                                    size = 56.dp,
                                )
                            } else {
                                CoverFrame(
                                    songId = item.songId,
                                    imageUrl = "",
                                    genre = "",
                                    size = 56.dp,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(item.artist)
                                        append(" · ")
                                        append(formatSize(item.sizeBytes))
                                    },
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 详情按钮
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenSong(item.songId) }
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
                            // 删除按钮
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val deleted = SongDownloader.delete(context, item.songId)
                                        Toast.makeText(
                                            context,
                                            if (deleted) "已删除「${item.title}」" else "删除失败",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        refreshTick++
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = MiuixGlassIcons.Regular.Delete,
                                    contentDescription = "删除",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
