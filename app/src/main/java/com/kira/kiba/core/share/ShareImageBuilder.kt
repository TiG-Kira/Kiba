package com.kira.kiba.core.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.stats.RelativeDifficulty
import com.kira.kiba.core.utils.CoverFetcher
import com.kira.kiba.core.utils.CoverSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 歌曲 / 谱面详情分享图生成器 —— 逐行移植自 KibaBot
 * `src/libraries/maimai_pic_generate.py` 的 `music_info`（id.png）与
 * `charts_info`（levelid.png）。
 *
 * 与上游保持一致的要点：
 * - 歌曲详情（id.png，600×1340）：封面 600×600 铺 (0,0) → 曲号 / 歌名（超 30 列截断
 *   20 列 + ...）/ BPM / 曲师 → 谱面信息卡两行：流派、版本 → 等级行（"  -  " 连接）、
 *   详细定级行（" - " 连接）。
 * - 谱面详情（levelid.png）：封面 600×600 + 难度角标（1BSC/ADV/EXP/MST/MST_Re，贴
 *   (5,8)，Re 贴 (8,8)）→ SD/DX 图标（0.8 倍，(480,26)）→ 曲号 / 歌名 / 难度等级
 *   （Andrea 40 白字，带 + 左移 4px）/ 定数 / 曲师 / 谱师 → 音符五列（TAP/HOLD/SLIDE/
 *   TOUCH/BREAK，按位数对齐）→ 音符总数。
 * - 字体：HOS / HOS_Med / Andrea（assets/fonts，与 Best50ImageBuilder 同源）。
 * - 文字锚点：PIL 是 ascender 线，Android 是基线，换算 baseline = y - ascent。
 *
 * **density 铁律**（踩过的坑）：底板在 res/drawable（mdpi=160），createBitmap 中间图
 * 与网络封面是设备密度（480），Canvas 会按 160/480 把元素缩成 1/3。所有位图
 * （decode / scale / crop / 封面）必须统一 `setDensity(DENSITY_NONE)`。
 *
 * **磨砂玻璃**：底板下半区（y>=600）是半透明面板，底下垫的是由封面派生的重度
 * 模糊图（见 [frostBackdrop]），所以最终成图是三张图叠出来的：磨砂底 → 底板 →
 * 顶部 600×600 清晰封面。别再把底板直接当画布用，否则下半区会露出透明像素。
 */
object ShareImageBuilder {

    private const val TAG = "ShareImage"

    /** 深色玻璃底板配色：旧版在亮青底板上写黑字，换底后必须整体改浅色。 */
    private val INK = Color.rgb(240, 246, 255)
    private val INK_DIM = Color.rgb(190, 204, 224)
    private val CYAN = Color.rgb(77, 226, 230)

    /** 底板下半区（磨砂玻璃面板）的起始高度，也是封面沿此线镜像的位置。 */
    private const val COVER_H = 600

    /** 磨砂玻璃参数：与 design_preview/render_preview.py 的 frost_backdrop 同值。 */
    private const val FROST_DIV = 14
    private const val FROST_SAT = 1.15f
    private const val FROST_BRIGHT = 0.92f
    private const val FROST_VEIL = 12

    /** 交叉淡化区间（卡片坐标）：起点越高 → 过渡越长，「图片在这里消失」的边越看不出。 */
    private const val FROST_FADE_FROM = 950
    private const val FROST_FADE_TO = 1340

    /** 最近一次失败原因（供 UI 提示，便于真机定位）。 */
    @Volatile
    var lastError: String = ""
        private set

    // ---------- 对外入口 ----------

