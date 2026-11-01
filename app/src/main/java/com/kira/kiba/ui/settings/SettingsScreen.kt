package com.kira.kiba.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.BuildConfig
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.utils.CacheCleaner
import com.kira.kiba.core.utils.DeviceGrade
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.restartMainActivity
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.size

/**
 * 设置页 ArrowPreference 的左侧功能图标（HyperOS 设置风格：次要色单色图标）。
 */
@Composable
private fun PrefIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(26.dp),
    )
}

/** 评分到档位文案。 */
private fun tierLabel(score: Int): String = when {
    score >= 75 -> "高"
    score >= DeviceGrade.PASS_SCORE -> "中"
    else -> "低"
}

/**
 * 设置页（严格 miuix preference 规范：ArrowPreference / SwitchPreference / OverlaySpinnerPreference）。
 */
@Composable
fun SettingsScreen(
    scrollBehavior: ScrollBehavior,
    onOpenLlmConfig: () -> Unit,
    onOpenMachineUpdate: () -> Unit,
    onOpenQrImport: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val themeMode by KibaState.themeMode.collectAsState()
    val dynamicColor by KibaState.dynamicColor.collectAsState()
    val syncState by SongDataSync.state.collectAsState()
    val updateAvailable by SongDataSync.updateAvailable.collectAsState()
    var llmOn by remember { mutableStateOf(SpUtil.isLlmEnabled()) }
    var coverSource by remember { mutableStateOf(SpUtil.getCoverSource()) }
    var showCoverSource by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // ── 真·高级材质 ──
    // 评分在启动时评测并落盘，这里只做展示与手动重评
    var advancedOn by remember { mutableStateOf(SpUtil.isAdvancedMaterialEnabled()) }
    var score by remember { mutableStateOf(SpUtil.getDeviceScore()) }
    var passes by remember { mutableStateOf(SpUtil.getDeviceScore() >= DeviceGrade.PASS_SCORE) }
    var scoring by remember { mutableStateOf(false) }

    // ── 封面缓存手动清理 ──
    var coverCacheMsg by remember { mutableStateOf<String?>(null) }
    var clearingCovers by remember { mutableStateOf(false) }
    var showCoverClearConfirm by remember { mutableStateOf(false) }

    // ── 查分器数据源（舞萌 / 中二各自独立） ──
    var maimaiSource by remember { mutableStateOf(SpUtil.getScoreSource(GameType.MAIMAI)) }
    var chuniSource by remember { mutableStateOf(SpUtil.getScoreSource(GameType.CHUNITHM)) }
    val lxnsState by LxnsAccount.state.collectAsState()
    var showLxnsKeyDialog by remember { mutableStateOf(false) }

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
            // ── 外观 ──
            item(key = "appearance_title") { SectionTitle("外观") }
            item(key = "appearance_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    OverlaySpinnerPreference(
                        items = listOf(
                            DropdownItem(text = "跟随系统"),
                            DropdownItem(text = "浅色"),
                            DropdownItem(text = "深色"),
                        ),
                        selectedIndex = themeMode.ordinal,
                        title = "主题",
                        summary = when (themeMode) {
                            KibaState.ThemeMode.System -> "跟随系统"
                            KibaState.ThemeMode.Light -> "浅色"
                            KibaState.ThemeMode.Dark -> "深色"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Theme) },
                        onSelectedIndexChange = { index ->
                            KibaState.ThemeMode.values().getOrNull(index)
                                ?.let { KibaState.setThemeMode(it) }
                        },
                    )
                    SwitchPreference(
                        checked = dynamicColor,
                        onCheckedChange = { KibaState.setDynamicColor(it) },
                        title = "动态取色",
                        summary = if (dynamicColor) "已开启（跟随壁纸取色）" else "已关闭",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune) },
                    )
                }
            }

            // ── 真·高级材质 ──
            item(key = "material_title") { SectionTitle("真·高级材质") }
            item(key = "material_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "设备性能评分",
                        summary = if (scoring) {
                            "正在重新评测…"
                        } else if (score < 0) {
                            "尚未评测 · 点击开始"
                        } else {
                            val breakdown = DeviceGrade.cachedOrEvaluate(context).details
                                .joinToString(" · ") { "${it.first} ${it.second}" }
                            "$score / 100 · ${"档位 " + tierLabel(score)}" +
                                if (score >= DeviceGrade.PASS_SCORE) {
                                    "\n$breakdown\n点击重新评分"
                                } else {
                                    "\n低于 ${DeviceGrade.PASS_SCORE} 分，材质不可用 · 点击重新评分"
                                }
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.MemoryFunction) },
                        onClick = {
                            scoring = true
                            scope.launch(Dispatchers.IO) {
                                val grade = DeviceGrade.evaluate(context)
                                SpUtil.setDeviceScore(grade.total)
                                withContext(Dispatchers.Main) {
                                    score = grade.total
                                    passes = grade.total >= DeviceGrade.PASS_SCORE
                                    scoring = false
                                    if (!passes) SpUtil.setAdvancedMaterialEnabled(false)
                                }
                            }
                        },
                        enabled = !scoring,
                    )
                    SwitchPreference(
                        checked = advancedOn,
                        onCheckedChange = {
                            advancedOn = it
                            SpUtil.setAdvancedMaterialEnabled(it)
                            // 全局 CompositionLocal 变了重启最干净（切换后会回到主屏）
                            context.restartMainActivity()
                        },
                        title = "启用真·高级材质",
                        summary = when {
                            !passes -> "设备评分不足 ${DeviceGrade.PASS_SCORE} 分，暂不可用"
                            advancedOn -> "已开启 · 超级柔光（弹层为玻璃材质）"
                            else -> "已关闭 · 沿用标准 miuix 质感"
                        },
                        enabled = passes,
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Layers) },
                    )
                }
            }

            // ── Agent ──
            item(key = "agent_title") { SectionTitle("Agent") }
            item(key = "agent_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    SwitchPreference(
                        checked = llmOn,
                        onCheckedChange = {
                            llmOn = it
                            SpUtil.setLlmEnabled(it)
                        },
                        title = "启用 LLM 对话",
                        summary = if (llmOn) {
                            "Agent 优先调用配置的模型回答开放问题"
                        } else {
                            "Agent 仅使用本地逻辑与网页搜索"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Messages) },
                    )
                    ArrowPreference(
                        title = "Agent 模型配置",
                        summary = if (llmOn) {
                            "${SpUtil.getLlmBaseUrl()} · ${SpUtil.getLlmModel()}"
                        } else {
                            "配置任意 OpenAI 兼容端点（官方 / 中转 / 本地 ollama）"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.AiScan) },
                        onClick = onOpenLlmConfig,
                    )
                }
            }

            // ── 查分器工具 ──
            // ── 查分器数据源 ──
            // 两个游戏各选一个源，互不影响（例如舞萌用水鱼、中二用落雪）。
            item(key = "source_title") { SectionTitle("查分器数据源") }
            item(key = "source_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    OverlaySpinnerPreference(
                        items = ScoreSource.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = maimaiSource.ordinal,
                        title = "舞萌成绩源",
                        summary = scoreSourceSummary(maimaiSource, lxnsState.bound, GameType.MAIMAI),
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill) },
                        onSelectedIndexChange = { index ->
                            ScoreSource.entries.getOrNull(index)?.let { picked ->
                                maimaiSource = picked
                                SpUtil.setScoreSource(GameType.MAIMAI, picked)
                                // 切源后立刻按新源刷新一次；失败由「我」页状态条提示，不打断设置操作
                                scope.launch { runCatching { DivingFishAccount.refreshRecords() } }
                            }
                        },
                    )
                    OverlaySpinnerPreference(
                        items = ScoreSource.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = chuniSource.ordinal,
                        title = "中二成绩源",
                        summary = scoreSourceSummary(chuniSource, lxnsState.bound, GameType.CHUNITHM),
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill) },
                        onSelectedIndexChange = { index ->
                            ScoreSource.entries.getOrNull(index)?.let { picked ->
                                chuniSource = picked
                                SpUtil.setScoreSource(GameType.CHUNITHM, picked)
                                scope.launch { runCatching { ChuniRecordSync.refresh() } }
                            }
                        },
                    )
                    ArrowPreference(
                        title = if (lxnsState.bound) "落雪账号" else "登录落雪账号",
                        summary = when {
                            lxnsState.bound ->
                                "${lxnsState.name.ifBlank { "已登录" }} · " +
                                    (if (lxnsState.oauth) "落雪账号授权" else "个人 API 密钥") +
                                    " · 两个游戏共用 · 点击管理"
                            LxnsAccount.isOAuthAvailable() ->
                                "未登录 · 推荐「用落雪账号授权」，一步到位；也可手填个人 API 密钥"
                            else -> "未登录 · 点击查看个人 API 密钥获取方法"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Contacts) },
                        onClick = { showLxnsKeyDialog = true },
                    )
                    if (maimaiSource == ScoreSource.LXNS || chuniSource == ScoreSource.LXNS) {
                        // 落雪官方的同步方式是「代理同步」：把 WLAN 代理指向他们的服务器，
                        // 由落雪代抓。它和本 App 的本地 VPN 抢同一块网络接管，只能二选一，
                        // 所以这里只做备选入口 —— App 内抓包走不通时用户还有路可走。
                        ArrowPreference(
                            title = "落雪官方同步（备选）",
                            summary = "App 内抓包走不通时用这条：把 WLAN 代理设为 proxy.maimai.lxns.net:8080，" +
                                "由落雪服务器代抓（与 App 的 VPN 不能同时开）",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill) },
                            onClick = { BrowserJump.openUrl(context, LxnsAccount.SYNC_URL) },
                        )
                    }
                }
            }

            item(key = "tool_title") { SectionTitle("查分器工具") }
            item(key = "tool_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "机台成绩更新（VPN）",
                        summary = "在机台 WiFi 下抓包，按各游戏当前选的数据源上传（水鱼 / 落雪）",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Update) },
                        onClick = onOpenMachineUpdate,
                    )
                    ArrowPreference(
                        title = "二维码导入成绩",
                        summary = "免 VPN：官方登录二维码 + 导入令牌直接同步",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Scan) },
                        onClick = onOpenQrImport,
                    )
                }
            }

            // ── 数据 ──
            item(key = "data_title") { SectionTitle("数据") }
            item(key = "data_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                ArrowPreference(
                    title = "自定义封面源",
                    summary = coverSource.ifBlank { "使用内置候选链（水鱼 CDN + 官网镜像）" }
                        .let { if (coverSource.isBlank()) it else coverSource },
                    startAction = { PrefIcon(MiuixGlassIcons.Regular.Photos) },
                    onClick = { showCoverSource = true },
                )
                ArrowPreference(
                    title = "曲库更新",
                    summary = when (val s = syncState) {
                        SongDataSync.SyncState.Idle -> "点击检查曲库更新（update.json）"
                        SongDataSync.SyncState.Checking -> "正在检查更新…"
                        is SongDataSync.SyncState.Downloading ->
                            "下载中 ${s.received / 1024} / ${s.total / 1024} KB"
                        is SongDataSync.SyncState.Done ->
                            if (updateAvailable != null) "发现新版本 $updateAvailable，点击更新"
                            else "已是最新版本 ${s.version}"
                        is SongDataSync.SyncState.Failed -> "更新失败：${s.message}（点击重试）"
                    },
                    startAction = { PrefIcon(MiuixGlassIcons.Regular.Refresh) },
                    onClick = {
                        if (SongDataSync.updateAvailable.value != null) {
                            KibaState.refreshSongData(context)
                        } else {
                            KibaState.checkSongUpdate()
                        }
                    },
                )
                ArrowPreference(
                    title = "清理封面缓存",
                    summary = when {
                        clearingCovers -> "正在清理…"
                        coverCacheMsg != null -> coverCacheMsg
                        else -> "自动清理不会清除封面缓存；清理后离线封面需重新联网加载"
                    },
                    startAction = { PrefIcon(MiuixGlassIcons.Clear) },
                    onClick = {
                        if (!clearingCovers) {
                            coverCacheMsg = null
                            showCoverClearConfirm = true
                        }
                    },
                    enabled = !clearingCovers,
                )
                }
            }

            // ── 关于 ──
            item(key = "about_title") { SectionTitle("关于") }
            item(key = "about_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "关于 Kiba",
                        summary = "版本 ${BuildConfig.VERSION_NAME}",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Info) },
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }

    if (showCoverClearConfirm) {
        KibaGlassDialog(
            show = true,
            title = "清理封面缓存",
            summary = "将清空已缓存的封面图片（内存 + 磁盘）。" +
                "自动清理永远不会清除封面缓存；手动清理后，离线时封面需重新联网加载。",
            onDismissRequest = { showCoverClearConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { showCoverClearConfirm = false })
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        showCoverClearConfirm = false
                        clearingCovers = true
                        scope.launch(Dispatchers.IO) {
                            val freed = CacheCleaner.clearCoverCache(context)
                            withContext(Dispatchers.Main) {
                                clearingCovers = false
                                coverCacheMsg = if (freed > 0) {
                                    if (freed >= 1 shl 20) "已清理 ${freed shr 20} MB（重新联网可恢复）"
                                    else "已清理 ${(freed shr 10).coerceAtLeast(1)} KB（重新联网可恢复）"
                                } else {
                                    "缓存为空，无需清理"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "清理")
                }
            }
        }
    }

    if (showCoverSource) {
        CoverSourceDialog(
            current = coverSource,
            onDismiss = { showCoverSource = false },
            onConfirm = {
                coverSource = it
                SpUtil.setCoverSource(it)
                showCoverSource = false
            },
        )
    }

    if (showLxnsKeyDialog) {
        LxnsAccountDialog(
            bound = lxnsState.bound,
            name = lxnsState.name,
            playerFound = lxnsState.playerFound,
            onDismiss = { showLxnsKeyDialog = false },
        )
    }
}

