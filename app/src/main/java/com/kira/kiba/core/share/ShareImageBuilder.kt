package com.kira.kiba.core.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.network.MaimaiDataClient
import com.kira.kiba.core.stats.RelativeDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
 */
object ShareImageBuilder {

    private const val TAG = "ShareImage"

    /** 最近一次失败原因（供 UI 提示，便于真机定位）。 */
    @Volatile
    var lastError: String = ""
        private set

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

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
            val canvas = Canvas(base)

            // 封面 600×600 居中裁切铺满顶部
            val cover = cover600(context, song.id)
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
            drawText(canvas, "${song.id}", 70f, 618f, 13f, Color.BLACK, fontBold)
            // 歌名（超 30 列截断到 20 列 + ...）
            drawText(canvas, trimTitle(song.title), 33f, 660f, 28f, Color.BLACK, fontBoldL)
            // BPM（<100 左移 3px 并补前导空格，视觉居中）
            if (song.bpm >= 100) {
                drawText(canvas, "${song.bpm}", 511f, 637f, 34f, Color.BLACK, fontLV)
            } else {
                drawText(canvas, " ${song.bpm}", 508f, 637f, 34f, Color.BLACK, fontLV)
            }
            // 曲师
            drawText(canvas, song.artist, 37f, 705f, 19f, Color.BLACK, font)
            // 谱面信息卡：流派 / 版本
            drawText(canvas, song.genre, 61f, 850f, 19f, Color.BLACK, font)
            drawText(canvas, song.from, 61f, 940f, 19f, Color.BLACK, font)
            // 等级行 / 详细定级行
            drawText(
                canvas,
                sorted.joinToString("  -  ") { it.level },
                50f, 1065f, 34f, Color.BLACK, fontLV,
            )
            drawText(
                canvas,
                sorted.joinToString(" - ") { trimDs(it.ds) },
                50f, 1170f, 20f, Color.BLACK, fontBoldLV,
            )

            toPng(base)
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
            val canvas = Canvas(base)

            // 封面 600×600 居中裁切 + 难度角标（角标贴在封面上，再整体铺到底板）
            val cover = cover600(context, song.id)
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
            drawText(canvas, "${song.id}", 63f, 625f, 13f, Color.BLACK, fontBold)
            drawText(canvas, trimTitle(song.title), 33f, 670f, 28f, Color.BLACK, fontBoldL)

            // 难度等级（Andrea 40 白字；带 + 左移 4px）
            val levelX = if (chart.level.contains("+")) 493f else 497f
            drawText(canvas, chart.level, levelX, 657f, 40f, Color.WHITE, fontforLV)

            // 定数 / 曲师 / 谱师
            drawText(canvas, trimDs(chart.ds), 355f, 840f, 26f, Color.BLACK, fontBoldLV)
            drawText(canvas, song.artist, 33f, 715f, 19f, Color.BLACK, font)
            // 相对难度（上游 tag 槽位 (55,854)）：评级 + 括号内拟合定数；缺数据显示「暂时缺少数据」
            drawText(
                canvas,
                RelativeDifficulty.display(chart.ds, fitDiff),
                55f, 854f, 19f, Color.BLACK, font,
            )
            drawText(canvas, chart.charter, 55f, 936f, 19f, Color.BLACK, font)

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

            toPng(base)
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "buildChartImage failed", it)
        }.getOrNull()
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
    private fun cover600(context: Context, songId: Int): Bitmap? {
        val src = fetchCover(context, songId) ?: return null
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

    /** 封面：水鱼 CDN 多种编号形态，失败退 null（底板自带的装饰仍在）。 */
    private fun fetchCover(context: Context, songId: Int): Bitmap? {
        val candidates = buildList {
            add(songId)
            val m10k = songId % 10000
            if (m10k != songId) add(m10k)
            val m100k = songId % 100000
            if (m100k != songId && m100k != m10k) add(m100k)
        }
        for (id in candidates) {
            val url = MaimaiDataClient.DIVING_FISH_COVER_URL + "%05d".format(id) + ".png"
            val bmp = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.let { it.copy(Bitmap.Config.ARGB_8888, true) }
                        ?.apply { setDensity(Bitmap.DENSITY_NONE) }
                }
            }.getOrNull()
            if (bmp != null) return bmp
        }
        return null
    }

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
