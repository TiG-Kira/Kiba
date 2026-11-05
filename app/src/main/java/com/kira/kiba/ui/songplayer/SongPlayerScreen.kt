package com.kira.kiba.ui.songplayer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.listen.SongDownloader
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.playback.SongPlaybackController.LoopMode
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.coverCandidateUrls
import com.kira.kiba.ui.material.KibaGlassDialog
import com.kira.kiba.ui.navigation.LocalSystemNavBarBottom
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Music
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.Playlist
import top.yukonga.miuix.kmp.icon.glass.Redo
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 沉浸式播放页配色（QQ 音乐同款口径）：封面即背景，整页压暗后统一白系文字
private val ImmerseText = Color(0xF2FFFFFF)
private val ImmerseTextDim = Color(0xB8FFFFFF)
private val ImmerseTextFaint = Color(0x8CFFFFFF)

/**
 * 乐曲播放页（曲目列表悬浮底栏 / 曲目详情「播放乐曲」/ 媒体通知 共同的目的地）。
 *
 * 布局对齐主流播放器（QQ 音乐口径）：封面放大铺满全屏做氛围背景（高斯模糊 +
 * 暗化），大封面居中 → 左对齐曲名/艺术家 → 功能行（播放列表 / 音源说明）→
 * 可拖进度条 → 上一首 / 白色大圆播放键 / 下一首。加载中与加载失败各有明确态：
 * 落雪 CDN 部分曲目（宴谱等特殊 id）没有整轨音源，失败时提示而非静音悬挂。
 *
 * 进度条刻意手写（Box + pointerInput）而不是 miuix Slider：需要「拖动中暂停跟随、
 * 松手才 seekTo」的唱片 App 手感，Slider 的 onValueChange 语义贴不上 MediaPlayer 的
 * seek 成本（每次拖动都是一次网络 Range 请求）。
 */
