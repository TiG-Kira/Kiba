package com.kira.kiba.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Kiba 全局主题：直接包裹 miuix 的 MiuixTheme（Termux Ultra 视觉体系）。
 * 底层配色全部走 miuix 的 colorScheme，避免混入其它主题体系导致玻璃栏渲染崩坏。
 */
@Composable
fun KibaTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) },
        content = content
    )
}
