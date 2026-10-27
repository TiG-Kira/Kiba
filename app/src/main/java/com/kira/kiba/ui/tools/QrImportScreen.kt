package com.kira.kiba.ui.tools

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.maimai.QrScoreImport
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
 * 二维码导入成绩（免 VPN，独立二级页——与机台 VPN 抓包页分开）。
 *
 * 两个凭据，缺一不可：
 * 1. 官方登录二维码：微信公众号「舞萌 DX」内生成的 SGWCMAID 串，用于拉取官方成绩；
 * 2. 水鱼成绩导入令牌：水鱼网页版「个人资料」页查看/设置，用于向水鱼上传。
 *
 * 隐私铁律：二维码与令牌只存在于本页临时状态里，导入完成（无论成败）立即清空，
 * 不写 SP / 不落盘 / 日志只出现二维码尾部 4 字符。
 */
@Composable
fun QrImportScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val account by DivingFishAccount.state.collectAsState()

    var qrText by remember { mutableStateOf("") }
    var importToken by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
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

    fun startImport() {
        if (importing) return
        importing = true
        scope.launch {
            val result = QrScoreImport.importFromQr(
                qrCodeRaw = qrText,
                importToken = importToken,
            ) { logs.add(it) }
            result.onSuccess {
                logs.add("导入完成：${it.recordCount} 条记录已上传水鱼（${it.serverMessage}）")
            }
            result.onFailure {
                logs.add("导入失败：${it.message}")
            }
            // 用完即毁：无论成败，二维码立即清空丢弃；令牌保留方便再次导入
            qrText = ""
            importing = false
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
                    .padding(padding)
                    .imePadding(),
                contentPadding = pageContentPadding(withNavBar = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "guide_title") { SectionTitle("使用前准备（两个凭据）") }
                item(key = "guide_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "⚠ 使用前请先到「我」页「接入 Easymai 账号」完成登录或注册" +
                                    "（第三方解析服务的自有账号，与水鱼账号无关，登录一次即可长期使用）。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "① 官方登录二维码（拉成绩用）",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "打开微信公众号「舞萌 DX」→ 底部菜单「用户中心」→「登录 / 验证」，" +
                                    "页面会弹出一张登录二维码。任选其一：\n" +
                                    "· 长按二维码 → 保存到相册（回到本页「从图片识别」）；\n" +
                                    "· 部分客户端可长按复制解析文本（SGWCMAID 开头），直接粘贴到下方输入框。\n" +
                                    "二维码有效期很短（约几分钟），过期请在公众号里重新生成。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "② 水鱼成绩导入令牌（上传用，可选）",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "导入令牌是水鱼查分器给第三方工具的「上传钥匙」，用于把成绩写回水鱼。\n" +
                                    "· 已在本应用「我」页 OAuth 授权登录水鱼的话，此栏可留空（自动用授权令牌上传）；\n" +
                                    "· 也可手动获取并粘贴：浏览器打开水鱼查分器 www.diving-fish.com/maimaidx/prober 登录 → " +
                                    "点右上角昵称进入「个人资料」→ 找到「导入令牌」一栏查看/复制，粘贴到下方输入框。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "使用流程：接入账号 → 粘贴二维码 → 点「开始导入」。导入会把二维码交给解析服务同步到账号云端，" +
                                    "再从云端拉取全量成绩上传水鱼，无需 VPN / 机台网络 / 微信。" +
                                    "之后也可在「接入 Easymai 账号」页手动同步，无需重新扫码。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                item(key = "qr_title") { SectionTitle("第一步：官方登录二维码") }
                item(key = "qr_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
                                    enabled = !importing,
                                )
                                TextButton(
                                    text = "从图片识别",
                                    onClick = {
                                        qrImagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !importing,
                                )
                            }
                        }
                    }
                }

                item(key = "token_title") { SectionTitle("第二步：水鱼导入令牌") }
                item(key = "token_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = importToken,
                                onValueChange = { importToken = it },
                                label = "导入令牌（OAuth 已授权可留空）",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "令牌等同上传权限，请勿透露给他人；本应用只在本次导入的内存中使用它，" +
                                    "不保存到本机（下次导入需重新粘贴）。已 OAuth 授权登录时此栏可留空。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                item(key = "action_card") {
                    Button(
                        onClick = { startImport() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing && qrText.isNotBlank() &&
                            (importToken.isNotBlank() || account.bound),
                    ) {
                        Text(
                            text = if (importing) "正在导入…" else "开始导入",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                item(key = "privacy_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "隐私说明",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "二维码内容仅本次内存使用，导入完成后（无论成败）立即丢弃，" +
                                    "不保存、不写入日志明文；你的图片文件不会被读取或修改。" +
                                    "二维码解析需经 Easymai 接口（与官方客户端行为一致），" +
                                    "成绩上传直达水鱼官方接口。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                if (logs.isNotEmpty()) {
                    item(key = "log_title") { SectionTitle("运行日志") }
                    item(key = "log") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
