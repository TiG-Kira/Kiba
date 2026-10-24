package com.kira.kiba.ui.navigation

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
import com.kira.kiba.ui.me.MeScreen
import com.kira.kiba.ui.settings.SettingsScreen
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

enum class KibaTab(val title: String) {
    Songs("曲目"),
    Agent("Agent"),
    Me("我"),
    Settings("设置")
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 关键：禁用默认 contentWindowInsets，让内容满高铺开
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides glassNavBackdrop) {
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
            }
        },
        bottomBar = {
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
    ) { padding ->
        // 顶栏遮挡量从 Scaffold 自身 padding 现取：顶栏塌缩时测量高度同步收缩，
        // 常量会留下一条不透带（Termux Ultra GlassPageHost 同款做法）。
        val topBarClearance = padding.calculateTopPadding()
        CompositionLocalProvider(
            LocalTopBarClearance provides topBarClearance,
            LocalNavBarBottomPadding provides navBarBottomPadding,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 内容注册到取景层：顶栏/底栏透过它折射页面内容
                    .layerBackdrop(glassNavBackdrop)
            ) {
                when (selectedTab) {
                    KibaTab.Songs -> SongsScreen(
                        scrollBehavior = scrollBehavior,
                        searchTrigger = songsSearchTrigger,
                    )
                    KibaTab.Agent -> AgentScreen(scrollBehavior = scrollBehavior)
                    KibaTab.Me -> MeScreen(scrollBehavior = scrollBehavior)
                    KibaTab.Settings -> SettingsScreen(scrollBehavior = scrollBehavior)
                }
            }
        }
    }
}
