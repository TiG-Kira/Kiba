package com.kira.kiba.core.best50

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 梗图生成器 —— 移植自 KibaBot 的两个图片命令：
 * - 「低情商 X 高情商 Y」：`plugins/image.py` + `libraries/image.py`
 *   模板 `high_eq_image.png` 800×640，左右各 400px，文字压在 y=360（0.5625 处），
 *   黑底白字、单边 ≤15 字，超过 390px 宽则按比例缩字号。
 * - 「gocho A B」：`libraries/gosen_choyen.py`
 *   红 / 银两组“多层渐变描边字”，红字在上、银字在下错位叠放，最后整体水平斜切 0.4。
 *
 * 字体：Bot 用 msyh / msyhbd，这里分别退到 HOS.ttf / HOS_Bold.ttf。
 */
object MemeImageBuilder {

    /** 低情商 / 高情商（模板 800×640）。 */
    fun buildLowHighEq(context: Context, left: String, right: String): Bitmap? = runCatching {
        val id = context.resources.getIdentifier("kiba_high_eq_image", "drawable", context.packageName)
        if (id == 0) return null
        val img = decodeMutable(context, id) ?: return null
        val canvas = Canvas(img)
        val font = runCatching { Typeface.createFromAsset(context.assets, "fonts/HOS.ttf") }.getOrNull()
        val half = img.width / 2f
        // Bot 固定 400 宽、y=360，按模板实际宽度等比换算
        val scale = img.width / 800f
        val baseY = img.height * (360f / 640f)
        drawHalf(canvas, left, 0f, half, baseY, 48f * scale, 390f * scale, font)
        drawHalf(canvas, right, half, half, baseY, 48f * scale, 390f * scale, font)
        img
    }.getOrNull()

    /** 对应 Bot 的 draw_text(img, text, offset_x)：半区居中、超宽自动缩字号。 */
    private fun drawHalf(
        canvas: Canvas,
        text: String,
        offsetX: Float,
        halfWidth: Float,
        topY: Float,
        baseSize: Float,
        maxWidth: Float,
        font: Typeface?,
    ) {
        if (text.isBlank()) return
        var size = baseSize
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = font
            color = Color.WHITE
        }
        var width = paint.measureText(text)
        if (width > maxWidth) {
            size = baseSize * maxWidth / width
            paint.textSize = size
            width = paint.measureText(text)
        }
        val fm = paint.fontMetrics
        val textH = fm.descent - fm.ascent
        val x = offsetX + (halfWidth - width) / 2f
        val baseline = topY - fm.ascent

