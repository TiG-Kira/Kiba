package com.kira.kiba.core.account

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 裁切结果：源图上的一个正方形（整数像素）。
 */
data class CropRect(val left: Int, val top: Int, val size: Int)

/**
 * 1:1 头像裁切的几何计算 —— **纯函数，不碰 Android / Compose**，由单测覆盖。
 *
 * ## 坐标系
 *
 * - 源图坐标：`(0, 0)` 到 `(imageW, imageH)`，单位是原图像素；
 * - 取景框坐标：正方形，`(0, 0)` 到 `(frame, frame)`，单位是屏幕像素；
 * - `offsetX/offsetY`：图片中心相对取景框中心的位移（屏幕像素，右/下为正）。
 *
 * 图片按 `s = baseScale × zoom` 缩放后居中画在取景框中心 + offset 处。于是取景框
 * 左上角对应的源图坐标是：
 *
 * ```
 * srcX = imageW / 2 - (frame / 2 + offsetX) / s
 * size = frame / s
 * ```
 *
 * 当 `zoom = 1`、`offset = 0` 时，`s` 取「铺满」缩放，算出来正好是源图正中间那块
 * 边长为 `frame / s = 短边` 的正方形 —— 也就是大多数裁切器的初始状态。
 */
object AvatarCrop {

    /** 最大放大倍数。再大就只是把一个像素糊成一片，没有意义。 */
    const val MAX_ZOOM = 5f

    /** 铺满取景框所需的最小缩放（屏幕像素 / 源像素）。 */
    fun baseScale(frame: Float, imageW: Int, imageH: Int): Float {
        if (frame <= 0f || imageW <= 0 || imageH <= 0) return 1f
        return max(frame / imageW, frame / imageH)
    }

    /** 实际缩放 = 铺满缩放 × 用户放大倍数（zoom 恒 ≥ 1，不允许缩到露出白边）。 */
    fun effectiveScale(frame: Float, imageW: Int, imageH: Int, zoom: Float): Float =
        baseScale(frame, imageW, imageH) * zoom.coerceIn(1f, MAX_ZOOM)

    /**
     * 平移的上限（x, y 的正值，双向对称）。
     *
     * 图片必须始终盖满取景框，否则取景框里会出现空白（裁出来是一块透明）。
     * 图片比取景框大多少，就允许多平移多少 —— 多出来的部分才是「可拖动的余量」。
     */
    fun maxOffset(frame: Float, imageW: Int, imageH: Int, zoom: Float): Pair<Float, Float> {
        val s = effectiveScale(frame, imageW, imageH, zoom)
        return Pair(
            ((imageW * s - frame) / 2f).coerceAtLeast(0f),
            ((imageH * s - frame) / 2f).coerceAtLeast(0f),
        )
    }

    /** 把平移量夹回合法范围（拖动时每帧调用）。 */
    fun clampOffset(
        offsetX: Float,
        offsetY: Float,
        frame: Float,
        imageW: Int,
        imageH: Int,
        zoom: Float,
    ): Pair<Float, Float> {
        val (mx, my) = maxOffset(frame, imageW, imageH, zoom)
        return Pair(offsetX.coerceIn(-mx, mx), offsetY.coerceIn(-my, my))
    }

    /**
     * 算出当前取景框落在源图上的正方形。
     *
     * 结果一定在源图范围内：`size` 取 `floor(frame / s)`（向下取整，不会多出半个像素），
     * `left/top` 四舍五入后再夹到 `[0, 边 - size]`。所以调用方拿去做
     * `Bitmap.createBitmap` 那类按像素切图的 API 不需要再判越界。
     */
    fun cropRect(
        offsetX: Float,
        offsetY: Float,
        frame: Float,
        imageW: Int,
        imageH: Int,
        zoom: Float,
    ): CropRect {
        if (imageW <= 0 || imageH <= 0) return CropRect(0, 0, 1)
        val s = effectiveScale(frame, imageW, imageH, zoom)
        if (s <= 0f) return CropRect(0, 0, min(imageW, imageH))

        val size = floor(frame / s).toInt().coerceIn(1, min(imageW, imageH))
        val rawLeft = imageW / 2f - (frame / 2f + offsetX) / s
        val rawTop = imageH / 2f - (frame / 2f + offsetY) / s
        return CropRect(
            left = rawLeft.roundToInt().coerceIn(0, imageW - size),
            top = rawTop.roundToInt().coerceIn(0, imageH - size),
            size = size,
        )
    }
}