    /**
     * 生成歌曲详情分享图（PNG 字节）。
     *
     * @param song  歌曲（曲号 / 歌名 / BPM / 曲师 / 流派 / 版本）
     * @param charts 全部难度谱面（等级 + 定数，按难度序）
     */
    suspend fun buildSongImage(
        context: Context,
        song: SongDataEntity,
        charts: List<ChartEntity>,
    ): ByteArray? = withContext(Dispatchers.IO) {
        lastError = ""
        runCatching {
            val base = decode(context, "kiba_id")
                ?.copy(Bitmap.Config.ARGB_8888, true)
                ?.apply { setDensity(Bitmap.DENSITY_NONE) }
                ?: run {
                    lastError = "底板 id.png 缺失"
                    return@withContext null
                }
            // 磨砂玻璃：模糊封面垫最下层 + 半透明底板 + 清晰封面盖顶部
            val card = newCard(base.width, base.height)
            val canvas = Canvas(card)

            // 封面 600×600 居中裁切铺满顶部（磨砂底图由它派生，见 frostBackdrop）
            val cover = cover600(context, song.id, song.imageUrl)
            canvas.drawBitmap(frostBackdrop(cover, card.width, card.height), 0f, 0f, null)
            canvas.drawBitmap(base, 0f, 0f, null)
            if (cover != null) {
                canvas.drawBitmap(cover, 0f, 0f, null)
            }

            val sorted = charts.sortedBy { it.difficultyType.ordinal }
            val fontBold = font(context, "HOS_Med.ttf")
            val fontBoldL = font(context, "HOS_Med.ttf")
            val fontLV = font(context, "HOS.ttf")
            val fontBoldLV = font(context, "HOS_Med.ttf")
            val font = font(context, "HOS.ttf")

            // 曲号
            drawText(canvas, "${song.id}", 70f, 618f, 13f, INK_DIM, fontBold)
            // 歌名（超 30 列截断到 20 列 + ...）
            drawText(canvas, trimTitle(song.title), 33f, 660f, 28f, INK, fontBoldL)
            // BPM（<100 左移 3px 并补前导空格，视觉居中），青色强调
            if (song.bpm >= 100) {
                drawText(canvas, "${song.bpm}", 511f, 637f, 34f, CYAN, fontLV)
            } else {
                drawText(canvas, " ${song.bpm}", 508f, 637f, 34f, CYAN, fontLV)
            }
            // 曲师
            drawText(canvas, song.artist, 37f, 705f, 19f, INK_DIM, font)
            // 谱面信息卡：流派 / 版本
            drawText(canvas, song.genre, 61f, 850f, 19f, INK, font)
            drawText(canvas, song.from, 61f, 940f, 19f, INK, font)
            // 等级行 / 详细定级行
            drawText(
                canvas,
                sorted.joinToString("  -  ") { it.level },
                50f, 1065f, 34f, INK, fontLV,
            )
            drawText(
                canvas,
                sorted.joinToString(" - ") { trimDs(it.ds) },
                50f, 1170f, 20f, INK_DIM, fontBoldLV,
            )

            toPng(card)
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "buildSongImage failed", it)
        }.getOrNull()
    }

    /**
     * 生成谱面详情分享图（PNG 字节）。
     *
     * @param song  歌曲（曲号 / 歌名 / 曲师）
     * @param chart 目标难度谱面（定数 / 谱师 / 音符构成 / SD-DX）
     */
    suspend fun buildChartImage(
        context: Context,
        song: SongDataEntity,
        chart: ChartEntity,
        fitDiff: Double? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        lastError = ""
        runCatching {
            val base = decode(context, "kiba_levelid")
                ?.copy(Bitmap.Config.ARGB_8888, true)
                ?.apply { setDensity(Bitmap.DENSITY_NONE) }
                ?: run {
                    lastError = "底板 levelid.png 缺失"
                    return@withContext null
                }
            val card = newCard(base.width, base.height)
            val canvas = Canvas(card)

            // 封面 600×600 居中裁切 + 难度角标（角标贴在封面上，再整体铺到底板）
            // 磨砂底必须先做：角标画进封面之后再派生底图，玻璃里会映出角标的残影。
            val cover = cover600(context, song.id, song.imageUrl)
            canvas.drawBitmap(frostBackdrop(cover, card.width, card.height), 0f, 0f, null)
            canvas.drawBitmap(base, 0f, 0f, null)
            if (cover != null) {
                val badge = decode(context, diffAsset(chart.difficultyType))
                if (badge != null) {
                    val badgeCopy = badge.copy(Bitmap.Config.ARGB_8888, true)
                        .apply { setDensity(Bitmap.DENSITY_NONE) }
                    val c = Canvas(cover)
                    val bx = if (chart.difficultyType == DifficultyType.REMASTER) 8 else 5
                    c.drawBitmap(badgeCopy, bx.toFloat(), 8f, null)
                }
                canvas.drawBitmap(cover, 0f, 0f, null)
            }

            // SD / DX 图标（App 内同款贴图，0.8 倍）
            val modeAsset = when (chart.type) {
                "DX" -> "kiba_ui_upe_infoicon_deluxemode"
                else -> "kiba_ui_upe_infoicon_standardmode"
            }
            decode(context, modeAsset)?.let { icon ->
                canvas.drawBitmap(scale(icon, 0.8f), 480f, 26f, null)
            }

            val fontBold = font(context, "HOS_Med.ttf")
            val fontBoldL = font(context, "HOS_Med.ttf")
            val fontLV = font(context, "HOS.ttf")
            val fontBoldLV = font(context, "HOS_Med.ttf")
            val fontforLV = font(context, "Andrea.otf")
            val font = font(context, "HOS.ttf")

            // 曲号 / 歌名
            drawText(canvas, "${song.id}", 63f, 625f, 13f, INK_DIM, fontBold)
            drawText(canvas, trimTitle(song.title), 33f, 670f, 28f, INK, fontBoldL)

            // 难度等级（Andrea 40 白字；带 + 左移 4px）
            // 框是素材里的 (476,626)-(570,706)，y=657 时墨迹落在 673..700、
            // 底部只剩 6px 而顶部与 LEVEL 标签隔 30px（真机看着吊在框底）。
            // 上移到 646 后墨迹 662..689：上 19px / 下 17px，视觉居中。
            val levelX = if (chart.level.contains("+")) 493f else 497f
            drawText(canvas, chart.level, levelX, 646f, 40f, Color.WHITE, fontforLV)

            // 定数 / 曲师 / 谱师
            drawText(canvas, trimDs(chart.ds), 355f, 840f, 26f, INK, fontBoldLV)
            drawText(canvas, song.artist, 33f, 715f, 19f, INK_DIM, font)
            // 相对难度（上游 tag 槽位 (55,854)）：评级 + 括号内拟合定数；缺数据显示「暂时缺少数据」
            drawText(
                canvas,
                RelativeDifficulty.display(chart.ds, fitDiff),
                55f, 854f, 19f, INK, font,
            )
            drawText(canvas, chart.charter, 55f, 936f, 19f, INK, font)

            // 音符五列（按位数对齐 x）
            drawNote(canvas, chart.notesTap, 41f, 49f, 57f, fontLV)
            drawNote(canvas, chart.notesHold, 153f, 164f, 172f, fontLV)
            drawNote(canvas, chart.notesSlide, 273f, 281f, 289f, fontLV)
            if (chart.type == "DX") {
                drawNote(canvas, chart.notesTouch, 387f, 395f, 403f, fontLV)
            } else {
                // SD 谱面无 TOUCH：上游画占位 --
                drawText(canvas, "--", 398f, 1100f, 36f, Color.WHITE, fontLV)
            }
            drawNote(canvas, chart.notesBreak, 502f, 510f, 518f, fontLV)

            // 音符总数
            drawText(
                canvas, "${chart.notesTotal}", 355f, 927f, 26f, Color.WHITE, fontBoldLV,
            )

            toPng(card)
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "buildChartImage failed", it)
        }.getOrNull()
    }

    // ---------- 磨砂玻璃底图 ----------

    private fun newCard(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setDensity(Bitmap.DENSITY_NONE)
        }

    /**
     * 磨砂玻璃底图：封面铺顶部 + 沿 y=[COVER_H] 镜像一次，余下用主题色渐变压底。
     *
     * - 底板 kiba_id / kiba_levelid 的下半区是**半透明**面板（见 build_assets
     *   的 FROST_ALPHAS），透出来的就是这张图；旧版底板死黑不透明，封面到 y=600
     *   直接断掉，像贴了张不透明纸。
     * - 映射方式踩过：把 600×600 纵向拉伸到 600×1340 会拉出竖条纹（看着像运动
     *   模糊）；「上下翻转」接缝正好落在封面底边那一行，翻下去等于官方插画自然延续。
     * - 旧版为了铺满还补了**第三段正向封面**，结果卡片底部又出现一次标题/人脸，
     *   像贴图没对齐。现在只翻一次（600→1200），剩余高度用「主题色 → 深底」的
     *   交叉淡化收尾：从 y≈950 起每行按 smoothstep 权重把图像混向主题色渐变，
     *   到卡片底边才是纯深底。等于「插画下缘渐隐进玻璃」，尺寸不足看不出来。
     * - 模糊走「降采样到 1/[FROST_DIV] → 双线性放大」，Android 没有 RenderScript
     *   了，这条路的开销只跟小图有关，比 RenderEffect 省一个数量级。
     * - 参数与 design_preview/render_preview.py 的 frost_backdrop 一一对应，改要同步。
     */
    private fun frostBackdrop(cover: Bitmap?, w: Int, h: Int): Bitmap {
        val back = newCard(w, h)
        if (cover == null) {
            // 拿不到封面时给一块纯深色，至少保证成图不透明（旧版这里会留透明区）
            Canvas(back).drawColor(Color.rgb(9, 13, 23))
            return back
        }
        val sw = (w / FROST_DIV).coerceAtLeast(1)
        val sh = (h / FROST_DIV).coerceAtLeast(1)
        // 小图高度按 card 坐标系换算，保证镜像接缝仍在 y=600 那条线上
        val tileH = (COVER_H.toFloat() * sh / h).coerceAtLeast(1f)
        val tile = downscaleSmooth(cover, sw, tileH.roundToInt().coerceAtLeast(1))

        val small = newCard(sw, sh)
        val sc = Canvas(small)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = RectF(0f, 0f, sw.toFloat(), tileH)
        sc.drawBitmap(tile, null, dst, filter)
        sc.save()
        sc.translate(0f, tileH * 2f)
        sc.scale(1f, -1f)
        sc.drawBitmap(tile, null, dst, filter)
        sc.restore()
        // 镜像到 2*tileH 为止（=y1200）；余下用「主题色 → 深底」的交叉淡化收尾。
        // 旧版在 y=1200 处整行切换（上一行还是图、下一行直接是纯色渐变），那条 0 宽度
        // 的边界就是用户看到的「图片消失的线」。现在改为同行混合：从 FROST_FADE_FROM
        // 起，每行按 smoothstep 权重 t 把图像混向 lerp(主题色, 深底, t)，图片是被逐渐
        // 稀释掉的 —— 相邻两行只差一点点，找不到「开始变暗」的那一行。
        val seamRow = (tileH * 2f).roundToInt().coerceIn(0, sh)
        val dark = Color.rgb(9, 13, 23)
        // 主题色 = 镜像块最后一行（= 缩略图首行）的真实均值，也就是这张曲绘自己的颜色
        val theme = averageRowColor(tile)
        if (seamRow < sh) {
            // 接缝以下先铺主题色，保证整卡不透明（否则透明区上叠加会留半透明）
            val bg = Paint()
            bg.color = theme
            sc.drawRect(0f, seamRow.toFloat(), sw.toFloat(), sh.toFloat(), bg)
        }
        val fadeFrom = (FROST_FADE_FROM.toFloat() * sh / h).roundToInt().coerceIn(0, sh - 1)
        val fadeTo = (FROST_FADE_TO.toFloat() * sh / h).roundToInt().coerceIn(fadeFrom + 1, sh)
        val fadeSpan = (fadeTo - fadeFrom).coerceAtLeast(1)
        val fade = Paint()
        for (y in fadeFrom until sh) {
            val raw = ((y - fadeFrom).toFloat() / fadeSpan).coerceIn(0f, 1f)
            val t = raw * raw * (3f - 2f * raw)
            val target = blendColor(theme, dark, t)
            // alpha = t 的实色矩形压在已有像素上 = 按 t 向目标色混合
            fade.color = Color.argb(
                (t * 255f).roundToInt(),
                Color.red(target), Color.green(target), Color.blue(target),
            )
            sc.drawRect(0f, y.toFloat(), sw.toFloat(), (y + 1).toFloat(), fade)
        }

        // 提饱和（否则压暗后退成灰雾）+ 压亮度；ColorMatrix 没有 postScale，
        // 用一个对角缩放矩阵 postConcat（乘在饱和之后，不会覆盖 setSaturation）
        val boost = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(FROST_SAT)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            FROST_BRIGHT, 0f, 0f, 0f, 0f,
                            0f, FROST_BRIGHT, 0f, 0f, 0f,
                            0f, 0f, FROST_BRIGHT, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
            })
        }
        val c = Canvas(back)
        c.drawBitmap(small, null, Rect(0, 0, w, h), boost)
        // 奶白薄雾：真实磨砂玻璃不是"变暗的照片"，是"带散射的暗玻璃"
        c.drawColor(Color.argb(FROST_VEIL, 255, 255, 255))
        return back
    }

    /** 线性插值两个不透明色（t=0 取 a，t=1 取 b），给逐行渐变用。 */
    private fun blendColor(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255),
    )

    /**
     * 取位图顶部若干行的平均色——镜像块的底边就是这个颜色，用它做渐变起点
     * 才能保证 y=1200 处严丝合缝；同时也等于这张曲绘自己的主题色。
     */
    private fun averageRowColor(bmp: Bitmap): Int {
        if (bmp.width <= 0 || bmp.height <= 0) return Color.rgb(9, 13, 23)
        // 只要第 0 行：镜像块的底边正好是这一行，多取几行反而会让起色偏亮
        val rows = 1
        val px = IntArray(bmp.width * rows)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, rows)
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in px) {
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = px.size.coerceAtLeast(1)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** 逐级减半降采样：一步缩 14 倍会丢采样点（出噪点），分几步缩才平滑。 */
    private fun downscaleSmooth(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width / 2 > w) {
            val nw = cur.width / 2
            val nh = (cur.height.toFloat() * nw / cur.width).roundToInt().coerceAtLeast(1)
            cur = Bitmap.createScaledBitmap(cur, nw, nh, true)
        }
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (out.density != Bitmap.DENSITY_NONE) out.setDensity(Bitmap.DENSITY_NONE)
        return out
    }

    // ---------- 素材与绘制工具（与 Best50ImageBuilder 同套安全规范） ----------

    private fun diffAsset(diff: DifficultyType): String = when (diff) {
        DifficultyType.BASIC -> "kiba_1bsc"
        DifficultyType.ADVANCED -> "kiba_1adv"
        DifficultyType.EXPERT -> "kiba_1exp"
        DifficultyType.MASTER -> "kiba_1mst"
        DifficultyType.REMASTER -> "kiba_1mst_re"
        else -> "kiba_1mst"
    }

    /**
     * 按名字取 drawable。inMutable + inScaled=false + density 归零，理由详见
     * [com.kira.kiba.core.best50.Best50ImageBuilder.decode] 的注释（mdpi 资源被
     * 设备密度中间图按 1/3 缩放的坑）。
     */
    private fun decode(context: Context, name: String): Bitmap? = runCatching {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) {
            Log.w(TAG, "drawable missing: $name")
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
        }
        val bmp = BitmapFactory.decodeResource(context.resources, id, opts)
            ?: BitmapFactory.decodeResource(context.resources, id)?.copy(Bitmap.Config.ARGB_8888, true)
            ?: return null
        bmp.setDensity(Bitmap.DENSITY_NONE)
        bmp
    }.getOrNull()

    /** 缩放并强制 density=none（createScaledBitmap 返回不可变位图，先 copy 再改）。 */
    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).roundToInt().coerceAtLeast(1)
        val h = (src.height * factor).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled.density == Bitmap.DENSITY_NONE) return scaled
        return scaled.copy(Bitmap.Config.ARGB_8888, true).apply {
            setDensity(Bitmap.DENSITY_NONE)
        }
    }

    private val fontCache = mutableMapOf<String, Typeface?>()

    private fun font(context: Context, assetName: String): Typeface? =
        fontCache.getOrPut(assetName) {
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$assetName") }.getOrNull()
        }

    /** 文本绘制：PIL 锚点是 ascender 线，Android 是基线，baseline = y - ascent。 */
    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        typeface: Typeface?,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = typeface
        }
        val baseline = y - paint.fontMetrics.ascent
        canvas.drawText(text, x, baseline, paint)
    }

    /** 音符数字：按位数选 x（上游 tap/hold/slide/touch/break 同款三段对齐）。 */
    private fun drawNote(
        canvas: Canvas,
        value: Int,
        x100: Float,
        x10: Float,
        x1: Float,
        typeface: Typeface?,
    ) {
        val x = when {
            value >= 100 -> x100
            value >= 10 -> x10
            else -> x1
        }
        drawText(canvas, "$value", x, 1100f, 36f, Color.WHITE, typeface)
    }

    /**
     * 封面（600×600 居中裁切）：网络封面尺寸不固定，需在铺到底板前裁剪到与封面区
     * 同尺寸，否则会铺不满顶部/错位。沿用 Best50ImageBuilder 的 cropCenter 思路。
     */
    private suspend fun cover600(context: Context, songId: Int, imageUrl: String): Bitmap? {
        val src = fetchCover(context, songId, imageUrl) ?: return null
        if (src.width == 600 && src.height == 600) return src
        return cropCenter(src, 600, 600)
    }

    /** 居中裁切到目标尺寸（保持比例，取中间区块）。 */
    private fun cropCenter(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setDensity(Bitmap.DENSITY_NONE)
        val c = Canvas(out)
        val (sx, sy, sw, sh) = if (src.width * h > w * src.height) {
            val cropW = (src.height * w.toFloat() / h).roundToInt()
            listOf((src.width - cropW) / 2, 0, cropW, src.height)
        } else {
            val cropH = (src.width * h.toFloat() / w).roundToInt()
            listOf(0, (src.height - cropH) / 2, src.width, cropH)
        }
        c.drawBitmap(
            src,
            android.graphics.Rect(sx, sy, sx + sw, sy + sh),
            android.graphics.Rect(0, 0, w, h),
            null,
        )
        return out
    }

    /** 封面：按 UI 同款候选链（自定义图床 → 水鱼 → lxns → EasyMai → 镜像）取位图，
     * 命中 Coil 磁盘缓存即秒回；全部失败退 null（底板自带装饰仍在）。 */
    private suspend fun fetchCover(context: Context, songId: Int, imageUrl: String): Bitmap? =
        CoverFetcher.fetch(context, CoverSources.candidates(songId, imageUrl))

    private fun toPng(base: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        base.compress(Bitmap.CompressFormat.PNG, 100, out)
        Log.i(TAG, "built ${base.width}x${base.height}")
        return out.toByteArray()
    }

    /** 定数显示：整数去小数点（14.0 → 14），非整数保留一位。 */
    private fun trimDs(ds: Double): String {
        val i = ds.toInt()
        return if (ds == i.toDouble()) "$i" else "%.1f".format(ds)
    }

    // ---------- 上游 _getCharWidth / coloumWidth / changeColumnWidth ----------

    private fun charWidth(code: Int): Int {
        if (code == 0xE || code == 0xF) return 0
        val table = intArrayOf(
            126, 1, 159, 0, 687, 1, 710, 0, 711, 1, 727, 0, 733, 1, 879, 0, 1154, 1, 1161, 0,
            4347, 1, 4447, 2, 7467, 1, 7521, 0, 8369, 1, 8426, 0, 9000, 1, 9002, 2, 11021, 1,
            12350, 2, 12351, 1, 12438, 2, 12442, 0, 19893, 2, 19967, 1, 55203, 2, 63743, 1,
            64106, 2, 65039, 1, 65059, 0, 65131, 2, 65279, 1, 65376, 2, 65500, 1, 65510, 2,
            120831, 1, 262141, 2, 1114109, 1,
        )
        var i = 0
        while (i < table.size) {
            if (code <= table[i]) return table[i + 1]
            i += 2
        }
        return 1
    }

    private fun columnWidth(s: String): Int = s.sumOf { charWidth(it.code) }

    /** 上游：宽度 > 30 列时截断到 20 列 + '...'。 */
    private fun trimTitle(title: String): String {
        if (columnWidth(title) <= 30) return title
        val sb = StringBuilder()
        var w = 0
        for (ch in title) {
            w += charWidth(ch.code)
            if (w > 20) break
            sb.append(ch)
        }
        return sb.append("...").toString()
    }
}
