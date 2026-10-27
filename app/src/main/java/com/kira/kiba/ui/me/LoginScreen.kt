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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 查分器登录页（独立二级页，非弹层——miuix OverlayDialog 与软键盘不兼容）。
 *
 * 登录方式：账号密码（原版 MaimaiData 方式）：POST username/password
 * 换长效 jwt_token，仅存本机私有 SP。
 *
 * OAuth 设备码通道已暂时停用（服务端返回 unauthorized_client，入口已移除）——
 * core 里 [DivingFishAccount.startDeviceAuth] / [finishDeviceAuth] 实现保留，
 * 审核通过后恢复入口只需加回 UI。
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
                modifier = (Modifier.fillMaxWidth()),
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

            item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
