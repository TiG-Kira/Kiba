package com.kira.kiba.ui.common

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.kiba.core.account.RatingHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Rating 变化曲线卡（B50 页完整曲线 / 「我」页最近 10 次）。
 *
 * 画法约定：
 * - X 轴按记录序号均分，不按真实时间比例——一天连打五次更新时按时间画会挤成一坨，
 *   均分才能看清每一次的变化量；
 * - Y 轴自适应 min/max 并各留 15% 余量，所有点同值时给 ±10 的假窗；
 * - `dropped` 点（新版本初始分数）：折线在此**断开分段** + 竖虚线分割符，
 *   点用警示色放大，上方标「新版本」小字与 ⓘ 图标；
 * - 点击命中任意点：dropped 点 toast 版本回落说明，普通点 toast 时间与 Rating。
 */

/** 新版本回落点警示色：暖橙，深浅主题下都清晰（miuix 色板无对应语义色）。 */
private val DropColor = Color(0xFFF59E0B)

private val DividerColor = Color(0x66888888)

@Composable
fun RatingTrendCard(
    title: String,
    entries: List<RatingHistory.Entry>,
    subtitle: String,
    modifier: Modifier = Modifier,
    /**
     * 数值格式化：舞萌是整数（16000），中二保留两位小数（16.23）。
     * 不传就按整数走，保持旧调用点行为不变。
     */
    valueText: (Double) -> String = { it.toInt().toString() },
) {
    val context = LocalContext.current
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val surface = MiuixTheme.colorScheme.onSurface

    val canvasHeight = 172.dp
    val textMeasurer = rememberTextMeasurer()
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    color = surface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = secondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                // 画布几何（px）在组合期算好，绘制与点击共用同一份点位，
                // 避免在 draw 阶段反算或写 state
                val padH = with(density) { 14.dp.toPx() }
                val padTop = with(density) { 28.dp.toPx() }
                val padBottom = with(density) { 20.dp.toPx() }
                val widthPx = constraints.maxWidth.toFloat()
                val heightPx = with(density) { canvasHeight.toPx() }
                val hitR = with(density) { 22.dp.toPx() }

                val points = remember(entries, widthPx, heightPx, padH, padTop, padBottom) {
                    buildPoints(entries, widthPx, heightPx, padH, padTop, padBottom)
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(canvasHeight)
                        .pointerInput(points) {
                            detectTapGestures { pos ->
                                val hit = points.minByOrNull {
                                    (it.x - pos.x) * (it.x - pos.x) + (it.y - pos.y) * (it.y - pos.y)
                                } ?: return@detectTapGestures
                                val dx = hit.x - pos.x
                                val dy = hit.y - pos.y
                                if (dx * dx + dy * dy > hitR * hitR) return@detectTapGestures
                                val msg = if (hit.entry.dropped) {
                                    // 显式短行：部分 ROM 给 Toast 设了 maxLines + 横向省略，
                                    // 一整句长文案会被截断（真机反证）
                                    "新版本初始分数 ${hit.entry.rating}\n" +
                                        "因新版本更新后新曲 Best 15 变化，\n" +
                                        "分数有所下降，属于更新后正常现象。"
                                } else {
                                    "${timeFmt.format(Date(hit.entry.ts))} · Rating ${valueText(hit.entry.rating)}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                ) {
                    if (points.isEmpty()) return@Canvas

                    // 「新版本」标注是否画在点上方（下方空间不够时画下方）
                    fun labelAbove(p: TrendPoint) = p.y - 30.dp.toPx() >= padTop * 0.2f

                    // ── 分割符：每个 dropped 点一条竖虚线（旧版本历史 | 新版本历史）──
                    // 起点避开标注文字：标注在上方时虚线从文字下缘开始，不穿过「新版本①」
                    points.filter { it.entry.dropped }.forEach { p ->
                        val top = if (labelAbove(p)) p.y - 34.dp.toPx() else p.y + 34.dp.toPx()
                        drawLine(
                            color = DividerColor,
                            start = Offset(p.x, top),
                            end = Offset(p.x, size.height - 4.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        )
                    }

                    // ── 折线：dropped 点与其前一点之间断开（分割），dropped 点是新段起点 ──
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val cur = points[i]
                        if (cur.entry.dropped) continue
                        drawLine(
                            color = primary,
                            start = Offset(prev.x, prev.y),
                            end = Offset(cur.x, cur.y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }

                    // ── 圆点 ──
                    points.forEach { p ->
                        if (p.entry.dropped) {
                            drawCircle(DropColor, radius = 5.dp.toPx(), center = Offset(p.x, p.y))
                            drawCircle(
                                Color.White,
                                radius = 2.dp.toPx(),
                                center = Offset(p.x, p.y),
                            )
                        } else {
                            drawCircle(primary, radius = 3.dp.toPx(), center = Offset(p.x, p.y))
                        }
                    }

                    // ── dropped 点标注：「新版本」小字 + ⓘ 图标（以点为水平中心）──
                    // 颜色必须显式给：TextStyle 不带 color 时 drawText 走默认黑，
                    // 深色主题下直接隐身（真机反证）。「新版本」用警示橙，数值用 onSurface
                    //（深色下近白、浅色下近黑，跟主题走）。
                    val labelStyle = TextStyle(fontSize = 10.sp, color = DropColor)
                    val valueStyle = TextStyle(fontSize = 10.sp, color = surface)
                    points.filter { it.entry.dropped }.forEach { p ->
                        val label = textMeasurer.measure("新版本", labelStyle)
                        val iconR = 5.dp.toPx()
                        val totalW = label.size.width + 4.dp.toPx() + iconR * 2
                        val textX = (p.x - totalW / 2).coerceIn(0f, widthPx - totalW)
                        val fitsAbove = p.y - 24.dp.toPx() >= padTop * 0.2f
                        val labelTop = if (fitsAbove) p.y - 26.dp.toPx() else p.y + 10.dp.toPx()
                        drawText(label, topLeft = Offset(textX, labelTop))
                        // ⓘ：圆环 + i（点 + 竖笔），随文字基线对齐
                        val iconCenter = Offset(
                            textX + label.size.width + 4.dp.toPx() + iconR,
                            labelTop + label.size.height / 2,
                        )
                        drawCircle(
                            DropColor,
                            radius = iconR,
                            center = iconCenter,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                        drawCircle(
                            DropColor,
                            radius = 1.dp.toPx(),
                            center = Offset(iconCenter.x, iconCenter.y - iconR * 0.45f),
                        )
                        drawLine(
                            DropColor,
                            start = Offset(iconCenter.x, iconCenter.y - iconR * 0.05f),
                            end = Offset(iconCenter.x, iconCenter.y + iconR * 0.55f),
                            strokeWidth = 1.2.dp.toPx(),
                        )
                    }

                    // ── 首末点数值标注（末点画在下方，避开「新版本」标注）──
                    val first = points.first()
                    val last = points.last()
                    val firstLabel = textMeasurer.measure(
                        valueText(first.entry.rating),
                        valueStyle,
                    )
                    val lastLabel = textMeasurer.measure(
                        valueText(last.entry.rating),
                        valueStyle,
                    )
                    drawText(
                        firstLabel,
                        topLeft = Offset(
                            (first.x - firstLabel.size.width / 2f)
                                .coerceIn(0f, widthPx - firstLabel.size.width),
                            (first.y - firstLabel.size.height - 4.dp.toPx()).coerceAtLeast(0f),
                        ),
                    )
                    drawText(
                        lastLabel,
                        topLeft = Offset(
                            (last.x - lastLabel.size.width / 2f)
                                .coerceIn(0f, widthPx - lastLabel.size.width),
                            (last.y + 6.dp.toPx()).coerceAtMost(heightPx - lastLabel.size.height),
                        ),
                    )
                }
            }
        }
    }
}
