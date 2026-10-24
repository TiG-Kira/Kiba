package com.kira.kiba.core.best50

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Best 50 图片生成器 —— 逐行移植自 KibaBot（KilluaSakata/Kiba）
 * `src/libraries/maimai_best_40.py` 的 `DrawBest`（b50 分支）。
 *
 * 与上游保持一致的要点：
 * - 底板 `b50.png`；旧曲（sd）35 首贴 `ROWS[i+1]+4`，新曲（dx）15 首贴 `ROWS[i+1]+1081`；
 *   列坐标统一 `COLUMNS[j]+4`，`i = num/5`、`j = num%5`。
 * - 单格 246×132：封面等比缩放到宽 246 → 居中裁高 132 → 高斯模糊(2) → 亮度 ×0.72
 *   → 叠 `mb.png` → 难度角标 → SD/DX 图标 → DX 星标 → 曲号 / 歌名 / 达成率
 *   → 评级 / 连击 / 同步图标 → DX 分 / 定数 / 百分比 / ra / 序号 → `alpha.png` 反向遮罩。
 * - 表头：标题 logo、DX Rating 底板（逐位数字）、段位牌、名牌（昵称逐字加空格）、
 *   称号条（`Best 50 底分模式`）、Splash、底部 `Rating.png` 四个大数字、
 *   右上小对话框（旧曲 / 新曲合计 ra）、`UI_RSL_MBase_Parts_01` 分隔条。
 * - ra 走上游 `computeRa(ds, achievement, spp=true)`：
 *   `floor(ds × min(100.5, 达成率)/100 × baseRa)`，baseRa 按达成率分档。
 *
 * 素材来自 KibaBot `src/static/static_pics.zip`（已随包打进 res/drawable），
 * 字体 `HOS / HOS_Med / Andrea / Exo / ExoM / adobe_simhei` 放在 assets/fonts。
 */
object Best50ImageBuilder {

    private const val TAG = "Best50Image"

    /** 最近一次失败原因（供 UI 提示，便于真机定位）。 */
    @Volatile
    var lastError: String = ""
        private set

    // ── 上游常量 ──
    private const val ITEM_W = 246
    private const val ITEM_H = 132

    /** 上游 ROWS_IMG（b50：1 + 8 行）。 */
    private val ROWS = listOf(2) + (0..7).map { 140 + 144 * it }

    /** 上游 COLOUMS_IMG（前 6 段；实际只用到前 5 列）。 */
    private val COLUMNS = (0..5).map { 2 + 258 * it }

    /** 新曲区相对 ROWS 的纵向偏移（b50 = 1081）。 */
    private const val DX_OFFSET = 1081

    /** 上游 _drawRating 的数字列。 */
    private val RATING_COLUMNS = listOf(74, 92, 110, 129, 147)

    /** 上游 rankPic（索引 = rate 档位序号）。 */
    private val RANK_PIC = listOf(
        "D", "C", "B", "BB", "BBB", "A", "AA", "AAA",
        "S", "Sp", "SS", "SSp", "SSS", "SSSp",
    )

    /** 上游 rate 串（水鱼 rate 字段）。 */
    private val RATE_KEYS = listOf(
        "d", "c", "b", "bb", "bbb", "a", "aa", "aaa",
        "s", "sp", "ss", "ssp", "sss", "sssp",
    )

    /** 上游 comboPic（索引 = fc 档位序号，0 = Blank）。 */
    private val COMBO_PIC = listOf("", "FC", "FCp", "AP", "APp")
    private val FC_KEYS = listOf("", "fc", "fcp", "ap", "app")

    /** 上游 syncPic（索引 = fs 档位序号，0 = Blank）。 */
    private val SYNC_PIC = listOf("", "FS", "FSp", "FSD", "FSDp")
    private val FS_KEYS = listOf("", "fs", "fsp", "fsd", "fsdp")

