// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.meme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 动图模板的本地合成引擎。
 *
 * `tools/meme_anim.py` 在导出阶段真实执行上游函数、把每一帧的绘制操作录成脚本
 * （[MemeAnimScript]），这里按脚本重放：解码帧资源 → 逐层贴图（用户图片 / 文字 /
 * 静态帧）→ 交给 [GifEncoder] 出 GIF。
 *
 * 只支持「有静态帧资源 + 贴图/缩放/旋转/裁剪/圆形遮罩」这一族，覆盖上游动图的
 * 大部分（贴照片、贴头像、多帧文字）；透视/像素运算类在导出阶段就被判为不可
 * 支持，根本不会进目录。
 */
object MemeAnimRenderer {

    /** 动图合成结果（GIF 字节 + 首帧尺寸，供对话气泡占位）。 */
    data class Result(val gif: ByteArray, val width: Int, val height: Int)

    fun render(
        context: Context,
        template: MemeTemplate,
        texts: List<String>,
        images: List<Bitmap>,
    ): Result? {
        val script = template.anim ?: return null
        if (script.frames.isEmpty()) return null
        val assetRoot = "meme/anim/${template.key}"

        val fileCache = HashMap<String, Bitmap>()
        fun fileBitmap(name: String): Bitmap? = fileCache.getOrPut(name) {
            runCatching {
                context.assets.open("$assetRoot/$name").use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
        }

        val composed = ArrayList<Bitmap>(script.frames.size)
        try {
            script.frames.forEach { frame ->
                val base = when (val b = frame.base) {
                    is AnimBase.File -> fileBitmap(b.name)
                    is AnimBase.Canvas -> Bitmap.createBitmap(
                        b.w.coerceAtLeast(1), b.h.coerceAtLeast(1), Bitmap.Config.ARGB_8888
                    ).also { bmp ->
                        val c = colorInt(b.color, 0)
                        if (Color.alpha(c) != 0) bmp.eraseColor(c)
                    }
                    null -> null
                } ?: return null

                val w = base.width
                val h = base.height
                val canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBmp)
                // below = 画在底图之下（上游 pastе(..., below=True)），
                // 其余按脚本顺序压在底图之上。
                val below = ArrayList<Bitmap>()
                val above = ArrayList<Bitmap>()
                frame.ops.forEach { op ->
                    val layer = resolveLayer(op, script, texts, images, ::fileBitmap) ?: return@forEach
                    if (op.below) below.add(layer) else above.add(layer)
                }
                below.forEach { canvas.drawBitmap(it, 0f, 0f, null) }
                canvas.drawBitmap(base, 0f, 0f, null)
                above.forEach { canvas.drawBitmap(it, 0f, 0f, null) }
                composed.add(canvasBmp)
            }
            if (composed.isEmpty()) return null
            val gif = GifEncoder.encode(composed, script.delayMs)
            return Result(gif, composed.first().width, composed.first().height)
        } finally {
            composed.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /** 把一层解成一个「已经摆到画布坐标」的位图。 */
    private fun resolveLayer(
        op: AnimOp,
        script: MemeAnimScript,
        texts: List<String>,
        images: List<Bitmap>,
        file: (String) -> Bitmap?,
    ): Bitmap? {
        val srcBmp = when (val s = op.src) {
            is AnimSrc.User -> images.getOrNull(s.index) ?: return null
            is AnimSrc.File -> file(s.name) ?: return null
            is AnimSrc.Text -> renderText(texts.getOrNull(s.index).orEmpty(), s.spec) ?: return null
        }
        val layer = applyTx(srcBmp, op.src.tx) ?: return null
        val out = Bitmap.createBitmap(script.width, script.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(layer, op.x, op.y, null)
        return out
    }

    private fun applyTx(src: Bitmap, tx: List<Tx>): Bitmap? {
        var cur = src
        tx.forEach { t ->
            val next = when (t.op) {
                "square" -> squareCrop(cur)
                "circle" -> circleMask(squareCrop(cur))
                "resize" -> if (t.w > 0 && t.h > 0) {
                    Bitmap.createScaledBitmap(cur, t.w, t.h, true)
                } else cur
                "rotate" -> rotate(cur, t.deg, t.expand)
                "crop" -> crop(cur, t.box)
                else -> cur
            }
            cur = next ?: return null
        }
        return cur
    }

    private fun squareCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    private fun circleMask(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = Path().apply {
            addOval(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), Path.Direction.CW)
        }
        c.clipPath(path)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun rotate(src: Bitmap, deg: Float, expand: Boolean): Bitmap {
        if (deg == 0f) return src
        val matrix = Matrix().apply { postRotate(deg) }
        if (!expand) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.translate(src.width / 2f, src.height / 2f)
            c.rotate(deg)
            c.translate(-src.width / 2f, -src.height / 2f)
            c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        val rad = Math.toRadians(deg.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val nw = ceil(src.width * c + src.height * s).toInt().coerceAtLeast(1)
        val nh = ceil(src.width * s + src.height * c).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postRotate(deg)
            postTranslate((nw - src.width) / 2f, (nh - src.height) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun crop(src: Bitmap, box: List<Int>): Bitmap? {
        if (box.size != 4) return src
        val l = box[0].coerceIn(0, src.width - 1)
        val t = box[1].coerceIn(0, src.height - 1)
        val r = box[2].coerceIn(l + 1, src.width)
        val b = box[3].coerceIn(t + 1, src.height)
        return runCatching { Bitmap.createBitmap(src, l, t, r - l, b - t) }.getOrNull()
    }

    /**
     * 宽松解析颜色。
     *
     * 上游脚本里的 `color` / `fill` 可能是 JSON `null`，而 `JSONObject.optString`
     * 会把 JSON `null` 转成字符串 `"null"`，直接喂给 `Color.parseColor` 会抛
     * `IllegalArgumentException: Unknown color` —— 「摸」这条 5 帧动图就是这么
     * 整条挂掉、对话里只回一句「出错了」的。
     *
     * 只拦 `"null"` 这一个毒值：`Color.parseColor` 本身还认 `white` / `black`
     * 这类颜色名，而动图脚本的底图偏偏就是用颜色名写的（love_you / petpet /
     * punch / suck / mahiro_readbook 的 `{"color":"white"}`），一律按前缀白名单
     * 过滤会把它们的底图判成透明。
     */
    private fun colorInt(raw: String?, fallback: Int): Int {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.equals("null", ignoreCase = true)) return fallback
        return runCatching { Color.parseColor(v) }.getOrDefault(fallback)
    }

    /** 在 [MemeTextSpec.rect] 里自动缩字号 + 换行绘制文字。 */
    private fun renderText(text: String, spec: MemeTextSpec?): Bitmap? {
        if (spec == null) return null
        val l = spec.rect.getOrElse(0) { 0 }
        val t = spec.rect.getOrElse(1) { 0 }
        val r = spec.rect.getOrElse(2) { l + 1 }
        val b = spec.rect.getOrElse(3) { t + 1 }
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt(spec.fill, Color.BLACK)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colorInt(spec.strokeFill, Color.BLACK)
            textAlign = Paint.Align.CENTER
            typeface = paint.typeface
        }
        if (text.isBlank()) return out

        var size = spec.maxFont.coerceAtLeast(8f)
        var lines: List<String> = emptyList()
        while (size >= spec.minFont.coerceAtLeast(6f)) {
            paint.textSize = size
            stroke.textSize = size
            stroke.strokeWidth = size * 0.08f
            lines = wrap(text, paint, w.toFloat(), spec.wrap)
            val lineH = size * 1.12f
            val totalH = lineH * lines.size
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            if (totalH <= h && (widest <= w || !spec.wrap)) break
            size -= 2f
        }
        paint.textSize = size
        stroke.textSize = size
        stroke.strokeWidth = size * 0.08f
        val lineH = size * 1.12f
        val startY = (h - lineH * lines.size) / 2f + size * 0.86f
        lines.forEachIndexed { i, line ->
            val y = startY + i * lineH
            if (stroke.strokeWidth > 0f) canvas.drawText(line, w / 2f, y, stroke)
            canvas.drawText(line, w / 2f, y, paint)
        }
        return out
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, allowWrap: Boolean): List<String> {
        if (!allowWrap) return listOf(text)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        text.forEach { ch ->
            val next = cur.toString() + ch
            if (paint.measureText(next) > maxWidth && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur = StringBuilder().append(ch)
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(text) }
    }

    // ── JSON → 脚本 ─────────────────────────────────────────────────────────
    fun parseScript(o: JSONObject): MemeAnimScript {
        val frames = (o.optJSONArray("frames") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).toFrame() }
        }
        return MemeAnimScript(
            width = o.optInt("width"),
            height = o.optInt("height"),
            delayMs = o.optInt("delayMs", 80),
            frames = frames,
        )
    }

    private fun JSONObject.toFrame(): MemeAnimFrame {
        val baseObj = optJSONObject("base")
        val base = when {
            baseObj == null -> null
            baseObj.has("file") -> AnimBase.File(baseObj.optString("file"))
            else -> AnimBase.Canvas(
                w = baseObj.optInt("w"),
                h = baseObj.optInt("h"),
                color = baseObj.optString("color").takeIf { it.isNotBlank() },
            )
        }
        val ops = (optJSONArray("ops") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AnimOp(
                    src = o.getJSONObject("src").toSrc(),
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    below = o.optBoolean("below"),
                )
            }
        }
        return MemeAnimFrame(base, ops)
    }

    private fun JSONObject.toSrc(): AnimSrc {
        val tx = (optJSONArray("tx") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                Tx(
                    op = t.optString("op"),
                    w = t.optInt("w"),
                    h = t.optInt("h"),
                    deg = t.optDouble("deg", 0.0).toFloat(),
                    expand = t.optBoolean("expand"),
                    box = (t.optJSONArray("box") ?: JSONArray()).let { bx ->
                        (0 until bx.length()).map { bx.optInt(it) }
                    },
                )
            }
        }
        return when (optString("kind")) {
            "user" -> AnimSrc.User(optInt("index"), tx)
            "text" -> AnimSrc.Text(
                index = optInt("index"),
                spec = optJSONObject("spec")?.toTextSpec(),
                tx = tx,
            )
            else -> AnimSrc.File(optString("file"), tx)
        }
    }

    private fun JSONObject.toTextSpec(): MemeTextSpec {
        val box = (optJSONArray("box") ?: JSONArray()).let { a ->
            (0 until a.length()).map { a.optInt(it) }
        }
        val rect = when (box.size) {
            4 -> box
            2 -> listOf(0, 0, box[0], box[1])
            else -> listOf(0, 0, 100, 100)
        }
        return MemeTextSpec(
            rect = rect,
            maxFont = optDouble("maxFont", 60.0).toFloat(),
            minFont = optDouble("minFont", 20.0).toFloat(),
            wrap = optBoolean("wrap"),
            fill = optString("fill").ifBlank { "#000000" },
            strokeFill = optString("strokeFill").ifBlank { "#000000" },
            align = optString("align").ifBlank { "center" },
        )
    }
}

data class MemeAnimScript(
    val width: Int,
    val height: Int,
    val delayMs: Int,
    val frames: List<MemeAnimFrame>,
)

data class MemeAnimFrame(val base: AnimBase?, val ops: List<AnimOp>)

sealed interface AnimBase {
    data class File(val name: String) : AnimBase
    data class Canvas(val w: Int, val h: Int, val color: String?) : AnimBase
}

data class AnimOp(val src: AnimSrc, val x: Float, val y: Float, val below: Boolean)

sealed interface AnimSrc {
    val tx: List<Tx>

    data class User(val index: Int, override val tx: List<Tx>) : AnimSrc
    data class Text(
        val index: Int,
        val spec: MemeTextSpec?,
        override val tx: List<Tx>,
    ) : AnimSrc

    data class File(val name: String, override val tx: List<Tx>) : AnimSrc
}

data class Tx(
    val op: String,
    val w: Int = 0,
    val h: Int = 0,
    val deg: Float = 0f,
    val expand: Boolean = false,
    val box: List<Int> = emptyList(),
)

data class MemeTextSpec(
    val rect: List<Int>,
    val maxFont: Float,
    val minFont: Float,
    val wrap: Boolean,
    val fill: String,
    val strokeFill: String,
    val align: String,
)
