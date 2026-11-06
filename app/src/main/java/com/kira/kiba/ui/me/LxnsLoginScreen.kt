package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 落雪账户登录页（独立二级页，版式对齐水鱼 [LoginScreen]）。
 *
 * 双通道：
 * 1. **OAuth 授权（推荐）**：无回调地址应用，授权页直接显示一次性授权码，复制粘回；
 * 2. **个人 API 密钥（兜底）**：未打包 OAuth 凭据的构建、或不想走授权的用户可用。
 *
 * 已登录时展示当前通道与账号状态，可解绑。落雪账号舞萌 / 中二两游戏通用。
 */
@Composable
fun LxnsLoginScreen(
    scrollBehavior: ScrollBehavior,
    onLoginSuccess: () -> Unit,
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

    // 与水鱼 LoginScreen 同一守门逻辑：只有本次真正提交过登录且 bound 翻真才退出，
    // 避免「重新登录」入口一进来就被弹回。
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.bound, submitted) {
        if (submitted && state.bound) onLoginSuccess()
    }
    // 进页复核一次玩家绑定状态：用户很可能是刚从落雪网页「同步游戏数据」回来，
    // 不重探就会一直看到上次缓存的「尚未绑定玩家」。
    LaunchedEffect(Unit) {
        if (state.bound) runCatching { LxnsAccount.refreshStatus() }
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
                .padding(padding)
                .imePadding(),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 已登录：账号状态 + 解绑 ──
            if (state.bound) {
                item(key = "bound_title") { SectionTitle("已登录落雪账号") }
                item(key = "bound_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = state.name.ifBlank { "已登录" },
                                style = MiuixTheme.textStyles.headline2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "当前通道：${if (state.oauth) "落雪账号授权（OAuth）" else "个人 API 密钥"}" +
                                    " · 舞萌 / 中二两游戏通用",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (!state.playerFound) {
                                Spacer(Modifier.height(8.dp))
                                // 最常见的误解来源：登录成功 ≠ 能查到成绩。落雪的数据挂在「玩家」上，
                                // 账号里没有玩家时所有成绩接口都 404 player not found。
                                Text(
                                    text = "注意：这个落雪账号还没有绑定玩家。请到落雪网页「同步游戏数据」" +
                                        "同步一次，把机台好友码绑上，否则成绩接口会一直返回「找不到玩家」。",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        text = "打开落雪同步页",
                                        onClick = { BrowserJump.openUrl(context, LxnsAccount.SYNC_URL) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = if (state.loading) "检测中…" else "已同步，重新检测",
                                        onClick = { scope.launch { runCatching { LxnsAccount.refreshStatus() } } },
                                        enabled = !state.loading,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "解绑只清除本机凭据，已同步进本地的成绩不会删除；" +
                                    "随时可以重新登录或换一把 Key。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        LxnsAccount.logout()
                                        error = null
                                    },
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                ) {
                                    Text(text = "解绑")
                                }
                            }
                        }
                    }
                }
            }

            // ── 通道切换 ──
            item(key = "channel_title") {
                SectionTitle(if (useOAuth) "落雪账号授权登录（推荐）" else "个人 API 密钥登录（兜底）")
            }
            item(key = "channel_switch") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = if (useOAuth) "● 落雪账号授权" else "○ 落雪账号授权",
                            onClick = {
                                useOAuth = true
                                error = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = if (useOAuth) "○ 个人 API 密钥" else "● 个人 API 密钥",
                            onClick = {
                                useOAuth = false
                                error = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (useOAuth) {
                item(key = "oauth_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            GuideStep(
                                index = "1",
                                text = "点下面的「打开授权页」，用落雪账号登录并点同意授权（没有账号先去注册）",
                            )
                            GuideStep(
                                index = "2",
                                text = "授权完成后页面会直接显示一串授权码（形如 JVJ6-VPTM-MGHZ），长按复制它；" +
                                    "授权码只能用一次、且很快过期",
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
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = "注册落雪",
                                    onClick = { BrowserJump.openUrl(context, LxnsAccount.REGISTER_URL) },
                                    modifier = Modifier.weight(1f),
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
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "授权范围：读取你的落雪资料、读取成绩、写入成绩（机台上分必需）、" +
                                    "以及一个稳定的账号标识。不含「读取个人 API 密钥」。\n" +
                                    "令牌 15 分钟自动续期（刷新令牌 30 天），随时可以在落雪「已授权应用」里撤销。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        if (busy || codeInput.isBlank()) return@Button
                                        busy = true
                                        error = null
                                        submitted = true
                                        scope.launch {
                                            LxnsAccount.loginWithOAuth(codeInput)
                                                .onSuccess { /* bound 后自动退出 */ }
                                                .onFailure { error = it.message ?: "授权登录失败" }
                                            busy = false
                                        }
                                    },
                                    enabled = codeInput.isNotBlank() && !busy,
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                ) {
                                    Text(text = if (busy) "登录中…" else "登录")
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "key_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = "打开账号详情",
                                    onClick = { BrowserJump.openUrl(context, LxnsAccount.API_KEY_URL) },
                                    modifier = Modifier.weight(1f),
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
                                    style = MiuixTheme.textStyles.footnote1,
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
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        if (busy || keyInput.isBlank()) return@Button
                                        busy = true
                                        error = null
                                        submitted = true
                                        scope.launch {
                                            LxnsAccount.login(keyInput)
                                                .onSuccess { /* bound 后自动退出 */ }
                                                .onFailure { error = it.message ?: "绑定失败" }
                                            busy = false
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
            }

            item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 引导步骤一行：序号 + 说明文字（与设置页旧弹层同款）。 */
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
