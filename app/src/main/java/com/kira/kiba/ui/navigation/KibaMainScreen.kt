package com.kira.kiba.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.ui.agent.AgentScreen
import com.kira.kiba.ui.agent.LlmConfigScreen
import com.kira.kiba.ui.me.Best50Screen
import com.kira.kiba.ui.me.LevelProgressScreen
import com.kira.kiba.ui.me.LoginScreen
import com.kira.kiba.ui.me.MeScreen
import com.kira.kiba.ui.me.RecordsScreen
import com.kira.kiba.ui.me.VersionProgressScreen
import com.kira.kiba.ui.settings.AboutScreen
import com.kira.kiba.ui.settings.SettingsScreen
import com.kira.kiba.ui.songdetail.ChartDetailScreen
import com.kira.kiba.ui.songdetail.SongDetailScreen
import com.kira.kiba.ui.songs.SongsScreen
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*

/**
 * Kiba 主框架：Termux Ultra 同款玻璃导航体系。
 *
 * - 顶栏：原生 miuix-glass [GlassTopAppBar]（大标题 + 滚动塌缩），采样 [glassNavBackdrop]；
 * - 底栏：**Termux Ultra 默认悬浮底栏** [GlassNavigationBar]（玻璃胶囊，非液态玻璃那套）；
 * - 取景层：单一 miuix-blur layerBackdrop，顶栏与底栏共用，内容区注册到同一层。
 *
 * 二级页面（歌曲详情 / Best 50 / 成绩记录 / 版本进度 / 等级进度 / 模型配置 / 关于）
 * 以 [KibaSubPage] 全屏覆盖当前 Tab：顶栏切「返回 + 标题」，底栏隐藏，
 * 系统返回与顶栏返回都先退子页面（BackHandler + KibaBackAction）。
 *
 * 关键坑位规避（与 Termux Ultra 一致）：
 * - `contentWindowInsets = WindowInsets(0, 0, 0, 0)`：内容满高铺开，不被顶/底栏推开；
 * - 页面顶部留白不硬编码，取 Scaffold 内容 lambda 的 `padding.calculateTopPadding()`
 *   （顶栏塌缩时其测量高度同步收缩，常量会留下一条不透带）；
 * - 内容 Box 挂 `.layerBackdrop(glassNavBackdrop)` 供顶栏/底栏采样。
 */

/** 顶栏玻璃材质共享 MainScreen 的取景层，各页无需各自持有。 */
val LocalGlassTopAppBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 顶栏占位高度：页面把首个 item 压到此值之下，避免顶栏遮挡。
 * 由 MainScreen 从 Scaffold padding 现算后注入，随顶栏塌缩同步。
 */
val LocalTopBarClearance = staticCompositionLocalOf<Dp> { 0.dp }

/** 悬浮底栏占位高度（54dp 栏高 + 系统导航栏 inset），页面底部需至少留出此值。 */
val LocalNavBarBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

/** 仅系统导航栏 inset 高度：二级页面（无悬浮底栏）底部留白用。 */
val LocalSystemNavBarBottom = staticCompositionLocalOf<Dp> { 0.dp }

enum class KibaTab(val title: String) {
    Songs("曲目"),
    Agent("Agent"),
    Me("我"),
    Settings("设置")
}

/** 二级页面（覆盖当前 Tab 的全屏子页）。 */
sealed interface KibaSubPage {
    val title: String

    /** 歌曲详情（歌曲库点击进入）。 */
    data class SongDetail(val songId: Int) : KibaSubPage {
        override val title: String = "歌曲详情"
    }

    /** 谱面详情（歌曲详情页点击难度卡进入）。 */
    data class ChartDetail(val songId: Int, val difficultyOrdinal: Int) : KibaSubPage {
        override val title: String = "谱面详情"
    }

    /** Best 50（官方口径 sd 35 + dx 15）。 */
    data object Best50 : KibaSubPage {
        override val title: String = "Best 50"
    }

    /** 成绩记录（查分器全量成绩 + 手动刷新）。 */
    data object Records : KibaSubPage {
        override val title: String = "成绩记录"
    }

    /** 版本进度（各版本歌曲收集 / 游玩情况）。 */
    data object VersionProgress : KibaSubPage {
        override val title: String = "版本进度"
    }

    /** 等级进度（各难度等级谱面游玩情况）。 */
    data object LevelProgress : KibaSubPage {
        override val title: String = "等级进度"
    }

    /** Agent 模型配置（LLM）。 */
    data object LlmConfig : KibaSubPage {
        override val title: String = "模型配置"
    }

    /** 查分器登录（账号密码 / OAuth 双通道；独立子页避免弹层与软键盘冲突）。 */
    data object Login : KibaSubPage {
        override val title: String = "登录查分器"
    }

    /** 关于。 */
    data object About : KibaSubPage {
        override val title: String = "关于"
    }
}

private fun tabIcon(tab: KibaTab) = when (tab) {
    KibaTab.Songs -> MiuixGlassIcons.Regular.Music
    KibaTab.Agent -> MiuixGlassIcons.Regular.AiScan
    KibaTab.Me -> MiuixGlassIcons.Regular.Contacts
    KibaTab.Settings -> MiuixGlassIcons.Regular.Settings
}

/** Termux Ultra 浮动玻璃底栏的固定栏高（GlassNavigationBarDefaults.Height）。 */
private val GLASS_NAV_HEIGHT = 54.dp