        // 黑底条（宽度略放宽，贴合 Bot 的 -2/+2）
        val pad = size * 0.06f
        canvas.drawRect(
            x - pad, topY,
            x + width + pad, topY + textH * 1.2f,
            Paint().apply { color = Color.BLACK },
        )
        canvas.drawText(text, x, baseline, paint)
    }

    /** gocho：红字 + 银字，整体斜切 0.4，最后裁掉空白。 */
    fun buildGocho(context: Context, red: String, silver: String): Bitmap? = runCatching {
        val bold = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/HOS_Bold.ttf")
        }.getOrNull()
        val rText = "  $red"
        val sText = "  $silver"
        val r = layeredText(rText, RED_LAYERS, bold)
        val s = layeredText(sText, SILVER_LAYERS, bold)
        val offset = maxOf(0, (r.width - s.width / 2.0 - 60).roundToInt())
        val w = maxOf(r.width, s.width + offset)
        val h = r.height + s.height
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        c.drawColor(Color.WHITE)
        c.drawBitmap(r, 0f, 0f, null)
        c.drawBitmap(s, offset.toFloat(), r.height.toFloat(), null)
        // 裁边 → 斜切 → 再裁边，得到和 Bot 一样紧凑的图
        val trimmed = trimWhite(base)
        val shearedW = (trimmed.width + 0.4f * trimmed.height).roundToInt()
        val sheared = Bitmap.createBitmap(shearedW, trimmed.height, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheared)
        sc.drawColor(Color.WHITE)
        sc.drawBitmap(trimmed, Matrix().apply { setSkew(0.4f, 0f) }, Paint(Paint.ANTI_ALIAS_FLAG))
        trimWhite(sheared)
    }.getOrNull()

    /**
     * 逐层叠渐变文字：每层 = 可选描边 + 竖直渐变填充，画在**同一尺寸**画布上，
     * 画布高度按字体度量算，保证不被裁掉（之前裁掉整行就是没算 descent）。
     */
    private fun layeredText(text: String, layers: List<Layer>, font: Typeface?): Bitmap {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = FONT_SIZE
            this.typeface = font
        }
        val fm = probe.fontMetrics
        val pad = 46f
        val textW = probe.measureText(text)
        val glyphH = fm.descent - fm.ascent
        val w = (textW + pad * 2).roundToInt()
        val h = (glyphH + pad * 2).roundToInt()
        val baseline = pad - fm.ascent

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        for (layer in layers) {
            val layerBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layerBmp)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = FONT_SIZE
                this.typeface = font
                color = Color.WHITE
                style = if (layer.stroke > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                strokeWidth = layer.stroke.toFloat()
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            lc.drawText(text, pad, baseline, maskPaint)
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, layer.offset.toFloat(), 0f, h.toFloat(),
                    layer.colors, layer.stops, Shader.TileMode.CLAMP,
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            lc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradPaint)
            c.drawBitmap(layerBmp, layer.shiftX, layer.shiftY, null)
        }
        return out
    }

    /** 去掉四周纯白边（Bot 的图是紧贴文字的）。 */
    private fun trimWhite(src: Bitmap): Bitmap {
        var left = src.width
        var top = src.height
        var right = -1
        var bottom = -1
        val step = 1
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (y in 0 until src.height step step) {
            for (x in 0 until src.width step step) {
                val c = pixels[y * src.width + x]
                val a = (c shr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val isWhite = a < 16 || (r > 245 && g > 245 && b > 245)
                if (!isWhite) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0 || bottom < 0) return src
        val m = 6
        val l = (left - m).coerceAtLeast(0)
        val t = (top - m).coerceAtLeast(0)
        val r = (right + m).coerceAtMost(src.width - 1)
        val bo = (bottom + m).coerceAtMost(src.height - 1)
        return Bitmap.createBitmap(src, l, t, r - l + 1, bo - t + 1)
    }

    private fun decodeMutable(context: Context, id: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
        }
        return BitmapFactory.decodeResource(context.resources, id, opts)
            ?: BitmapFactory.decodeResource(context.resources, id)?.copy(Bitmap.Config.ARGB_8888, true)
    }

    private const val FONT_SIZE = 100f

    /** 一层：渐变色标 + 描边宽度 + 渐变起点 + 位移。 */
    private data class Layer(
        val colors: IntArray,
        val stops: FloatArray,
        val stroke: Int = 0,
        val offset: Int = 0,
        val shiftX: Float = 0f,
        val shiftY: Float = 0f,
    ) {
        constructor(color: Int, stroke: Int = 0, offset: Int = 0, shiftX: Float = 0f, shiftY: Float = 0f) : this(
            intArrayOf(color, color), floatArrayOf(0f, 1f), stroke, offset, shiftX, shiftY,
        )
    }

    private fun c(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    /** Bot red_text 的层序（黑描边 → 银白高光 → 黄橙渐变 → 红渐变）。 */
    private val RED_LAYERS: List<Layer> by lazy {
        listOf(
            Layer(c(0, 0, 0), stroke = 17, offset = 20, shiftX = -4f, shiftY = -3f),
            Layer(
                intArrayOf(
                    c(0, 15, 36), c(255, 255, 255), c(55, 58, 59), c(55, 58, 59),
                    c(200, 200, 200), c(55, 58, 59), c(25, 20, 31), c(240, 240, 240),
                    c(166, 175, 194), c(50, 50, 50),
                ),
                floatArrayOf(0f, 0.10f, 0.18f, 0.25f, 0.5f, 0.75f, 0.85f, 0.91f, 0.95f, 1f),
                stroke = 14, offset = 20, shiftX = -4f, shiftY = -3f,
            ),
            Layer(c(0, 0, 0), stroke = 10, offset = 20),
            Layer(
                intArrayOf(
                    c(253, 241, 0), c(245, 253, 187), c(255, 255, 255), c(253, 219, 9),
                    c(127, 53, 0), c(243, 196, 11),
                ),
                floatArrayOf(0f, 0.25f, 0.4f, 0.75f, 0.9f, 1f),
                stroke = 8, offset = 20, shiftY = 2f,
            ),
            Layer(c(0, 0, 0), stroke = 4, offset = 20, shiftY = 2f),
            Layer(c(255, 255, 255), stroke = 4, offset = 20, shiftY = 2f),
            Layer(
                intArrayOf(c(255, 100, 0), c(123, 0, 0), c(240, 0, 0), c(5, 0, 0)),
                floatArrayOf(0f, 0.5f, 0.51f, 1f),
                offset = 20, shiftY = 2f,
            ),
            Layer(
                intArrayOf(c(230, 0, 0), c(230, 0, 0), c(240, 0, 0), c(5, 0, 0)),
                floatArrayOf(0f, 0.5f, 0.51f, 1f),
                stroke = 1, offset = 20, shiftY = 2f,
            ),
        )
    }

    /** Bot sliver_text 的层序（黑描边 → 银白渐变 → 冷蓝高光）。 */
    private val SILVER_LAYERS: List<Layer> by lazy {
        listOf(
            Layer(c(0, 0, 0), stroke = 17, offset = 20, shiftX = -4f, shiftY = -3f),
            Layer(
                intArrayOf(
                    c(0, 15, 36), c(250, 250, 250), c(150, 150, 150), c(55, 58, 59),
                    c(25, 20, 31), c(240, 240, 240), c(166, 175, 194), c(50, 50, 50),
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 0.85f, 0.91f, 0.95f, 1f),
                stroke = 14, offset = 20, shiftX = -4f, shiftY = -3f,
            ),
            Layer(c(16, 25, 58), stroke = 12, offset = 20),
            Layer(c(221, 221, 221), stroke = 7, offset = 20),
            Layer(
                intArrayOf(c(16, 25, 58), c(255, 255, 255), c(16, 25, 58)),
                floatArrayOf(0f, 0.05f, 0.1f),
                stroke = 6, offset = 20,
            ),
            Layer(
                intArrayOf(
                    c(245, 246, 248), c(255, 255, 255), c(195, 213, 220), c(160, 190, 201),
                    c(160, 190, 201), c(196, 215, 222), c(255, 255, 255),
                ),
                floatArrayOf(0f, 0.15f, 0.35f, 0.5f, 0.51f, 0.52f, 1f),
                offset = 20,
            ),
        )
    }
}
