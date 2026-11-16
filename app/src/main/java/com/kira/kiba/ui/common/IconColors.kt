package com.kira.kiba.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 卡片图标的语义色板（「彩色图标」全局口径）。
 *
 * 设计规则：
 * - **类型相近的图标用同一色系**（颜色可稍有不同）——账户 / 查分是蓝、数据同步是绿、
 *   工具计算是橙、AI / 外观是紫、收藏歌单是粉、信息版本是青、危险操作是红；
 * - 亮色与暗色主题各给一档：暗色下提亮一档保证在玻璃底色上的可读性；
 * - 只用于**卡片上的语义图标**（preference 行 / 功能卡）：导航返回键、折叠箭头、
 *   关闭按钮这类「结构型」图标保持中性灰，不上色。
 */
enum class KibaIconTint {
    /** 蓝：账户 / 查分 / 查询 / 检索。 */
    BLUE,

    /** 绿：数据同步 / 下载 / 更新 / 播放。 */
    GREEN,

    /** 橙：工具 / 计算 / 设备。 */
    ORANGE,

    /** 紫：AI / Agent / 外观主题。 */
    PURPLE,

    /** 粉：收藏 / 歌单 / 加入。 */
    PINK,

    /** 青：信息 / 版本 / 云服务 / 详情。 */
    CYAN,

    /** 红：退出 / 删除等破坏性操作。 */
    RED,
}

/** 取语义色的当前主题色值（暗色提亮一档）。 */
@Composable
fun kibaIconTint(tint: KibaIconTint): Color {
    val dark = isSystemInDarkTheme()
    return when (tint) {
        KibaIconTint.BLUE -> if (dark) Color(0xFF6AA9FF) else Color(0xFF2E7CF6)
        KibaIconTint.GREEN -> if (dark) Color(0xFF5FDD8D) else Color(0xFF2FAE5B)
        KibaIconTint.ORANGE -> if (dark) Color(0xFFFFB74D) else Color(0xFFF29200)
        KibaIconTint.PURPLE -> if (dark) Color(0xFFC688FB) else Color(0xFF9F51DC)
        KibaIconTint.PINK -> if (dark) Color(0xFFFF8FB0) else Color(0xFFE8547E)
        KibaIconTint.CYAN -> if (dark) Color(0xFF54CFE0) else Color(0xFF0E9AA7)
        KibaIconTint.RED -> if (dark) Color(0xFFFF7B7F) else Color(0xFFE5484D)
    }
}

/**
 * 卡片语义图标（彩色版 PrefIcon）：各页 preference 行 / 功能卡左侧的功能图标。
 * 结构型图标（返回 / 箭头 / 关闭）不要用这个，保持中性灰。
 */
@Composable
fun ColoredPrefIcon(
    icon: ImageVector,
    tint: KibaIconTint,
    size: Dp = 26.dp,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = kibaIconTint(tint),
        modifier = Modifier.size(size),
    )
}
