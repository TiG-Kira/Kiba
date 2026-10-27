package com.kira.kiba.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.maimai.QrScoreImport
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.vpn.VpnSettings
import com.kira.kiba.core.vpn.crawler.CrawlerCaller
import com.kira.kiba.core.vpn.crawler.WechatCrawlerListener
import com.kira.kiba.core.vpn.core.LocalVpnService
import com.kira.kiba.core.vpn.server.HttpServerService
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 机台成绩更新（VPN 方案，移植自上游 MaimaiData 的"查分器代理"）。
 *
 * 原理：本机起一个本地 VPN，把机台 WiFi 下的 maimai 接口流量导向本地代理抓包；
 * 同时起一个本地 HTTP 跳转服务（127.0.0.2:8284），把随机链接发到微信里打开，
 * 微信访问机台页面时数据被本地代理捕获，解析后上传水鱼，实现"离线机台也能更新成绩"。
 *
 * 使用前提：
 * - 手机与机台处于同一 WiFi（或机台热点）；
 * - 已在水鱼登录（账号密码用于上传成绩）；
 * - 需要微信（用微信打开链接才能带上机台的登录态）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineUpdateScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(LocalVpnService.IsRunning) }
    var difficulties by remember { mutableStateOf(VpnSettings.getUpdateDifficulty()) }

    // ── 二维码导入（Easymai 方式）状态 ──
    // 隐私铁律：二维码内容只存在于这些临时状态里，更新完成后立即清空，
    // 不写 SP / 不落盘 / 日志中只出现尾部 4 字符。
    var qrText by remember { mutableStateOf("") }
    var manualImportToken by remember { mutableStateOf("") }
    var qrImporting by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCamera = true
        } else {
            logs.add("未授予摄像头权限，无法扫码")
        }
    }

    val qrImagePicker = rememberQrImagePicker { text ->
        if (text == null) {
            logs.add("图片中未识别到二维码")
        } else {
            qrText = QrScoreImport.extractQrCode(text) ?: text
            logs.add("已从图片识别二维码内容")
        }
    }

    fun startQrImport() {
        if (qrImporting) return
        qrImporting = true
        scope.launch {
            val result = QrScoreImport.importFromQr(
                qrCodeRaw = qrText,
                manualImportToken = manualImportToken.ifBlank { null },
            ) { logs.add(it) }
            result.onSuccess {
                logs.add("导入完成：${it.recordCount} 条记录已上传水鱼（${it.serverMessage}）")
            }
            result.onFailure {
                logs.add("导入失败：${it.message}")
            }
            // 用完即毁：无论成败，二维码与令牌输入框立即清空，数据随状态丢弃
            qrText = ""
            manualImportToken = ""
            qrImporting = false
        }
    }

    DisposableEffect(Unit) {
        val listener = object : WechatCrawlerListener {
            override fun onMessageReceived(logString: String) {
                logs.add(logString)
            }

            override fun onStartAuth() {
                logs.add("开始获取微信授权…")
            }

            override fun onFinishUpdate() {
                logs.add("成绩更新完成")
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "机台更新 onError", e)
                logs.add("出错：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        CrawlerCaller.setOnWechatCrawlerListener(listener)
        onDispose { CrawlerCaller.removeOnWechatCrawlerListener() }
    }

    fun stopServices() {
        runCatching {
            context.stopService(Intent(context, LocalVpnService::class.java))
            context.stopService(Intent(context, HttpServerService::class.java))
        }
        LocalVpnService.IsRunning = false
        running = false
        logs.add("已停止抓包")
    }

    /** 一条龙：起服务 → 复制链接 → 拉起微信 → 自动取微信授权 → 抓包上传。 */
    fun startServices() {
        runCatching {
            context.startService(Intent(context, LocalVpnService::class.java))
            context.startService(Intent(context, HttpServerService::class.java))
        }.onFailure { logs.add("服务启动失败：${it.javaClass.simpleName}: ${it.message}") }
        running = true
        // 生成随机链接并复制到剪贴板，引导用户在微信里打开
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = (1..10).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val link = "http://127.0.0.2:8284/$random"
        copyToClipboard(context, link)
        logs.add("已复制链接：$link")
        logs.add("正在拉起微信…请在微信里把链接粘贴并打开")
        openWechat(context)

        // 自动取微信授权链接并进入抓包上传流程（无需用户再点按钮）
        scope.launch {
            try {
                delay(3000)
                val authUrl = CrawlerCaller.getWechatAuthUrl()
                if (authUrl.isNullOrBlank()) {
                    logs.add("获取微信授权链接失败，请确认手机能访问 tgk-wcaime.wahlap.com")
                    return@launch
                }
                logs.add("已获取微信授权，开始抓取机台成绩…")
                CrawlerCaller.fetchData(authUrl)
            } catch (e: Exception) {
                Log.e(TAG, "抓包失败", e)
                logs.add("抓包失败：${e.javaClass.simpleName}: ${e.message}")
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                            text = "机台成绩更新",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "两种方式：「二维码导入」用官方公众号登录二维码直接拉取官方成绩上传水鱼（免 VPN、免机台网络）；" +
                                "「VPN 抓包」在机台 WiFi 下开本地 VPN 抓取成绩并上传水鱼：" +
                                "点「开始抓包」授权 VPN → 链接自动复制 → 切到微信把链接粘贴打开，等待抓包完成即可。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
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
                }
            }

            // ── 二维码导入（免 VPN，Easymai 方式）──
            item(key = "qr_title") { SectionTitle("二维码导入（免 VPN）") }
            item(key = "qr_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "用官方登录二维码更新成绩",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "在「舞萌 DX」公众号长按登录二维码复制解析文本（SGWCMAID 开头），" +
                                "粘贴 / 扫码 / 选图任选其一，拉取官方全难度成绩后上传水鱼，无需 VPN 与机台网络。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        val dfLoggedIn = SpUtil.isDivingFishLoggedIn()
                        val hasJwt = SpUtil.getDivingFishJwt().isNotBlank()
                        val qrAccountHint = when {
                            !dfLoggedIn -> "未登录水鱼（无法上传，请先到「我」页登录）"
                            !hasJwt -> "当前为 OAuth 授权登录，二维码导入需「账号密码」登录水鱼"
                            else -> null
                        }
                        if (qrAccountHint != null) {
                            Text(
                                text = qrAccountHint,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        TextField(
                            value = qrText,
                            onValueChange = { qrText = it },
                            label = "粘贴二维码内容（SGWCMAID…）",
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = "摄像头扫码",
                                onClick = {
                                    if (hasCameraPermission(context)) {
                                        showCamera = true
                                    } else {
                                        cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !qrImporting,
                            )
                            TextButton(
                                text = "从图片识别",
                                onClick = {
                                    qrImagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !qrImporting,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = manualImportToken,
                            onValueChange = { manualImportToken = it },
                            label = "导入令牌（可选，留空自动生成）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { startQrImport() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !qrImporting &&
                                qrText.isNotBlank() &&
                                SpUtil.isDivingFishLoggedIn() &&
                                SpUtil.getDivingFishJwt().isNotBlank(),
                        ) {
                            Text(
                                text = if (qrImporting) "正在导入…" else "拉取官方成绩并更新水鱼",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "隐私：二维码内容仅本次内存使用，更新完成后立即丢弃，不保存、不写入日志明文；" +
                                "解析需经 Easymai 接口（与官方客户端行为一致）。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item(key = "diff_title") { SectionTitle("更新难度（VPN 方案）") }
            item(key = "diff_card") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        listOf(
                            "BASIC" to 0, "ADVANCED" to 1, "EXPERT" to 2,
                            "MASTER" to 3, "Re:MASTER" to 4,
                        ).forEach { (name, idx) ->
                            CheckboxPreference(
                                title = name,
                                checked = idx in difficulties,
                                onCheckedChange = { checked ->
                                    difficulties = if (checked) {
                                        difficulties + idx
                                    } else {
                                        difficulties - idx
                                    }
                                    VpnSettings.setUpdateDifficulty(difficulties)
                                },
                                summary = "${chartCountHint(idx)}",
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
                                onClick = {
                                    if (running) {
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
                                Text(text = if (running) "停止抓包" else "开始抓包")
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

    // 摄像头扫码全屏浮层（覆盖在 Scaffold 之上）
    if (showCamera) {
        QrCameraScanner(
            onResult = { text ->
                qrText = QrScoreImport.extractQrCode(text) ?: text
                showCamera = false
                logs.add("已识别二维码内容")
            },
            onClose = { showCamera = false },
        )
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
