package com.kira.kiba.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kira.kiba.ui.agent.AgentScreen
import com.kira.kiba.ui.me.MeScreen
import com.kira.kiba.ui.songs.SongsScreen
import com.kira.kiba.ui.settings.SettingsScreen

/** 顶栏玻璃材质共享 MainScreen 的取景层（miuix-blur Backdrop），各页无需各自持有。 */
val LocalGlassTopAppBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 顶栏占位高度（状态栏 + 栏高），页面顶部需至少留出此值，避免内容顶进顶栏内。 */
val LocalTopBarClearance = staticCompositionLocalOf<Dp> { 0.dp }

enum class KibaTab(val title: String) {
    Songs("曲目"),
    Agent("Agent"),
    Me("我"),
    Settings("设置")
}

fun tabIcon(tab: KibaTab) = when (tab) {
    KibaTab.Songs -> MiuixGlassIcons.Regular.Music
    KibaTab.Agent -> MiuixGlassIcons.Regular.AiScan
    KibaTab.Me -> MiuixGlassIcons.Regular.Contacts
    KibaTab.Settings -> MiuixGlassIcons.Regular.Settings
}

@Composable
fun KibaMainScreen() {
    val tabs = KibaTab.values()
    var selectedTab by remember { mutableStateOf(KibaTab.Songs) }

    val density = LocalDensity.current
    // 本机 Compose 版本无 statusBars 顶层/伴随属性，用 systemBars（含状态栏）取顶部高度
    val statusBar = WindowInsets.systemBars.asPaddingValues(density).calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val topBarClearance = statusBar + 56.dp

    // 两套 backdrop：
    // - glassNavBackdrop（miuix-blur）供给顶栏 GlassTopAppBar 采样页面内容；
    // - liquidGlassBackdrop（kyant）供给底部 LiquidGlassNavigationBar 采样（玻璃地板修复关键）。
    val glassNavBackdrop = rememberMiuixLayerBackdrop()
    val liquidGlassBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // 关键：禁用默认 contentWindowInsets，让内容满高铺开，不被顶/底栏推开
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides glassNavBackdrop) {
                    KibaTopBar(
                        title = selectedTab.title,
                        actions = {
                            when (selectedTab) {
                                KibaTab.Songs -> GlassIconButton(onClick = { /* TODO 搜索 */ }) {
                                    Icon(
                                        MiuixGlassIcons.Regular.Search,
                                        contentDescription = "搜索",
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                KibaTab.Me -> GlassIconButton(onClick = { /* TODO 刷新 */ }) {
                                    Icon(
                                        MiuixGlassIcons.Regular.Refresh,
                                        contentDescription = "刷新",
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                                else -> Unit
                            }
                        }
                    )
                }
            },
            bottomBar = {
                val glassDims = computeNavDimensions(tabs.size, NavStyle.GLASS)
                LiquidGlassNavigationBarWithIndicator(
                    selectedIndex = selectedTab.ordinal,
                    itemCount = tabs.size,
                    backdrop = liquidGlassBackdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = navBarBottom),
                    onIndexChange = { index -> selectedTab = tabs.getOrElse(index) { selectedTab } }
                ) { onPositioned ->
                    tabs.forEachIndexed { index, tab ->
                        LiquidGlassNavigationBarItem(
                            icon = tabIcon(tab),
                            label = tab.title,
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            dims = glassDims,
                            index = index,
                            onPositioned = onPositioned
                        )
                    }
                }
            }
        ) { _ ->
            // 内容同时注册到两套 backdrop：底栏（kyant）采样内容做玻璃地板，
            // 顶栏（miuix）采样内容做玻璃透视。内容满高铺在悬浮导航之下（无底部留白=玻璃地板修复核心）。
            CompositionLocalProvider(LocalTopBarClearance provides topBarClearance) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .miuixLayerBackdrop(glassNavBackdrop)
                        .layerBackdrop(liquidGlassBackdrop)
                ) {
                    when (selectedTab) {
                        KibaTab.Songs -> SongsScreen()
                        KibaTab.Agent -> AgentScreen()
                        KibaTab.Me -> MeScreen()
                        KibaTab.Settings -> SettingsScreen()
                    }
                }
            }
        }
    }
}
