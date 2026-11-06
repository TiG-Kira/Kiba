// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material

import androidx.compose.runtime.staticCompositionLocalOf
import com.kira.kiba.core.utils.DeviceTier
import com.kira.kiba.core.utils.SpUtil

/**
 * 真·高级材质总开关（用户在设置页控制；评分不达标时不会提供 true）。
 * 关闭时顶栏 / 弹层回落为原生默认样式（纯色带、普通弹窗）。
 */
val LocalAdvancedMaterialEnabled = staticCompositionLocalOf { false }

/**
 * 当前选用的材质风格。
 *
 * - [MaterialFamily.SOFT]（默认）：miuix-glass 的「柔光玻璃+」，顶栏 / 底栏 / 弹层
 *   直接取景 + 柔化折射，观感克制，列表滚动时最稳；
 * - [MaterialFamily.LIQUID]：Kyant0/AndroidLiquidGlass（Maven 上的 `io.github.kyant0:backdrop`）
 *   的「液态玻璃」——胶囊形体 + 强透镜折射 + 边缘高光 + 色散，控件形体按 demo 重做。
 */
val LocalMaterialFamily = staticCompositionLocalOf { MaterialFamily.SOFT }

/** 设备性能档位：低档设备自动收敛渲染强度。 */
val LocalDeviceTier = staticCompositionLocalOf { DeviceTier.MID }

/**
 * 材质风格（设置页持久化的枚举名契约，[MaterialFamily.of] 反序列化）。
 *
 * 默认值必须是 SOFT：液态玻璃的透镜 shader 在低端机上开销明显，而且它是**新增**选项，
 * 老用户升级后不该被悄悄换一套观感（用户反馈第 7 条的括号里已明确「默认现在的逻辑」）。
 */
enum class MaterialFamily(val label: String, val summary: String) {
    /** 液态：胶囊形体、强折射 + 色散、边缘高光锐利，接近水滴 / 厚玻璃。 */
    LIQUID(
        label = "液态玻璃",
        summary = "强透镜折射 + 边缘高光 + 色散，胶囊形体（Kyant0/AndroidLiquidGlass demo 口径）",
    ),

    /** 柔光：折射收敛、补光更亮、光带更宽，接近磨砂柔光玻璃（默认）。 */
    SOFT(
        label = "柔光玻璃+",
        summary = "miuix-glass 柔光取景，折射收敛、滚动最稳（默认）",
    ),
    ;

    companion object {
        fun of(key: String): MaterialFamily = entries.firstOrNull { it.name == key } ?: SOFT

        /** 读设置页当前选择（容错：未知值回落 SOFT）。 */
        fun current(): MaterialFamily = of(SpUtil.getMaterialFamily())
    }
}