/**
 * 数据源说明文案（设置页下拉副标题）。
 *
 * **必须按游戏区分**：水鱼「只回 Best 30、当前版本 B20 会缺」是**中二专有**的限制，
 * 舞萌侧水鱼给的是完整成绩、而且是机台更新最成熟的通道。1.8.3 把这段文案共用给了
 * 两个下拉，导致舞萌用户看到莫名其妙的中二问题描述。
 */
private fun scoreSourceSummary(
    source: ScoreSource,
    lxnsBound: Boolean,
    game: GameType,
): String = when (source) {
    ScoreSource.WATER_FISH -> if (game == GameType.CHUNITHM) {
        "水鱼 · 中二只有账号密码通道，且只回 Best 30（当前版本 B20 会缺）"
    } else {
        "水鱼 · 完整成绩，机台更新链路最成熟（推荐）"
    }
    ScoreSource.LXNS -> when {
        !lxnsBound -> "落雪 · 尚未登录落雪账号，请先点下方「登录落雪账号」"
        game == GameType.CHUNITHM -> "落雪 · 全量成绩 + 官方当前版本 B20"
        else -> "落雪 · 全量成绩（需先在落雪同步过一次，账号才会绑定玩家）"
    }
}

/**
 * 落雪账号弹层 —— **两条登录通道**，默认走 OAuth。
 *
 * ## 为什么优先 OAuth
 * 落雪官方明确把 OAuth 定位成「替代个人 API 密钥」：权限更细（本项目只要
 * `read_user_profile / read_player / write_player / openid`，**不要**能读密钥的
 * `read_user_token`）、令牌 15 分钟一换、用户可随时在站内撤销。
 * 对用户来说也少一步：不用去「账号详情」翻那串 44 位密钥。
 *
 * ## 为什么是「复制授权码」而不是自动回调
 * 这个 OAuth 应用登记的是**无回调地址**，授权成功后落雪**不重定向**，
 * 而是直接在页面上把授权码（形如 `JVJ6-VPTM-MGHZ`）显示出来。
 * 所以流程就是「打开授权页 → 复制那串码 → 粘回来」，不需要起本地 HTTP 监听，
 * 也不用给每台设备登记回调。
 *
 * ## 兜底通道
 * 打包时没带 OAuth 应用凭据（公开仓库构建即是）会自动切到「个人 API 密钥」引导；
 * 用户也可以手动切 —— 两条都能用，OAuth 令牌失效时只要 Key 还在就还能同步。
 */
