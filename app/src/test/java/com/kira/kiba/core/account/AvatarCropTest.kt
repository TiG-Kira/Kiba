package com.kira.kiba.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [AvatarCrop] 的几何单测。
 *
 * 这里的每个数都是手算的：改裁切逻辑前先让这些 case 挂掉，比上真机用眼睛看快得多
 * （尤其「拖到边缘会不会裁出界」这类，UI 上几乎看不出来，但会得到一张带透明边的图）。
 */
class AvatarCropTest {

    /** 横图（400×300）铺进 300 的方框：铺满是 1:1，左右各留 50 的可拖余量。 */
    @Test
    fun `横图居中时裁中间正方形`() {
        val rect = AvatarCrop.cropRect(0f, 0f, frame = 300f, imageW = 400, imageH = 300, zoom = 1f)
        assertEquals(50, rect.left)
        assertEquals(0, rect.top)
        assertEquals(300, rect.size)
    }

    /** 竖图镜像：上下留余量。 */
    @Test
    fun `竖图居中时裁中间正方形`() {
        val rect = AvatarCrop.cropRect(0f, 0f, frame = 300f, imageW = 300, imageH = 400, zoom = 1f)
        assertEquals(0, rect.left)
        assertEquals(50, rect.top)
        assertEquals(300, rect.size)
    }

    /** 小图（100×100）铺进 300 的框要放大 3 倍，最终裁的是整张图。 */
    @Test
    fun `小图被放大到铺满后裁整张`() {
        val rect = AvatarCrop.cropRect(0f, 0f, frame = 300f, imageW = 100, imageH = 100, zoom = 1f)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(100, rect.size)
    }

    /** 放大 2 倍后，取景框只覆盖源图的一半。 */
    @Test
    fun `放大两倍后只裁一半`() {
        val rect = AvatarCrop.cropRect(0f, 0f, frame = 300f, imageW = 100, imageH = 100, zoom = 2f)
        assertEquals(25, rect.left)
        assertEquals(25, rect.top)
        assertEquals(50, rect.size)
    }

    /** 平移量被夹在「图片盖满取景框」的范围内，不会拖出白边。 */
    @Test
    fun `平移超界会被夹回`() {
        val (maxX, maxY) = AvatarCrop.maxOffset(frame = 300f, imageW = 400, imageH = 300, zoom = 1f)
        assertEquals(50f, maxX, 0.01f)
        assertEquals(0f, maxY, 0.01f)

        val (x, y) = AvatarCrop.clampOffset(999f, 999f, 300f, 400, 300, 1f)
        assertEquals(50f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    /** 图片右移 = 取景框相对左移 = 裁出来的是源图左侧那一块。 */
    @Test
    fun `右移到底时裁到图的最左边`() {
        val rect = AvatarCrop.cropRect(50f, 0f, frame = 300f, imageW = 400, imageH = 300, zoom = 1f)
        assertEquals(0, rect.left)
        assertEquals(300, rect.size)
    }

    /** 任意 offset / zoom 组合都不能裁出图外（否则会得到一张带透明边的头像）。 */
    @Test
    fun `任何参数组合都落在图内`() {
        val cases = listOf(
            400 to 300,
            300 to 400,
            100 to 100,
            1920 to 1080,
            1080 to 1920,
            7 to 500,
            500 to 7,
        )
        val offsets = listOf(-9999f, -300f, -1f, 0f, 1f, 300f, 9999f)
        val zooms = listOf(1f, 1.3f, 2f, AvatarCrop.MAX_ZOOM, 99f) // 99 会被 MAX_ZOOM 夹住
        for ((w, h) in cases) {
            for (ox in offsets) {
                for (oy in offsets) {
                    for (z in zooms) {
                        val rect = AvatarCrop.cropRect(ox, oy, 300f, w, h, z)
                        assertTrue(
                            "越界: img=${w}x$h off=($ox,$oy) zoom=$z -> $rect",
                            rect.left >= 0 && rect.top >= 0 && rect.size >= 1 &&
                                rect.left + rect.size <= w && rect.top + rect.size <= h,
                        )
                    }
                }
            }
        }
    }

    /** zoom 小于 1 会被抬到 1，保证图始终铺满（不允许出现白边）。 */
    @Test
    fun `缩放不会小于铺满`() {
        val s = AvatarCrop.effectiveScale(300f, 400, 300, zoom = 0.2f)
        assertEquals(1f, s, 0.001f)
        val rect = AvatarCrop.cropRect(0f, 0f, 300f, 400, 300, 0.2f)
        assertEquals(300, rect.size)
    }

    /** 极端长条图也不该算出 0 或负数边长。 */
    @Test
    fun `长条图边长至少一像素`() {
        val rect = AvatarCrop.cropRect(0f, 0f, frame = 300f, imageW = 5000, imageH = 3, zoom = AvatarCrop.MAX_ZOOM)
        assertTrue(rect.size >= 1)
        assertTrue(abs(rect.top) <= 3)
    }
}
