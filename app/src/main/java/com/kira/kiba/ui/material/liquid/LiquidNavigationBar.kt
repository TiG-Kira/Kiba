// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 液态玻璃底栏的固定高度：比柔光玻璃+ 的 54dp 更「厚」，胶囊形状才立得住（demo 口径 64dp）。 */
val LiquidNavHeight: Dp = 64.dp

/** 选中指示胶囊相对条内边缘的内缩。 */
private val IndicatorInset: Dp = 5.dp

/**
 * 液态玻璃版底部导航栏（Kyant0/AndroidLiquidGlass 独立实现）。
 *
 * 与柔光玻璃+（`GlassNavigationBar`）的差别：
 * - 形体：64dp 高的整条胶囊，外边缘不再有 miuix 的描边/内阴影，改用 24dp 折射 + 景深；
 * - 选中态：一条随选中项平滑滑动的半透明胶囊（demo 的 indicator），而不是 miuix 的
 *   窄高亮条；
 * - 图标：选中项放大 1.08 且染成主色，未选中项降到 60% 不透明度。
 *
 * `backdrop` 为 null（材质关闭 / 渲染不支持）时回落实色胶囊，保证任何设备上都能读清。
 */
@Composable
fun LiquidNavigationBar(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    backgroundBoard: Color? = null,
    height: Dp = LiquidNavHeight,
) {
    if (items.isEmpty()) return
    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    // demo 口径：亮色 0xFFFAFAFA / 暗色 0xFF121212，统一 40% 透明度，玻璃感来自取景本身
    val container = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.40f) else Color(0xFFFAFAFA).copy(alpha = 0.40f)
    }
    val indicatorColor = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    }
    val selectedTint = MiuixTheme.colorScheme.primary
    val unselectedTint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.60f)

    // 形体与效果 lambda 必须记忆：drawBackdrop 的 ModifierNodeElement 按 lambda 身份判等，
    // 每次重组新建 lambda 会触发 invalidateDrawCache → 整套取景重录（底栏抖动根源）。
    val capsuleShape: () -> Shape = remember { { Capsule() } }
    val barEffects: BackdropEffectScope.() -> Unit = remember { { liquidBarEffects() } }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val surface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(container, backgroundBoard) { { drawRect(backgroundBoard ?: container) } }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = capsuleShape,
                        effects = barEffects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = surface,
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface, Capsule())
                },
            )
            .clip(Capsule()),
    ) {
        val density = LocalDensity.current
        val barWidthPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { IndicatorInset.toPx() }
        val tabWidthPx = (barWidthPx - insetPx * 2f) / items.size

        val progress by animateFloatAsState(
            targetValue = index.toFloat(),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "liquidNavIndicator",
        )

        Box(
            modifier = Modifier
                .offset(x = with(density) { (insetPx + progress * tabWidthPx).toDp() })
                .width(with(density) { tabWidthPx.toDp() })
                .fillMaxHeight()
                .padding(vertical = IndicatorInset)
                .background(indicatorColor, Capsule()),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val selected = i == index
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "liquidNavScale$i",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(i) },
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription ?: item.label,
                        tint = if (selected) selectedTint else unselectedTint,
                        // 与 miuix GlassNavigationBarDefaults.IconSize 对齐（28dp）：
                        // 24dp 摆在 64dp 胶囊里显得空，和默认底栏也不一致。
                        modifier = Modifier.size(28.dp),
                    )
                    if (item.label != null) {
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            color = if (selected) selectedTint else unselectedTint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