    /** 上游 diffpic：完整等级框（左侧难度彩条 + 黑框 + DX 分数条），盖在 mb 蒙版之上。 */
    private fun diffAsset(diff: Int): String = when (diff) {
        0 -> "kiba_ui_pfc_ms_info02_bsc"
        1 -> "kiba_ui_pfc_ms_info02_adv"
        2 -> "kiba_ui_pfc_ms_info02_exp"
        3 -> "kiba_ui_pfc_ms_info02_mst"
        4 -> "kiba_ui_pfc_ms_info02_mst_re"
        else -> "kiba_ui_pfc_ms_info02_mst"
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 生成 Best 50 图片（PNG 字节）。
     *
     * @param old 旧曲 Best 35（按 ra 降序）
     * @param new 新曲 Best 15（按 ra 降序）
     * @param nickname 昵称
     * @param rating Rating 合计（用于表头底板分档）
     */
    suspend fun build(
        context: Context,
        old: List<RecordEntity>,
        new: List<RecordEntity>,
        nickname: String,
        rating: Int,
        charts: List<ChartEntity> = emptyList(),
    ): ByteArray? = withContext(Dispatchers.IO) {
        chartCache.clear()
        charts.forEach { chartCache[it.songId to it.difficultyType.ordinal] = it }
        lastError = ""
        runCatching {
            val base = decode(context, "kiba_b50")
                ?.copy(Bitmap.Config.ARGB_8888, true)
                ?.apply { setDensity(Bitmap.DENSITY_NONE) }
                ?: run {
                    lastError = "底板 b50.png 缺失"
                    return@withContext null
                }
            val canvas = Canvas(base)

            val ratingForHeader = if (rating > 0) {
                rating
            } else {
                old.sumOf { computeRa(it.ds, it.achievements, true) } +
                    new.sumOf { computeRa(it.ds, it.achievements, true) }
            }
            runCatching { drawHeader(context, canvas, nickname, ratingForHeader) }

            // ── 旧曲（35）──
            val sdSize = 35
            old.take(sdSize).forEachIndexed { num, r ->
                val i = num / 5
                val j = num % 5
                if (i + 1 < ROWS.size) {
                    runCatching { drawTile(context, canvas, COLUMNS[j] + 4, ROWS[i + 1] + 4, r, num, old.size) }
                }
            }
            // 空位补 noimage（上游用 cover/1000.png）
            for (num in old.size until sdSize) {
                val i = num / 5
                val j = num % 5
                if (i + 1 < ROWS.size) runCatching { drawEmptyTile(context, canvas, COLUMNS[j] + 4, ROWS[i + 1] + 4) }
            }

            // ── 新曲（15）──
            val dxSize = 15
            new.take(dxSize).forEachIndexed { num, r ->
                val i = num / 5
                val j = num % 5
                if (i + 1 < ROWS.size) {
                    runCatching { drawTile(context, canvas, COLUMNS[j] + 4, ROWS[i + 1] + DX_OFFSET, r, num, new.size) }
                }
            }
            for (num in new.size until dxSize) {
                val i = num / 5
                val j = num % 5
                if (i + 1 < ROWS.size) runCatching { drawEmptyTile(context, canvas, COLUMNS[j] + 4, ROWS[i + 1] + DX_OFFSET) }
            }

            // ── 底部 Rating.png 的四个大数字 ──
            val ratingImg = decode(context, "kiba_rating")
            if (ratingImg != null && old.isNotEmpty() && new.isNotEmpty()) {
                val c = Canvas(ratingImg)
                val andrea = font(context, "Andrea.otf")
                val b50ra = { r: RecordEntity -> computeRa(r.ds, r.achievements, true) }
                drawText(c, "${b50ra(old.first())}", 146f, 25f, 68f, Color.WHITE, andrea)
                drawText(c, "${b50ra(old.last())}", 424f, 25f, 68f, Color.WHITE, andrea)
                drawText(c, "${b50ra(new.first())}", 695f, 25f, 68f, Color.BLACK, andrea)
                drawText(c, "${b50ra(new.last())}", 972f, 25f, 68f, Color.BLACK, andrea)
                canvas.drawBitmap(ratingImg, 6f, 1660f, null)
            }

            // ── 右上小对话框：旧曲 / 新曲合计 ──
            val totalSd = old.take(sdSize).sumOf { computeRa(it.ds, it.achievements, true) }
            val totalDx = new.take(dxSize).sumOf { computeRa(it.ds, it.achievements, true) }
            val board = decode(context, "kiba_ui_cmn_minidialog_01")
            if (board != null) {
                val c = Canvas(board)
                val med = font(context, "HOS_Med.ttf")
                drawText(c, "$totalSd", 75f, 144f, 48f, Color.BLACK, med)
                drawText(c, "$totalDx", 313f, 144f, 48f, Color.BLACK, med)
                val scaled = scale(board, 0.45f)
                canvas.drawBitmap(scaled, 1070f, 10f, null)
            }

            // ── 新旧曲分隔条 ──
            decode(context, "kiba_ui_rsl_mbase_parts_01")?.let { parts ->
                canvas.drawBitmap(scale(parts, 0.5f), 8f, 1187f, null)
            }

            val out = java.io.ByteArrayOutputStream()
            base.compress(Bitmap.CompressFormat.PNG, 100, out)
            Log.i(TAG, "built ${base.width}x${base.height} sd=$totalSd dx=$totalDx")
            out.toByteArray()
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "build failed", it)
        }.getOrNull()
    }