@Composable
fun KibaMainScreen() {
    val tabs = KibaTab.values()
    var selectedTab by remember { mutableStateOf(KibaTab.Songs) }

    // 二级页面：非空时覆盖当前 Tab（顶栏带返回、底栏隐藏）
    var subPage by remember { mutableStateOf<KibaSubPage?>(null) }

    // 顶栏搜索按钮：每次点击 +1，曲目页据此聚焦搜索框
    var songsSearchTrigger by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // 隐藏手势提示线后 navigationBars inset 可能为 0，保底 12dp 贴屏幕底边
    val systemNavBarsHeight = with(density) {
        maxOf(WindowInsets.navigationBars.getBottom(density), 12.dp.roundToPx()).toDp()
    }
    val navBarBottomPadding = GLASS_NAV_HEIGHT + systemNavBarsHeight

    // 顶栏塌缩行为：rememberSaveable 状态，切 tab / 进程恢复都不丢
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior: ScrollBehavior = MiuixScrollBehavior(topAppBarState)

    // 顶栏与底栏共用的取景层（miuix-blur）
    val glassNavBackdrop = rememberLayerBackdrop()

    // 系统返回：先退二级页面，再放行给 Activity
    BackHandler(enabled = subPage != null) {
        subPage = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 关键：禁用默认 contentWindowInsets，让内容满高铺开
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides glassNavBackdrop) {
                val sub = subPage
                if (sub == null) {
                    KibaTopBar(
                        title = selectedTab.title,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            when (selectedTab) {
                                KibaTab.Songs -> KibaSearchAction { songsSearchTrigger++ }
                                KibaTab.Me -> KibaRefreshAction { KibaState.refreshRecords() }
                                else -> Unit
                            }
                        },
                    )
                } else {
                    KibaTopBar(
                        title = sub.title,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { KibaBackAction { subPage = null } },
                        actions = {
                            if (sub is KibaSubPage.Records) {
                                KibaRefreshAction { KibaState.refreshRecords() }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (subPage == null) {
                // Termux Ultra 默认悬浮底栏：GlassNavigationBar
                // 左右各 24dp 内缩成浮动胶囊，底部贴系统导航栏上边缘
                GlassNavigationBar(
                    items = tabs.map { GlassNavigationItem(icon = tabIcon(it), label = it.title) },
                    selectedIndex = selectedTab.ordinal,
                    onSelect = { index -> selectedTab = tabs.getOrElse(index) { selectedTab } },
                    backdrop = glassNavBackdrop,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = systemNavBarsHeight),
                )
            }
        }
    ) { padding ->
        // 顶栏遮挡量从 Scaffold 自身 padding 现取：顶栏塌缩时测量高度同步收缩，
        // 常量会留下一条不透带（Termux Ultra GlassPageHost 同款做法）。
        val topBarClearance = padding.calculateTopPadding()
        CompositionLocalProvider(
            LocalTopBarClearance provides topBarClearance,
            LocalNavBarBottomPadding provides navBarBottomPadding,
            LocalSystemNavBarBottom provides systemNavBarsHeight,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 内容注册到取景层：顶栏/底栏透过它折射页面内容
                    .layerBackdrop(glassNavBackdrop)
            ) {
                val sub = subPage
                if (sub != null) {
                    when (sub) {
                        is KibaSubPage.SongDetail -> SongDetailScreen(
                            songId = sub.songId,
                            scrollBehavior = scrollBehavior,
                            onOpenChart = { songId, difficultyOrdinal ->
                                subPage = KibaSubPage.ChartDetail(songId, difficultyOrdinal)
                            },
                        )
                        is KibaSubPage.ChartDetail -> ChartDetailScreen(
                            songId = sub.songId,
                            difficultyOrdinal = sub.difficultyOrdinal,
                            scrollBehavior = scrollBehavior,
                        )
                        is KibaSubPage.Best50 -> Best50Screen(scrollBehavior = scrollBehavior)
                        is KibaSubPage.Records -> RecordsScreen(scrollBehavior = scrollBehavior)
                        is KibaSubPage.VersionProgress -> VersionProgressScreen(scrollBehavior = scrollBehavior)
                        is KibaSubPage.LevelProgress -> LevelProgressScreen(scrollBehavior = scrollBehavior)
                        is KibaSubPage.LlmConfig -> LlmConfigScreen(scrollBehavior = scrollBehavior)
                        is KibaSubPage.Login -> LoginScreen(
                            scrollBehavior = scrollBehavior,
                            onLoginSuccess = { subPage = null },
                        )
                        is KibaSubPage.About -> AboutScreen(scrollBehavior = scrollBehavior)
                    }
                } else {
                    when (selectedTab) {
                        KibaTab.Songs -> SongsScreen(
                            scrollBehavior = scrollBehavior,
                            searchTrigger = songsSearchTrigger,
                            onSongClick = { song ->
                                subPage = KibaSubPage.SongDetail(song.songData.id)
                            },
                        )
                        KibaTab.Agent -> AgentScreen(scrollBehavior = scrollBehavior)
                        KibaTab.Me -> MeScreen(
                            scrollBehavior = scrollBehavior,
                            onOpenLogin = { subPage = KibaSubPage.Login },
                            onOpenBest50 = { subPage = KibaSubPage.Best50 },
                            onOpenRecords = { subPage = KibaSubPage.Records },
                            onOpenVersionProgress = { subPage = KibaSubPage.VersionProgress },
                            onOpenLevelProgress = { subPage = KibaSubPage.LevelProgress },
                        )
                        KibaTab.Settings -> SettingsScreen(
                            scrollBehavior = scrollBehavior,
                            onOpenLlmConfig = { subPage = KibaSubPage.LlmConfig },
                            onOpenAbout = { subPage = KibaSubPage.About },
                        )
                    }
                }
            }
        }
    }
}
