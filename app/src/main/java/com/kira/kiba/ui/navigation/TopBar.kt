package com.kira.kiba.ui.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.glass.GlassTopAppBar

/**
 * Kiba 顶栏：原生 miuix-glass 的 GlassTopAppBar（非自建）。
 * backdrop 取自 LocalGlassTopAppBarBackdrop（MainScreen 共享的 miuix-blur 取景层）。
 * actions 中的按钮请统一使用 GlassIconButton，保持“按钮也走 glass 系”。
 */
@Composable
fun KibaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backdrop = LocalGlassTopAppBarBackdrop.current
    GlassTopAppBar(
        title = title,
        modifier = modifier,
        backdrop = backdrop,
        actions = actions,
    )
}