    // ---------- 表头（对应 DrawBest.draw）----------

    private fun drawHeader(context: Context, canvas: Canvas, nickname: String, rating: Int) {
        // 无 QQ 头像分支：左上角 maimai 标题 logo
        decode(context, "kiba_ui_cmn_tabtitle_maimaititle_ver214")?.let { logo ->
            canvas.drawBitmap(scale(logo, 0.65f), 10f, 35f, null)
        }

        // DX Rating 底板 + 逐位数字（右对齐 5 位）
        val baseImg = decode(context, raAsset(rating))?.copy(Bitmap.Config.ARGB_8888, true)
            ?.apply { setDensity(Bitmap.DENSITY_NONE) }
        if (baseImg != null) {
            val c = Canvas(baseImg)
            var value = rating
            var i = 4
            while (value > 0 && i >= 0) {
                val digit = value % 10
                value /= 10
                decode(context, "kiba_ui_num_drating_$digit")?.let { d ->
                    c.drawBitmap(scale(d, 0.7f), (RATING_COLUMNS[i] - 2).toFloat(), 14f, null)
                }
                i--
            }
            canvas.drawBitmap(scale(baseImg, 0.8f), 240f, 12f, null)
        }

        // 段位牌（无 additional_rating 时取 00）
        decode(context, "kiba_ui_cmn_daniplate_00")?.let { plate ->
            canvas.drawBitmap(scale(plate, 0.65f), 395f, 9f, null)
        }

        // 名牌：昵称逐字加空格
        val plateMask = decode(context, "kiba_ui_tst_platemask")
        if (plateMask != null) {
            val namePlate = Bitmap.createScaledBitmap(plateMask, 285, 40, true)
                .copy(Bitmap.Config.ARGB_8888, true)
                .apply { setDensity(Bitmap.DENSITY_NONE) }
            val c = Canvas(namePlate)
            val spaced = toHalfWidth(nickname).toList().joinToString(" ")
            drawText(c, spaced, 12f, 3f, 28f, Color.BLACK, font(context, "HOS.ttf"))
            decode(context, "kiba_ui_cmn_name_dx")?.let { dx ->
                c.drawBitmap(scale(dx, 0.9f), 230f, 4f, null)
            }
            canvas.drawBitmap(namePlate, 240f, 52f, null)
        }

        // 称号条
        val shougou = decode(context, "kiba_ui_cmn_shougou_rainbow")?.copy(Bitmap.Config.ARGB_8888, true)
            ?.apply { setDensity(Bitmap.DENSITY_NONE) }
        if (shougou != null) {
            val c = Canvas(shougou)
            val hos = font(context, "HOS.ttf")
            val info = "Best 50 底分模式"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f
                typeface = hos
            }
            val w = paint.measureText(info)
            drawText(
                c, info, ((shougou.width - w) / 2f), 5f, 14f,
                Color.rgb(104, 186, 255), hos,
            )
            canvas.drawBitmap(scale(shougou, 1.05f), 240f, 95f, null)
        }