@Composable
fun SongPlayerScreen(
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
) {
    val state by SongPlaybackController.state.collectAsState()
    val track = state.track
    var showQueue by remember { mutableStateOf(false) }
    val navBottom = LocalSystemNavBarBottom.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 下载状态：切曲时重查（已下载则按钮变「已下载」提示）
    var downloaded by remember(track?.songId) { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    LaunchedEffect(track?.songId) {
        val id = track?.songId ?: return@LaunchedEffect
        downloaded = withContext(Dispatchers.IO) {
            SongDownloader.isDownloaded(context, id)
        }
    }
    // 歌曲简要信息（版本/流派/BPM/ID）：从曲库现查，不依赖 Track 结构——
    // 升级前已存在的播放会话（Track 无新增字段）也能完整显示。
    val songInfo by produceState<SongDataEntity?>(initialValue = null, track?.songId) {
        val id = track?.songId ?: return@produceState
        value = runCatching {
            AppDataBase.getInstance().songDao().getAllSongsList().firstOrNull { it.id == id }
        }.getOrNull()
    }
    // 二级页面顶栏由 KibaMainScreen 提供，页面自己把内容压到 clearance 之下，
    // 否则封面会顶到大标题/返回键背后（首版实况：封面被顶栏盖住半截）。
    val clearance = LocalTopBarClearance.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (track == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = clearance + 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有正在播放的乐曲\n去曲目详情页点「播放乐曲」试试",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // ── 全屏封面氛围背景：同款候选链逐个回退 + 超高度模糊 + 暗化
            // （QQ 音乐口径：背景只剩封面的色彩氛围，纹理不可辨）。
            // 注意：不能用首个候选直填——部分曲目水鱼 CDN 404，背景会变纯灰。
            BlurredBackdrop(
                candidates = remember(track.songId, track.imageUrl) {
                    coverCandidateUrls(track.songId, track.imageUrl)
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )

            // ── 分辨率自适应（BoxWithConstraints 拿实际可用宽度）：
            // < 340dp 隐藏玻璃信息面板；< 280dp 连功能行也隐藏，
            // 优先保证媒体控制按钮（上一首/播放/下一首）的显示。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val showInfoCard = maxWidth >= 340.dp
                val showFuncRow = maxWidth >= 280.dp
                // 控制区间距：过窄时压缩，控制按钮永远保留（媒体控制可用性优先级最高）
                val controlGap = if (maxWidth < 280.dp) 16.dp else 40.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(top = clearance)
                        .padding(horizontal = 28.dp)
                        .padding(bottom = navBottom + 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面与曲名/艺术家由顶栏提供（SongDetailTopBarSlot 复用，见 KibaMainScreen），
                    // 页面主体：简要信息卡 → 功能行 → 进度 → 控制，压着顶栏底沿排布。
                    Spacer(Modifier.weight(1f))

                    // ── 歌曲简要信息卡（深色玻璃观感：半透明黑 + 微高光描边）。
                    // 不能用 backdrop 玻璃：本页在取景层子树内，采样会成环。
                    if (showInfoCard) songInfo?.let { info ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.42f))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                SongInfoCell("版本", info.from, Modifier.weight(1f))
                                SongInfoCell("流派", info.genre, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                SongInfoCell("BPM", "${info.bpm}", Modifier.weight(1f))
                                SongInfoCell("ID", "${info.id}", Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                    }

                    // ── 功能行（QQ 音乐铃声/音效/下载位）：下载 + 播放列表 + 音源说明 ──
                    if (showFuncRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            PlayerFunc(
                                icon = if (downloaded) MiuixGlassIcons.Regular.Ok else MiuixGlassIcons.Regular.Download,
                                label = when {
                                    downloading -> "下载中…"
                                    downloaded -> "已下载"
                                    else -> "下载乐曲"
                                },
                                onClick = {
                                    val t = track
                                    if (downloading) {
                                        Toast.makeText(context, "正在下载中，稍等", Toast.LENGTH_SHORT).show()
                                    } else if (downloaded) {
                                        Toast.makeText(context, "已保存在 Music/Kiba/，可在「听 → 我的下载」管理", Toast.LENGTH_SHORT).show()
                                    } else if (t != null) {
                                        downloading = true
                                        scope.launch {
                                            val result = runCatching {
                                                SongDownloader.download(
                                                    context = context,
                                                    songId = t.songId,
                                                    title = t.title,
                                                    artist = t.artist,
                                                    audioUrl = t.audioUrl,
                                                    coverCandidates = t.coverCandidates,
                                                )
                                            }
                                            downloading = false
                                            result.onSuccess {
                                                downloaded = true
                                                Toast.makeText(context, "「${t.title}」已下载（含封面元数据）", Toast.LENGTH_SHORT).show()
                                            }.onFailure { e ->
                                                Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.width(40.dp))
                            PlayerFunc(
                                icon = MiuixGlassIcons.Regular.Music,
                                label = "音源来自落雪",
                                onClick = {
                                    Toast.makeText(context, "整轨音源：assets2.lxns.net（落雪）", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }

                        Spacer(Modifier.height(26.dp))
                    }

                    // ── 进度条（白系，沉浸背景上可读）──
                    SeekBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        enabled = state.prepared,
                        onSeek = { SongPlaybackController.seekTo(it) },
                    )
                    Spacer(Modifier.height(22.dp))

                    // ── 控制区：暂停大键绝对居中（唱片 App 惯例），循环居左、
                    // 播放列表居右（用户口径：暂停按钮居中，播放列表放最右）。
                    // 宽度不足 280dp 时播放列表退场，只留媒体控制三键。
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 中间组：上一首 / 大播放键 / 下一首
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ControlIcon(
                                icon = MiuixGlassIcons.Regular.ChevronBackward,
                                contentDescription = "上一首",
                                enabled = state.queueIndex > 0,
                                size = 46.dp,
                                tint = ImmerseText,
                            ) { SongPlaybackController.prev() }
                            Spacer(Modifier.width(controlGap))
                            Box(
                                modifier = Modifier
                                    .size(78.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { SongPlaybackController.toggle() }, // 停止态/失败态由 resume 内部兜底重播
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = if (state.playing) MiuixGlassIcons.Regular.Pause else MiuixGlassIcons.Regular.Play,
                                    contentDescription = if (state.playing) "暂停" else "播放",
                                    tint = Color.Black,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                            Spacer(Modifier.width(controlGap))
                            ControlIcon(
                                icon = MiuixGlassIcons.Regular.ChevronForward,
                                contentDescription = "下一首",
                                enabled = state.queueIndex < state.queue.lastIndex,
                                size = 46.dp,
                                tint = ImmerseText,
                            ) { SongPlaybackController.next() }
                        }
                        // 左：循环模式
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
                            ControlIcon(
                                icon = MiuixGlassIcons.Regular.Redo,
                                contentDescription = when (state.loopMode) {
                                    LoopMode.OFF -> "顺序播放"
                                    LoopMode.ALL -> "列表循环"
                                    LoopMode.ONE -> "单曲循环"
                                },
                                badge = when (state.loopMode) {
                                    LoopMode.ONE -> "1"
                                    else -> null
                                },
                                active = state.loopMode != LoopMode.OFF,
                                enabled = true,
                                size = 46.dp,
                                tint = ImmerseText,
                            ) { SongPlaybackController.cycleLoopMode() }
                        }
                        // 右：播放列表（宽屏才显示；窄屏优先保证控制三键的呼吸感）
                        if (showFuncRow) {
                            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                ControlIcon(
                                    icon = MiuixGlassIcons.Regular.Playlist,
                                    contentDescription = "播放列表 · ${state.queue.size} 首",
                                    enabled = true,
                                    size = 46.dp,
                                    tint = ImmerseText,
                                ) { showQueue = true }
                            }
                        }
                    }

                    // 播完不再提示「列表已播完」：状态收敛到通知与顶栏，页面保持干净
                    if (!state.prepared && state.error == null) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "加载音源中…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = ImmerseTextDim,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }

    // ── 播放列表弹层：会话队列，点选切歌，当前曲高亮 ──
    KibaGlassDialog(
        show = showQueue,
        title = "播放列表",
        summary = if (state.queue.isNotEmpty() && state.queueIndex >= 0) {
            "第 ${state.queueIndex + 1} / ${state.queue.size} 首 · 点选切歌"
        } else null,
        onDismissRequest = { showQueue = false },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(state.queue) { index, item ->
                val isCurrent = index == state.queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            SongPlaybackController.playAt(index)
                            showQueue = false
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isCurrent) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MiuixTheme.textStyles.body2,
                            color = if (isCurrent) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = item.artist,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 信息卡单格：小标签 + 值（左对齐）。 */
@Composable
private fun SongInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = ImmerseTextFaint,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = ImmerseText,
            maxLines = 1,
        )
    }
}

/**
 * 背景候选链：与 CoverFrame 同款口径（SubcomposeAsyncImage error slot 递归回退），
 * 任一 URL 成功即铺满全屏做超高度模糊氛围底；全部失败落深灰兜底。
 */
@Composable
private fun BlurredBackdrop(
    candidates: List<String>,
    index: Int = 0,
) {
    if (index >= candidates.size) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2A2D33)))
        return
    }
    SubcomposeAsyncImage(
        model = candidates[index],
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .blur(64.dp),
        error = { BlurredBackdrop(candidates, index + 1) },
    )
}

/** 功能行图标 + 小字（播放列表 / 音源说明）。 */
@Composable
private fun PlayerFunc(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = label,
            tint = ImmerseText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = ImmerseTextDim,
        )
    }
}

