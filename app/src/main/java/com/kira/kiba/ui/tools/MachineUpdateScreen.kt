package com.kira.kiba.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.vpn.VpnSettings
import com.kira.kiba.core.vpn.crawler.CrawlerCaller
import com.kira.kiba.core.vpn.crawler.WechatCrawlerListener
import com.kira.kiba.core.vpn.core.LocalVpnService
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.vpn.tunnel.HttpCapturerTunnel
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 机台成绩更新（VPN 抓包方案，移植自上游 MaimaiData 的"查分器代理"）。
 *
 * 原理：本机起一个本地 VPN，把机台 WiFi 下的 maimai 接口流量导向本地代理抓包；
 * 同时起一个本地 HTTP 跳转服务（127.0.0.2:8284），把随机链接发到微信里打开，
 * 微信访问机台页面时数据被本地代理捕获，解析后上传水鱼，实现"离线机台也能更新成绩"。
 *
 * 免 VPN 的替代方案见 [QrImportScreen]（官方二维码 + 导入令牌）。
 *
 * 使用前提：
 * - 手机与机台处于同一 WiFi（或机台热点）；
 * - 已在水鱼登录（账号密码用于上传成绩）；
 * - 需要微信（用微信打开链接才能带上机台的登录态）。
 */
@Composable
fun MachineUpdateScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(LocalVpnService.IsRunning) }
    /** 已发起会话但还没收到微信回调（按钮会锁住，避免重复点把 VPN 反复拆建）。 */
    var waitingCallback by remember { mutableStateOf(false) }
    var difficulties by remember { mutableStateOf(VpnSettings.getUpdateDifficulty()) }
    var chuniDifficulties by remember { mutableStateOf(VpnSettings.getUpdateChuniDifficulty()) }
    // 当前游戏（跟随曲目页开关）：决定微信授权路径、取数与上传方式、难度项
    val gameType = remember { SpUtil.getGameType() }
    val isChunithm = gameType == GameType.CHUNITHM
    val selectedDifficulties = if (isChunithm) chuniDifficulties else difficulties
    // 上传目标跟随该游戏在设置页选的数据源（两游戏各自独立）
    val source = remember { SpUtil.getScoreSource(gameType) }
    val useLxns = source == ScoreSource.LXNS
    val lxnsState by LxnsAccount.state.collectAsState()

    DisposableEffect(Unit) {
        val listener = object : WechatCrawlerListener {
            override fun onMessageReceived(logString: String) {
                logs.add(logString)
            }

            override fun onStartAuth() {
                logs.add("开始获取微信授权…")
            }

            override fun onFinishUpdate() {
                waitingCallback = false
                logs.add("成绩更新完成")
                // 立刻刷新对应游戏的成绩：防止回到「我」页时还挂着上一次失败的错误提示
                if (isChunithm) {
                    scope.launch { runCatching { ChuniRecordSync.refresh() } }
                } else {
                    KibaState.refreshRecords()
                }
            }

            override fun onError(e: Exception) {
                waitingCallback = false
                Log.e(TAG, "机台更新 onError", e)
                logs.add("出错：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        CrawlerCaller.setOnWechatCrawlerListener(listener)
        onDispose { CrawlerCaller.removeOnWechatCrawlerListener() }
    }

    // VPN 真实状态跟随服务回调：中途被系统断开时 UI 不能还显示「抓包中」。
    // 注意 VPN 在收到回调后会被抓取逻辑主动关闭，所以这里只同步 running，
    // 会话是否结束由 waitingCallback 决定（否则正常流程也会被误判成失败）。
    DisposableEffect(Unit) {
        val statusListener = object : LocalVpnService.onStatusChangedListener {
            override fun onStatusChanged(status: String?, isRunning: Boolean?) {
                running = isRunning == true
            }

            override fun onLogReceived(logString: String?) = Unit
        }
        LocalVpnService.addOnStatusChangedListener(statusListener)
        onDispose { LocalVpnService.removeOnStatusChangedListener(statusListener) }
    }

    fun stopServices() {
        runCatching { LocalVpnService.shutdown() }
        running = false
        waitingCallback = false
        logs.add("已停止抓包")
    }

    /**
     * 一条龙：起前台服务 → 复制链接 → 拉起微信 → **等微信回调**。
     *
     * 这里刻意**不**由 App 自己发授权请求：`getWechatAuthUrl()` 拿到的只是
     * `authorize` 入口，没有微信登录态，跟着它走只会拿到登录页，抓下来的页面是空的
     * （落雪会直接报「页面内容为空」）。必须等微信带着 `code` 回调到
     * `callback/{game}`，那条请求被 VPN 抓到后才会真正开始取数 —— 见
     * [HttpCapturerTunnel]。
     *
     * 也正因如此，这个流程**必须**让 VPN 一直活着（前台服务），
     * 用户切到微信期间服务被系统停掉就前功尽弃。
     */
    fun startServices() {
        runCatching {
            context.startForegroundService(Intent(context, LocalVpnService::class.java))
        }.onFailure { logs.add("服务启动失败：${it.javaClass.simpleName}: ${it.message}") }
        running = true
        waitingCallback = true
        HttpCapturerTunnel.resetCaptureState()
        // 生成随机链接并复制到剪贴板，引导用户在微信里打开
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = (1..10).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val link = "http://127.0.0.2:8284/$random"
        copyToClipboard(context, link)
        logs.add("已复制链接：$link")
        logs.add("正在拉起微信…请在微信里把链接粘贴并打开")
        logs.add("打开后如果出现授权页请点「允许」，然后留在微信里，别急着切回来")
        openWechat(context)

        // 只是提醒，不强制结束会话（微信那边可能多等一会儿）
        scope.launch {
            delay(180_000)
            if (waitingCallback) {
                logs.add("已等待 3 分钟还没收到微信授权回调：确认链接是在微信里打开的，可点「停止抓包」重试")
            }
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            logs.add("VPN 已授权，正在启动服务…")
            startServices()
        } else {
            logs.add("未授予 VPN 权限，已取消")
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
            item(key = "intro") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "机台成绩更新（VPN 抓包）",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "点「开始抓包」并授权 VPN → 链接自动复制 → 切到微信粘贴打开，" +
                                "授权后留在微信里等它自动跳转，成绩就会抓取并上传到${source.label}。\n" +
                                "抓包期间通知栏会有一条「机台抓包进行中」——那是前台服务，" +
                                "保证切到微信后 VPN 不被系统掐断，请别手动划掉。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (useLxns) {
                            // 落雪：只要绑定了个人 API 密钥就能上传，不需要账密 / jwt
                            val keyBound = lxnsState.bound || SpUtil.isLxnsLoggedIn()
                            Text(
                                text = when {
                                    !keyBound -> "落雪账号：未绑定 API Key（无法上传成绩）"
                                    !lxnsState.playerFound ->
                                        "落雪账号：${lxnsState.name.ifBlank { "已绑定" }}" +
                                            " · 尚未绑定玩家，请先在落雪网页同步一次"
                                    else -> "落雪账号：${lxnsState.name.ifBlank { "已绑定" }}"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (!keyBound || !lxnsState.playerFound) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        } else {
                            // 登录状态以 isDivingFishLoggedIn 为准（OAuth 绑定或原版 jwt 任一即可），
                            // 不能只看 getUserName——OAuth 设备码登录不落地用户名，
                            // 否则实际已登录却显示"未登录"。
                            val dfLoggedIn = SpUtil.isDivingFishLoggedIn()
                            val dfDisplayName = SpUtil.getUserName()
                                .ifBlank { SpUtil.getDivingFishOAuthNickname() }
                                .ifBlank { "已登录" }
                            val dfHint = when {
                                !dfLoggedIn -> "未登录（无法上传成绩）"
                                SpUtil.getUserName().isBlank() -> "$dfDisplayName（OAuth 授权）"
                                else -> dfDisplayName
                            }
                            Text(
                                text = "水鱼账号：$dfHint",
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (!dfLoggedIn) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 水鱼中二上传必须走账号密码 jwt 通道（OAuth scope 不支持中二）；
                        // 落雪没有这个限制，别把水鱼的约束错挂到落雪上。
                        val chuniNoJwt = isChunithm && !useLxns && SpUtil.getDivingFishJwt().isBlank()
                        Text(
                            text = when {
                                !isChunithm -> "当前游戏：舞萌 DX（在「曲目」页顶部切换游戏）"
                                useLxns -> "当前游戏：中二节奏 · 落雪通道"
                                chuniNoJwt -> "当前游戏：中二节奏 · 需改用「账号密码」登录（OAuth 不支持中二上传）"
                                else -> "当前游戏：中二节奏 · 账号密码通道可用"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (chuniNoJwt) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
            }

            // ── 更新难度（按当前游戏切换：舞萌 5 项 / 中二 7 项）──
            item(key = "diff_title") {
                SectionTitle(if (isChunithm) "更新难度（中二节奏）" else "更新难度（舞萌）")
            }
            item(key = "diff_card") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val items = if (isChunithm) {
                            listOf(
                                "BASIC" to 0, "ADVANCED" to 1, "EXPERT" to 2,
                                "MASTER" to 3, "ULTIMA" to 4,
                                "WORLD'S END" to 5, "最近游玩（Rating 详情，仅水鱼）" to 6,
                            )
                        } else {
                            listOf(
                                "BASIC" to 0, "ADVANCED" to 1, "EXPERT" to 2,
                                "MASTER" to 3, "Re:MASTER" to 4,
                            )
                        }
                        items.forEach { (name, idx) ->
                            CheckboxPreference(
                                title = name,
                                checked = idx in selectedDifficulties,
                                onCheckedChange = { checked ->
                                    if (isChunithm) {
                                        chuniDifficulties = if (checked) {
                                            chuniDifficulties + idx
                                        } else {
                                            chuniDifficulties - idx
                                        }
                                        VpnSettings.setUpdateChuniDifficulty(chuniDifficulties)
                                    } else {
                                        difficulties = if (checked) {
                                            difficulties + idx
                                        } else {
                                            difficulties - idx
                                        }
                                        VpnSettings.setUpdateDifficulty(difficulties)
                                    }
                                },
                                summary = diffHint(idx, isChunithm),
                            )
                        }
                    }
                }
            }

            item(key = "action_title") { SectionTitle("操作") }
            item(key = "action_card") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                onClick = {
                                    if (running || waitingCallback) {
                                        stopServices()
                                    } else {
                                        val prepare = LocalVpnService.prepare(context)
                                        if (prepare == null) {
                                            startServices()
                                        } else {
                                            vpnLauncher.launch(prepare)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = when {
                                        running -> "停止抓包"
                                        waitingCallback -> "抓取中…"
                                        else -> "开始抓包"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (logs.isNotEmpty()) {
                item(key = "log_title") { SectionTitle("运行日志") }
                item(key = "log") {
                    Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            logs.takeLast(30).forEach { line ->
                                Text(
                                    text = line,
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
}

/** 难度对应的曲库等级说明。 */
private const val TAG = "MachineUpdate"

private fun chartCountHint(difficultyIndex: Int): String = when (difficultyIndex) {
    0 -> "BASIC 初级"
    1 -> "ADVANCED 高级"
    2 -> "EXPERT 专家"
    3 -> "MASTER 大师"
    4 -> "Re:MASTER 宗师"
    else -> ""
}

/** 难度项说明（按游戏：中二含 WORLD'S END 与最近游玩）。 */
private fun diffHint(difficultyIndex: Int, isChunithm: Boolean): String = if (isChunithm) {
    when (difficultyIndex) {
        0 -> "BASIC 初级"
        1 -> "ADVANCED 高级"
        2 -> "EXPERT 专家"
        3 -> "MASTER 大师"
        4 -> "ULTIMA 究极"
        5 -> "WORLD'S END 特殊谱面（无定数）"
        6 -> "最近游玩记录（服务端按 recent=1 处理）"
        else -> ""
    }
} else {
    chartCountHint(difficultyIndex)
}

/** 复制到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("kiba", text))
}

/** 拉起微信。 */
private fun openWechat(context: Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
        }
        context.startActivity(intent)
    }
}
