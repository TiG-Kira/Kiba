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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 查分器登录页（独立二级页，非弹层——miuix OverlayDialog 与软键盘不兼容）。
 *
 * 双通道：
 * 1. 账号密码（原版 MaimaiData 方式，当前可用）：POST username/password
 *    换长效 jwt_token，仅存本机私有 SP；
 * 2. OAuth 设备码（需应用获批机密客户端）：当前水鱼服务端返回
 *    unauthorized_client，保留入口待放通。
 */
@Composable
fun LoginScreen(
    scrollBehavior: ScrollBehavior,
    onLoginSuccess: () -> Unit,
) {
    val account by DivingFishAccount.state.collectAsState()
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordBusy by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // OAuth 通道状态
    var oauthBusy by remember { mutableStateOf(false) }
    var oauthError by remember { mutableStateOf<String?>(null) }
    var oauthCode by remember { mutableStateOf("") }
    var deviceAuth by remember { mutableStateOf<DivingFishAccount.DeviceAuth?>(null) }

    // 只有「本次进入后真正发起过登录」且已登录，才自动退出本页。
    // 否则在已登录状态点「重新登录 / 切换账户」时，bound 一进来就是 true，
    // 会立刻被弹回上一页，表现为入口点了没反应。
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(account.bound, submitted) {
        if (submitted && account.bound) onLoginSuccess()
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
            // ── 账号密码（原版方式，当前可用）──
            item(key = "pw_title") { SectionTitle("账号密码登录（原版方式）") }
            item(key = "pw_card") {
                Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "使用水鱼查分器（diving-fish.com）账号直接登录。" +
                                "登录凭证（jwt）仅保存在本机私有目录，不会上传；" +
                                "修改水鱼密码或在网页端注销即可使其失效。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = username,
                            onValueChange = { username = it },
                            label = "水鱼用户名",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "密码",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        passwordError?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = msg,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    passwordBusy = true
                                    passwordError = null
                                    submitted = true
                                    scope.launch {
                                        DivingFishAccount.loginWithPassword(username, password)
                                            .onSuccess { /* bound 后自动退出 */ }
                                            .onFailure { e -> passwordError = e.message }
                                        passwordBusy = false
                                    }
                                },
                            ) {
                                Text(text = if (passwordBusy) "正在登录…" else "登录")
                            }
                        }
                    }
                }
            }

            // ── OAuth 设备码（备用通道，当前服务端未放行）──
            item(key = "oauth_title") { SectionTitle("OAuth 授权登录（备用）") }
            item(key = "oauth_card") {
                Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "设备码授权流程（不碰口令，授权关系存水鱼服务端）。" +
                                "当前应用为公开客户端、未获批机密流程，" +
                                "服务端会返回 unauthorized_client；待审核通过后此通道可用。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (deviceAuth == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    text = if (oauthBusy) "正在发起…" else "尝试 OAuth 授权",
                                    onClick = {
                                        oauthBusy = true
                                        oauthError = null
                                        submitted = true
                                        scope.launch {
                                            DivingFishAccount.startDeviceAuth()
                                                .onSuccess { deviceAuth = it }
                                                .onFailure { e -> oauthError = e.message }
                                            oauthBusy = false
                                        }
                                    },
                                )
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
                                value = oauthCode,
                                onValueChange = { oauthCode = it },
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
                                    text = "完成绑定",
                                    onClick = {
                                        oauthBusy = true
                                        oauthError = null
                                        submitted = true
                                        scope.launch {
                                            DivingFishAccount.finishDeviceAuth(oauthCode)
                                                .onFailure { e -> oauthError = e.message }
                                            oauthBusy = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                TextButton(
                                    text = "取消",
                                    onClick = {
                                        deviceAuth = null
                                        oauthCode = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        oauthError?.let { msg ->
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

            item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
