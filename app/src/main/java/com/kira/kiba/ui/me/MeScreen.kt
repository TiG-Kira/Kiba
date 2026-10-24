package com.kira.kiba.ui.me

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.stats.MaimaiStats
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * “我”页面（重设计）：
 * - 置顶卡片显示查分器实时 Rating 与对外昵称（OAuth 授权取得，非用户名），点击进 Best 50 详情；
 *   未绑定时显示授权引导（原“查分按钮”已删除，查分并入 Agent 与本页）；
 * - 下方依次提供 重新绑定 / 版本进度 / 等级进度 / DX 分数转换 入口。
 */
@Composable
fun MeScreen(scrollBehavior: ScrollBehavior) {
    val scope = rememberCoroutineScope()
    val account by DivingFishAccount.state.collectAsState()

    var showBind by remember { mutableStateOf(false) }
    var showBest50 by remember { mutableStateOf(false) }
    var showVersionProgress by remember { mutableStateOf(false) }
    var showLevelProgress by remember { mutableStateOf(false) }
    var showDxConvert by remember { mutableStateOf(false) }

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
            // ── 置顶：Rating 卡片 / 绑定引导 ──
            item(key = "rating") {
                if (account.bound) {
                    RatingCard(
                        nickname = account.nickname.ifBlank { "查分器用户" },
                        rating = account.rating,
                        additionalRating = account.additionalRating,
                        recordCount = account.recordCount,
                        loading = account.loading,
                        error = account.error,
                        onClick = { showBest50 = true },
                        onRefresh = { KibaState.refreshRecords() },
                    )
                } else {
                    BindGuideCard(
                        loading = account.loading,
                        error = account.error,
                        onClick = { showBind = true },
                    )
                }
            }

            item(key = "tools_title") { SectionTitle("工具") }

            item(key = "tools") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = if (account.bound) "重新绑定账户" else "绑定查分器账户",
                        summary = if (account.bound) {
                            "当前：${account.nickname.ifBlank { "查分器用户" }}（Rating ${account.rating}）"
                        } else {
                            "通过水鱼 OAuth 授权，启用成绩类功能"
                        },
                        onClick = { showBind = true },
                    )
                    BasicComponent(
                        title = "版本进度",
                        summary = "各版本歌曲的收集 / 游玩情况",
                        onClick = { showVersionProgress = true },
                    )
                    BasicComponent(
                        title = "等级进度",
                        summary = "各难度等级谱面的游玩情况",
                        onClick = { showLevelProgress = true },
                    )
                    BasicComponent(
                        title = "DX 分数转换",
                        summary = "DX 分数 → 估算达成率与单曲 ra",
                        onClick = { showDxConvert = true },
                    )
                    if (account.bound) {
                        BasicComponent(
                            title = "解除绑定",
                            summary = "清除本机成绩缓存；远端授权可在 auth.diving-fish.com/apps 撤销",
                            onClick = { DivingFishAccount.unbind() },
                        )
                    }
                }
            }
        }
    }

    // ── 弹层 ──
    if (showBind) {
        BindDialog(onDismiss = { showBind = false })
    }
    if (showBest50) {
        Best50Dialog(onDismiss = { showBest50 = false })
    }
    if (showVersionProgress) {
        ProgressDialog(
            title = "版本进度",
            onDismiss = { showVersionProgress = false },
            load = { MaimaiStats.versionProgress() },
        )
    }
    if (showLevelProgress) {
        ProgressDialog(
            title = "等级进度",
            onDismiss = { showLevelProgress = false },
            load = { MaimaiStats.levelProgress() },
        )
    }
    if (showDxConvert) {
        DxConvertDialog(onDismiss = { showDxConvert = false })
    }
}

/** 置顶 Rating 卡片（已绑定）。 */
@Composable
private fun RatingCard(
    nickname: String,
    rating: Int,
    additionalRating: Int,
    recordCount: Int,
    loading: Boolean,
    error: String?,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nickname,
                        style = MiuixTheme.textStyles.headline1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            loading -> "正在获取成绩…"
                            error != null -> error
                            else -> "成绩 $recordCount 条 · 点击查看 Best 50"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$rating",
                        style = MiuixTheme.textStyles.title1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    if (additionalRating > 0) {
                        Text(
                            text = "+$additionalRating",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = "Rating",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (error != null && !loading) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "重试", onClick = onRefresh)
                }
            }
        }
    }
}

/** 未绑定引导卡片。 */
@Composable
private fun BindGuideCard(loading: Boolean, error: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = "未绑定查分器",
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = error ?: "通过水鱼 OAuth 授权后，可查看实时 Rating 与昵称，并解锁 Best 50 / 推分推荐",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onClick) {
                    Text(text = if (loading) "处理中…" else "去绑定")
                }
            }
        }
    }
}

/**
 * 绑定弹层（水鱼 OAuth 设备码流程）：
 * 1. 点「打开授权页面」→ 拉起系统浏览器完成授权；
 * 2. 授权页会显示一次性确认码，回填到输入框；
 * 3. 点「完成绑定」→ 授权关系存水鱼服务端，本 App 不保存任何口令。
 */
