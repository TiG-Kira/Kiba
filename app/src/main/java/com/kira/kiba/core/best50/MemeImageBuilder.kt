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
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 梗图生成器 —— 移植自 KibaBot 的两个图片命令：
 * - 「低情商 X 高情商 Y」：`src/plugins/image.py` + `src/libraries/image.py`
 *   模板 `high_eq_image.png`（左右各 400px），黑底白字居中，单边不超过 15 字。
 * - 「gocho A B」：`src/libraries/gosen_choyen.py`
 *   红字 / 银字两层渐变描边文字，最后整体水平斜切 0.4。
 *
 * 字体：Bot 用 msyh / msyhbd，这里分别退到 HOS.ttf / HOS_Bold.ttf。
 */
object MemeImageBuilder {

    /** 低情商 / 高情商（模板左右两半，各 400px 宽，文字基线 y=360）。 */
    fun buildLowHighEq(context: Context, left: String, right: String): Bitmap? = runCatching {
        val id = context.resources.getIdentifier("kiba_high_eq_image", "drawable", context.packageName)
        if (id == 0) return null
        val img = BitmapFactory.decodeResource(context.resources, id).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(img)
        val font = runCatching { Typeface.createFromAsset(context.assets, "fonts/HOS.ttf") }.getOrNull()
        drawHalf(canvas, left, 0, font, img.width)
        drawHalf(canvas, right, 400, font, img.width)
        img
    }.getOrNull()

    /** 对应 Bot 的 draw_text(img, text, offset_x)。 */
    private fun drawHalf(canvas: Canvas, text: String, offsetX: Int, font: Typeface?, imgW: Int) {
        if (text.isBlank()) return
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            this.typeface = font
        }
        var width = probe.measureText(text)
        val size = if (width > 390) {
            (390 * 48f / width).also { }
        } else {
            48f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = font
            color = Color.WHITE
        }
        width = paint.measureText(text)
        val height = paint.fontMetrics.descent - paint.fontMetrics.ascent
        val x = if (width <= 390) (400 - width) / 2 else 5f
        // 黑底条
        val rect = Paint().apply { color = Color.BLACK }
        canvas.drawRect(
            x + offsetX - 2, 360f,
            x + 2 + width + offsetX, 360f + height * 1.2f,
            rect,
        )
        val baseline = 360f - paint.fontMetrics.ascent
        canvas.drawText(text, x + offsetX, baseline, paint)
    }

    /** gocho：红字 + 银字，整体斜切 0.4。 */
    fun buildGocho(context: Context, red: String, silver: String): Bitmap? = runCatching {
        val font = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/HOS_Bold.ttf")
        }.getOrNull()
        val rText = "  $red"
        val sText = "  $silver"
        val r = layeredText(rText, RED_LAYERS, font, redFill = true)
        val s = layeredText(sText, SILVER_LAYERS, font, redFill = false)
        val offset = maxOf(0, (r.width - s.width / 2.0 - 60).roundToInt())
        val w = maxOf(r.width, s.width + offset)
        val h = r.height + s.height
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        c.drawColor(Color.WHITE)
        c.drawBitmap(r, 0f, 0f, null)
        c.drawBitmap(s, offset.toFloat(), r.height.toFloat(), null)
        // 整体水平斜切（PIL AFFINE (1, 0.4, 0, 0, 1, 0)）
        val sheared = Bitmap.createBitmap((w + 0.4f * h).roundToInt(), h, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheared)
        sc.drawColor(Color.WHITE)
        val matrix = Matrix().apply { setSkew(0.4f, 0f) }
        sc.drawBitmap(base, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
        sheared
    }.getOrNull()

    /**
     * 逐层叠加渐变文字：每一层是「可选描边 + 竖直渐变填充」，
     * 按 Bot 的顺序叠到同一张图上（尺寸取最大层，居中贴）。
     */
    private fun layeredText(
        text: String,
        layers: List<Layer>,
        font: Typeface?,
        redFill: Boolean,
    ): Bitmap {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = FONT_SIZE
            this.typeface = font
        }
        val tw = probe.measureText(text).roundToInt() + 40
        val th = (FONT_SIZE * 1.4f).roundToInt() + 40
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val baseline = (FONT_SIZE + 20f) - probe.fontMetrics.ascent
        for (layer in layers) {
            val layerBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layerBmp)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = FONT_SIZE
                this.typeface = font
                color = Color.WHITE
                style = if (layer.stroke > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                strokeWidth = layer.stroke.toFloat()
                strokeJoin = Paint.Join.ROUND
            }
            lc.drawText(text, 20f, baseline, maskPaint)
            // 竖直渐变填充，按文字 alpha 裁剪
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, layer.offset.toFloat(), 0f, th.toFloat(),
                    layer.colors, layer.stops, Shader.TileMode.CLAMP,
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            lc.drawRect(0f, 0f, tw.toFloat(), th.toFloat(), gradPaint)
            val dx = ((tw - layerBmp.width) / 2f)
            val dy = ((th - layerBmp.height) / 2f) + layer.shiftY
            c.drawBitmap(layerBmp, dx + layer.shiftX, dy, null)
        }
        return out
    }

    private const val FONT_SIZE = 100f

    /** 一层：渐变色标 + 描边宽度 + 位移（对应 PIL 的 transform 平移）。 */
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

    /** Bot red_text 的层序。 */
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

    /** Bot sliver_text 的层序。 */
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
