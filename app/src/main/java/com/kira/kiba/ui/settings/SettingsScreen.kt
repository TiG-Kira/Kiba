package com.kira.kiba.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.BuildConfig
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页：全部走 miuix Card / BasicComponent 规范。 */
@Composable
fun SettingsScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val themeMode by KibaState.themeMode.collectAsState()
    val dynamicColor by KibaState.dynamicColor.collectAsState()
    val account by DivingFishAccount.state.collectAsState()
    val syncState by SongDataSync.state.collectAsState()
    val updateAvailable by SongDataSync.updateAvailable.collectAsState()

    var showThemePicker by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

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
            item(key = "appearance") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "主题",
                        summary = when (themeMode) {
                            KibaState.ThemeMode.System -> "跟随系统"
                            KibaState.ThemeMode.Light -> "浅色"
                            KibaState.ThemeMode.Dark -> "深色"
                        },
                        onClick = { showThemePicker = true },
                    )
                    BasicComponent(
                        title = "动态取色",
                        summary = if (dynamicColor) "已开启（跟随壁纸取色）" else "已关闭",
                        onClick = { KibaState.setDynamicColor(!dynamicColor) },
                    )
                }
            }

            // ── 数据 ──
            item(key = "data_title") { SectionTitle("数据") }
            item(key = "data") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
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

            // ── 查分器 ──
            item(key = "account_title") { SectionTitle("查分器") }
            item(key = "account") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "账户绑定",
                        summary = if (account.bound) {
                            "已绑定：${account.nickname.ifBlank { "查分器用户" }}（Rating ${account.rating}）"
                        } else {
                            "未绑定 — 请在「我」页面通过水鱼 OAuth 完成授权"
                        },
                        onClick = null,
                    )
                }
            }

            // ── 关于 ──
            item(key = "about_title") { SectionTitle("关于") }
            item(key = "about") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "版本",
                        summary = "Kiba ${BuildConfig.VERSION_NAME}（基于 MaimaiData 二次开发）",
                        onClick = { showAbout = true },
                    )
                }
            }
        }
    }

    if (showThemePicker) {
        ThemePickerDialog(
            current = themeMode,
            onDismiss = { showThemePicker = false },
            onSelect = {
                KibaState.setThemeMode(it)
                showThemePicker = false
            },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun ThemePickerDialog(
    current: KibaState.ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (KibaState.ThemeMode) -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "主题",
        summary = "选择应用配色模式",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            KibaState.ThemeMode.values().forEach { mode ->
                val label = when (mode) {
                    KibaState.ThemeMode.System -> "跟随系统"
                    KibaState.ThemeMode.Light -> "浅色"
                    KibaState.ThemeMode.Dark -> "深色"
                }
                TextButton(
                    text = if (mode == current) "✓ $label" else label,
                    onClick = { onSelect(mode) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lastUpdate = remember {
        val ts = SpUtil.getLastUpdateChartStats()
        if (ts <= 0L) "从未更新" else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }
    OverlayDialog(
        show = true,
        title = "关于 Kiba",
        summary = "版本 ${BuildConfig.VERSION_NAME}",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Kiba 是基于开源项目 MaimaiData 的二次开发，UI 采用 Termux Ultra 视觉体系（miuix + miuix-glass）。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "曲库上次更新：$lastUpdate",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开源组件：MaimaiData / miuix / miuix-glass / Coil / OkHttp / Room",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
