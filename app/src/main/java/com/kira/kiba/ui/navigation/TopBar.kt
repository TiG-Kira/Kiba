package com.kira.kiba.ui.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Kiba 顶栏：原生 miuix-glass 的 GlassTopAppBar（非自建）。
 * - backdrop 取自 [LocalGlassTopAppBarBackdrop]（MainScreen 共享的 miuix-blur 取景层）；
 * - 大标题 + 滚动塌缩由 [scrollBehavior] 驱动，页面 LazyColumn 需挂 nestedScroll；
 * - actions 中的按钮统一使用 [GlassIconButton]，保持“按钮也走 glass 系”。
 */
@Composable
fun KibaTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassTopAppBar(
        title = title,
        largeTitle = largeTitle,
        modifier = modifier,
        backdrop = LocalGlassTopAppBarBackdrop.current,
        scrollBehavior = scrollBehavior,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

/** 顶栏返回按钮（glass 系，二级页面用）。[tint] 供封面背景等自定义底色场景切换字色。 */
@Composable
fun KibaBackAction(
    tint: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    GlassIconButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Back,
            contentDescription = "返回",
            tint = tint,
        )
    }
}

/** 顶栏搜索按钮（glass 系）。 */
@Composable
fun KibaSearchAction(onClick: () -> Unit) {
    GlassIconButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Search,
            contentDescription = "搜索",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 顶栏刷新按钮（glass 系）。 */
@Composable
fun KibaRefreshAction(onClick: () -> Unit) {
    GlassIconButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Refresh,
            contentDescription = "刷新",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 顶栏归类按钮（glass 系，曲目页用）。 */
@Composable
fun KibaSortAction(onClick: () -> Unit) {
    GlassIconButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Sort,
            contentDescription = "归类方式",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}
