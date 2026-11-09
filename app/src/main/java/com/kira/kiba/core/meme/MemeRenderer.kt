// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.meme

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
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 表情包渲染引擎（离线、纯 Android Canvas）。
 *
 * 两种渲染器：
 * - `generic`：[MemeCatalog] 里 52 个模板走声明式流水线：背景图 → 用户图片层 → 文字层。
 *   文字层复刻 pil_utils `draw_text` 的主要行为（框内自动换行、字号二分自适应、
 *   描边、水平/垂直对齐、旋转）。
 * - `choyen`：`5000choyen`（gocho）是纯代码生成的渐变描边字，按上游逐层参数特化，
 *   比 Kiba 早期手搓的那版更贴近原版（斜切 0.45、
 *   两行独立字体与渐变、白底 580 高）。
 *
 * 上游动图 / 透视变换模板不在这里伪造降级效果：目录里能看到，但生成会明确失败。
 */
object MemeRenderer {

    private const val ASSET_DIR = "meme/"
    private val typefaces = mutableMapOf<String, Typeface?>()

    /**
     * 生成一张表情包。
     *
     * @param texts 用户填的文字，按 `{0}` / `{1}` … 占位符顺序
     * @param images 用户选的图片 Uri，按 [MemeImageLayer.index] 取用
     */
    fun render(
        context: Context,
        template: MemeTemplate,
        texts: List<String>,
        images: List<Uri> = emptyList(),
    ): Bitmap? = runCatching {
        if (!template.renderable) return null
        when (template.renderer) {
            "choyen" -> renderChoyen(context, texts)
            else -> renderGeneric(context, template, texts, images)
        }
    }.getOrNull()

    // ────────────────────────────── 通用流水线 ──────────────────────────────

    private fun renderGeneric(
        context: Context,
        template: MemeTemplate,
        texts: List<String>,
        images: List<Uri>,
    ): Bitmap {
        val base = decodeAsset(context, template.background)
            ?.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("模板背景图缺失：${template.key}")
        val canvas = Canvas(base)
        val recycled = mutableListOf<Bitmap>()

        try {
            template.imageLayers.forEach { layer ->
                val src = images.getOrNull(layer.index)
                    ?: images.lastOrNull()
                    ?: return@forEach
                val bmp = prepareImageLayer(context, src, layer) ?: return@forEach
                recycled += bmp
                canvas.drawBitmap(bmp, layer.x.toFloat(), layer.y.toFloat(), null)
            }
            template.layers.forEach { layer ->
                drawTextLayer(context, canvas, layer, texts, template)
            }
        } finally {
            recycled.forEach { if (!it.isRecycled) it.recycle() }
        }
        return base
    }

    private fun decodeAsset(context: Context, path: String): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.applicationContext.assets.open(ASSET_DIR + path).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** 用户图片按模板要求缩放 / 旋转 / 圆形裁剪。 */
    private fun prepareImageLayer(context: Context, uri: Uri, layer: MemeImageLayer): Bitmap? = runCatching {
        val raw = context.applicationContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        val w = if (layer.w > 0) layer.w else raw.width
        val h = if (layer.h > 0) layer.h else raw.height
        var bmp = Bitmap.createScaledBitmap(raw, w, h, true)
        if (bmp !== raw) raw.recycle()
        if (layer.rotate != 0f) {
            val matrix = Matrix().apply { postRotate(layer.rotate) }
            val rotated = runCatching {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }.getOrNull()
            if (rotated != null) {
                bmp.recycle()
                bmp = rotated
            }
        }
        if (layer.circle) {
            val size = max(1, minOf(bmp.width, bmp.height))
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val scaled = if (bmp.width != size || bmp.height != size) {
                Bitmap.createScaledBitmap(bmp, size, size, true)
            } else bmp
            c.drawBitmap(scaled, (size - scaled.width) / 2f, (size - scaled.height) / 2f, p)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            bmp = out
        }
        bmp
    }.getOrNull()

