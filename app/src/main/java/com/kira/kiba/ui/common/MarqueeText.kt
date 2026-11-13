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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * 滚动位移走 draw 阶段（graphicsLayer lambda 读 Animatable），不触发重组；
 * 遮罩刷子按需要重建，同样只在 draw 阶段读状态。
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

    // 文本理想宽度（px，来自 multiParagraph，不受布局约束截断）
    var textWidthPx by remember { mutableIntStateOf(0) }
    // 视口宽度（本组件被给到的宽度）
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var overflow by remember { mutableStateOf(false) }

    val offset = remember { Animatable(0f) }
    // 是否处于「滚动中」（决定遮罩形态）；用 derivedStateOf 让 draw 阶段按需读取
    val isScrolling = remember {
        derivedStateOf { offset.value > 0.5f }
    }

    LaunchedEffect(text, overflow, textWidthPx, viewportWidthPx) {
        // 几何变化导致 effect 重启时，先回到起点：杜绝沿用中途 offset 继续滚。
        offset.snapTo(0f)
        while (true) {
            // 每轮滚动前重读最新几何（textWidth/viewport 成对更新）。万一状态晚到，
            // 下一轮自动用上正确值，不会带着失真距离一路滚到底。
            val distance = (textWidthPx - viewportWidthPx).toFloat()
            if (!overflow || textWidthPx <= 0 || viewportWidthPx <= 0 || distance <= 0f) {
                offset.snapTo(0f)
                return@LaunchedEffect
            }
            val speedPx = SCROLL_SPEED_DP_PER_SEC * density.density
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
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 仅在溢出时加淡出遮罩：静止 = 右缘；滚动 = 两侧
                if (overflow && textWidthPx > 0 && viewportWidthPx > 0) {
                    val w = viewportWidthPx.toFloat()
                    val fade = minOf(
                        FADE_WIDTH.toPx() * density.density,
                        w / 2f,
                    )
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
        androidx.compose.material3.Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            style = style ?: TextStyle.Default,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout: TextLayoutResult ->
                textWidthPx = layout.multiParagraph.width.roundToInt()
                overflow = layout.hasVisualOverflow
            },
            modifier = Modifier
                // 视口宽度取自 Text 自身（与 onTextLayout 同一元素、同一次布局成对触发）：
                // textWidth/viewport 永远来自同一批布局结果，不会出现一个新一个旧的
                // 失配对——失配会让滚动距离超长，滚出文本尾部进空白区。
                .onSizeChanged { viewportWidthPx = it.width }
                .graphicsLayer {
                    // 兜底钳制：位移永不越过「文本尾对齐视口右缘」的位置，
                    // 即使动画目标短暂失真也不会滚出文本尾部进空白区。
                    val maxScroll = (textWidthPx - viewportWidthPx).coerceAtLeast(0).toFloat()
                    translationX = -offset.value.coerceIn(0f, maxScroll)
                },
        )
    }
}

/** 滚回开头后的停留时长。 */
private val RESTART_DELAY = 5_000L

/** 滚到末尾后的短暂停留。 */
private val END_HOLD = 600L

/** 滚动速度（dp/s）。 */
private const val SCROLL_SPEED_DP_PER_SEC = 45f

/** 单次滚动时长上下限（毫秒）。 */
private const val MIN_SCROLL_MS = 1_000
private const val MAX_SCROLL_MS = 12_000

/** 滚回开头的速度倍率（回程略快）。 */
private const val BACK_SPEED_FACTOR = 1.6f

/** 淡出遮罩宽度。 */
private val FADE_WIDTH: Dp = 28.dp
