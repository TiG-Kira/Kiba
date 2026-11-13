package com.kira.kiba.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 单行跑马灯文字：内容超出一行时循环滚动把内容显示完，行为与普通单行
 * [androidx.compose.material3.Text] 一致（不溢出时完全无差异）。
 *
 * - 每次滚回开头后停留 [RESTART_DELAY]（5 秒）再开始下一轮滚动；
 * - 滚到末尾后短暂停留 [END_HOLD]，再滚回开头；
 * - 滚动速度恒定 [SCROLL_SPEED_DP_PER_SEC]；
 * - **淡出遮罩**：静止（含 5 秒等待期）时右缘淡出（替代省略号）；
 *   滚动时左右两侧均淡出，遮罩宽 [FADE_WIDTH]；
 * - 不溢出时不绘制任何遮罩，观感与普通 Text 一致。
 *
 * 布局结构（防「滚进空白区」的关键）：
 * - 外层 Box = 视口，唯一裁切点（clipToBounds），宽度 = 组件被给到的宽度；
 * - 内层 Text 用 **无限宽度约束** 测量，尺寸 = 文本理想宽度 → 整行字符
 *   全部布局、全部绘制。若让 Text 直接继承视口约束，`overflow=Clip` 会把
 *   绘制裁在其布局宽度处，后半段字符根本不画，滚动却按全文距离进行 →
 *   滚出绘制区后只剩空白；
 * - 裁切只发生在视口，滚动位移在 draw 阶段钳制为不越过文本尾部。
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle? = null,
) {
    val density = LocalDensity.current

    // 文本理想宽度（px）——来自无限宽度测量下的 Text 自身尺寸
    var textWidthPx by remember { mutableIntStateOf(0) }
    // 视口宽度（px）——外层 Box 被给到的宽度
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    val offset = remember { Animatable(0f) }
    // 是否处于「滚动中」（决定遮罩形态）；用 derivedStateOf 让 draw 阶段按需读取
    val isScrolling = remember {
        derivedStateOf { offset.value > 0.5f }
    }

    LaunchedEffect(text) {
        // 只按 text 重启：几何变化绝不重启（否则几何/组合抖动时每次都
        // snapTo(0)，滚动画永远起不来，观感就是「永远截断在起点」）。
        // 几何未就绪或不溢出时轮询等待，几何到达后自动开始滚动。
        offset.snapTo(0f)
        while (true) {
            val tw = textWidthPx
            val vw = viewportWidthPx
            val distance = (tw - vw).toFloat()
            if (tw <= 0 || vw <= 0 || distance <= 0f) {
                offset.snapTo(0f)
                delay(GEOMETRY_POLL_MS)
                continue
            }
            val speedPx = SCROLL_SPEED_DP_PER_SEC * density.density
            // 时长按真实距离计算：小溢出量也能明显滚完，仅设很低下限防闪帧。
            val durationMs = (distance / speedPx * 1000f).roundToInt()
                .coerceIn(MIN_SCROLL_MS, MAX_SCROLL_MS)
            val backMs = (durationMs * BACK_SPEED_FACTOR).roundToInt()
            delay(RESTART_DELAY)
            offset.animateTo(distance, tween(durationMs, easing = LinearEasing))
            delay(END_HOLD)
            offset.animateTo(0f, tween(backMs, easing = LinearEasing))
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportWidthPx = it.width }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 仅在溢出时加淡出遮罩：静止 = 右缘；滚动 = 两侧。
                // 溢出判定 = 文本理想宽 > 视口宽（两个尺寸都来自真实测量）。
                val w = viewportWidthPx.toFloat()
                if (w > 0f && textWidthPx > viewportWidthPx) {
                    val overflowPx = (textWidthPx - viewportWidthPx).toFloat()
                    // 淡出带统一自适应：取「标准宽度 / 溢出量 / 半视口」的最小值。
                    // - 小溢出量：淡出带缩到溢出量，只隐住超出部分，不吞可见文字；
                    // - 大溢出量：淡出带就是标准宽度，贴视口右缘——绝不是随
                    //   文本尾移动（文本尾在视口外时按尾定位会让整行线性淡出）。
                    // 注意 .toPx() 已含 density，不再另乘。
                    val fade = minOf(FADE_WIDTH.toPx(), overflowPx, w / 2f)
                    val brush = if (isScrolling.value) {
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            (fade / w) to Color.White,
                            ((w - fade) / w) to Color.White,
                            1f to Color.Transparent,
                        )
                    } else {
                        Brush.horizontalGradient(
                            0f to Color.White,
                            ((w - fade) / w) to Color.White,
                            1f to Color.Transparent,
                        )
                    }
                    drawRect(brush, blendMode = BlendMode.DstIn)
                }
            },
    ) {
        // 自定义 measure：给 Text 无限宽度约束测量，保证整行完整布局与绘制；
        // 组件自身尺寸仍跟随外部约束（固定宽 = 视口宽，wrap 内容 = 文本宽）。
        Layout(
            content = {
                androidx.compose.material3.Text(
                    text = text,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    style = style ?: TextStyle.Default,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        // Text 以无限宽测量，其尺寸即文本理想宽度（全部字符）
                        .onSizeChanged { textWidthPx = it.width }
                        .graphicsLayer {
                            // 兜底钳制：位移永不越过「文本尾对齐视口右缘」的位置，
                            // 即使动画目标短暂失真也不会滚出文本尾部进空白区。
                            val maxScroll =
                                (textWidthPx - viewportWidthPx).coerceAtLeast(0).toFloat()
                            translationX = -offset.value.coerceIn(0f, maxScroll)
                        },
                )
            },
            measurePolicy = { measurables, constraints ->
                val placeable = measurables[0].measure(
                    // 关键：无限宽度。文本永不折行也不被约束截断，完整绘制。
                    Constraints(
                        minWidth = 0,
                        maxWidth = Constraints.Infinity,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight,
                    )
                )
                val w = when {
                    constraints.hasFixedWidth -> constraints.maxWidth
                    constraints.hasBoundedWidth ->
                        minOf(placeable.width, constraints.maxWidth)
                    else -> placeable.width
                }
                val h = if (constraints.hasFixedHeight) {
                    constraints.maxHeight
                } else {
                    placeable.height
                }
                layout(w, h) {
                    placeable.place(0, (h - placeable.height) / 2)
                }
            },
        )
    }
}

/** 几何未就绪/暂不溢出时的轮询间隔。 */
private const val GEOMETRY_POLL_MS = 200L

/** 滚回开头后的停留时长。 */
private val RESTART_DELAY = 5_000L

/** 滚到末尾后的短暂停留。 */
private val END_HOLD = 600L

/** 滚动速度（dp/s）。 */
private const val SCROLL_SPEED_DP_PER_SEC = 45f

/** 单次滚动时长上下限（毫秒）。下限仅防闪帧，小溢出量也如实快滚。 */
private const val MIN_SCROLL_MS = 350
private const val MAX_SCROLL_MS = 12_000

/** 滚回开头的速度倍率（回程略快）。 */
private const val BACK_SPEED_FACTOR = 1.6f

/** 淡出遮罩宽度。 */
private val FADE_WIDTH: Dp = 28.dp
