// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 液态玻璃底栏高度：与柔光玻璃+（miuix `GlassNavigationBar`，54dp）对齐，
 * 两套材质切换时底栏长宽、列表底部垫高完全一致，页面不会跳。
 */
val LiquidNavHeight: Dp = 54.dp

/**
 * 液态玻璃版底部导航栏。
 *
 * **交互状态机与手感整体移植自 HyperIsland**（1812z/HyperIsland 的
 * `LiquidGlassNavigationBar.kt` / `FloatingLiquidBar`）：
 * - 胶囊本体独占双 pass 手势，按下瞬间就浮现玻璃 + 横纵不同阻尼地鼓起；
 *   **按住当前选中的 tab 也有完整液态反馈**（旧实现把 press 挂在底层 Row 上，
 *   被 tab 的 clickable settle 抢掉，等于没有任何动作）；
 * - 五条独立弹簧（位置 / 速度 / press / scaleX / scaleY）+ `MutatorMutex` +
 *   真实 `VelocityTracker`，松手等飞行收敛才凝回提示；
 * - 镜面高光用 AGSL `smoothstep` + `BlendMode.Plus` 加色叠加，并按设备重力方向偏移。
 *
 * 渲染层仍走 Kiba 的 Kyant0 backdrop 2.0.1（与 miuix blur 是两套不兼容的取景 API），
 * 因此只做**等效适配**：透明度口径按对方（blur 4dp / 容器 30% / 常驻镜面高光与投影 /
 * 胶囊 `depthEffect`），三层取景结构（Row A / 隐形 Row B / combined 胶囊）保持不变。
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
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    // HyperIsland 口径：表面尽量淡，让折射/高光成为主角（旧值 40% 偏实、发闷）
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.3f) else Color(0xFF121212).copy(0.3f)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    // 保留 State 对象本身：回调 lambda 在 remember 里构造，捕获解包值会读到旧 index / 旧回调
    val latestSelectedIndexState = rememberUpdatedState(selectedIndex)
    val latestOnSelectState = rememberUpdatedState(onSelect)
    // 镜面主光跟随设备倾斜（对方 rememberGravityHighlight 的等效实现：
    // Kyant0 Highlight 没有可配置光源，改为偏移 InteractiveHighlight 的光斑中心）
    val tiltState = rememberDeviceTilt()

    // 动画回调在组件最外层构造，但需要 BoxWithConstraints 里的测量值：
    // 用普通可变对象桥接（BoxWithConstraints 是 SubcomposeLayout，内部 remember 会重建）
    class Metrics {
        var tabWidth: Float = 0f
        var totalWidth: Float = 0f
        var isLtr: Boolean = true
        // 拖动时每帧都会 cancel/重建，绝不能用 State（会把含 glass 的子树拖进逐帧重组）
        var offsetSnapJob: Job? = null
    }
    val metrics = remember { Metrics() }
    metrics.isLtr = isLtr

    val totalWidthState = remember { mutableFloatStateOf(0f) }
    val offsetAnimation = remember { Animatable(0f) }
    // 记录最后一次驱动过动画的 tab，避免「拖动吸附 + selectedIndex 回流」播两次玻璃。
    // 保留 State 对象本身：回调 lambda 在 remember 里构造，捕获解包后的 Int 会读到旧值。
    val lastAnimatedState = remember { mutableIntStateOf(selectedIndex) }

    val panelOffsetState = remember(density) {
        derivedStateOf {
            val total = totalWidthState.floatValue
            if (total == 0f) 0f else {
                val fraction = (offsetAnimation.value / total).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    // 自引用桥：canDrag / onDragStopped 需要访问尚未构造完的动画对象
    class Holder { var animation: DampedDragAnimation? = null }
    val holder = remember { Holder() }

    val dragAnimation = remember(scope, items.size, density) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            canDrag = { offset ->
                val animation = holder.animation ?: return@DampedDragAnimation false
                val w = metrics.tabWidth
                if (w <= 0f || metrics.totalWidth <= 0f) return@DampedDragAnimation false
                val padding = with(density) { 4f.dp.toPx() }
                val indicatorX = animation.value * w
                val touchX = if (metrics.isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    metrics.totalWidth - padding - w - indicatorX + offset.x
                }
                touchX in 0f..metrics.totalWidth
            },
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                if (target != latestSelectedIndexState.value) latestOnSelectState.value(target)
                // 纯按住当前 tab（没换页、胶囊也没离开整数位）：release() 自己收尾即可，
                // 再播一次 animateToValue 会和 release 抢同一条 press 动画，玻璃会闪。
                // 拖动后吸附回原 tab 时 value 是半程点，仍需动画把它带回整数位。
                val needsSettle = target != lastAnimatedState.intValue ||
                    abs(value - target.toFloat()) > 0.01f
                if (needsSettle) {
                    lastAnimatedState.intValue = target
                    holder.animation?.animateToValue(target.toFloat())
                }
                metrics.offsetSnapJob?.cancel()
                metrics.offsetSnapJob = null
                scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, amount ->
                val w = metrics.tabWidth
                if (w > 0f) {
                    updateValue(
                        (targetValue + amount.x / w * if (metrics.isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (items.size - 1).toFloat()),
                    )
                    metrics.offsetSnapJob?.cancel()
                    metrics.offsetSnapJob = scope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + amount.x)
                    }
                }
            },
        ).also { holder.animation = it }
    }

    // 外部切 Tab（含点按 tab 后的 selectedIndex 回流）→ 驱动液滴飞行。
    // 拖动吸附已经自己播过动画，靠 lastAnimated 去重，避免玻璃起落两次。
    LaunchedEffect(dragAnimation) {
        snapshotFlow { latestSelectedIndexState.value }.collectLatest { index ->
            val target = index.fastCoerceIn(0, items.size - 1)
            if (target != lastAnimatedState.intValue) {
                lastAnimatedState.intValue = target
                dragAnimation.animateToValue(target.toFloat())
            }
        }
    }

    // 图标高亮跟随胶囊实际位置，只在跨过 tab 边界的那一帧重组
    val highlightIndex by remember {
        derivedStateOf { dragAnimation.value.fastRoundToInt().fastCoerceIn(0, items.size - 1) }
    }

    val interactiveHighlight = remember(scope) {
        InteractiveHighlight(
            animationScope = scope,
            position = { size, gestureOffset ->
                val w = metrics.tabWidth
                val center = if (metrics.isLtr) {
                    (dragAnimation.value + 0.5f) * w + panelOffsetState.value
                } else {
                    size.width - (dragAnimation.value + 0.5f) * w + panelOffsetState.value
                }
                val gravity = tiltState.value
                val magnitudeSquared =
                    gravity.gravityX * gravity.gravityX + gravity.gravityY * gravity.gravityY
                val gx = if (magnitudeSquared > 0.01f) {
                    gravity.gravityX / sqrt(magnitudeSquared)
                } else {
                    0f
                }
                // 手指在胶囊内的偏移轻微牵引 + 重力决定镜面主光方向
                val pull = (gestureOffset.x - w / 2f) * 0.2f
                Offset(
                    (center + pull + gx * size.minDimension * 0.12f)
                        .fastCoerceIn(0f, size.width),
                    size.height / 2f,
                )
            },
        )
    }

    // DrawBackdropElement.equals 按 lambda 引用判等，update() 无条件
    // invalidateDrawCache()。lambda 换身份会重录整套取景链（滚动时底栏细抖），
    // 所以下面一律 remember 固定身份，动画值只在 draw / graphicsLayer 阶段读。
    val capsuleShape: () -> Shape = remember { { Capsule() } }
    val barEffects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(4f.dp.toPx())
            lens(24f.dp.toPx(), 24f.dp.toPx())
        }
    }
    val barLayerBlock: GraphicsLayerScope.() -> Unit = remember(dragAnimation) {
        {
            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, dragAnimation.pressProgress)
            scaleX = scale
            scaleY = scale
        }
    }
    val barSurface: DrawScope.() -> Unit = remember(containerColor) {
        { drawRect(containerColor) }
    }
    // 常驻镜面高光 + 淡投影：HyperIsland 的 baseHighlight(0.75) / dropShadow(10dp)
    val barHighlight: () -> Highlight? = remember {
        { Highlight.Default.copy(alpha = 0.75f) }
    }
    val barShadow: () -> Shadow? = remember(isLightTheme) {
        {
            Shadow(
                radius = 10f.dp,
                color = Color.Black,
                alpha = if (isLightTheme) 0.1f else 0.2f,
            )
        }
    }
    val exportEffects: BackdropEffectScope.() -> Unit = remember(dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            vibrancy()
            blur(4f.dp.toPx())
            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
        }
    }
    val exportHighlight: () -> Highlight? = remember(dragAnimation) {
        { Highlight.Default.copy(alpha = dragAnimation.pressProgress) }
    }
    val pillLens: BackdropEffectScope.() -> Unit = remember(dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            // depthEffect 对齐 HyperIsland：折射带纵深，不再是一层平面色散
            lens(
                10f.dp.toPx() * progress,
                14f.dp.toPx() * progress,
                depthEffect = true,
                chromaticAberration = true,
            )
        }
    }
    val pillHighlight: () -> Highlight? = remember(dragAnimation) {
        { Highlight.Default.copy(alpha = dragAnimation.pressProgress) }
    }
    val pillShadow: () -> Shadow? = remember(dragAnimation) {
        { Shadow(alpha = dragAnimation.pressProgress) }
    }
    val pillInnerShadow: () -> InnerShadow? = remember(dragAnimation) {
        {
            InnerShadow(
                radius = 8f.dp * dragAnimation.pressProgress,
                alpha = dragAnimation.pressProgress,
            )
        }
    }
    val pillLayerBlock: GraphicsLayerScope.() -> Unit = remember(dragAnimation) {
        {
            scaleX = dragAnimation.scaleX
            scaleY = dragAnimation.scaleY
            val velocity = dragAnimation.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
        }
    }
    val pillSurface: DrawScope.() -> Unit = remember(isLightTheme, dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            // 静止 = 灰色提示；按住/飞行 = 白玻璃气泡（透镜正对图标时也有清晰液滴）
            drawRect(
                if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                alpha = 1f - progress,
            )
            drawRect(
                if (isLightTheme) Color.White.copy(0.38f) else Color.White.copy(0.14f),
                alpha = progress,
            )
        }
    }
    val pressScale: () -> Float = remember(dragAnimation) {
        { lerp(1f, 1.2f, dragAnimation.pressProgress) }
    }
    val combinedBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else {
        null
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / items.size
        }

        // Row A：可见底栏。手势已全部收归胶囊，这里只负责取景 + tab 点击。
        Row(
            Modifier
                .onGloballyPositioned { coordinates ->
                    val w = coordinates.size.width.toFloat()
                    totalWidthState.floatValue = w
                    val tabWidthMeasured = ((w - with(density) { 8f.dp.toPx() }) / items.size)
                        .coerceAtLeast(0f)
                    metrics.totalWidth = w
                    metrics.tabWidth = tabWidthMeasured
                }
                .graphicsLayer { translationX = panelOffsetState.value }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = capsuleShape,
                            effects = barEffects,
                            highlight = barHighlight,
                            shadow = barShadow,
                            layerBlock = barLayerBlock,
                            onDrawSurface = barSurface,
                        )
                    } else {
                        Modifier.background(containerColor, Capsule())
                    },
                )
                .then(interactiveHighlight.modifier)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                )
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
                    // 直接上报换页：selectedIndex 回流后由 snapshotFlow 统一驱动液滴飞行
                    onClick = { latestOnSelectState.value(i) },
                )
            }
        }

        // Row B：隐形导出层（图标染成强调色录进 tabsBackdrop）
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffsetState.value }
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
            // 前两个会把 tabsBackdrop 染成实心蓝，第三个会拦掉 Row A 的真实点按。
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

        // 胶囊：手势 + 高光 + 一切玻璃效果。按住当前 tab 时手指正落在这块上，
        // inspectDragGestures 的 Initial pass 立刻 press() —— 这是本次修复的关键。
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (metrics.isLtr) {
                        dragAnimation.value * tabWidth + panelOffsetState.value
                    } else {
                        size.width - (dragAnimation.value + 1f) * tabWidth +
                            panelOffsetState.value
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dragAnimation.modifier)
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
        if (item.iconPainter != null) {
            // 位图图标（产品 Logo）同样跟随 tint：SrcIn 只保留 alpha，颜色取 tint，
            // 与矢量图标一样能随选中态变主色 / 变灰。
            Image(
                painter = item.iconPainter,
                contentDescription = item.contentDescription ?: item.label,
                modifier = Modifier.size(28.dp),
                colorFilter = ColorFilter.tint(tint),
            )
        } else {
            Icon(
                imageVector = item.icon,
                contentDescription = item.contentDescription ?: item.label,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
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
