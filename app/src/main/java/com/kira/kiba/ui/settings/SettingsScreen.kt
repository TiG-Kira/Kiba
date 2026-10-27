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
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.utils.DeviceGrade
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.restartMainActivity
import top.yukonga.miuix.kmp.basic.Button
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
            item(key = "tool_title") { SectionTitle("查分器工具") }
            item(key = "tool_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "机台成绩更新（VPN）",
                        summary = "在机台 WiFi 下抓包，把成绩同步到水鱼",
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
                Button(onClick = { onConfirm(value) }) {
                    Text(text = "保存")
                }
            }
        }
    }
}
