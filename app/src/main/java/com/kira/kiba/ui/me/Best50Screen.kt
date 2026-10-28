package com.kira.kiba.ui.me

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.best50.Best50ImageBuilder
import com.kira.kiba.core.best50.Best50ImageService
import com.kira.kiba.core.best50.GeneratedImage
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.share.ShareService
import com.kira.kiba.ui.material.KibaGlassDialog
import kotlinx.coroutines.launch
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.HGap
import com.kira.kiba.ui.common.InfoPill
import com.kira.kiba.ui.common.ListSnapshotCache
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Best 50 页（官方口径 sd 35 + dx 15，ra 之和即 DX Rating）。
 * 数据源：绑定查分器后水鱼服务端下发的官方 B50；未绑定时引导去「我」页面授权。
 *
 * 歌曲卡与「生成 Best 50 图片」的单曲贴图同一套视觉（见 [Best50Tile]），
 * 按屏幕宽度自适应一行列数；点击进入该歌曲的歌曲详情页。
 */
/** B50 页数据状态：加载中 / 已就绪（produceState 在 IO 线程读缓存）。 */
private sealed interface B50UiState {
    data object Loading : B50UiState
    data class Done(val b50: DivingFishAccount.Best50) : B50UiState
}

@Composable
fun Best50Screen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit = {},
) {
    val account by DivingFishAccount.state.collectAsState()

    // ── 异步加载：进页先显示加载态，缓存读完后渲染卡片；账号刷新后重读 ──
    val b50State by produceState<B50UiState>(B50UiState.Loading, account.recordCount) {
        value = B50UiState.Loading
        val result = runCatching {
            withContext(Dispatchers.Default) { DivingFishAccount.best50() }
        }.getOrDefault(DivingFishAccount.Best50())
        value = B50UiState.Done(result)
    }
    val b50Loading = b50State is B50UiState.Loading
    val b50 = (b50State as? B50UiState.Done)?.b50 ?: DivingFishAccount.Best50()
    val old = b50.old
    val new = b50.new
    val total = b50.rating

    // DX 满分表（notes 总数 × 3）：贴图右下的 DX 分/满分 与 星标 都要用
    val charts by produceState<List<ChartEntity>>(initialValue = emptyList()) {
        value = runCatching {
            AppDataBase.getInstance().chartDao().getAllChartsList()
        }.getOrDefault(emptyList())
    }
    val fullScoreByKey = remember(charts) {
        charts.associate { (it.songId to it.difficultyType.ordinal) to it.notesTotal * 3 }
    }

    // 曲库（版本 tag 用：拉伸空白区显示歌曲所属版本）
    val songsById by produceState<Map<Int, SongDataEntity>>(emptyMap()) {
        value = runCatching {
            AppDataBase.getInstance().songDao().getAllSongsList().associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    // ── 一行一首：固定单列，贴图九宫格拉长到整行宽（详见 Best50Tile）──
    val columns = 1

    // ── 生成 Best 50 图片（暂存缓存，可显式存相册 / 分享）──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<GeneratedImage?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                b50Loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    LoadingCard(text = "正在加载 Best 50…")
                }
                old.isEmpty() && new.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        text = if (account.bound) {
                            "还没有成绩数据\n可在「我」页面下拉刷新，或稍后重试"
                        } else {
                            "查看 Best 50 需要先绑定查分器\n请到「我」页面通过水鱼 OAuth 完成授权"
                        },
                    )
                }
                else -> {
                item(key = "save", span = { GridItemSpan(maxLineSpan) }) {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    saving -> "正在生成图片…"
                                    saveMsg != null -> saveMsg!!
                                    else -> "生成 Best 50 图片（暂存缓存，可存相册 / 分享）"
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = if (saveMsg?.contains("失败") == true) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (saving) "生成中" else "生成图片",
                                enabled = !saving && (old.isNotEmpty() || new.isNotEmpty()),
                                onClick = {
                                    if (!saving) {
                                        saving = true
                                        saveMsg = null
                                        scope.launch {
                                            val img = Best50ImageService.generate(
                                                context = context,
                                                old = b50.old,
                                                new = b50.new,
                                                nickname = account.nickname.ifBlank { "Kiba" },
                                                rating = b50.rating,
                                            )
                                            if (img != null) {
                                                pending = img
                                            } else {
                                                saveMsg = "生成失败：${
                                                    Best50ImageBuilder.lastError.ifBlank { "图片绘制异常" }
                                                }"
                                            }
                                            saving = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "DX Rating $total",
                                    style = MiuixTheme.textStyles.headline1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "旧曲 ${old.size}（ra ${b50.oldRa}） + 新曲 ${new.size}（ra ${b50.newRa}）",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "旧 ${old.size} / 新 ${new.size}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                if (new.isNotEmpty()) {
                    item(key = "new_title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "新曲 ${new.size}（当前版本，Best15）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    itemsIndexed(
                        items = new,
                        key = { _, r -> "b50_new_${r.songId}_${r.levelIndex}" },
                    ) { index, r ->
                        Best50TileCard(
                            rank = index + 1,
                            listSize = new.size,
                            record = r,
                            fullScore = fullScoreByKey[r.songId to r.levelIndex] ?: 0,
                            version = songsById[r.songId]?.from ?: "",
                            genre = songsById[r.songId]?.genre ?: "",
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
                if (old.isNotEmpty()) {
                    item(key = "old_title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "旧曲 ${old.size}（Best35）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    itemsIndexed(
                        items = old,
                        key = { _, r -> "b50_old_${r.songId}_${r.levelIndex}" },
                    ) { index, r ->
                        Best50TileCard(
                            rank = index + 1,
                            listSize = old.size,
                            record = r,
                            fullScore = fullScoreByKey[r.songId to r.levelIndex] ?: 0,
                            version = songsById[r.songId]?.from ?: "",
                            genre = songsById[r.songId]?.genre ?: "",
                            onClick = { onOpenSong(r.songId) },
                        )
                    }
                }
            }
        }
    }
}
    // ── 生成完成：保存到相册 / 分享 选择弹层 ──
    val img = pending
    if (img != null) {
        KibaGlassDialog(
            show = true,
            title = "Best 50 图片",
            summary = "图片已生成到缓存，选择保存方式：",
            onDismissRequest = { pending = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { pending = null })
                Spacer(Modifier.width(8.dp))
                TextButton(text = "保存到相册", onClick = {
                    val uri = Best50ImageService.saveToGallery(context, img)
                    saveMsg = if (uri != null) "已保存到相册：Pictures/Kiba/${img.displayName}" else "保存失败：无法写入相册"
                    pending = null
                })
                Spacer(Modifier.width(8.dp))
                TextButton(text = "分享", onClick = {
                    val ok = ShareService.shareFile(context, img.file, img.displayName)
                    if (!ok) saveMsg = "分享失败"
                    pending = null
                })
            }
        }
    }
}

/** 加载态卡片：小旋转环 + 提示文字（Best 50 数据异步加载期间显示）。 */@Composable
private fun LoadingCard(text: String) {
    Card(modifier = (Modifier.fillMaxWidth())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val angle by rememberInfiniteTransition(label = "b50spin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "b50angle",
            )
            val spinnerColor = MiuixTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(18.dp)) {
                rotate(angle) {
                    drawArc(
                        color = spinnerColor,
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * Best 50 贴图卡：单曲贴图（[Best50Tile]）包在可点击卡片里。
 * 点击进入歌曲详情页（与旧版行卡行为一致）；一行一首，贴图九宫格拉长到整行宽。
 */
@Composable
private fun Best50TileCard(
    rank: Int,
    listSize: Int,
    record: RecordEntity,
    fullScore: Int,
    version: String,
    genre: String,
    onClick: () -> Unit,
) {
    Card(modifier = (Modifier.fillMaxWidth()), onClick = onClick) {
        Box(modifier = Modifier.padding(4.dp)) {
            Best50Tile(
                record = record,
                fullScore = fullScore,
                rankNum = rank,
                listSize = listSize,
                version = version,
                genre = genre,
            )
        }
    }
}

/**
 * 成绩记录快照（进程级）：从歌曲详情返回时首帧即渲染上次的成绩列表，
 * 否则 LazyColumn 空测量会把恢复出的滚动位置钳回 0（详见 [ListSnapshotCache]）。
 */
object RecordsListCache : ListSnapshotCache<List<RecordEntity>>(emptyList())

