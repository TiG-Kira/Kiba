package com.kira.kiba.ui.songdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import android.widget.Toast
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.material.KibaGlassDialog
import com.kira.kiba.core.stats.SongRating
import com.kira.kiba.core.utils.CoverSources
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.InfoRow
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.VGap
import com.kira.kiba.ui.common.RankBadge
import com.kira.kiba.ui.common.ComboBadge
import com.kira.kiba.ui.common.SyncBadge
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 歌曲详情页（对标 MaimaiData SongDetailActivity + SongLevelFragment，Compose 化）：
 * - 头部：封面 / 标题 / ID / 艺术家 / BPM / 流派 / SD-DX / 版本 / 收藏开关；
 * - 别名 chips（alias 表实时刷新）；
 * - 各难度谱面卡（可点击进谱面详情）：定数（含旧定数升降提示）、谱师、notes 构成、
 *   BREAK 扣分线、单曲 Rating 档位表、本地成绩（达成率 / ra / DX 分数 / FC / FS）。
 */
@Composable
fun SongDetailScreen(
    songId: Int,
    scrollBehavior: ScrollBehavior,
    onOpenChart: (songId: Int, difficultyOrdinal: Int) -> Unit,
    onOpenPlayer: () -> Unit = {},
) {
    val db = remember { AppDataBase.getInstance() }
    val context = LocalContext.current

    // 歌曲 + 谱面：一次性挂载加载（详情页粒度）
    val song by produceState<SongDataEntity?>(initialValue = null) {
        value = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }
            .getOrNull()
    }
    val charts by produceState<List<ChartEntity>>(initialValue = emptyList()) {
        value = runCatching { db.chartDao().getChartsBySongId(songId) }.getOrDefault(emptyList())
    }
    // 别名 / 本地成绩：LiveData → Flow 响应式
    val aliases by remember(songId) {
        db.aliasDao().getAliasListBySongId(songId).asFlow()
    }.collectAsState(initial = emptyList())
    val records by remember(songId) {
        db.recordDao().getRecordsBySongId(songId).asFlow()
    }.collectAsState(initial = emptyList())

    val currentSong = song
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (currentSong == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = pageContentPadding(withNavBar = false),
            ) {
                item {
                    if (charts.isEmpty()) {
                        EmptyStateCard(
                            text = "歌曲加载中…\n若持续无数据，请先到「设置 → 曲库更新」同步曲库",
                        )
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 信息列表：类型（SD/DX 贴图）/ ID / BPM / 流派 / 版本 ──
            item(key = "tags") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "类型",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.weight(1f))
                            ChartTypeChip(type = currentSong.type)
                        }
                        InfoRow("ID", "${currentSong.id}")
                        InfoRow("BPM", "${currentSong.bpm}")
                        InfoRow("流派", currentSong.genre)
                        InfoRow("版本", currentSong.from)
                    }
                }
            }

            // ── 乐曲播放（音源：落雪整轨 mp3，详见 SongPlaybackController）──
            item(key = "play") {
                val playerState by SongPlaybackController.state.collectAsState()
                val isCurrent = playerState.track?.songId == currentSong.id
                val isPlayingThis = isCurrent && playerState.playing
                var showQueueChoice by remember { mutableStateOf(false) }
                val buildTrack = {
                    SongPlaybackController.Track(
                        songId = currentSong.id,
                        title = currentSong.title,
                        artist = currentSong.artist,
                        // imageUrl 是官网镜像的相对路径口径，UI 侧经 CoverFrame
                        // 候选链加载（与列表/详情页封面同源同款回退）。
                        imageUrl = currentSong.imageUrl,
                        genre = currentSong.genre,
                        coverCandidates = CoverSources.candidates(currentSong.id, currentSong.imageUrl),
                        audioUrl = SongPlaybackController.audioUrlFor(currentSong.id),
                    )
                }
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    onClick = {
                        // 会话进行中且点的是另一首：让用户选「立即切换」还是「加入列表」
                        val active = playerState.track
                        if (active != null && active.songId != currentSong.id && playerState.prepared) {
                            showQueueChoice = true
                        } else {
                            SongPlaybackController.play(context, buildTrack())
                            onOpenPlayer()
                        }
                    },
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = if (isPlayingThis) MiuixGlassIcons.Regular.Pause else MiuixGlassIcons.Regular.Play,
                            contentDescription = if (isPlayingThis) "正在播放" else "播放乐曲",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPlayingThis) "正在播放本曲" else "播放乐曲",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "整轨试听 · 音源来自落雪",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                // 会话进行中点其他曲：「立即切换 / 加入列表」选择弹层
                KibaGlassDialog(
                    show = showQueueChoice,
                    title = "正在播放中",
                    summary = "《${playerState.track?.title ?: ""}》正在播放，要怎么播放《${currentSong.title}》？",
                    onDismissRequest = { showQueueChoice = false },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(text = "加入列表", onClick = {
                            SongPlaybackController.queueTrack(context, buildTrack())
                            Toast.makeText(
                                context,
                                "已加入播放列表，当前曲播完后播放",
                                Toast.LENGTH_SHORT,
                            ).show()
                            showQueueChoice = false
                        })
                        Spacer(Modifier.width(12.dp))
                        TextButton(text = "立即播放", onClick = {
                            SongPlaybackController.play(context, buildTrack())
                            onOpenPlayer()
                            showQueueChoice = false
                        })
                    }
                }

                // ── 加入歌单（「听」页我的歌单的挑歌入口）──
                Spacer(Modifier.height(8.dp))
                val playlistScope = rememberCoroutineScope()
                var showAddPlaylist by remember { mutableStateOf(false) }
                var playlists by remember { mutableStateOf<List<com.kira.kiba.core.db.entity.PlaylistEntity>>(emptyList()) }
                LaunchedEffect(showAddPlaylist) {
                    if (showAddPlaylist) {
                        playlists = withContext(Dispatchers.IO) {
                            AppDataBase.getInstance().playlistDao().observeAll().firstOrNull().orEmpty()
                        }
                    }
                }
                Card(
                    modifier = (Modifier.fillMaxWidth()),
                    onClick = { showAddPlaylist = true },
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = MiuixGlassIcons.Regular.Add,
                            contentDescription = "加入歌单",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "加入歌单",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "收进「听」页我的歌单，整单连播",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                // 加入歌单弹层：列出全部歌单点选加入（无歌单时引导去「听」页创建）
                KibaGlassDialog(
                    show = showAddPlaylist,
                    title = "加入歌单",
                    summary = "把《${currentSong.title}》收进哪个歌单？",
                    onDismissRequest = { showAddPlaylist = false },
                ) {
                    Column {
                        if (playlists.isEmpty()) {
                            Text(
                                text = "还没有歌单\n去「听」页点「新建歌单」创建一个",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(12.dp))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                                items(playlists.size) { index ->
                                    val pl = playlists[index]
                                    TextButton(
                                        text = pl.name,
                                        onClick = {
                                            playlistScope.launch {
                                                withContext(Dispatchers.IO) {
                                                    AppDataBase.getInstance().playlistDao()
                                                        .appendSong(pl.id, currentSong.id)
                                                }
                                                Toast.makeText(
                                                    context,
                                                    "已加入「${pl.name}」",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                showAddPlaylist = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            // ── 别名 ──
            if (aliases.isNotEmpty()) {
                item(key = "alias_title") { SectionTitle("别名") }
                item(key = "alias") {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            aliases.forEach { alias ->
                                MetaChip(alias.alias)
                            }
                        }
                    }
                }
            }

            // ── 谱面 ──
            item(key = "charts_title") { SectionTitle("谱面（${charts.size}）· 点击查看详情") }
            for (chart in charts.sortedBy { it.difficultyType.ordinal }) {
                item(key = "chart_${chart.difficultyType.ordinal}") {
                    ChartCard(
                        chart = chart,
                        record = records.firstOrNull { it.levelIndex == chart.difficultyType.ordinal },
                        onClick = { onOpenChart(currentSong.id, chart.difficultyType.ordinal) },
                    )
                }
            }

            item(key = "footer") {
                Text(
                    text = "song id ${currentSong.id} · cat ${currentSong.catCode} · " +
                        "数据来源：水鱼曲库（diving-fish）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 单谱面卡（简略）：定数 / 谱师 / notes 构成 / BREAK 扣分线 / Rating 档位 / 本地成绩。点击进谱面详情。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(
    chart: ChartEntity,
    record: RecordEntity?,
    onClick: () -> Unit,
) {
    val diffColor = difficultyColor(chart.difficultyType)
    // 判定权重总分（上游 SongLevelFragment 口径，仅用于失分比例计算）
    val totalScore =
        (chart.notesTap + chart.notesTouch) + chart.notesHold * 2 +
            chart.notesSlide * 3 + chart.notesBreak * 5

    Card(
        modifier = (Modifier.fillMaxWidth()),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 定数行
            Row(verticalAlignment = Alignment.CenterVertically) {
                DifficultyChip(
                    text = "${difficultyLabel(chart.difficultyType)} ${chart.level}",
                    color = diffColor,
                )
                HGap()
                Text(
                    text = "定数 ${chart.ds}",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                chart.oldDs?.let { old ->
                    val (arrow, color) = when {
                        old < chart.ds -> "↑ 上调" to MiuixTheme.colorScheme.primary
                        old > chart.ds -> "↓ 下调" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else -> "· 持平" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Text(
                        text = "$arrow（原 $old）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = color,
                    )
                }
            }
            VGap(8.dp)

            // 谱师
            Text(
                text = "谱师：${chart.charter}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            VGap(8.dp)

            // notes 构成
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaChip("总 ${chart.notesTotal}")
                MetaChip("TAP ${chart.notesTap}")
                MetaChip("HOLD ${chart.notesHold}")
                MetaChip("SLIDE ${chart.notesSlide}")
                if (chart.notesTouch > 0) MetaChip("TOUCH ${chart.notesTouch}")
                MetaChip("BREAK ${chart.notesBreak}")
            }
            VGap(8.dp)

            // BREAK 扣分线（上游口径：权重 5/总分 + 1% 达成修正）
            if (chart.notesBreak > 0) {
                val breakMiss = 5.0 / totalScore + 0.01 / chart.notesBreak
                val breakGreat4x = 5.0 / totalScore * 0.2 + 0.01 / chart.notesBreak * 0.6
                Text(
                    text = "断 1 个 BREAK ≈ -${"%.2f".format(breakMiss * 100)}%" +
                        "　BREAK 大 G ≈ -${"%.2f".format(breakGreat4x * 100)}%",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                VGap(8.dp)
            }

            // 单曲 Rating 档位（定数 × 完成度 × 乘数，四舍五入）
            Text(
                text = "单曲 Rating",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            VGap(4.dp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RatingTierChip("SSS+", SongRating.maxSongRating(chart.ds))
                RatingTierChip("SSS", SongRating.songRating(chart.ds, 100.0))
                RatingTierChip("SS+", SongRating.songRating(chart.ds, 99.5))
                RatingTierChip("S+", SongRating.songRating(chart.ds, 98.0))
                RatingTierChip("S", SongRating.songRating(chart.ds, 97.0))
            }

            // 本地成绩
            record?.let { r ->
                VGap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "%.4f%%".format(r.achievements),
                        style = MiuixTheme.textStyles.title1,
                        color = diffColor,
                    )
                }
                // 成绩的下一行：评级 / 连击 / 同步贴图 + 本曲 Rating
                VGap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RankBadge(rate = r.rate.ifBlank { SongRating.rankOf(r.achievements) })
                    HGap(6.dp)
                    ComboBadge(fc = r.fc)
                    if (r.fs.isNotBlank()) HGap(6.dp)
                    SyncBadge(fs = r.fs)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "获得的本曲 Rating ${r.ra}",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                VGap(2.dp)
                Text(
                    text = "DX ${r.dxScore} / ${chart.notesTotal * 3}" +
                        "　单曲 Rating ${SongRating.songRating(chart.ds, r.achievements)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Rating 档位小胶囊（档位名 + 该档位单曲 Rating）。 */
@Composable
private fun RatingTierChip(rank: String, rating: Int) {
    Box(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$rank $rating",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 小信息胶囊（流派 / BPM / notes 数等）。 */
@Composable
private fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