@Composable
private fun LxnsAccountDialog(
    bound: Boolean,
    name: String,
    playerFound: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LxnsAccount.state.collectAsState()
    val oauthAvailable = remember { LxnsAccount.isOAuthAvailable() }

    var useOAuth by remember { mutableStateOf(oauthAvailable) }
    var codeInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val authModeLabel = if (state.oauth) "落雪账号授权" else "个人 API 密钥"

    KibaGlassDialog(
        show = true,
        title = if (bound) "落雪账号" else "登录落雪账号",
        summary = if (bound) {
            "${name.ifBlank { "已登录" }} · 两个游戏的成绩同步共用这一个凭据"
        } else {
            "落雪是「舞萌 / 中二」的第三方查分器。登录后即可用落雪源同步成绩、往落雪上分。"
        },
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (bound) {
                Text(
                    text = "当前通道：$authModeLabel",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (!playerFound) {
                    Spacer(Modifier.height(8.dp))
                    // 这是最常见的误解来源：登录成功 ≠ 能查到成绩。落雪的数据挂在「玩家」上，
                    // 账号里没有玩家时所有成绩接口都 404 player not found。
                    Text(
                        text = "注意：这个落雪账号还没有绑定玩家。请到落雪网页「同步游戏数据」" +
                            "同步一次，把机台好友码绑上，否则成绩接口会一直返回「找不到玩家」。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "解绑只清除本机的凭据，已同步进本地的成绩不会删除；" +
                        "随时可以重新登录或换一把 Key。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "关闭", onClick = onDismiss)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            LxnsAccount.logout()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(text = "解绑")
                    }
                }
                return@Column
            }

            // ── 通道切换 ──
            if (oauthAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = if (useOAuth) "● 落雪账号授权" else "○ 落雪账号授权",
                        onClick = {
                            useOAuth = true
                            error = null
                        },
                    )
                    TextButton(
                        text = if (useOAuth) "○ 个人 API 密钥" else "● 个人 API 密钥",
                        onClick = {
                            useOAuth = false
                            error = null
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (useOAuth) {
                GuideStep(
                    index = "1",
                    text = "点下面的「打开授权页」，用落雪账号登录并点同意授权（没有账号先去注册）",
                )
                GuideStep(
                    index = "2",
                    text = "授权完成后页面会**直接显示一串授权码**（形如 JVJ6-VPTM-MGHZ），长按复制它，" +
                        "注意授权码只能用一次、且很快过期",
                )
                GuideStep(
                    index = "3",
                    text = "回到这里粘贴授权码，点「登录」",
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "打开授权页",
                        onClick = {
                            LxnsAccount.authorizeUrl()
                                .onSuccess { BrowserJump.openUrl(context, it) }
                                .onFailure { error = it.message }
                        },
                    )
                    TextButton(
                        text = "注册落雪",
                        onClick = { BrowserJump.openUrl(context, LxnsAccount.REGISTER_URL) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                TextField(
                    value = codeInput,
                    onValueChange = {
                        codeInput = it
                        error = null
                    },
                    label = "粘贴授权码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val message = error ?: state.error
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "授权范围：读取你的落雪资料、读取成绩、写入成绩（机台上分必需）、" +
                        "以及一个稳定的账号标识。**不含**「读取个人 API 密钥」。\n" +
                        "令牌 15 分钟自动续期（刷新令牌 30 天），随时可以在落雪「已授权应用」里撤销。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            error = null
                            scope.launch {
                                LxnsAccount.loginWithOAuth(codeInput)
                                    .onSuccess {
                                        busy = false
                                        onDismiss()
                                    }
                                    .onFailure {
                                        busy = false
                                        error = it.message ?: "授权登录失败"
                                    }
                            }
                        },
                        enabled = codeInput.isNotBlank() && !busy,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(text = if (busy) "登录中…" else "登录")
                    }
                }
                return@Column
            }

            // ── 个人 API 密钥通道 ──
            GuideStep(
                index = "1",
                text = "打开落雪并登录（没有账号就点下面的「注册落雪」）",
            )
            GuideStep(
                index = "2",
                text = "点右上角头像进「账号详情」（maimai.lxns.net/user/profile）",
            )
            GuideStep(
                index = "3",
                text = "找到「个人 API 密钥」一栏，点右侧复制按钮（没有就点「重置 API 密钥」生成）",
            )
            GuideStep(
                index = "4",
                text = "粘贴到下面的输入框，点「绑定」",
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "注册落雪",
                    onClick = { BrowserJump.openUrl(context, LxnsAccount.REGISTER_URL) },
                )
                TextButton(
                    text = "打开账号详情",
                    onClick = { BrowserJump.openUrl(context, LxnsAccount.API_KEY_URL) },
                )
            }

            Spacer(Modifier.height(12.dp))
            TextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it
                    error = null
                },
                label = "粘贴落雪 API Key",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val message = error ?: state.error
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "要点：这里要的是「个人 API 密钥」（账号详情页），" +
                    "不是开发者面板的「开发者 API 密钥」——两者不能混用。" +
                    "Key 等同账号凭据，只保存在本机，可随时在落雪站内重置。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            LxnsAccount.login(keyInput)
                                .onSuccess {
                                    busy = false
                                    onDismiss()
                                }
                                .onFailure {
                                    busy = false
                                    error = it.message ?: "绑定失败"
                                }
                        }
                    },
                    enabled = keyInput.isNotBlank() && !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = if (busy) "验证中…" else "绑定")
                }
            }
        }
    }
}

/** 引导步骤一行：序号圆点 + 说明文字。 */
@Composable
private fun GuideStep(index: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Text(
            text = "$index.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 自定义封面图床前缀编辑弹层。 */
@Composable
private fun CoverSourceDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    KibaGlassDialog(
        show = true,
        title = "自定义封面源",
        summary = "指定一个自建的封面图床前缀来替换默认图源。" +
            "填入后 Kiba 会优先用「前缀 + 封面文件名」取图，" +
            "例：https://your-mirror/maimai-mobile/img/Music/ 。留空则使用内置候选链。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = "图床前缀",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onConfirm(value) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(text = "保存")
                }
            }
        }
    }
}
