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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.alpha
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
import com.kira.kiba.core.utils.RingtoneSetter
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.coverCandidateUrls
import com.kira.kiba.ui.navigation.LocalSystemNavBarBottom
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Alarm
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
 * 播放列表面板开合的缓动：快起慢收三次贝塞尔（与顶栏程序折叠共用同一曲线，
 * 两边同频动画才像一块完整的转场而不是各动各的）。
 */
private val QueuePanelEasing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 0.85f, 0.16f, 1f)

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
    // 播放列表改为页内显示（不再弹层）：显隐状态放 [SongPlayerPanelState]，
    // 顶栏（KibaMainScreen 提供）也要读它做收窄 + 改标题，兄弟树共享
    val queueVisible by SongPlayerPanelState.queueVisible.collectAsState()
    val navBottom = LocalSystemNavBarBottom.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 下载状态：切曲时重查（已下载则按钮变「已下载」提示）
    var downloaded by remember(track?.songId) { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    // 铃声设置进行中（未下载时先落盘，几秒到十几秒）
    var ringtoneBusy by remember { mutableStateOf(false) }
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

    // 离开页面时面板必须复位：顶栏是全局共享的 scrollBehavior，残留的
    // programCollapsed 会让下一次进入任意二级页都从收窄态开始（实测踩过）。
    DisposableEffect(Unit) {
        onDispose { SongPlayerPanelState.setVisible(false) }
    }

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
            // 控制区改 SpaceBetween 等距分布后间距自适应，无需手动 gap。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val showInfoCard = maxWidth >= 340.dp
                val showFuncRow = maxWidth >= 280.dp

                // 播放列表面板的高度动画：weight(0→1) 用非线性缓动逐帧插值，
                // Column 每帧重测（与顶栏收窄同一套缓动），内容随进度淡入。
                // 比 AnimatedVisibility 稳：weight 必须由父 Column 作用域给出，
                // 包在 AnimatedVisibility 里就没法跟布局动画共享同一个时钟。
                val queueWeight by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (queueVisible) 1f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 380,
                        easing = QueuePanelEasing,
                    ),
                    label = "queuePanelWeight",
                )

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
                    // 页面主体：播放列表 / 简要信息卡 → 功能行 → 进度 → 控制。
                    if (queueWeight > 0.01f) {
                        QueuePanel(
                            state = state,
                            reveal = queueWeight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(queueWeight.coerceIn(0f, 1f)),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    // ── 歌曲简要信息卡（深色玻璃观感：半透明黑 + 微高光描边）。
                    // 不能用 backdrop 玻璃：本页在取景层子树内，采样会成环。
                    // 播放列表面板展开时让位（面板 + 功能行 + 进度 + 控制已占满一屏）。
                    if (!queueVisible && showInfoCard) songInfo?.let { info ->
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

                    // ── 功能行（QQ 音乐铃声/音效/下载位）：下载 + 设为铃声 + 音源说明 ──
                    // 三项等宽均分整行（各自居中）：固定间隙会把「音源来自落雪」这种
                    // 宽标签的图标挤得离邻项更远，视觉间距不一致（用户实测反馈）
                    if (!queueVisible && showFuncRow) {
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
                                modifier = Modifier.weight(1f),
                            )
                            // ── 设为铃声：需「修改系统设置」特殊权限（两段式流程）；
                            // 流媒体播放中的歌先落盘（已下载则直接复用本地文件）
                            PlayerFunc(
                                icon = MiuixGlassIcons.Regular.Alarm,
                                label = if (ringtoneBusy) "处理中…" else "设为铃声",
                                onClick = {
                                    val t = track ?: return@PlayerFunc
                                    if (ringtoneBusy) {
                                        Toast.makeText(context, "正在处理，稍等", Toast.LENGTH_SHORT).show()
                                        return@PlayerFunc
                                    }
                                    if (!RingtoneSetter.canWriteSettings(context)) {
                                        // 特殊权限 requestPermissions 拿不到，只能引导去系统设置页手动开
                                        Toast.makeText(
                                            context,
                                            "需要「修改系统设置」权限：请在接下来的页面允许后，回来再点一次",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        RingtoneSetter.openWriteSettingsPage(context)
                                        return@PlayerFunc
                                    }
                                    ringtoneBusy = true
                                    scope.launch {
                                        val msg = runCatching {
                                            val localUri = SongDownloader.query(context, t.songId)?.uri
                                                ?: SongDownloader.download(
                                                    context = context,
                                                    songId = t.songId,
                                                    title = t.title,
                                                    artist = t.artist,
                                                    audioUrl = t.audioUrl,
                                                    coverCandidates = t.coverCandidates,
                                                ).also { downloaded = true }
                                            RingtoneSetter.setRingtone(context, localUri)
                                            "已将「${t.title}」设为来电铃声"
                                        }.getOrElse { e ->
                                            "设置失败：${e.message ?: "无法获取音源"}"
                                        }
                                        ringtoneBusy = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            PlayerFunc(
                                icon = MiuixGlassIcons.Regular.Music,
                                label = "音源来自落雪",
                                onClick = {
                                    Toast.makeText(context, "整轨音源：assets2.lxns.net（落雪）", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
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

                    // ── 控制区：五元素等距分布（SpaceBetween + 左右对称槽位，
                    // 暂停大键自然正中），循环居左、播放列表居右。窄屏（<280dp）
                    // 播放列表退场但保留 46dp 右槽占位——左右对称被破坏会让空隙
                    // 全挤到中间两侧（此前 Box 分层布局的窄屏聚簇问题）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左：循环模式
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
                        // 中间组：上一首 / 大播放键 / 下一首
                        ControlIcon(
                            icon = MiuixGlassIcons.Regular.ChevronBackward,
                            contentDescription = "上一首",
                            enabled = state.queueIndex > 0,
                            size = 46.dp,
                            tint = ImmerseText,
                        ) { SongPlaybackController.prev() }
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
                        ControlIcon(
                            icon = MiuixGlassIcons.Regular.ChevronForward,
                            contentDescription = "下一首",
                            enabled = state.queueIndex < state.queue.lastIndex,
                            size = 46.dp,
                            tint = ImmerseText,
                        ) { SongPlaybackController.next() }
                        // 右：播放列表（宽屏才显示；窄屏保留空槽占位保对称）。
                        // 页内显隐切换：顶栏随面板收窄 / 恢复（见 KibaMainScreen 播放页分支）
                        Box(
                            modifier = Modifier.width(46.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showFuncRow) {
                                ControlIcon(
                                    icon = MiuixGlassIcons.Regular.Playlist,
                                    contentDescription = "播放列表 · ${state.queue.size} 首",
                                    enabled = true,
                                    size = 46.dp,
                                    tint = if (queueVisible) MiuixTheme.colorScheme.primary else ImmerseText,
                                    active = queueVisible,
                                ) { SongPlayerPanelState.toggle() }
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
}

/**
 * 页内播放列表面板：会话队列，点选切歌，当前曲高亮。
 *
 * 高度由父级 weight 动画驱动（[SongPlayerScreen] 里 0→1 插值），本组件只负责
 * 按 [reveal] 淡入内容；面板展开时自动滚到当前播放曲。
 */
@Composable
private fun QueuePanel(
    state: SongPlaybackController.PlayerState,
    reveal: Float,
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 面板每次展开 / 切歌都把当前曲滚进视野（首条就不滚，scrollToItem(0) 无意义抖动）
    androidx.compose.runtime.LaunchedEffect(state.queueIndex, state.queue.size) {
        if (state.queueIndex > 0) {
            runCatching { listState.scrollToItem(state.queueIndex) }
        }
    }
    Column(
        modifier = modifier
            .alpha(reveal.coerceIn(0f, 1f)),
    ) {
        Text(
            text = if (state.queue.isNotEmpty() && state.queueIndex >= 0) {
                "第 ${state.queueIndex + 1} / ${state.queue.size} 首 · 点选切歌"
            } else {
                "队列为空"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = ImmerseTextFaint,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(state.queue) { index, item ->
                val isCurrent = index == state.queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (isCurrent) {
                                Modifier.background(Color.White.copy(alpha = 0.08f))
                            } else {
                                Modifier
                            }
                        )
                        .clickable { SongPlaybackController.playAt(index) }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isCurrent) MiuixTheme.colorScheme.primary else ImmerseTextFaint,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MiuixTheme.textStyles.body2,
                            color = if (isCurrent) MiuixTheme.colorScheme.primary else ImmerseText,
                            maxLines = 1,
                        )
                        Text(
                            text = item.artist,
                            style = MiuixTheme.textStyles.footnote2,
                            color = ImmerseTextDim,
                            maxLines = 1,
                        )
                    }
                    if (isCurrent) {
                        androidx.compose.material3.Icon(
                            imageVector = if (state.playing) MiuixGlassIcons.Regular.Pause else MiuixGlassIcons.Regular.Play,
                            contentDescription = if (state.playing) "正在播放" else "已暂停",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
