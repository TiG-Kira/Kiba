package com.kira.kiba.core.best50

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sinmai（游戏本体）TMP 字体的位图实现 —— 姓名牌与 DX 分数的字形直接取自游戏资源。
 *
 * 资源由 `tools/nameplate_font_pack.py` 打包：
 * - `tmp_morisawa_r`   Morisawa UD 新ゴ SC-R，`TMP_Name` / `TMP_Title` 指定的主字体（日文/汉字）
 * - `tmp_liberation`   LiberationSans SDF，TMP 的 fallback —— 游戏里拉丁字母实际由它出字形
 * - `tmp_newrodin_eb`  SEGA NewRodinN v2-EB，**达成率**（`99.9999%`）用的数字字体，
 *   只打包了 0-9 / `.` / `%` / `±` 这些字形
 *
 * 排版与 TMP 一致：`pen += bearingX`，字形左上角 `(pen + bx*scale, baseline - by*scale)`，
 * 之后 `pen += advance*scale`，`scale = size / pointSize`（每个字形用它自己所属字体的 scale，
 * 这样 fallback 混排时字距才对）。
 */
class TmpFont private constructor(
    val pointSize: Int,
    val ascent: Float,
    val descent: Float,
    internal val atlas: Bitmap,
    private val glyphs: Map<Int, Glyph>,
    private val fallback: TmpFont?,
) {

    class Glyph(
        val rx: Int,
        val ry: Int,
        val rw: Int,
        val rh: Int,
        val w: Float,
        val h: Float,
        val bx: Float,
        val by: Float,
        val adv: Float,
    )

    private val src = Rect()
    private val dst = RectF()

    /** 命中字形时连同它所属的字体一起返回（fallback 的 scale 不一样）。 */
    fun find(codePoint: Int): Pair<TmpFont, Glyph>? {
        glyphs[codePoint]?.let { return this to it }
        return fallback?.find(codePoint)
    }

    fun glyph(codePoint: Int): Glyph? = glyphs[codePoint] ?: fallback?.glyph(codePoint)

    /** 整串前进宽度；[cell] > 0 时走等宽槽（游戏姓名就是每字符一个 1em 槽）。 */
    fun advance(text: CharSequence, size: Float, cell: Float = 0f): Float {
        var total = 0f
        text.forEach { ch ->
            if (cell > 0f) {
                total += cell
            } else {
                val (f, g) = find(ch.code) ?: return@forEach
                total += g.adv * (size / f.pointSize)
            }
        }
        return total
    }

    /** 墨迹上下沿（相对基线，向上为正），用于视觉垂直居中。 */    fun inkBounds(text: CharSequence, size: Float): FloatArray {
        var top = Float.NEGATIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        text.forEach { ch ->
            val (f, g) = find(ch.code) ?: return@forEach
            val s = size / f.pointSize
            top = maxOf(top, g.by * s)
            bottom = minOf(bottom, (g.by - g.h) * s)
        }
        if (top == Float.NEGATIVE_INFINITY) return floatArrayOf(0f, 0f)
        return floatArrayOf(top, bottom)
    }

    /** 基线在「行顶→行底」里的比例（0..1）。Compose 侧的自定义对齐线直接用它。 */
    fun baselineRatio(): Float {
        val h = ascent - descent
        return if (h > 0f) ascent / h else 0.8f
    }

    /** 单行高度（与 [size] 同单位）。 */
    fun lineHeight(size: Float): Float = (ascent - descent) * size / pointSize

    /** 基线上沿到行顶的距离（与 [size] 同单位）。 */
    fun ascentOf(size: Float): Float = ascent * size / pointSize

    /**
     * 逐字绘制。[cell] > 0 时走等宽槽，否则按字形 advance 正常前进。
     * [paint] 只需要给 `color`：图集是 ALPHA_8 遮罩，颜色由 paint 决定。
     *
     * [systemFallback] 非 null 时，图集缺失的字形（拉丁 / 全角 / 生僻符号等
     * Morisawa 与 Liberation 都没有的字符）用系统字体绘制——绝对不吞字：
     * 曾因设备端 Liberation 图集加载失败导致昵称字母整段空白。
     */
    fun draw(
        canvas: Canvas,
        text: CharSequence,
        x: Float,
        baseline: Float,
        size: Float,
        paint: Paint,
        cell: Float = 0f,
        systemFallback: Paint? = null,
    ) {
        val fb = systemFallback?.let { Paint(it).apply { textSize = size } }
        var pen = x
        text.forEach { ch ->
            val hit = find(ch.code)
            if (hit != null) {
                val (f, g) = hit
                val s = size / f.pointSize
                val gx = pen + g.bx * s
                val gy = baseline - g.by * s
                src.set(g.rx, g.ry, g.rx + g.rw, g.ry + g.rh)
                dst.set(gx, gy, gx + g.w * s, gy + g.h * s)
                canvas.drawBitmap(f.atlas, src, dst, paint)
                pen += if (cell > 0f) cell else g.adv * s
            } else if (fb != null && !ch.isWhitespace()) {
                canvas.drawText(ch.toString(), pen, baseline, fb)
                pen += if (cell > 0f) cell else fb.measureText(ch.toString())
            } else if (cell > 0f) {
                pen += cell
            } else {
                pen += size * 0.5f
            }
        }
    }

    companion object {
        private const val MAGIC = 0x3146424B // "KBF1"

        /** 进程级缓存；[name] 是 assets/font 下的文件名（不含扩展名）。 */
        private val cache = HashMap<String, TmpFont>()

        /**
         * 加载并缓存。[fallback] 只在首次加载时生效——缓存命中时忽略
         * （调用方总是成对传入，语义一致）。加锁：B50 页面渲染（主线程）与
         * 分享图生成（IO 线程）可能并发首次加载，HashMap 裸并发会出脏读。
         * 加载失败打日志——图集缺失时 fallback 链断掉的表现是「某类字符整段
         * 空白」，没有这行日志排查无从下手。
         */
        @Synchronized
        fun load(context: Context, name: String, fallback: TmpFont? = null): TmpFont? {
            cache[name]?.let { return it }
            val font = runCatching { read(context, name, fallback) }
                .onFailure {
                    android.util.Log.w(
                        "KibaB50",
                        "TmpFont 加载失败: $name (fallback=${fallback != null})",
                        it,
                    )
                }
                .getOrNull()
            font?.let { cache[name] = it }
            return font
        }

        /** 姓名牌口径的字体：Morisawa 主字体 + LiberationSans fallback。 */
        fun namePlate(context: Context): TmpFont? =
            load(context, "tmp_morisawa_r", load(context, "tmp_liberation"))

        /**
         * 达成率口径的字体：SEGA NewRodinN v2-EB。
         *
         * B50 卡片与导出图里的 `99.9999%` 都用它 —— 也就是用户截图里那排又粗又圆的数字。
         * 只打包了数字 / 小数点 / 百分号，别的字符由 Liberation 兜（正常用不到）。
         */
        fun achievements(context: Context): TmpFont? =
            load(context, "tmp_newrodin_eb", load(context, "tmp_liberation"))

        // 注：SEGA_NewRodinN EB 是**达成率**的数字字体，不是 DX 评分数字。
        // 姓名牌里的 DX 评分走 `SpriteCounter` 的 UI_CMN_Num_26p 精灵（见 NamePlateRenderer）。
        private fun read(context: Context, name: String, fallback: TmpFont?): TmpFont {
            val bytes = context.assets.open("font/$name.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.int == MAGIC) { "bad font magic: $name" }
            val pointSize = bb.int
            val ascent = bb.float
            val descent = bb.float
            bb.float // lineHeight
            bb.float // capLine
            bb.float // meanLine
            bb.float // baseline
            val count = bb.int
            val atlasW = bb.int
            val atlasH = bb.int
            val glyphs = HashMap<Int, Glyph>(count * 2)
            repeat(count) {
                val cp = bb.int
                val rx = bb.short.toInt() and 0xFFFF
                val ry = bb.short.toInt() and 0xFFFF
                val rw = bb.short.toInt() and 0xFFFF
                val rh = bb.short.toInt() and 0xFFFF
                glyphs[cp] = Glyph(
                    rx = rx, ry = ry, rw = rw, rh = rh,
                    w = bb.float, h = bb.float, bx = bb.float, by = bb.float, adv = bb.float,
                )
            }
            val atlas = loadMask(context, name, atlasW, atlasH)
            return TmpFont(pointSize, ascent, descent, atlas, glyphs, fallback)
        }

        /**
         * 图集是灰度 WebP（导出时已把 SDF 转成抗锯齿 alpha），这里转成 ALPHA_8 遮罩：
         * 绘制时颜色完全由 paint 决定，换色不用重新解图，内存也只有 1/4。
         */
        private fun loadMask(context: Context, name: String, w: Int, h: Int): Bitmap {
            val gray = context.assets.open("font/$name.webp").use {
                BitmapFactory.decodeStream(it)
            } ?: error("font atlas missing: $name")
            val gw = gray.width
            val gh = gray.height
            require(gw == w && gh == h) { "atlas size $gw x $gh != $w x $h ($name)" }
            val px = IntArray(gw * gh)
            gray.getPixels(px, 0, gw, 0, 0, gw, gh)
            gray.recycle()
            // 灰度图 r=g=b：取红通道当 alpha（ALPHA_8 的 setPixels 只吃 alpha 通道）
            for (i in px.indices) {
                px[i] = ((px[i] shr 16) and 0xFF) shl 24
            }
            val mask = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
            mask.setDensity(Bitmap.DENSITY_NONE)
            mask.setPixels(px, 0, gw, 0, 0, gw, gh)
            return mask
        }
    }
}
