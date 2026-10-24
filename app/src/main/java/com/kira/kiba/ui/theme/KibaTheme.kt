package com.kira.kiba.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kira.kiba.core.state.KibaState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Kiba 全局主题：直接包裹 miuix 的 MiuixTheme（Termux Ultra 视觉体系）。
 * 底层配色全部走 miuix 的 colorScheme，避免混入其它主题体系导致玻璃栏渲染崩坏。
 *
 * 主题模式（跟随系统/浅色/深色）与动态取色由 [KibaState] 驱动，
 * ThemeController 的 colorSchemeMode 是构造参数且只读，切换时重建实例（Termux Ultra 同款做法）。
 */
@Composable
fun KibaTheme(content: @Composable () -> Unit) {
    val themeMode by KibaState.themeMode.collectAsState()
    val dynamicColor by KibaState.dynamicColor.collectAsState()

    val colorSchemeMode = when {
        dynamicColor && themeMode == KibaState.ThemeMode.Light -> ColorSchemeMode.MonetLight
        dynamicColor && themeMode == KibaState.ThemeMode.Dark -> ColorSchemeMode.MonetDark
        dynamicColor -> ColorSchemeMode.MonetSystem
        themeMode == KibaState.ThemeMode.Light -> ColorSchemeMode.Light
        themeMode == KibaState.ThemeMode.Dark -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }

    MiuixTheme(
        // colorSchemeMode 只读，切换必须重建实例
        controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode = colorSchemeMode) },
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        },
    )
}
