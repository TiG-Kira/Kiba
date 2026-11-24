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
import androidx.compose.runtime.mutableIntStateOf
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
import com.kira.kiba.core.account.CustomAvatar
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.data.ChuniSongSync
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.utils.CacheCleaner
import com.kira.kiba.core.utils.DeviceGrade
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.restartMainActivity
import com.kira.kiba.ui.material.MaterialFamily
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
private fun PrefIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: com.kira.kiba.ui.common.KibaIconTint,
) {
    com.kira.kiba.ui.common.ColoredPrefIcon(icon = icon, tint = tint)
}

/**
 * 设置页（严格 miuix preference 规范：ArrowPreference / SwitchPreference / OverlaySpinnerPreference）。
 */
@Composable
fun SettingsScreen(
    scrollBehavior: ScrollBehavior,
    onOpenLlmConfig: () -> Unit,
    onOpenAgentMemory: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDeviceScore: () -> Unit,
    onOpenAvatarCrop: () -> Unit,
) {
    val context = LocalContext.current
    val themeMode by KibaState.themeMode.collectAsState()
    val dynamicColor by KibaState.dynamicColor.collectAsState()
    val syncState by SongDataSync.state.collectAsState()
    val updateAvailable by SongDataSync.updateAvailable.collectAsState()
    // 中二侧同步状态与版本号：设置页的「曲库更新」现在是**两边一起更**，摘要要能同时反映两边的状态
    val chuniSyncState by ChuniSongSync.state.collectAsState()
    var llmOn by remember { mutableStateOf(SpUtil.isLlmEnabled()) }
    var coverSource by remember { mutableStateOf(SpUtil.getCoverSource()) }
    var showCoverSource by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // ── 材质与特效 ──
    // 评分在启动时评测并落盘，这里只做展示与手动重评
    var advancedOn by remember { mutableStateOf(SpUtil.isAdvancedMaterialEnabled()) }
    var materialFamily by remember { mutableStateOf(SpUtil.getMaterialFamily()) }
    // 评分本身在独立页面（DeviceScoreScreen）里重新评测，这里只需要「是否达标」这一个布尔：
    // 页面返回时本组合会重建，remember 重新读一次 SpUtil，分数变化自动反映到开关可用性上。
    val passes = remember { SpUtil.getDeviceScore() >= DeviceGrade.PASS_SCORE }

    // ── 图片缓存手动清理（Coil 缓存 + Agent 歌单未使用的历史封面）──
    var coverCacheMsg by remember { mutableStateOf<String?>(null) }
    var clearingCovers by remember { mutableStateOf(false) }
    var showCoverClearConfirm by remember { mutableStateOf(false) }

    // ── 查分器数据源（舞萌 / 中二各自独立） ──
    var maimaiSource by remember { mutableStateOf(SpUtil.getScoreSource(GameType.MAIMAI)) }
    var chuniSource by remember { mutableStateOf(SpUtil.getScoreSource(GameType.CHUNITHM)) }
    val lxnsState by LxnsAccount.state.collectAsState()

    // 自定义头像状态：保存 / 移除后摘要要跟着变（StateFlow，无需重进页面）
    val customAvatar by CustomAvatar.state.collectAsState()

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
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Theme, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
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
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                    )
                    // 账户页右上角那张方形大图：自定义优先，没设就回落到自动抓取 / 默认头像。
                    ArrowPreference(
                        title = "自定义头像",
                        summary = if (customAvatar.hasImage) {
                            "已设置 · 账户页右上角显示自定义图（点击更换或恢复默认）"
                        } else {
                            "当前为默认头像 · 点击上传并裁切（1:1）"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Image, com.kira.kiba.ui.common.KibaIconTint.CYAN) },
                        onClick = onOpenAvatarCrop,
                    )
                }
            }

            // ── 材质与特效 ──
            // 组名不再叫「真·高级材质」：这一组里除了那个开关，还包含设备评分与材质风格，
            // 用总开关的名字当组名会让「评分」「风格」看起来像是它的子项。
            item(key = "material_title") { SectionTitle("材质与特效") }
            item(key = "material_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    // 摘要只留一句引导：分数 / 档位 / 六项明细 / 重新评分都在独立页面里，
                    // 挤在 preference 副标题的三行小字里既看不清也不好点。
                    ArrowPreference(
                        title = "设备性能评分",
                        summary = "点击了解设备性能",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.MemoryFunction, com.kira.kiba.ui.common.KibaIconTint.ORANGE) },
                        onClick = onOpenDeviceScore,
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
                            advancedOn ->
                                "已开启 · ${MaterialFamily.of(materialFamily).label}（弹层为玻璃材质）"
                            else -> "已关闭 · 沿用标准 miuix 质感"
                        },
                        enabled = passes,
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Layers, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                    )
                    // 风格选择：两档实现差异很大（液态是胶囊 + 透镜着色器，柔光是取景柔化），
                    // 都涉及全局 CompositionLocal，改完同样走 Activity 重启落地。
                    val families = MaterialFamily.entries
                    OverlaySpinnerPreference(
                        items = families.map { DropdownItem(text = it.label) },
                        selectedIndex = families.indexOf(MaterialFamily.of(materialFamily))
                            .coerceAtLeast(0),
                        title = "材质风格",
                        summary = MaterialFamily.of(materialFamily).summary,
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Filter, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        enabled = passes && advancedOn,
                        onSelectedIndexChange = { index ->
                            val picked = families.getOrNull(index) ?: return@OverlaySpinnerPreference
                            if (picked.name == materialFamily) return@OverlaySpinnerPreference
                            materialFamily = picked.name
                            SpUtil.setMaterialFamily(picked.name)
                            context.restartMainActivity()
                        },
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
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Messages, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                    )
                    ArrowPreference(
                        title = "Agent 模型配置",
                        summary = if (llmOn) {
                            "${SpUtil.getLlmBaseUrl()} · ${SpUtil.getLlmModel()}"
                        } else {
                            "配置任意 OpenAI 兼容端点（官方 / 中转 / 本地 ollama）"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.AiScan, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        onClick = onOpenLlmConfig,
                    )
                    // 摘要不能只 remember 一次：进编辑页改完回来，条数要跟着刷新。
                    // key 用条目数没有响应性，干脆每次重组都读 SpUtil（很便宜）。
                    ArrowPreference(
                        title = "长期记忆",
                        summary = run {
                            val n = SpUtil.getAgentMemoryCount()
                            when {
                                !SpUtil.isAgentMemoryEnabled() ->
                                    if (n > 0) "已关闭 · 保留 $n 条（点击编辑）" else "已关闭（点击开启或编辑）"
                                n > 0 -> "已记住 $n 条 · 说「记住…」时 Agent 会自动保存（点击编辑）"
                                else -> "跨会话记住你的偏好与要求 · 点击添加"
                            }
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Notes, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        onClick = onOpenAgentMemory,
                    )
                    // 「畅听」页 Agent 歌单的自动更新间隔（进畅听时比对上次生成时间）
                    val intervalOptions = listOf(6, 12, 24, 48, 72)
                    var agentInterval by remember {
                        mutableIntStateOf(SpUtil.getAgentPlaylistIntervalHours())
                    }
                    OverlaySpinnerPreference(
                        items = intervalOptions.map { DropdownItem(text = "$it 小时") },
                        selectedIndex = intervalOptions.indexOf(agentInterval).coerceAtLeast(0),
                        title = "Agent 歌单更新间隔",
                        // 实际行为：进「畅听」时比对上次生成时间，过期就**追加一张新歌单**，
                        // 旧歌单原样留在历史里（3.0 起是历史存档，不再覆盖）。
                        // 没配 LLM 时不会触发，所以一并写进摘要，免得用户以为设置失效。
                        summary = "进「畅听」时若距上次生成超过 $agentInterval 小时，" +
                            "自动追加一张新歌单（旧的保留）；需先启用模型配置",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Refresh, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        onSelectedIndexChange = { idx ->
                            val hours = intervalOptions.getOrElse(idx) { 24 }
                            agentInterval = hours
                            SpUtil.setAgentPlaylistIntervalHours(hours)
                        },
                    )
                }
            }

            // ── 查分器工具 ──
            // ── 查分器数据源 ──
            // 两个游戏各选一个源，互不影响（例如舞萌用水鱼、中二用落雪）。
            item(key = "source_title") { SectionTitle("查分器数据源") }
            item(key = "source_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    // 本地扩展宿主：常规源之后可能追加扩展源选项（无扩展注册时为空，行为不变）
                    val extSources = com.kira.kiba.core.host.AppExtensions.extraScoreSources(GameType.MAIMAI)
                    val extMaimai = com.kira.kiba.core.host.AppExtensions.activeExtraSource(GameType.MAIMAI)
                    OverlaySpinnerPreference(
                        items = (ScoreSource.entries.map { DropdownItem(text = it.label) } +
                            extSources.map { DropdownItem(text = it.label) }),
                        selectedIndex = if (extMaimai != null) ScoreSource.entries.size + extSources.indexOfFirst { it.key == extMaimai.key } else maimaiSource.ordinal,
                        title = "舞萌成绩源",
                        summary = if (extMaimai != null) {
                            extMaimai.summary
                        } else {
                            scoreSourceSummary(maimaiSource, lxnsState.bound, GameType.MAIMAI)
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill, com.kira.kiba.ui.common.KibaIconTint.BLUE) },
                        onSelectedIndexChange = { index ->
                            val extIndex = index - ScoreSource.entries.size
                            if (extIndex >= 0 && extIndex < extSources.size) {
                                maimaiSource = ScoreSource.WATER_FISH
                                com.kira.kiba.core.host.AppExtensions.onExtraSourcePicked(GameType.MAIMAI, extIndex)
                            } else {
                                ScoreSource.entries.getOrNull(index)?.let { picked ->
                                    maimaiSource = picked
                                    SpUtil.setScoreSource(GameType.MAIMAI, picked)
                                    com.kira.kiba.core.host.AppExtensions.onRegularSourcePicked(GameType.MAIMAI)
                                    // 切源后立刻按新源刷新一次；用应用级作用域触发，
                                    // 避免离开设置页（UI 协程取消）把刷新掐死在半路、Rating 卡卡在「正在获取成绩…」
                                    DivingFishAccount.refreshRecordsAsync()
                                }
                            }
                        },
                    )
                    val extSourcesChuni = com.kira.kiba.core.host.AppExtensions.extraScoreSources(GameType.CHUNITHM)
                    val extChuni = com.kira.kiba.core.host.AppExtensions.activeExtraSource(GameType.CHUNITHM)
                    OverlaySpinnerPreference(
                        items = (ScoreSource.entries.map { DropdownItem(text = it.label) } +
                            extSourcesChuni.map { DropdownItem(text = it.label) }),
                        selectedIndex = if (extChuni != null) ScoreSource.entries.size + extSourcesChuni.indexOfFirst { it.key == extChuni.key } else chuniSource.ordinal,
                        title = "中二成绩源",
                        summary = if (extChuni != null) {
                            extChuni.summary
                        } else {
                            scoreSourceSummary(chuniSource, lxnsState.bound, GameType.CHUNITHM)
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill, com.kira.kiba.ui.common.KibaIconTint.BLUE) },
                        onSelectedIndexChange = { index ->
                            val extIndex = index - ScoreSource.entries.size
                            if (extIndex >= 0 && extIndex < extSourcesChuni.size) {
                                chuniSource = ScoreSource.WATER_FISH
                                com.kira.kiba.core.host.AppExtensions.onExtraSourcePicked(GameType.CHUNITHM, extIndex)
                            } else {
                                ScoreSource.entries.getOrNull(index)?.let { picked ->
                                    chuniSource = picked
                                    SpUtil.setScoreSource(GameType.CHUNITHM, picked)
                                    com.kira.kiba.core.host.AppExtensions.onRegularSourcePicked(GameType.CHUNITHM)
                                    ChuniRecordSync.refreshAsync()
                                }
                            }
                        },
                    )
                    // 落雪账户登录入口已迁至「我」页查分器组（与水鱼账户登录并排），此处不再重复。
                }
            }

            // ── 查分器工具已整组迁至「我」页查分器组（2.3.0）──
            // 设置页只保留「数据源」这种纯配置项：登录态 + 机台更新 / 二维码导入放在同一处，
            // 用户不必在两页之间来回找「上传成绩该从哪进」。

            // ── 数据 ──
            item(key = "data_title") { SectionTitle("数据") }
            item(key = "data_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                ArrowPreference(
                    title = "自定义封面源",
                    summary = coverSource.ifBlank { "使用内置候选链（水鱼 CDN + 官网镜像）" }
                        .let { if (coverSource.isBlank()) it else coverSource },
                    startAction = { PrefIcon(MiuixGlassIcons.Regular.Photos, com.kira.kiba.ui.common.KibaIconTint.CYAN) },
                    onClick = { showCoverSource = true },
                )
                // 版本号在同步结束（SyncState 变化）后重新读取：SpUtil 里的版本不是 snapshot state，
                // 只在 remember 里读一次的话，更新完摘要会停在旧版本号直到离开页面。
                val maimaiVersion = remember(syncState) {
                    SpUtil.getDataVersion().takeIf { it.isNotBlank() && it != "0" }
                }
                val chuniVersion = remember(chuniSyncState) {
                    ChuniSongSync.getLocalVersion().takeIf { it.isNotBlank() }
                }
                val maimaiBusy = syncState is SongDataSync.SyncState.Checking ||
                    syncState is SongDataSync.SyncState.Downloading
                val chuniBusy = chuniSyncState is ChuniSongSync.SyncState.Syncing
                ArrowPreference(
                    title = "曲库更新",
                    summary = buildString {
                        append("舞萌 ").append(maimaiVersion ?: "未同步")
                        append(" · 中二 ").append(chuniVersion ?: "未同步")
                        append("\n")
                        append(
                            when {
                                maimaiBusy -> when (val s = syncState) {
                                    is SongDataSync.SyncState.Downloading ->
                                        "正在下载舞萌曲库 ${s.received / 1024} / ${s.total / 1024} KB"
                                    else -> "正在检查舞萌曲库更新…"
                                }
                                chuniBusy -> "正在同步中二节奏曲库…"
                                syncState is SongDataSync.SyncState.Failed ->
                                    "舞萌更新失败：${(syncState as SongDataSync.SyncState.Failed).message}（点击重试）"
                                chuniSyncState is ChuniSongSync.SyncState.Failed ->
                                    "中二更新失败：${(chuniSyncState as ChuniSongSync.SyncState.Failed).message}（点击重试）"
                                updateAvailable != null -> "发现舞萌新版本 $updateAvailable，点击同时更新两个游戏"
                                else -> "点击同时更新舞萌与中二节奏的曲库"
                            }
                        )
                    },
                    startAction = { PrefIcon(MiuixGlassIcons.Regular.Refresh, com.kira.kiba.ui.common.KibaIconTint.GREEN) },
                    onClick = {
                        if (!maimaiBusy && !chuniBusy) {
                            // 两边一起更：舞萌沿用「有新版本才下载」，中二曲库小、直接全量刷新。
                            // 设置页不点亮曲目页的进度卡（showProgress = false），进度看本行摘要。
                            KibaState.refreshAllSongData(context, showProgress = false)
                        }
                    },
                )
                ArrowPreference(
                    title = "清理图片缓存",
                    summary = when {
                        clearingCovers -> "正在清理…"
                        coverCacheMsg != null -> coverCacheMsg
                        else -> "清空歌曲封面与分享图缓存，并删除 Agent 歌单已不再引用的历史封面；清理后离线封面需重新联网加载"
                    },
                    startAction = { PrefIcon(MiuixGlassIcons.Clear, com.kira.kiba.ui.common.KibaIconTint.RED) },
                    onClick = {
                        if (!clearingCovers) {
                            coverCacheMsg = null
                            showCoverClearConfirm = true
                        }
                    },
                    enabled = !clearingCovers,
                )
                }
            }

            // ── 插件 ──
            // 插件由独立模块提供（安装后经 ContentProvider 自动注册）：
            // 开启开关时插件会立即执行自身的验证逻辑，验证不通过插件有权拒绝启用。
            item(key = "plugin_title") { SectionTitle("插件") }
            item(key = "plugin_card") {
                val plugin = com.kira.kiba.core.host.AppExtensions.pluginInfo()
                Card(modifier = (Modifier.fillMaxWidth())) {
                    if (plugin == null) {
                        ArrowPreference(
                            title = "插件管理",
                            summary = "暂无已安装的插件 · 插件由独立模块提供，安装后在此启用与配置",
                            enabled = false,
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        )
                    } else {
                        // enabledTick：启用/关闭走插件回调（无 StateFlow），用计数触发重组重读状态
                        var enabledTick by remember { mutableIntStateOf(0) }
                        val enabled = remember(enabledTick) {
                            com.kira.kiba.core.host.AppExtensions.pluginEnabled()
                        }
                        SwitchPreference(
                            checked = enabled,
                            onCheckedChange = { want ->
                                if (want) {
                                    com.kira.kiba.core.host.AppExtensions.onPluginEnableRequested(context)
                                } else {
                                    com.kira.kiba.core.host.AppExtensions.onPluginDisabled(context)
                                }
                                enabledTick++
                            },
                            title = plugin.name,
                            summary = "v${plugin.version} · " + if (enabled) "已启用" else "未启用（开启将进行激活验证）",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune, com.kira.kiba.ui.common.KibaIconTint.PURPLE) },
                        )
                    }
                }
            }

            // ── 关于 ──
            item(key = "about_title") { SectionTitle("关于") }
            item(key = "about_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "关于 Kiba",
                        summary = "版本 ${BuildConfig.VERSION_NAME}",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Info, com.kira.kiba.ui.common.KibaIconTint.CYAN) },
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }

    if (showCoverClearConfirm) {
        KibaGlassDialog(
            show = true,
            title = "清理图片缓存",
            summary = "将清空已缓存的歌曲封面与分享图（内存 + 磁盘），" +
                "并删除 Agent 歌单里已不再被引用的历史封面（正在使用的歌单封面不受影响）。" +
                "自动清理永远不会清除图片缓存；手动清理后，离线时封面需重新联网加载。",
            onDismissRequest = { showCoverClearConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { showCoverClearConfirm = false })
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        showCoverClearConfirm = false
                        clearingCovers = true
                        scope.launch(Dispatchers.IO) {
                            val freed = CacheCleaner.clearImageCache(context)
                            withContext(Dispatchers.Main) {
                                clearingCovers = false
                                coverCacheMsg = if (freed > 0) {
                                    if (freed >= 1 shl 20) "已清理 ${freed shr 20} MB（离线封面需重新联网加载）"
                                    else "已清理 ${(freed shr 10).coerceAtLeast(1)} KB（离线封面需重新联网加载）"
                                } else {
                                    "缓存为空，无需清理"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "清理")
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

/**
 * 数据源说明文案（设置页下拉副标题）。
 *
 * **必须按游戏区分**：水鱼「只回 Best 30、当前版本 B20 会缺」是**中二专有**的限制，
 * 舞萌侧水鱼给的是完整成绩、而且是机台更新最成熟的通道。1.8.3 把这段文案共用给了
 * 两个下拉，导致舞萌用户看到莫名其妙的中二问题描述。
 */
private fun scoreSourceSummary(
    source: ScoreSource,
    lxnsBound: Boolean,
    game: GameType,
): String = when (source) {
    ScoreSource.WATER_FISH -> if (game == GameType.CHUNITHM) {
        "水鱼 · 中二只有账号密码通道，且只回 Best 30（当前版本 B20 会缺）"
    } else {
        "水鱼 · 完整成绩，机台更新链路最成熟（推荐）"
    }
    ScoreSource.LXNS -> when {
        !lxnsBound -> "落雪 · 尚未登录落雪账号，请到「账户」页的「落雪账户登录」"
        game == GameType.CHUNITHM -> "落雪 · 全量成绩 + 官方当前版本 B20"
        else -> "落雪 · 全量成绩（需先在落雪同步过一次，账号才会绑定玩家）"
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
                Button(onClick = { onConfirm(value) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(text = "保存")
                }
            }
        }
    }
}
