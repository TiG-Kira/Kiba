package com.kira.kiba.ui.agent

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.agent.KibaLlm
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent LLM 配置页（严格 miuix preference 规范 + miuix Card 分组包裹）。
 *
 * 配置任意 OpenAI 兼容 chat/completions 端点（官方 API / 各类中转 / 本地 ollama）。
 * 关闭时 Agent 只用本地 maimai 技能（点击技能卡触发）。
 */
@Composable
fun LlmConfigScreen(scrollBehavior: ScrollBehavior) {
    // SpUtil 是即时读写的单一数据源，这里用 remember + 手动刷新保持简单可靠
    var enabled by remember { mutableStateOf(SpUtil.isLlmEnabled()) }
    var baseUrl by remember { mutableStateOf(SpUtil.getLlmBaseUrl()) }
    var apiKey by remember { mutableStateOf(SpUtil.getLlmApiKey()) }
    var model by remember { mutableStateOf(SpUtil.getLlmModel()) }
    var temperature by remember { mutableStateOf(SpUtil.getLlmTemperature()) }
    var maxTokens by remember { mutableStateOf(SpUtil.getLlmMaxTokens()) }
    var systemPrompt by remember { mutableStateOf(SpUtil.getLlmSystemPrompt()) }

    var editing by remember { mutableStateOf<EditField?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
            item(key = "enable_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            SpUtil.setLlmEnabled(it)
                        },
                        title = "启用 LLM 对话",
                        summary = if (enabled) {
                            "已开启：聊天提问会交给下方配置的模型回答"
                        } else {
                            "已关闭：Agent 只保留技能卡（点击技能卡仍可离线使用）"
                        },
                    )
                }
            }

            item(key = "conn_title") { SectionTitle("连接") }

            item(key = "conn_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "API 地址",
                        summary = baseUrl.ifBlank { "未设置" },
                        onClick = { editing = EditField.BaseUrl },
                    )
                    ArrowPreference(
                        title = "API Key",
                        summary = if (apiKey.isBlank()) "未设置（本地模型可留空）" else "已保存（${apiKey.take(4)}…）",
                        onClick = { editing = EditField.ApiKey },
                    )
                    ArrowPreference(
                        title = "模型",
                        summary = model.ifBlank { "未设置" },
                        onClick = { editing = EditField.Model },
                    )
                    ArrowPreference(
                        title = if (testing) "正在测试…" else "测试连接",
                        summary = testResult ?: "发送一条最小请求验证配置是否可用",
                        onClick = {
                            if (!testing) {
                                testing = true
                                testResult = null
                                scope.launch {
                                    testResult = KibaLlm.testConnection()
                                    testing = false
                                }
                            }
                        },
                    )
                }
            }

            item(key = "gen_title") { SectionTitle("生成参数") }

            item(key = "gen_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SliderPreference(
                        value = temperature,
                        onValueChange = {
                            temperature = it
                            SpUtil.setLlmTemperature(it)
                        },
                        onValueChangeFinished = { SpUtil.setLlmTemperature(temperature) },
                        title = "温度",
                        valueText = "%.1f".format(temperature),
                        valueRange = 0f..2f,
                        steps = 19,
                        showKeyPoints = true,
                        keyPoints = listOf(0f, 0.7f, 1f, 2f),
                    )
                    ArrowPreference(
                        title = "最大输出 tokens",
                        summary = "$maxTokens",
                        onClick = { editing = EditField.MaxTokens },
                    )
                    ArrowPreference(
                        title = "系统提示词",
                        summary = systemPrompt.take(60).ifBlank { "未设置" },
                        onClick = { editing = EditField.SystemPrompt },
                    )
                    ArrowPreference(
                        title = "恢复默认",
                        summary = "地址 / 模型 / 参数 / 提示词回默认值（不清 Key）",
                        onClick = {
                            baseUrl = SpUtil.LLM_DEFAULT_BASE_URL
                            model = SpUtil.LLM_DEFAULT_MODEL
                            temperature = SpUtil.LLM_DEFAULT_TEMPERATURE
                            maxTokens = SpUtil.LLM_DEFAULT_MAX_TOKENS
                            systemPrompt = SpUtil.LLM_DEFAULT_SYSTEM_PROMPT
                            SpUtil.setLlmBaseUrl(baseUrl)
                            SpUtil.setLlmModel(model)
                            SpUtil.setLlmTemperature(temperature)
                            SpUtil.setLlmMaxTokens(maxTokens)
                            SpUtil.setLlmSystemPrompt(systemPrompt)
                            testResult = "已恢复默认"
                        },
                    )
                }
            }
        }
    }

    // ── 字段编辑弹层（副标题说明这个设置是做什么的）──
    editing?.let { field ->
        when (field) {
            EditField.BaseUrl -> TextEditDialog(
                title = "API 地址",
                summary = "填写 OpenAI 兼容接口的基础地址（Kiba 会自动补 /chat/completions）。" +
                    "官方填 https://api.openai.com/v1，中转填其根地址，本地 ollama 填 http://127.0.0.1:11434/v1。",
                placeholder = "https://api.openai.com/v1",
                initial = baseUrl,
                singleLine = true,
                keyboardType = KeyboardType.Uri,
                onDismiss = { editing = null },
                onConfirm = {
                    baseUrl = it
                    SpUtil.setLlmBaseUrl(it)
                    editing = null
                },
            )

            EditField.ApiKey -> TextEditDialog(
                title = "API Key",
                summary = "接口的鉴权密钥，会以 Bearer 头发送。" +
                    "官方 / 中转服务需要填；本地模型（ollama 等）可留空。仅保存在本机。",
                placeholder = "sk-…（本地模型可留空）",
                initial = apiKey,
                singleLine = true,
                password = true,
                onDismiss = { editing = null },
                onConfirm = {
                    apiKey = it
                    SpUtil.setLlmApiKey(it)
                    editing = null
                },
            )

            EditField.Model -> TextEditDialog(
                title = "模型",
                summary = "指定模型名，必须是接口实际提供的模型 id。" +
                    "例：gpt-4o-mini、gpt-4o、deepseek-chat、qwen-plus。",
                placeholder = "gpt-4o-mini",
                initial = model,
                singleLine = true,
                onDismiss = { editing = null },
                onConfirm = {
                    model = it
                    SpUtil.setLlmModel(it)
                    editing = null
                },
            )

            EditField.MaxTokens -> TextEditDialog(
                title = "最大输出 tokens",
                summary = "单次回答允许生成的最大 token 数（64–8192）。" +
                    "数值越大越啰嗦也越慢，聊天用 1024–2048 足够。",
                placeholder = "1024",
                initial = maxTokens.toString(),
                singleLine = true,
                keyboardType = KeyboardType.Number,
                onDismiss = { editing = null },
                onConfirm = { text ->
                    maxTokens = text.toIntOrNull()?.coerceIn(64, 8192) ?: maxTokens
                    SpUtil.setLlmMaxTokens(maxTokens)
                    editing = null
                },
            )

            EditField.SystemPrompt -> TextEditDialog(
                title = "系统提示词",
                summary = "设定 Agent 的身份与回答风格，是每次对话的第一条指令。" +
                    "留空则使用内置提示词（舞萌助手 + 调用本地技能取真实数据）。",
                placeholder = SpUtil.LLM_DEFAULT_SYSTEM_PROMPT,
                initial = systemPrompt,
                singleLine = false,
                onDismiss = { editing = null },
                onConfirm = {
                    systemPrompt = it.ifBlank { SpUtil.LLM_DEFAULT_SYSTEM_PROMPT }
                    SpUtil.setLlmSystemPrompt(systemPrompt)
                    editing = null
                },
            )
        }
    }
}

private enum class EditField { BaseUrl, ApiKey, Model, MaxTokens, SystemPrompt }

/** 通用文本编辑弹层（miuix OverlayDialog + TextField）。供配置页与 Agent 页共用。 */
@Composable
internal fun TextEditDialog(
    title: String,
    summary: String,
    initial: String,
    placeholder: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    var value by remember { mutableStateOf(initial) }
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = title,
                useLabelAsPlaceholder = true,
                singleLine = singleLine,
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(value) },
                ) {
                    Text(
                        text = "保存",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