/**
 * 控制图标（上一首/下一首/循环模式）。
 *
 * onClick 为 null 时不挂 clickable：首版内层挂着默认空 onClick 的 clickable，
 * 会把点击事件消费掉，外层播放键的 toggle 永远收不到 tap——这就是
 * 「播放页暂停不好使」的根因。
 *
 * @param badge 右下角小角标（单曲循环的「1」）
 * @param active 激活态高亮（循环开启时主色点亮）
 */
@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    tint: Color = MiuixTheme.colorScheme.onSurface,
    badge: String? = null,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (enabled && onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                active -> MiuixTheme.colorScheme.primary
                enabled -> tint
                else -> ImmerseTextFaint
            },
            modifier = Modifier.size(size * 0.6f),
        )
        badge?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * 可拖进度条：拖动中只更新本地预览位置（不打 seek），松手才真正 seekTo。
 * duration 未就绪（prepared 前）禁用。白系配色（沉浸背景）。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragPos by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val progress = if (durationMs > 0) {
        if (dragging) dragPos / durationMs
        else positionMs.toFloat() / durationMs
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // 触控区（视觉条只有 5dp 高）
                .then(if (enabled) {
                    Modifier
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                            }
                        }
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragging = true
                                    dragPos = (offset.x / size.width * durationMs).coerceIn(0f, durationMs.toFloat())
                                },
                                onDragEnd = {
                                    dragging = false
                                    onSeek(dragPos.toLong().coerceIn(0, durationMs))
                                },
                                onDragCancel = { dragging = false },
                            ) { change, _ ->
                                change.consume()
                                dragPos = (change.position.x / size.width * durationMs)
                                    .coerceIn(0f, durationMs.toFloat())
                            }
                        }
                } else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 底槽
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            // 已播放
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatMs(if (dragging) dragPos.toLong() else positionMs),
                style = MiuixTheme.textStyles.footnote2,
                color = ImmerseTextDim,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMs(durationMs),
                style = MiuixTheme.textStyles.footnote2,
                color = ImmerseTextDim,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
