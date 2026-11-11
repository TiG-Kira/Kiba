package com.kira.kiba.core.listen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Agent 歌单封面模板（1:1 / 1080×1080）。
 *
 * ## 为什么是「模板 + 填字段」而不是让模型画图
 * - 端上不能跑图像模型，外部图片接口又不一定可用；
 * - 歌单封面需要**稳定的排版与可读性**（歌名长、中文多、深色背景下要清晰），
 *   模板化能保证 100% 可读，模型只负责「填文案 + 选情绪」。
 *
 * 所以 Agent 每生成一张歌单，都要按这个模板填四个字段：
 * `title`（封面主标题，≤ 12 字）、`subtitle`（一行副标题，≤ 24 字）、
 * `mood`（情绪关键词，决定配色）、`tags`（可选，2~3 个短标签）。
 * 见 [com.kira.kiba.core.agent.KibaAgent] 的 `playlist_cover` 技能说明。
 *
 * ## 版式（定稿）
 * ```
 * ┌──────────────────────────────┐
 * │                              │  渐变底 + 左上角柔光
 * │  AGENT PLAYLIST              │  字距拉开的小标签（强调色）
 * │                              │
 * │  深夜循环                     │  主标题 84px 粗体，最多两行
 * │  给凌晨还在打歌的人            │  副标题 34px 70% 白
 * │                              │
 * │  ─────                       │  强调色短线
 * │  12 首 · 夜行                 │  页脚 28px 55% 白
 * └──────────────────────────────┘  右下角同心弧装饰
 * ```
 */
object PlaylistCover {

    /** 封面尺寸（正方形，列表缩略图与顶栏大图共用同一张）。 */
    const val SIZE = 1080

    /** 生成的封面落盘目录（filestore，不占媒体库）。 */
    private const val DIR = "playlist_covers"

    /** 模板名（写进歌单元数据，便于以后换模板时区分）。 */
    const val TEMPLATE = "kiba-nocturne"

    /** 配色方案：渐变两端 + 强调色。 */
    private data class Palette(
        val name: String,
        val from: Int,
        val to: Int,
        val accent: Int,
    )

    /**
     * 情绪关键词 → 配色。命中方式为**子串匹配**（模型给「深夜」「夜里」都能落到夜行），
     * 全不命中回落 [DEFAULT_PALETTE]，绝不因为文案奇怪而失败。
     */
    private val PALETTES = listOf(
        "夜行" to Palette("夜行", 0xFF0B1026.toInt(), 0xFF1B2A5B.toInt(), 0xFF6C8CFF.toInt()),
        "清晨" to Palette("清晨", 0xFF1B2B34.toInt(), 0xFF3E6B62.toInt(), 0xFF7FE3C4.toInt()),
        "热血" to Palette("热血", 0xFF2B0B0B.toInt(), 0xFF6B1D18.toInt(), 0xFFFF7A45.toInt()),
        "静谧" to Palette("静谧", 0xFF101418.toInt(), 0xFF2C3A46.toInt(), 0xFF9FC6D6.toInt()),
        "赛博" to Palette("赛博", 0xFF12002B.toInt(), 0xFF3A0C63.toInt(), 0xFFE45CFF.toInt()),
        "复古" to Palette("复古", 0xFF241C14.toInt(), 0xFF5A4632.toInt(), 0xFFE8B96A.toInt()),
        "海洋" to Palette("海洋", 0xFF04131F.toInt(), 0xFF0F3A56.toInt(), 0xFF56C8E8.toInt()),
        "甜梦" to Palette("甜梦", 0xFF2A1026.toInt(), 0xFF5B2A52.toInt(), 0xFFFF9ED2.toInt()),
    )

    private val DEFAULT_PALETTE = Palette("夜行", 0xFF0B1026.toInt(), 0xFF1B2A5B.toInt(), 0xFF6C8CFF.toInt())

    /** 情绪关键词里常见的别名（中英混输都能对上）。 */
    private val ALIASES = mapOf(
        "night" to "夜行", "midnight" to "夜行", "深夜" to "夜行", "夜里" to "夜行", "安静" to "静谧",
        "dawn" to "清晨", "morning" to "清晨", "早晨" to "清晨", "清新" to "清晨",
        "burn" to "热血", "hype" to "热血", "燃" to "热血", "激烈" to "热血", "快节奏" to "热血",
        "calm" to "静谧", "chill" to "静谧", "温柔" to "静谧", "舒缓" to "静谧",
        "cyber" to "赛博", "电子" to "赛博", "future" to "赛博", "未来" to "赛博",
        "retro" to "复古", "old" to "复古", "怀旧" to "复古", "citypop" to "复古",
        "ocean" to "海洋", "sea" to "海洋", "海" to "海洋", "空灵" to "海洋",
        "dream" to "甜梦", "sweet" to "甜梦", "可爱" to "甜梦", "少女" to "甜梦",
    )

