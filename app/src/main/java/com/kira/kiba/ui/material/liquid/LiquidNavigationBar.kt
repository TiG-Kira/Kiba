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
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
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
import kotlinx.coroutines.Job
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
 * 切换意图：`serial` 每次写都自增，保证「松手吸附回原 tab」这种 index 不变的情况
 * 也会触发一次状态流。单个状态对象 = 单次写 = 单次 emit，避免两次写让
 * `collectLatest` 取消掉正在播的吸附动画并吞掉那次的 `onSelect`。
 */
private data class NavSettle(val index: Int, val serial: Int)

/**
 * 液态玻璃版底部导航栏 —— 1:1 移植 AndroidLiquidGlass demo 的 `LiquidBottomTabs`
 * （Kyant0 官方实现；酷安的 `LiquidGlassTabPanel` 即基于同库同结构的工程化封装）。
 *
 * 绘制分三层（z 序）：
 * 1. **Row A 可见底栏**：`vibrancy + blur(8) + lens(24,24)`，表面 = 容器色；
 *    按压时整条底栏 `scale = 1 + 16dp/宽`（随 pressProgress，酷安按压时整条微鼓）；
 * 2. **Row B 隐形导出层**：`alpha(0)` 不可见，只把「底栏 + 图标」染成强调色录进
 *    `tabsBackdrop`，胶囊折射吸进来的图标是染过色的；
 * 3. **胶囊**：采样 `combined(page, tabsBackdrop)`，折射/高光/内阴影/表面强度全部乘
 *    pressProgress——**静止时全为 0，只剩 Black 10% 的灰色提示**（酷安同款）；
 *    速度驱动的拉伸是 demo 的除法对称式：`scaleX /= 1 - v*0.75`。
 *
 * 交互：点按 tab、横向拖动胶囊、外部切 Tab 三条路径统一汇入 [NavSettle] 状态流，
 * 动画与 `onSelect` 上报只发生在一处；拖动时 `updateValue` 跟手 + 整条底栏
 * EaseOut 微移（panelOffset，最多 4dp），松手吸附最近 tab 并回弹。
 * 图标高亮跟随胶囊的实际位置（`round(damped.value)`），不跟随目标 tab。
 *
 * 只服务于 `MaterialFamily.LIQUID`；柔光玻璃+（默认）与未开启高级材质时的经典
 * 底栏都由 `GlassNavigationBar` 承担，与本文件无关。
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
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()

    val tabsBackdrop = rememberLayerBackdrop()

    // 桥接：动画回调（在 BoxWithConstraints 外构造）需要拿内部测量的量。
    // 回调每次重组都会被重新赋值（捕获最新状态），所以字段可变即可。
    class DragBridge {
        var tabWidth: Float = 0f
        var isLtr: Boolean = true
        var offsetAnimation: Animatable<Float, *>? = null
        var offsetSnapJob: Job? = null
        var setCurrent: (Int) -> Unit = {}
    }
    val bridge = remember { DragBridge() }
    bridge.isLtr = isLtr
    var dampedRef by remember { mutableStateOf<DampedDragAnimation?>(null) }

    var navSettle by remember { mutableStateOf(NavSettle(selectedIndex, 0)) }
    // 只在上报目标真的变化时回调 onSelect，避免吸附回原位也上报一次
    var reportedIndex by remember { mutableIntStateOf(selectedIndex) }

    val damped = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStopped = {
                dampedRef?.let { d ->
                    // targetValue 读同步字段，不受排队 snapTo 滞后影响；roundToInt
                    // 让停在半程的胶囊吸附到最近 tab，并经由状态流触发换页
                    val target = d.targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                    bridge.setCurrent(target)
                    // demo 的松手回弹：先丢弃排队中的让位 snapTo，否则它会覆盖回弹动画
                    bridge.offsetSnapJob?.cancel()
                    bridge.offsetSnapJob = null
                    bridge.offsetAnimation?.let { a ->
                        scope.launch { a.animateTo(0f, spring(1f, 300f, 0.5f)) }
                    }
                }
            },
            onDrag = { _, dragAmount ->
                dampedRef?.let { d ->
                    val w = bridge.tabWidth
                    if (w > 0f) {
                        d.updateValue(
                            d.targetValue + dragAmount.x / w * if (bridge.isLtr) 1f else -1f,
                        )
                        bridge.offsetAnimation?.let { a ->
                            bridge.offsetSnapJob?.cancel()
                            bridge.offsetSnapJob = scope.launch { a.snapTo(a.value + dragAmount.x) }
                        }
                    }
                }
            },
        ).also { dampedRef = it }
    }

    // 外部 selectedIndex（其它入口切 Tab）→ 汇入同一条流。
    // 形参不是 State，靠 LaunchedEffect 的 key 变化驱动即可，无需再包一层 snapshotFlow
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != navSettle.index) {
            navSettle = NavSettle(selectedIndex, navSettle.serial + 1)
        }
    }
    // 点按 / 拖动吸附 / 外部切换的唯一出口：液滴到位后只上报一次换页
    LaunchedEffect(damped) {
        snapshotFlow { navSettle }
            .drop(1)
            .collectLatest { (index, _) ->
                damped.animateToValue(index.toFloat())
                if (index != reportedIndex) {
                    reportedIndex = index
                    onSelect(index)
                }
            }
    }

    // 图标高亮跟随胶囊的实际位置，只在跨过 tab 边界的那一帧重组
    val highlightIndex by remember {
        derivedStateOf { damped.value.fastRoundToInt().fastCoerceIn(0, items.size - 1) }
    }

    // DrawBackdropElement.equals 按 lambda 引用判等，update() 无条件
    // invalidateDrawCache()。这些 lambda 一旦每次重组新建，整套取景链就会重录
    // （滚动时表现为底栏细抖），所以身份一律固定在这里。
    val capsuleShape: () -> Shape = remember { { Capsule() } }
    val barEffects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(8f.dp.toPx())
            lens(24f.dp.toPx(), 24f.dp.toPx())
        }
    }
    val barLayerBlock: GraphicsLayerScope.() -> Unit = remember(damped) {
        {
            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, damped.pressProgress)
            scaleX = scale
            scaleY = scale
        }
    }
    val barSurface: DrawScope.() -> Unit = remember(containerColor) {
        { drawRect(containerColor) }
    }
    val exportEffects: BackdropEffectScope.() -> Unit = remember(damped) {
        {
            val progress = damped.pressProgress
            vibrancy()
            blur(8f.dp.toPx())
            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
        }
    }
    val exportHighlight: () -> Highlight? = remember(damped) {
        { Highlight.Default.copy(alpha = damped.pressProgress) }
    }
    val pillLens: BackdropEffectScope.() -> Unit = remember(damped) {
        {
            val progress = damped.pressProgress
            lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
        }
    }
    val pillHighlight: () -> Highlight? = remember(damped) {
        { Highlight.Default.copy(alpha = damped.pressProgress) }
    }
    val pillShadow: () -> Shadow? = remember(damped) {
        { Shadow(alpha = damped.pressProgress) }
    }
    val pillInnerShadow: () -> InnerShadow? = remember(damped) {
        { InnerShadow(radius = 8f.dp * damped.pressProgress, alpha = damped.pressProgress) }
    }
    val pillLayerBlock: GraphicsLayerScope.() -> Unit = remember(damped) {
        {
            scaleX = damped.scaleX
            scaleY = damped.scaleY
            val velocity = damped.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
        }
    }
    val pillSurface: DrawScope.() -> Unit = remember(isLightTheme, damped) {
        {
            val progress = damped.pressProgress
            drawRect(
                if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                alpha = 1f - progress,
            )
            drawRect(Color.Black.copy(alpha = 0.03f * progress))
        }
    }
    // Row B 录制层的图标随按压鼓起（demo 的 LocalLiquidBottomTabScale 口径）
    val pressScale: () -> Float = remember(damped) {
        { lerp(1f, 1.2f, damped.pressProgress) }
    }
    val combinedBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else {
        null
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / items.size
        }
        bridge.tabWidth = tabWidth

        // 整条底栏的"被拖动"偏移：拖动时底栏向拖动方向微微让位（最多 4dp，EaseOut 收敛）
        val offsetAnimation = remember { Animatable(0f) }
        bridge.offsetAnimation = offsetAnimation
        // 单次状态写：serial 自增保证「吸附回原 tab」也会 emit 一次
        bridge.setCurrent = { index ->
            navSettle = NavSettle(index, navSettle.serial + 1)
        }
        val panelOffsetState = remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val panelOffset by panelOffsetState

        val interactiveHighlight = remember(scope) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    val w = bridge.tabWidth
                    Offset(
                        if (bridge.isLtr) {
                            (damped.value + 0.5f) * w + panelOffsetState.value
                        } else {
                            size.width - (damped.value + 0.5f) * w + panelOffsetState.value
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // Row A：可见底栏
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = capsuleShape,
                            effects = barEffects,
                            layerBlock = barLayerBlock,
                            onDrawSurface = barSurface,
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
                    // 不直接 onSelect：交给状态流在液滴到位后统一上报一次
                    onClick = {
                        navSettle = NavSettle(i, navSettle.serial + 1)
                    },
                )
            }
        }

        // Row B：隐形导出层（图标染成强调色录进 tabsBackdrop）
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
                            shape = capsuleShape,
                            effects = exportEffects,
                            highlight = exportHighlight,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(interactiveHighlight.modifier)
                .height(height - 8f.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 不画表面色、不整层 ColorFilter.tint、不挂 clickable：
            // 前两个会把 tabsBackdrop 染成实心蓝（胶囊折射出一整块色块），
            // 第三个会拦掉 Row A 的真实点按（均为 AVD 实证）。
            items.forEach { item ->
                TabContent(
                    item = item,
                    tint = accentColor,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = null,
                    scale = pressScale,
                )
            }
        }

        // 胶囊：一切玻璃效果 × pressProgress；静止 = Black 10% 灰色提示
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (bridge.isLtr) {
                        damped.value * tabWidth + panelOffset
                    } else {
                        size.width - (damped.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(damped.modifier)
                .then(
                    if (combinedBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = capsuleShape,
                            effects = pillLens,
                            highlight = pillHighlight,
                            shadow = pillShadow,
                            innerShadow = pillInnerShadow,
                            layerBlock = pillLayerBlock,
                            onDrawSurface = pillSurface,
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
    scale: (() -> Float)? = null,
) {
    val iconScale = scale
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
            )
            .then(
                if (iconScale != null) {
                    Modifier.graphicsLayer {
                        val s = iconScale()
                        scaleX = s
                        scaleY = s
                    }
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
