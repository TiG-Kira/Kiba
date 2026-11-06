// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃底栏高度：与柔光玻璃+（miuix `GlassNavigationBar`，54dp）对齐，
 * 两套材质切换时底栏长宽、列表底部垫高完全一致，页面不会跳。
 */
val LiquidNavHeight: Dp = 54.dp

/**
 * 液态玻璃版底部导航栏 —— 1:1 移植 AndroidLiquidGlass demo 的 `LiquidBottomTabs`
 * （Kyant0 官方实现；酷安的 `LiquidGlassTabPanel` 即基于同库同结构的工程化封装）。
 *
 * 绘制分三层（z 序）：
 * 1. **Row A 可见底栏**：`vibrancy + blur(8) + lens(24,24)`，表面 = 容器色；
 *    按压时整条底栏 `scale = 1 + 16dp/宽`（随 pressProgress，酷安按压时整条微鼓）；
 * 2. **Row B 隐形导出层**：`alpha(0)` 不可见，只把「底栏 + 图标」**染成强调色**
 *    （`ColorFilter.tint(accent)`）录进 `tabsBackdrop`——胶囊折射吸进来的图标是
 *    染过色的，酷安录屏里"图标被玻璃拉出彩色变形"就来自这层；
 * 3. **胶囊**：采样 `combined(page, tabsBackdrop)`，折射/高光/内阴影/表面强度全部乘
 *    pressProgress——**静止时全为 0，只剩 Black 10% 的灰色提示**（酷安同款）；
 *    速度驱动的拉伸是 demo 的除法对称式：`scaleX /= 1 - v*0.75`。
 *
 * 交互（对齐酷安）：点按 tab 或横向拖动胶囊都触发切换；拖动时 `updateValue` 跟手 +
 * 整条底栏 EaseOut 微移（panelOffset，最多 4dp）；松手吸附最近 tab。
 * 图标高亮跟随胶囊的实际位置（`round(damped.value)`），不跟随目标 tab。
 */
@Composable
fun LiquidNavigationBar(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    height: Dp = LiquidNavHeight,
) {
    if (items.isEmpty()) return
    val isLightTheme = !isSystemInDarkTheme()
    // demo 的强调色口径（选中/染色都用它）；与 Kiba 主色（蓝）同族
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
    val scope = rememberCoroutineScope()

    val tabsBackdrop = rememberLayerBackdrop()

    // 桥接：动画回调（在 BoxWithConstraints 外构造）需要拿内部测量的量。
    // 回调每次重组都会被重新赋值（捕获最新状态），所以字段可变即可。
    class DragBridge {
        var tabWidth: Float = 0f
        var offsetAnimation: Animatable<Float, *>? = null
        var setCurrent: (Int) -> Unit = {}
    }
    val bridge = remember { DragBridge() }
    var dampedRef by remember { mutableStateOf<DampedDragAnimation?>(null) }

    val damped = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStopped = {
                // 松手吸附最近 tab；换页动画与 onSelect 由 currentIndex 流统一驱动
                dampedRef?.let { d ->
                    bridge.setCurrent(d.targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1))
                }
            },
            onDrag = { _, dragAmount ->
                dampedRef?.let { d ->
                    val w = bridge.tabWidth
                    if (w > 0f) {
                        d.updateValue(d.targetValue + dragAmount.x / w)
                        bridge.offsetAnimation?.let { a ->
                            scope.launch { a.snapTo(a.value + dragAmount.x) }
                        }
                    }
                }
            },
        ).also { dampedRef = it }
    }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    // 外部 selectedIndex（点按 tab）→ 驱动胶囊动画 + 上报 onSelect
    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex }.collectLatest { index ->
            if (index != currentIndex) currentIndex = index
        }
    }
    // currentIndex 变化（点按或拖动吸附）→ 液滴飞过去 + 上报
    LaunchedEffect(damped) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                damped.animateToValue(index.toFloat())
                onSelect(index)
            }
    }

    // 图标高亮跟随胶囊的实际位置，只在跨过 tab 边界的那一帧重组
    val highlightIndex by remember {
        derivedStateOf { damped.value.fastRoundToInt().fastCoerceIn(0, items.size - 1) }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / items.size
        }
        bridge.tabWidth = tabWidth

        // 整条底栏的"被拖动"偏移：拖动时底栏向拖动方向微微让位（最多 4dp，EaseOut 收敛）
        val offsetAnimation = remember { Animatable(0f) }
        bridge.offsetAnimation = offsetAnimation
        bridge.setCurrent = { index -> currentIndex = index }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val interactiveHighlight = remember(scope) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    Offset(
                        (damped.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f,
                    )
                },
            )
        }

        // ── Row A：可见底栏 ──
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(8f.dp.toPx())
                                lens(24f.dp.toPx(), 24f.dp.toPx())
                            },
                            layerBlock = {
                                val progress = damped.pressProgress
                                val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, Capsule())
                    },
                )
                .then(interactiveHighlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                TabContent(
                    item = item,
                    tint = if (i == highlightIndex) accentColor else unselectedTint(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onSelect(i) },
                )
            }
        }

        // ── Row B：隐形导出层（整层染成强调色录进 tabsBackdrop）──
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = damped.pressProgress
                                vibrancy()
                                blur(8f.dp.toPx())
                                lens(
                                    24f.dp.toPx() * progress,
                                    24f.dp.toPx() * progress,
                                )
                            },
                            highlight = {
                                val progress = damped.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .height(height - 8f.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 注意：这层录进 tabsBackdrop 的只有「玻璃效果输出 + 强调色图标」，
            // 不画表面色——demo 的整层 ColorFilter.tint 会把玻璃表面也染成实心蓝，
            // 胶囊折射出来就是一整块蓝（AVD 实证）。
            items.forEach { item ->
                // 不挂 clickable：Row B 盖在 Row A 之上，空点击会拦掉真实点按（AVD 实证）
                TabContent(item = item, tint = accentColor, modifier = Modifier.weight(1f).fillMaxHeight(), onClick = null)
            }
        }

        // ── 胶囊：一切玻璃效果 × pressProgress；静止 = Black 10% 灰色提示 ──
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = damped.value * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(damped.modifier)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { Capsule() },
                            effects = {
                                val progress = damped.pressProgress
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                val progress = damped.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            shadow = {
                                val progress = damped.pressProgress
                                Shadow(alpha = progress)
                            },
                            innerShadow = {
                                val progress = damped.pressProgress
                                InnerShadow(
                                    radius = 8f.dp * progress,
                                    alpha = progress,
                                )
                            },
                            layerBlock = {
                                scaleX = damped.scaleX
                                scaleY = damped.scaleY
                                val velocity = damped.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = damped.pressProgress
                                drawRect(
                                    if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                    } else {
                        Modifier.background(Color.Black.copy(alpha = 0.1f), Capsule())
                    },
                )
                .height(height - 8f.dp)
                .fillMaxWidth(1f / items.size),
        )
    }
}

@Composable
private fun unselectedTint(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.62f)

@Composable
private fun TabContent(
    item: GlassNavigationItem,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.contentDescription ?: item.label,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        if (item.label != null) {
            Text(
                text = item.label,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
