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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.maimai.MaiQrGateway
import com.kira.kiba.core.maimai.QrScoreImport
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * 接入 Easymai 账号（登录/注册 + 同步管理）。
 *
 * 第三方解析服务需要自有账号鉴权（二维码取数 / 拉取云端同步成绩都挂在账号下），
 * 本页提供：
 * - 未接入：用户名密码登录 / 注册（凭据只存本机私有 SP）；
 * - 已接入：登录状态（用户名）、Kiba 上的最近同步时间、「手动同步」
 *   （把账号云端已同步的成绩拉取并上传到水鱼，无需重新扫码）、注销。
 *
 * 同步上传凭证与二维码导入页一致（导入令牌 / OAuth），账号页不重复收集。
 */
@Composable
fun EasyMaiAccountScreen(scrollBehavior: ScrollBehavior) {
    val scope = rememberCoroutineScope()

    // bound 以本地凭据为准；key 变化（登录/注销）后重新读取
    var stateKey by remember { mutableStateOf(0) }
    val bound = remember(stateKey) { MaiQrGateway.isAccountBound() }
    val boundName = remember(stateKey) { MaiQrGateway.boundUsername() }
    val lastSync = remember(stateKey) { QrScoreImport.lastSyncAt() }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val syncLogs = remember { mutableListOf<String>() }

    fun refreshState() {
        stateKey++
    }

    fun doLogin() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            MaiQrGateway.login(username.trim(), password)
                .onSuccess {
                    message = "登录成功"
                    password = ""
                    refreshState()
                }
                .onFailure { message = "登录失败：${it.message}" }
            busy = false
        }
    }

    fun doRegister() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            MaiQrGateway.register(username.trim(), password)
                .onSuccess { message = "注册成功，请点击「登录」完成接入" }
                .onFailure { message = "注册失败：${it.message}" }
            busy = false
        }
    }

    fun doManualSync() {
        if (syncing) return
        syncing = true
        syncLogs.clear()
        scope.launch {
            QrScoreImport.syncGatewayToDivingFish { syncLogs.add(it) }
                .onSuccess {
                    message = "手动同步完成：$it 条记录已上传水鱼"
                    refreshState()
                    // 立刻刷新查分器账户态：拉最新成绩/评分，防止回到「我」页时
                    // 账户卡还挂着旧的错误提示/过期缓存
                    KibaState.refreshRecords()
                }
                .onFailure { message = "同步失败：${it.message}" }
            syncing = false
        }
    }

    fun doUnbind() {
        MaiQrGateway.unbind()
        username = ""
        password = ""
        message = "已注销"
        refreshState()
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
            if (!bound) {
                item(key = "login_title") { SectionTitle("登录 / 注册") }
                item(key = "login_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = username,
                                onValueChange = { username = it },
                                label = "用户名",
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
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                    onClick = { doLogin() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                                ) {
                                    Text(
                                        text = if (busy) "请稍候…" else "登录",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                                TextButton(
                                    text = "注册新账号",
                                    onClick = { doRegister() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "接入说明：这是第三方解析服务的自有账号（与水鱼账号无关），" +
                                    "用于二维码取数鉴权。没有账号可直接「注册新账号」，" +
                                    "凭据只保存在本机，登录一次即可长期使用（token 自动续期）。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                item(key = "status_title") { SectionTitle("接入状态") }
                item(key = "status_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "已接入：$boundName",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (lastSync > 0L) {
                                    "最近同步（账号 → 水鱼）：" +
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                            .format(Date(lastSync))
                                } else {
                                    "最近同步：尚未同步过"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item(key = "sync_button") {
                    Button(
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = { doManualSync() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncing,
                    ) {
                        Text(
                            text = if (syncing) "正在同步…" else "手动同步到水鱼",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
                if (syncing && syncLogs.isNotEmpty()) {
                    item(key = "sync_logs") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = CardDefaults.CornerRadius,
                            insideMargin = CardDefaults.InsideMargin,
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                syncLogs.takeLast(20).forEach {
                                    Text(
                                        text = it,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "sync_note") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "「手动同步」把账号云端已同步的成绩（上次二维码导入的数据）" +
                                    "拉取并上传到水鱼，无需重新扫码。上传凭证沿用二维码导入页的" +
                                    "设置（OAuth 优先，其次导入令牌）。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item(key = "unbind_button") {
                    TextButton(
                        text = "注销登录",
                        onClick = { doUnbind() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            message?.let { msg ->
                item(key = "message") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
