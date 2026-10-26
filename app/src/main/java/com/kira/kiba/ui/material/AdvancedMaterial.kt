// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material

import androidx.compose.runtime.staticCompositionLocalOf
import com.kira.kiba.core.utils.DeviceTier

/**
 * 真·高级材质总开关（用户在设置页控制；评分不达标时不会提供 true）。
 * 关闭时顶栏 / 弹层回落为原生默认样式（纯色带、普通弹窗）。
 */
val LocalAdvancedMaterialEnabled = staticCompositionLocalOf { false }

/** 当前选用的材质风格（预留；当前唯一启用的观感由 miuix-glass 在开启时直接取景实现）。 */
val LocalMaterialFamily = staticCompositionLocalOf { MaterialFamily.LIQUID }

/** 设备性能档位：低档设备自动收敛渲染强度。 */
val LocalDeviceTier = staticCompositionLocalOf { DeviceTier.MID }

/**
 * 材质风格：保留枚举仅为后续扩展（如重新引入「Liquid」）预留，
 * 同时是设置页持久化的枚举名契约（[MaterialFamily.of] 反序列化）。
 * 当前唯一启用的观感为「超级柔光」，由 miuix-glass 的
 * [top.yukonga.miuix.kmp.glass.GlassTopAppBar] 与
 * [top.yukonga.miuix.kmp.glass.GlassDialog] 在开启时直接取景实现。
 */
enum class MaterialFamily(val label: String) {
    /** 液态：折射强、色散明显、边缘光锐利，接近水滴 / 厚玻璃。 */
    LIQUID("Liquid"),

    /** 柔光：折射收敛、补光更亮、光带更宽，接近磨砂柔光玻璃。 */
    SOFT("Soft"),
    ;

    companion object {
        fun of(key: String): MaterialFamily = entries.firstOrNull { it.name == key } ?: LIQUID
    }
}