    /** 封面文案（Agent 填的四个字段）。 */
    data class Spec(
        val title: String,
        val subtitle: String = "",
        /** 情绪关键词（决定配色）。 */
        val mood: String = "",
        /** 可选短标签，会拼在页脚。 */
        val tags: List<String> = emptyList(),
        /** 曲目数（页脚展示）。 */
        val songCount: Int = 0,
    ) {
        /** 实际使用的配色名（页脚展示 + 元数据）。 */
        val paletteName: String get() = resolvePalette(mood).name
    }

    private fun resolvePalette(mood: String): Palette {
        if (mood.isBlank()) return DEFAULT_PALETTE
        val lower = mood.lowercase(Locale.ROOT)
        // 先按别名换成中文关键词，再按「关键词 ⊂ 情绪串」匹配
        val normalized = ALIASES.entries.firstOrNull { lower.contains(it.key) }?.value ?: mood
        return PALETTES.firstOrNull { normalized.contains(it.first) }?.second ?: DEFAULT_PALETTE
    }

    /**
     * 渲染封面并落盘。
     *
     * @param id 唯一标识（歌单 id），用作文件名；同名会覆盖（重新设计封面即覆盖）
     * @return 生成的 PNG 文件；任何异常返回 null（调用方回落首曲封面）
     */
    fun render(context: Context, spec: Spec, id: String): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "$id.png")
        val bmp = draw(spec)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        file
    }.getOrNull()

    /** 纯绘制（不落盘，单测/预览可用）。 */
    fun draw(spec: Spec): Bitmap {
        val size = SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val palette = resolvePalette(spec.mood)

        // ── 底：对角渐变 ──
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                palette.from, palette.to, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        // ── 左上柔光（强调色 20%）──
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.18f, size * 0.12f, size * 0.72f,
                intArrayOf(withAlpha(palette.accent, 0.30f), withAlpha(palette.accent, 0f)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), glow)

        // ── 右下同心弧（强调色描边，极淡）──
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = withAlpha(palette.accent, 0.22f)
        }
        val cx = size * 1.02f
        val cy = size * 1.02f
        for (r in listOf(0.34f, 0.46f, 0.58f)) {
            canvas.drawCircle(cx, cy, size * r, arc)
        }

        // ── 小标签：AGENT PLAYLIST（字距拉开）──
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.22f
        }
        canvas.drawText("AGENT PLAYLIST", 96f, 300f, label)

        // ── 主标题：84px 粗体，最多两行（超长按字符截断加省略号）──
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 84f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLines = wrap(spec.title.ifBlank { "专属歌单" }, titlePaint, size - 96f * 2, maxLines = 2)
        var y = 420f
        titleLines.forEach { line ->
            canvas.drawText(line, 96f, y, titlePaint)
            y += 100f
        }

        // ── 副标题：34px 70% 白，最多两行 ──
        if (spec.subtitle.isNotBlank()) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(Color.WHITE, 0.72f)
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            y += 8f
            wrap(spec.subtitle, subPaint, size - 96f * 2, maxLines = 2).forEach { line ->
                canvas.drawText(line, 96f, y, subPaint)
                y += 46f
            }
        }

        // ── 强调短线 ──
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(96f, size - 190f, 96f + 88f, size - 190f, bar)

        // ── 页脚：N 首 · 情绪 · 模板 ──
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(Color.WHITE, 0.58f)
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val footText = buildString {
            if (spec.songCount > 0) append("${spec.songCount} 首 · ")
            append(spec.paletteName)
            spec.tags.take(3).forEach { append(" · ").append(it) }
        }
        canvas.drawText(footText, 96f, size - 138f, foot)

        // ── 右下角小字标（不是 Logo：封面不抢主体，只留一行归属）──
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(Color.WHITE, 0.34f)
            textSize = 24f
            letterSpacing = 0.12f
        }
        val markText = "KIBA"
        val markW = mark.measureText(markText)
        canvas.drawText(markText, size - 96f - markW, size - 96f, mark)

        // ── 内描边：给纯色封面一个边界，避免在白色卡片上「糊出去」──
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = withAlpha(Color.WHITE, 0.10f)
        }
        canvas.drawRoundRect(RectF(1.5f, 1.5f, size - 1.5f, size - 1.5f), 0f, 0f, border)

        return bmp
    }

    /**
     * 字符级换行：中文没有词边界，按「能在宽度内塞下最多字符」切。
     * 超出行数时最后一行尾部补省略号。
     */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (paint.measureText(sb.toString() + ch) > maxWidth && sb.isNotEmpty()) {
                lines.add(sb.toString())
                sb.clear()
                if (lines.size == maxLines) break
            }
            sb.append(ch)
        }
        if (lines.size < maxLines && sb.isNotEmpty()) lines.add(sb.toString())
        if (lines.size == maxLines) {
            // 还有没塞下的内容 → 末行补省略号
            val consumed = lines.sumOf { it.length }
            if (consumed < text.length) {
                var last = lines.last()
                while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) {
                    last = last.dropLast(1)
                }
                lines[lines.lastIndex] = "$last…"
            }
        }
        return lines
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