@Composable
private fun BindDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deviceAuth by remember { mutableStateOf<DivingFishAccount.DeviceAuth?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openVerification(uri: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.onFailure {
            error = "无法打开浏览器，请手动访问：$uri"
        }
    }

    OverlayDialog(
        show = true,
        title = "绑定查分器",
        summary = "水鱼 OAuth 授权（只读你的资料与成绩）",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (deviceAuth == null) {
                Text(
                    text = "点击下方按钮打开水鱼授权页面并登录，" +
                        "同意授权后页面会显示一串一次性确认码，把它带回来即可完成绑定。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                DivingFishAccount.startDeviceAuth()
                                    .onSuccess { da ->
                                        deviceAuth = da
                                        openVerification(da.verificationUri)
                                    }
                                    .onFailure { e -> error = e.message }
                                busy = false
                            }
                        },
                    ) {
                        Text(text = if (busy) "正在发起…" else "打开授权页面")
                    }
                }
            } else {
                val da = deviceAuth
                Text(
                    text = "授权页显示的确认码（${da?.userCode.orEmpty()}）：",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "一次性确认码",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "授权链接：${da?.verificationUri.orEmpty()}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "重开链接",
                        onClick = { da?.verificationUri?.let { openVerification(it) } },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "完成绑定",
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                DivingFishAccount.finishDeviceAuth(code)
                                    .onSuccess { onDismiss() }
                                    .onFailure { e -> error = e.message }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Best 50 详情弹层。 */
@Composable
private fun Best50Dialog(onDismiss: () -> Unit) {
    val records = remember { DivingFishAccount.best50() }
    val total = records.sumOf { it.ra }
    OverlayDialog(
        show = true,
        title = "Best 50",
        summary = if (records.isEmpty()) "暂无成绩数据" else "合计 ra = $total",
        onDismissRequest = onDismiss,
    ) {
        if (records.isEmpty()) {
            Text(
                text = "请先绑定查分器账户并刷新成绩",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                items(records.size) { index ->
                    val r = records[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = r.title,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "[${difficultyName(r.levelIndex)} ${r.level}] " +
                                    "%.4f%%".format(r.achievements),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = "${r.ra}",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 进度弹层（版本进度 / 等级进度共用）。 */
@Composable
private fun ProgressDialog(
    title: String,
    onDismiss: () -> Unit,
    load: suspend () -> List<MaimaiStats.ProgressItem>,
) {
    val state = produceState(initialValue = emptyList<MaimaiStats.ProgressItem>()) {
        value = load()
    }
    val items = state.value
    OverlayDialog(
        show = true,
        title = title,
        summary = if (items.isEmpty()) "统计中…（曲库或成绩为空时无数据）" else "共 ${items.size} 组",
        onDismissRequest = onDismiss,
    ) {
        if (items.isEmpty()) {
            Text(
                text = "请先在设置中更新曲库，并绑定查分器账户",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                items(items.size) { index ->
                    val item = items[index]
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = item.key,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${item.played} / ${item.total}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 进度条（miuix primary 色）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    MiuixTheme.colorScheme.surface,
                                    RoundedCornerShape(3.dp),
                                ),
                        ) {
                            if (item.ratio > 0f) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth(item.ratio)
                                        .height(6.dp)
                                        .background(
                                            MiuixTheme.colorScheme.primary,
                                            RoundedCornerShape(3.dp),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** DX 分数转换弹层：选歌曲（取最高难度）+ 输入 DX 分数 → 估算达成率 / ra。 */
@Composable
private fun DxConvertDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<ResolvedChart?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var dxInput by remember { mutableStateOf("") }

    val dxScore = dxInput.toIntOrNull() ?: 0
    val achievement = resolved?.let {
        MaimaiStats.estimateAchievement(
            notesTap = it.notesTap,
            notesHold = it.notesHold,
            notesSlide = it.notesSlide,
            notesTouch = it.notesTouch,
            notesBreak = it.notesBreak,
            dxScore = dxScore,
        )
    }
    val ra = resolved?.let { MaimaiStats.estimateRa(it.ds, achievement ?: 0.0) }

    OverlayDialog(
        show = true,
        title = "DX 分数转换",
        summary = "DX 分数 → 估算达成率与单曲 ra",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "歌曲名 / ID",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "查询",
                    onClick = {
                        scope.launch {
                            val db = AppDataBase.getInstance()
                            val byId = query.trim().toIntOrNull()?.let { id ->
                                db.songDao().getAllSongsList().firstOrNull { it.id == id }
                            }
                            val song = byId ?: db.songDao()
                                .searchSongsList(query.trim(), 1)
                                .firstOrNull()
                            if (song == null) {
                                notFound = true
                                resolved = null
                                return@launch
                            }
                            notFound = false
                            val chart = db.chartDao().getChartsBySongId(song.id)
                                .maxByOrNull { it.ds }
                            resolved = chart?.let {
                                ResolvedChart(
                                    songId = song.id,
                                    title = song.title,
                                    level = it.level,
                                    ds = it.ds,
                                    notesTap = it.notesTap,
                                    notesHold = it.notesHold,
                                    notesSlide = it.notesSlide,
                                    notesTouch = it.notesTouch,
                                    notesBreak = it.notesBreak,
                                )
                            }
                        }
                    },
                )
            }
            if (notFound) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "没有找到相关歌曲（曲库可能未更新）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            resolved?.let { r ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${r.title} · ${r.level} · ds ${r.ds}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = dxInput,
                    onValueChange = { input ->
                        if (input.all { c -> c.isDigit() }) dxInput = input
                    },
                    label = "DX 分数",
                    useLabelAsPlaceholder = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (dxScore > 0 && achievement != null) {
                    Text(
                        text = "估算达成率：%.4f%%".format(achievement),
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "该难度下单曲 ra ≈ $ra",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

/** DX 转换用的已解析谱面（歌曲最高难度）。 */
private data class ResolvedChart(
    val songId: Int,
    val title: String,
    val level: String,
    val ds: Double,
    val notesTap: Int,
    val notesHold: Int,
    val notesSlide: Int,
    val notesTouch: Int,
    val notesBreak: Int,
)

private fun difficultyName(index: Int): String = when (index) {
    0 -> "BASIC"
    1 -> "ADVANCED"
    2 -> "EXPERT"
    3 -> "MASTER"
    4 -> "Re:MASTER"
    else -> "UTAGE"
}
