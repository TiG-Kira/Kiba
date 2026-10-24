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
 * Agent LLM 配置页（严格 miuix preference 规范）。
 *
 * 配置任意 OpenAI 兼容 chat/completions 端点（官方 API / 各类中转 / 本地 ollama）。
 * 关闭时 Agent 只用本地 maimai 逻辑（Best 50 / 推分 / 查歌）与网页搜索兜底。
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
            item(key = "enable") {
                SwitchPreference(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        SpUtil.setLlmEnabled(it)
                    },
                    title = "启用 LLM 对话",
                    summary = if (enabled) "Agent 将优先调用下方配置的模型回答" else "Agent 仅使用本地逻辑与网页搜索",
                )
            }

            item(key = "conn_title") { SectionTitle("连接") }

            item(key = "base_url") {
                ArrowPreference(
                    title = "API 地址",
                    summary = baseUrl.ifBlank { "未设置" },
                    onClick = { editing = EditField.BaseUrl },
                )
            }
            item(key = "api_key") {
                ArrowPreference(
                    title = "API Key",
                    summary = if (apiKey.isBlank()) "未设置（本地模型可留空）" else "已保存（${apiKey.take(4)}…）",
                    onClick = { editing = EditField.ApiKey },
                )
            }
            item(key = "model") {
                ArrowPreference(
                    title = "模型",
                    summary = model.ifBlank { "未设置" },
                    onClick = { editing = EditField.Model },
                )
            }
            item(key = "test") {
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

            item(key = "gen_title") { SectionTitle("生成参数") }

            item(key = "temperature") {
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
            }
            item(key = "max_tokens") {
                ArrowPreference(
                    title = "最大输出 tokens",
                    summary = "$maxTokens",
                    onClick = { editing = EditField.MaxTokens },
                )
            }
            item(key = "system_prompt") {
                ArrowPreference(
                    title = "系统提示词",
                    summary = systemPrompt.take(60).ifBlank { "未设置" },
                    onClick = { editing = EditField.SystemPrompt },
                )
            }
            item(key = "reset") {
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

    // ── 字段编辑弹层 ──
    editing?.let { field ->
        when (field) {
            EditField.BaseUrl -> TextEditDialog(
                title = "API 地址",
                initial = baseUrl,
                placeholder = "https://api.openai.com/v1",
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
                initial = apiKey,
                placeholder = "sk-…（本地模型可留空）",
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
                initial = model,
                placeholder = "gpt-4o-mini",
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
                initial = maxTokens.toString(),
                placeholder = "1024",
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
                initial = systemPrompt,
                placeholder = SpUtil.LLM_DEFAULT_SYSTEM_PROMPT,
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

/** 通用文本编辑弹层（miuix OverlayDialog + TextField）。 */
@Composable
private fun TextEditDialog(
    title: String,
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
        summary = placeholder,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = title,
                useLabelAsPlaceholder = true,
                singleLine = singleLine,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
