package com.kira.kiba.ui.common

import com.kira.kiba.core.account.RatingHistory

/**
 * Rating 曲线的纯几何计算（无 Android / Compose 依赖，JVM 单测直测）。
 * 见 [buildPoints] 的画法约定。
 */

/** 曲线上的一个点：画布坐标 + 关联的记录（点击命中要用回数据）。 */
internal data class TrendPoint(
    val x: Float,
    val y: Float,
    val entry: RatingHistory.Entry,
)

/**
 * 均分 X、自适应 Y 的点位计算。
 *
 * - X 按记录序号均分（不按真实时间比例：一天连打五次更新按时间画会挤成一坨）；
 * - y 窗口 = [min - span*0.15, max + span*0.15]；span = 0（全部同值）时给 ±10 假窗，
 *   保证水平线不至于贴在画布边缘；
 * - 单点时居中（画不出线的曲线至少能点出这一次的记录）。
 */
internal fun buildPoints(
    entries: List<RatingHistory.Entry>,
    widthPx: Float,
    heightPx: Float,
    padH: Float,
    padTop: Float,
    padBottom: Float,
): List<TrendPoint> {
    if (entries.isEmpty()) return emptyList()
    val ratings = entries.map { it.rating }
    val rawMin = ratings.min()
    val rawMax = ratings.max()
    var span = (rawMax - rawMin).toFloat()
    if (span == 0f) span = if (rawMax > 60) rawMax * 0.04f else 20f
    val yMin = rawMin - span * 0.15f
    val yMax = rawMax + span * 0.15f
    val plotW = (widthPx - padH * 2).coerceAtLeast(1f)
    val plotH = (heightPx - padTop - padBottom).coerceAtLeast(1f)

    return entries.mapIndexed { i, e ->
        val x = if (entries.size == 1) widthPx / 2f else padH + plotW * i / (entries.size - 1)
        val y = padTop + plotH * (1f - (e.rating - yMin) / (yMax - yMin))
        TrendPoint(x, y, e)
    }
}