    /**
     * 一行文字：把 `{0}` 占位符替换成用户输入，再按矩形自适应排版。
     * rect 只有两个值时是上游的「定点绘制」（如鲁迅说的 `--鲁迅` 落款）。
     */
    private fun drawTextLayer(
        context: Context,
        canvas: Canvas,
        layer: MemeTextLayer,
        texts: List<String>,
        template: MemeTemplate,
    ) {
        val content = Regex("\\{(\\d+)\\}").replace(layer.text) { m ->
            val i = m.groupValues[1].toIntOrNull() ?: return@replace ""
            texts.getOrNull(i) ?: template.defaultTexts.getOrNull(i).orEmpty()
        }
        if (content.isBlank()) return
        val font = font(context, layer)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font
            color = parseColor(layer.fill)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font
            color = parseColor(layer.strokeFill)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        if (layer.rect.size < 4) {
            // 定点绘制：不换行、不缩字号
            val size = if (layer.fontSize > 0f) layer.fontSize else layer.maxFont
            paint.textSize = size
            strokePaint.textSize = size
            val x = layer.rect.getOrNull(0)?.toFloat() ?: 0f
            val y = layer.rect.getOrNull(1)?.toFloat() ?: 0f
            val baseline = y - paint.fontMetrics.ascent
            if (layer.strokeRatio > 0f) {
                strokePaint.strokeWidth = size * layer.strokeRatio
                canvas.drawText(content, x, baseline, strokePaint)
            }
            canvas.drawText(content, x, baseline, paint)
            return
        }

        val (l, t, r, b) = layer.rect
        val boxW = (r - l).toFloat().coerceAtLeast(1f)
        val boxH = (b - t).toFloat().coerceAtLeast(1f)
        val hi = layer.maxFont.coerceAtLeast(layer.minFont)
        val lo = layer.minFont.coerceAtMost(hi)
        var bestSize = lo
        var bestLines = breakLines(content, lo, boxW, paint, layer.wrap)

        // 二分找「能塞进框里」的最大字号，避免线性递减在大字号模板上卡住。
        var low = lo
        var high = hi
        repeat(9) {
            if (high - low < 0.6f) return@repeat
            val mid = (low + high) / 2f
            val lines = breakLines(content, mid, boxW, paint, layer.wrap)
            val fits = fits(lines, mid, boxW, boxH, paint)
            if (fits) {
                bestSize = mid
                bestLines = lines
                low = mid
            } else {
                high = mid
            }
        }
        // 上限本身可行时别漏掉
        val topLines = breakLines(content, hi, boxW, paint, layer.wrap)
        if (fits(topLines, hi, boxW, boxH, paint)) {
            bestSize = hi
            bestLines = topLines
        }

        paint.textSize = bestSize
        strokePaint.textSize = bestSize
        strokePaint.strokeWidth = bestSize * layer.strokeRatio
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.16f
        val totalH = lineH * bestLines.size
        val startY = when (layer.valign.lowercase()) {
            "top" -> t.toFloat() - fm.ascent
            "bottom" -> b.toFloat() - totalH - fm.ascent
            else -> t.toFloat() + (boxH - totalH) / 2f - fm.ascent
        }
        val rotate = layer.rotate
        if (rotate != 0f) {
            canvas.save()
            canvas.rotate(rotate, l + boxW / 2f, t + boxH / 2f)
        }
        bestLines.forEachIndexed { i, line ->
            val lineW = paint.measureText(line)
            val x = when (layer.align.lowercase()) {
                "left" -> l.toFloat()
                "right" -> r.toFloat() - lineW
                else -> l + (boxW - lineW) / 2f
            }
            val y = startY + i * lineH
            if (layer.strokeRatio > 0f) canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)
        }
        if (rotate != 0f) canvas.restore()
    }

    /** 按框宽断行；中文按字、连续英文按整段，避免把单词劈碎。 */
    private fun breakLines(text: String, size: Float, boxW: Float, paint: Paint, wrap: Boolean): List<String> {
        paint.textSize = size
        val out = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            if (!wrap) {
                out += paragraph
                return@forEach
            }
            val sb = StringBuilder()
            paragraph.forEach { ch ->
                val trial = sb.toString() + ch
                if (sb.isNotEmpty() && paint.measureText(trial) > boxW) {
                    out += sb.toString()
                    sb.clear()
                }
                sb.append(ch)
            }
            out += sb.toString()
        }
        return out.ifEmpty { listOf("") }
    }

    private fun fits(lines: List<String>, size: Float, boxW: Float, boxH: Float, paint: Paint): Boolean {
        paint.textSize = size
        if (lines.any { paint.measureText(it) > boxW * 1.02f }) return false
        val lineH = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.16f
        return lineH * lines.size <= boxH * 1.05f
    }

    /** 上游用 Noto Sans SC / Noto Serif SC，这里退到 App 自带的 HOS 字体。 */
    private fun font(context: Context, layer: MemeTextLayer): Typeface? =
        fontFromAssets(context, if (layer.bold) "fonts/HOS_Bold.ttf" else "fonts/HOS.ttf")

    private fun parseColor(value: String): Int = runCatching {
        Color.parseColor(value)
    }.getOrDefault(Color.BLACK)

    // ───────────────────────── 5000choyen（gocho） ─────────────────────────

    /** 一层渐变/纯色描边文字（参数逐条对应上游 add_color_text / add_gradient_text）。 */
    private data class ChoyenLayer(
        val colors: IntArray,
        val stops: FloatArray,
        val stroke: Float,
        val gradientFrom: Float,
        val gradientTo: Float,
        val dx: Float,
        val dy: Float,
    ) {
        constructor(color: Int, stroke: Float, dx: Float, dy: Float) : this(
            intArrayOf(color, color), floatArrayOf(0f, 1f), stroke, 0f, 1f, dx, dy,
        )
    }

    private const val CHOYEN_FONT = 200f
    private const val CHOYEN_SKEW = 0.45f
    private const val CHOYEN_HEIGHT = 580

    /**
     * 上游 `memes/5000choyen/__init__.py` 的 Kotlin 版：
     * 两行文字各叠 8 / 6 层描边与渐变，每层独立斜切后按位置贴到白底。
     */
    private fun renderChoyen(context: Context, texts: List<String>): Bitmap {
        val red = texts.getOrNull(0).orEmpty().ifBlank { "我去" }
        val silver = texts.getOrNull(1).orEmpty().ifBlank { "洛天依" }
        val sans = fontFromAssets(context, "fonts/HOS_Bold.ttf") ?: Typeface.DEFAULT_BOLD
        val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        data class Row(val text: String, val posX: Float, val posY: Float, val font: Typeface, val layers: List<ChoyenLayer>)
        data class Placed(val bmp: Bitmap, val x: Float, val y: Float)
        val rows = listOf(
            Row("  $red", 20f, 0f, sans, redLayers()),
            Row("  $silver", 280f, 260f, serif, silverLayers()),
        )
        val placed = mutableListOf<Placed>()
        val scratch = mutableListOf<Bitmap>()
        var out: Bitmap? = null
        try {
            rows.forEach { row ->
                row.layers.forEach { l ->
                    val textBmp = choyenText(row.text, row.font, l, row.posY) ?: return@forEach
                    val skewed = skewChoyen(textBmp)
                    textBmp.recycle()
                    placed += Placed(skewed, row.posX + l.dx, row.posY + l.dy)
                    scratch += skewed
                }
            }
            val w = placed.maxOfOrNull { it.bmp.width + it.x.roundToInt() }
                ?: error("没有可绘制的文字层")
            val frame = Bitmap.createBitmap(w, CHOYEN_HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame)
            c.drawColor(Color.WHITE)
            placed.forEach { c.drawBitmap(it.bmp, it.x, it.y, null) }
            out = frame
        } finally {
            scratch.forEach { if (!it.isRecycled) it.recycle() }
        }
        return out ?: error("gocho 生成失败")
    }

    /** 生成一层文字位图（统一尺寸保证各层对齐），渐变按上游 dir 减去行偏移换算。 */
    private fun choyenText(text: String, font: Typeface, layer: ChoyenLayer, rowY: Float): Bitmap? = runCatching {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CHOYEN_FONT
            typeface = font
        }
        val fm = probe.fontMetrics
        val pad = 40f
        val w = (probe.measureText(text) + pad * 2).roundToInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + pad * 2).roundToInt().coerceAtLeast(1)
        val baseline = pad - fm.ascent
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CHOYEN_FONT
            typeface = font
            color = Color.WHITE
            style = if (layer.stroke > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = layer.stroke
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        c.drawText(text, pad, baseline, mask)
        val gradient = LinearGradient(
            0f, layer.gradientFrom - rowY + pad, 0f, layer.gradientTo - rowY + pad,
            layer.colors, layer.stops, Shader.TileMode.CLAMP,
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fillPaint)
        out
    }.getOrNull()

    /** PIL AFFINE (1, skew, -dw, 0, 1, 0) 的等价变换：顶部右移、底部不动。 */
    private fun skewChoyen(src: Bitmap): Bitmap {
        val dw = (src.height * CHOYEN_SKEW).roundToInt()
        val out = Bitmap.createBitmap(src.width + dw, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = Matrix().apply {
            setValues(floatArrayOf(1f, -CHOYEN_SKEW, dw.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f))
        }
        c.drawBitmap(src, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun c(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    private fun grad(colors: IntArray, stops: FloatArray, stroke: Float, from: Float, to: Float, dx: Float, dy: Float) =
        ChoyenLayer(colors, stops, stroke, from, to, dx, dy)

    /** 第一行：黑 → 银 → 黑 → 金 → 黑 → 白 → 红 → 红（上游顺序）。 */
    private fun redLayers(): List<ChoyenLayer> = listOf(
        ChoyenLayer(Color.BLACK, 22f, 8f, 8f),
        grad(
            intArrayOf(
                c(0, 15, 36), c(255, 255, 255), c(55, 58, 59), c(55, 58, 59),
                c(200, 200, 200), c(55, 58, 59), c(25, 20, 31), c(240, 240, 240),
                c(166, 175, 194), c(50, 50, 50),
            ),
            floatArrayOf(0f, .10f, .18f, .25f, .5f, .75f, .85f, .91f, .95f, 1f),
            20f, 38f, 234f, 8f, 8f,
        ),
        ChoyenLayer(Color.BLACK, 16f, 0f, 0f),
        grad(
            intArrayOf(
                c(253, 241, 0), c(245, 253, 187), c(255, 255, 255),
                c(253, 219, 9), c(127, 53, 0), c(243, 196, 11),
            ),
            floatArrayOf(0f, .25f, .4f, .75f, .9f, 1f),
            10f, 40f, 200f, 0f, 0f,
        ),
        ChoyenLayer(Color.BLACK, 6f, 4f, -6f),
        ChoyenLayer(Color.WHITE, 6f, 0f, -6f),
        grad(
            intArrayOf(c(255, 100, 0), c(123, 0, 0), c(240, 0, 0), c(5, 0, 0)),
            floatArrayOf(0f, .5f, .51f, 1f),
            4f, 50f, 200f, 0f, -6f,
        ),
        grad(
            intArrayOf(c(230, 0, 0), c(230, 0, 0), c(240, 0, 0), c(5, 0, 0)),
            floatArrayOf(0f, .5f, .51f, 1f),
            0f, 50f, 200f, 0f, -6f,
        ),
    )

    /** 第二行：银白 / 冷蓝高光，渐变 y 为上游绝对坐标（行偏移 260 由 [choyenText] 扣掉）。 */
    private fun silverLayers(): List<ChoyenLayer> = listOf(
        ChoyenLayer(Color.BLACK, 22f, 10f, 4f),
        grad(
            intArrayOf(
                c(0, 15, 36), c(250, 250, 250), c(150, 150, 150), c(55, 58, 59),
                c(25, 20, 31), c(240, 240, 240), c(166, 175, 194), c(50, 50, 50),
            ),
            floatArrayOf(0f, .25f, .5f, .75f, .85f, .91f, .95f, 1f),
            19f, 320f, 506f, 10f, 4f,
        ),
        ChoyenLayer(c(16, 25, 58), 17f, 0f, 0f),
        ChoyenLayer(c(208, 208, 208), 8f, 0f, 0f),
        grad(
            intArrayOf(c(16, 25, 58), c(255, 255, 255), c(16, 25, 58), c(16, 25, 58), c(16, 25, 58)),
            floatArrayOf(0f, .03f, .08f, .2f, 1f),
            7f, 320f, 480f, 0f, 0f,
        ),
        grad(
            intArrayOf(
                c(245, 246, 248), c(255, 255, 255), c(195, 213, 220), c(160, 190, 201),
                c(160, 190, 201), c(196, 215, 222), c(255, 255, 255),
            ),
            floatArrayOf(0f, .15f, .35f, .5f, .51f, .52f, 1f),
            0f, 320f, 480f, 0f, -6f,
        ),
    )

    private fun fontFromAssets(context: Context, name: String): Typeface? =
        typefaces.getOrPut(name) {
            runCatching { Typeface.createFromAsset(context.applicationContext.assets, name) }.getOrNull()
        }
}