        decode(context, "kiba_splash")?.let { splash ->
            canvas.drawBitmap(scale(splash, 0.2f), 685f, 2f, null)
        }
    }

    // ---------- 单格（对应 _drawBestList 的单首绘制）----------

    private fun drawTile(
        context: Context,
        canvas: Canvas,
        x: Int,
        y: Int,
        r: RecordEntity,
        num: Int,
        listSize: Int,
    ) {
        val tile = newBitmap(ITEM_W, ITEM_H)
        val c = Canvas(tile)

        // 背景封面
        val cover = fetchCover(context, r.songId)
        val bg = if (cover != null) {
            val scaled = scaleToWidth(cover, ITEM_W)
            darken(blur(cropCenter(scaled, ITEM_W, ITEM_H)), 0.72f)
        } else {
            val noimage = decode(context, "kiba_noimage")
            if (noimage != null) {
                cropCenter(
                    scaleToWidth(noimage, ITEM_W),
                    ITEM_W, ITEM_H,
                )
            } else {
                newBitmap(ITEM_W, ITEM_H)
            }
        }
        c.drawBitmap(bg, 0f, 0f, null)
        decode(context, "kiba_mb")?.let { c.drawBitmap(it, 0f, 0f, null) }

        val fullScore = fullDxScore(r)
        val dxper = dxStarLevel(r.dxScore, fullScore)

        // SD / DX 图标
        val modeAsset = when (r.type) {
            "SD" -> "kiba_ui_upe_infoicon_standardmode"
            "DX" -> "kiba_ui_upe_infoicon_deluxemode"
            else -> null
        }
        modeAsset?.let { asset ->
            decode(context, asset)?.let { icon ->
                c.drawBitmap(scale(icon, 0.6f), 170f, if (dxper == 0) 6f else 16f, null)
            }
        }
        // DX 星标
        if (dxper > 0) {
            decode(context, "kiba_dxstar_$dxper")?.let { star ->
                c.drawBitmap(scale(star, 0.65f), 170f, 4f, null)
            }
        }

        // 难度角标
        decode(context, diffAsset(r.levelIndex))?.let { c.drawBitmap(it, 0f, 0f, null) }

        // 曲号（DX 老曲 +10000）
        val trackId = if (r.songId < 10000 && r.type == "DX") r.songId + 10000 else r.songId
        drawText(c, "$trackId", 48f, 6f, 13f, Color.WHITE, font(context, "ExoM.ttf"))

        // 歌名（超 12 列截断到 11 列 + ...）
        drawText(c, trimTitle(r.title), 32f, 27f, 16f, Color.WHITE, font(context, "adobe_simhei.otf"))

        // 达成率
        drawText(c, "%.4f%%".format(r.achievements), 30f, 42f, 26f, Color.WHITE, font(context, "Andrea.otf"))

        // 评级
        val rankIdx = RATE_KEYS.indexOf(r.rate.lowercase())
        if (rankIdx >= 0) {
            decode(context, "kiba_ui_gam_rank_${RANK_PIC[rankIdx].lowercase()}")?.let { img ->
                c.drawBitmap(scale(img, 0.8f), 156f, 40f, null)
            }
        }

        // 连击 / 同步
        val comboIdx = FC_KEYS.indexOf(r.fc.lowercase()).coerceAtLeast(0)
        val comboAsset = if (comboIdx == 0) "kiba_ui_mss_mbase_icon_blank" else {
            "kiba_ui_mss_mbase_icon_${COMBO_PIC[comboIdx].lowercase()}_s"
        }
        decode(context, comboAsset)?.let { c.drawBitmap(scale(it, 0.6f), 172f, 81f, null) }

        val syncIdx = FS_KEYS.indexOf(r.fs.lowercase()).coerceAtLeast(0)
        val syncAsset = if (syncIdx == 0) "kiba_ui_mss_mbase_icon_blank" else {
            "kiba_ui_mss_mbase_icon_${SYNC_PIC[syncIdx].lowercase()}_s"
        }
        decode(context, syncAsset)?.let { c.drawBitmap(scale(it, 0.6f), 201f, 81f, null) }

        // DX 分 / 定数 / 百分比 / ra / 序号
        drawText(c, "${r.dxScore} / $fullScore", 82f, 76f, 11f, Color.BLACK, font(context, "HOS.ttf"))
        drawText(c, "${r.ds}", 36f, 102f, 18f, Color.BLACK, font(context, "Exo.ttf"))
        val percent = if (fullScore > 0) (r.dxScore * 100 / fullScore) else 0
        drawText(c, "$percent%", 90f, 105f, 13f, Color.BLACK, font(context, "Exo.ttf"))
        drawText(
            c, "${computeRa(r.ds, r.achievements, true)}", 135f, 99f, 21f,
            Color.BLACK, font(context, "ExoM.ttf"),
        )
        drawText(
            c, "#${num + 1}/$listSize", if (num >= 9) 177f else 181f, 105f, 13f,
            Color.BLACK, font(context, "Andrea.otf"),
        )

        // alpha 反向遮罩
        applyAlphaMask(context, tile)
        canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
    }

    /** 空位（上游用 cover/1000.png 铺底，这里退化为 noimage）。 */
    private fun drawEmptyTile(context: Context, canvas: Canvas, x: Int, y: Int) {
        val noimage = decode(context, "kiba_noimage") ?: return
        val scaled = scaleToWidth(noimage, ITEM_W)
        val tile = blur(cropCenter(scaled, ITEM_W, ITEM_H))
        applyAlphaMask(context, tile)
        canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
    }

    // ---------- 素材与绘制工具 ----------

    /**
     * 按名字取 drawable（资源名全部小写）。
     *
     * 必须 inMutable=true：`decodeResource` 默认返回不可变位图，
     * 直接 `Canvas(bitmap)` 会抛 `IllegalStateException: Immutable bitmap passed to Canvas constructor`
     * （Rating.png / 小对话框 / 称号条 都要在上面写字）。
     *
     * 同时把 density 归零（`DENSITY_NONE`）：`res/drawable` 里的 PNG 没有 dpi 限定，
     * 资源密度是 mdpi(160)，而 `Bitmap.createBitmap()` 建的中间图是设备密度（如 xxhdpi=480）。
     * `Canvas.drawBitmap` 在两者密度不同时会按 `canvasDensity / bitmapDensity` 自动缩放
     * （160/480 = 1/3），导致整格 246×132 被缩成 82×44、元素挤成一团。
     * density 归零后该缩放逻辑直接跳过（canvasDensity == 0），像素级 1:1 绘制。
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

    /** 新建位图时统一 density=none，避免被 Canvas 按密度自动缩放。 */
    private fun newBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setDensity(Bitmap.DENSITY_NONE)
        }

    /**
     * 缩放并强制 density=none。
     * `createScaledBitmap` 返回不可变位图，`setDensity` 对不可变位图会抛异常，
     * 所以这里先 copy 成可变副本再改 density。
     */
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

    /**
     * 等比缩放到指定宽度（density 归零）。
     * 上游是 `temp.resize(itemW / temp.size[0])`，即按宽度比例缩放。
     */
    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        val w = targetW.coerceAtLeast(1)
        val h = (src.height * (w.toFloat() / src.width)).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled.density == Bitmap.DENSITY_NONE) return scaled
        return scaled.copy(Bitmap.Config.ARGB_8888, true).apply {
            setDensity(Bitmap.DENSITY_NONE)
        }
    }

    /** 居中裁切（上游 crop 到 itemH）。 */
    private fun cropCenter(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = newBitmap(w, h)
        val c = Canvas(out)
        val srcRect = if (src.width * h > w * src.height) {
            val cropW = (src.height * w.toFloat() / h).roundToInt()
            val left = (src.width - cropW) / 2
            Rect(left, 0, left + cropW, src.height)
        } else {
            val cropH = (src.width * h.toFloat() / w).roundToInt()
            val top = (src.height - cropH) / 2
            Rect(0, top, src.width, top + cropH)
        }
        c.drawBitmap(src, srcRect, Rect(0, 0, w, h), null)
        return out
    }

    /** 两趟盒式模糊，近似 PIL GaussianBlur(2)。 */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        var pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { pixels = boxBlur(pixels, w, h, 2) }
        val out = newBitmap(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val n = 2 * r + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var rr = 0
                var gg = 0
                var bb = 0
                for (k in -r..r) {
                    val c = src[y * w + (x + k).coerceIn(0, w - 1)]
                    a += (c shr 24) and 0xFF
                    rr += (c shr 16) and 0xFF
                    gg += (c shr 8) and 0xFF
                    bb += c and 0xFF
                }
                tmp[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (gg / n shl 8) or (bb / n)
            }
        }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var rr = 0
                var gg = 0
                var bb = 0
                for (k in -r..r) {
                    val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    a += (c shr 24) and 0xFF
                    rr += (c shr 16) and 0xFF
                    gg += (c shr 8) and 0xFF
                    bb += c and 0xFF
                }
                out[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (gg / n shl 8) or (bb / n)
            }
        }
        return out
    }

    /** 上游 point(lambda p: p * factor)。 */
    private fun darken(src: Bitmap, factor: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = newBitmap(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 上游 putalpha(invert(alpha.png))。 */
    private fun applyAlphaMask(context: Context, tile: Bitmap) {
        val alphaSrc = decode(context, "kiba_alpha") ?: return
        val mask = if (alphaSrc.width == tile.width && alphaSrc.height == tile.height) {
            alphaSrc
        } else {
            Bitmap.createScaledBitmap(alphaSrc, tile.width, tile.height, true)
                .copy(Bitmap.Config.ARGB_8888, true)
                .apply { setDensity(Bitmap.DENSITY_NONE) }
        }
        val mw = mask.width
        val mh = mask.height
        val mp = IntArray(mw * mh)
        mask.getPixels(mp, 0, mw, 0, 0, mw, mh)
        val tp = IntArray(tile.width * tile.height)
        tile.getPixels(tp, 0, tile.width, 0, 0, tile.width, tile.height)
        for (i in tp.indices) {
            val m = mp.getOrElse(i) { 0 }
            val gray = (((m shr 16) and 0xFF) * 299 + ((m shr 8) and 0xFF) * 587 + (m and 0xFF) * 114) / 1000
            val a = 255 - gray
            tp[i] = (a shl 24) or (tp[i] and 0x00FFFFFF)
        }
        tile.setPixels(tp, 0, tile.width, 0, 0, tile.width, tile.height)
    }

    /**
     * 文本绘制：PIL 默认锚点是 ascender 线，Android 用基线，
     * 这里换算 baseline = y - metrics.ascent。
     */
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

    /** 上游 _findRaPic（b50 分档）。 */
    private fun raAsset(rating: Int): String {
        val num = when {
            rating < 1000 -> "01"
            rating < 2000 -> "02"
            rating < 4000 -> "03"
            rating < 7000 -> "04"
            rating < 10000 -> "05"
            rating < 12000 -> "06"
            rating < 13000 -> "07"
            rating < 14500 -> "08"
            rating < 15000 -> "09"
            else -> "10"
        }
        return "kiba_ui_cmn_dxrating_s_$num"
    }

    /** 上游 _Q2B：全角转半角。 */
    private fun toHalfWidth(s: String): String = buildString {
        for (ch in s) {
            val code = ch.code
            val converted = when {
                code == 0x3000 -> 0x0020
                code in 0xFF01..0xFF5E -> code - 0xFEE0
                else -> code
            }
            append(if (converted in 0x20..0x7E) converted.toChar() else ch)
        }
    }

    /** 上游 _getCharWidth：字符显示宽度（全角 2，半角 1）。 */
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

    /** 上游：宽度 > 12 列时截断到 11 列 + '...'。 */
    private fun trimTitle(title: String): String {
        if (columnWidth(title) <= 12) return title
        val sb = StringBuilder()
        var w = 0
        for (ch in title) {
            w += charWidth(ch.code)
            if (w > 11) break
            sb.append(ch)
        }
        return sb.append("...").toString()
    }

    /** 谱面缓存（外部注入，用于 DX 满分计算）。 */
    val chartCache: MutableMap<Pair<Int, Int>, ChartEntity> = mutableMapOf()

    /** 上游 full_dxScore_calc：notes 总数 × 3。 */
    private fun fullDxScore(r: RecordEntity): Int {
        val chart = chartCache[r.songId to r.levelIndex] ?: return 0
        return (chart.notesTap + chart.notesHold + chart.notesSlide + chart.notesTouch + chart.notesBreak) * 3
    }

    /** 上游 percent_dxScore_calc。 */
    private fun dxStarLevel(dxScore: Int, fullScore: Int): Int {
        if (fullScore <= 0) return 0
        val p = dxScore * 100.0 / fullScore
        return when {
            p >= 97 -> 5
            p >= 95 -> 4
            p >= 93 -> 3
            p >= 90 -> 2
            p >= 85 -> 1
            else -> 0
        }
    }

    /** 封面：水鱼 CDN（与 App 内一致），失败退 noimage。 */
    private fun fetchCover(context: Context, songId: Int): Bitmap? {
        val candidates = buildList {
            add(songId)
            val m10k = songId % 10000
            if (m10k != songId) add(m10k)
            val m100k = songId % 100000
            if (m100k != songId && m100k != m10k) add(m100k)
        }
        for (id in candidates) {
            val url = "https://www.diving-fish.com/covers/%05d.png".format(id)
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

    /**
     * 上游 computeRa：b50 走 spp=True 的 baseRa 分档。
     * `floor(ds × min(100.5, 达成率)/100 × baseRa)`
     */
    fun computeRa(ds: Double, achievement: Double, spp: Boolean): Int {
        val baseRa = when {
            achievement < 50 -> 0.0
            achievement < 60 -> if (spp) 8.0 else 5.0
            achievement < 70 -> if (spp) 9.6 else 6.0
            achievement < 75 -> if (spp) 11.2 else 7.0
            achievement < 80 -> if (spp) 12.0 else 7.5
            achievement < 90 -> if (spp) 13.6 else 8.5
            achievement < 94 -> if (spp) 15.2 else 9.5
            achievement < 97 -> if (spp) 16.8 else 10.5
            achievement < 98 -> if (spp) 20.0 else 12.5
            achievement < 99 -> if (spp) 20.0 else 12.7
            achievement < 99.5 -> if (spp) 20.8 else 13.0
            achievement < 100 -> if (spp) 21.1 else 13.2
            achievement < 100.5 -> if (spp) 21.6 else 13.5
            else -> if (spp) 22.4 else 14.0
        }
        return floor(ds * (minOf(100.5, achievement) / 100.0) * baseRa).toInt()
    }
}
