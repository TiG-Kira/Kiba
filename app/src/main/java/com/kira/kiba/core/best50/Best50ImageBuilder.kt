package com.kira.kiba.core.best50

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.utils.CoverFetcher
import com.kira.kiba.core.utils.CoverSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

    /**
     * 纯算法版 `Color.rgb`：`0xFF000000 or (r shl 16) or (g shl 8) or b`，与
     * `android.graphics.Color.rgb` 逐位一致。用它替换全部取色，是为了让本类能在
     * JVM 单测里安全初始化——[computeRa] 被 Best50RaTest / MaimaiLxnsSyncTest 直接触达，
     * 而伴生/顶层 `val` 若在类初始化阶段调 android.graphics.Color，单测直接炸
     * 「Method rgb not mocked」。
     */
    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    /** 同上，带 alpha（对应 `Color.argb`）。 */
    private fun rgbAlpha(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

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
            val totalSd = old.take(35).sumOf { computeRa(it.ds, it.achievements, true) }
            val totalDx = new.take(15).sumOf { computeRa(it.ds, it.achievements, true) }
            runCatching {
                drawHeader(context, canvas, nickname, ratingForHeader, totalSd, totalDx)
            }

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

            // ── 底部 Rating.png 的四个大数字（深色玻璃条上统一白字）──
            val ratingImg = decode(context, "kiba_rating")
            if (ratingImg != null && old.isNotEmpty() && new.isNotEmpty()) {
                val c = Canvas(ratingImg)
                val andrea = font(context, "Andrea.otf")
                val b50ra = { r: RecordEntity -> computeRa(r.ds, r.achievements, true) }
                val ink = rgb(240, 246, 255)
                drawText(c, "${b50ra(old.first())}", 146f, 25f, 68f, ink, andrea)
                drawText(c, "${b50ra(old.last())}", 424f, 25f, 68f, ink, andrea)
                drawText(c, "${b50ra(new.first())}", 695f, 25f, 68f, ink, andrea)
                drawText(c, "${b50ra(new.last())}", 972f, 25f, 68f, ink, andrea)
                canvas.drawBitmap(ratingImg, 6f, 1660f, null)
            }
            // 右上小对话框 / 新旧曲分隔条已移除：底板 kiba_b50 自带统一的表头带、
            // 分区标题与合计槽位，再叠原生素材只会互相打架。

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

    /**
     * 表头（参考稿 111.png 定稿）：极简深色条，不要 KIBA 大字标，也不再贴
     * 名牌框 / Rating 框 / 段位牌 / 称号条那套 2019 年原生素材。
     *
     * 左：菱形 + PLAYER 标签 + 玩家名；右：DX RATING 标签 + 大数字 + OLD/NEW 两行；
     * 左下「BEST 35 / 旧曲」由底板 kiba_b50 印，代码不重复写。
     *
     * 大数字按 maimai 官方 Rating 段位代表色上色（见 ratingColorArgb），
     * 15000+ 虹段走 LinearGradient 彩虹，色值与 ui/common/DxRatingTag.kt 的
     * DxRatingTier.badgeColor() 同源，深色表头底上取其可读亮版。
     */
    private fun drawHeader(
        context: Context,
        canvas: Canvas,
        nickname: String,
        rating: Int,
        totalSd: Int,
        totalDx: Int,
    ) {
        val med = font(context, "HOS_Med.ttf")
        val andrea = font(context, "Andrea.otf")
        val exo = font(context, "Exo.ttf")
        val simHei = font(context, "adobe_simhei.otf")
        val ink = rgb(240, 246, 255)
        val dim = rgb(168, 184, 208)
        val faint = rgb(128, 146, 176)
        val cyan = rgb(77, 226, 230)
        val magenta = rgb(255, 95, 162)

        // 左上：菱形 + PLAYER + 玩家名
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
        val diamond = Path().apply {
            moveTo(39f, 19f); lineTo(46f, 26f); lineTo(39f, 33f); lineTo(32f, 26f); close()
        }
        canvas.drawPath(diamond, mark)
        drawText(canvas, "PLAYER", 54f, 14f, 13f, faint, med)
        drawText(canvas, toHalfWidth(nickname), 54f, 36f, 32f, ink, simHei)

        // DXNET 标识：原版 B50 左上角那颗「maimai でらっくす / DX NET」，原素材里就有
        // （kiba_ui_cmn_tabtitle_maimaititle_ver214.png，326×99，自带透明通道），
        // 直接按高度等比缩到 54（≈0.545×），不做任何抠图 / 重绘。
        // 位置：右缘 884（与 DX RATING 的 x=900 留 16px），**上下居中于「标签 + 大数字」
        // 这块墨迹** —— 标签 ink ≈21..30、数字 ink ≈60..112（Andrea 68px 起于 y=36），
        // 整块 21..112 中心 66.5，54 高的一半 27 → y ≈ 39.5，取 40。
        // 贴标签顶（y=18）会明显偏高（用户当场指出）。
        decode(context, "kiba_ui_cmn_tabtitle_maimaititle_ver214")?.let { logo ->
            val scaled = scale(logo, 54f / logo.height)
            canvas.drawBitmap(scaled, 884f - scaled.width, 40f, null)
        }

        // 右上：DX RATING + 官方段位色大数字
        drawText(canvas, "DX RATING", 900f, 18f, 15f, faint, med)
        drawRatingValue(canvas, "$rating", 896f, 36f, 68f, rating, andrea)

        // 数字右侧两行合计（参考稿是上下两行，不是并排）
        drawText(canvas, "OLD  $totalSd", 1105f, 38f, 17f, dim, exo)
        drawText(canvas, "NEW  $totalDx", 1105f, 68f, 17f, magenta, exo)
    }

    /**
     * 官方段位代表色（深色底可读亮版）。铜/银/金/铂四档只是**兜底色**，实际绘制走
     * [ratingStops] 的金属渐变——白 #F5FAFF / 银 #CFD8DC / 铂 #E6F3F7 三色太接近，
     * 平色画在深色表头上几乎分不出来（真机反证）。
     */
    private fun ratingColorArgb(rating: Int): Int = when {
        rating < 1000 -> rgb(245, 250, 255)  // 白
        rating < 2000 -> rgb(66, 165, 245)   // 蓝
        rating < 4000 -> rgb(102, 187, 106)  // 绿
        rating < 7000 -> rgb(253, 216, 53)   // 黄
        rating < 10000 -> rgb(239, 83, 80)   // 红
        rating < 12000 -> rgb(171, 71, 188)  // 紫
        rating < 13000 -> rgb(226, 145, 78)  // 铜（渐变中段）
        rating < 14000 -> rgb(110, 126, 146) // 银（渐变中段）
        rating < 14500 -> rgb(240, 188, 44)  // 金（渐变中段）
        rating < 15000 -> rgb(255, 243, 194) // 铂（渐变中段）
        else -> rgb(230, 243, 247)           // 虹段用 shader 覆盖
    }

    private val RAINBOW_STOPS = intArrayOf(
        rgb(239, 83, 80), rgb(255, 167, 38), rgb(255, 213, 79),
        rgb(102, 187, 106), rgb(66, 165, 245), rgb(171, 71, 188),
    )

    /** 铜：暖橙铜镜面（色相与原平色同源，只加明暗镀层，别往金色靠）。 */
    private val COPPER_STOPS = intArrayOf(
        rgb(255, 214, 170), rgb(226, 145, 78),
        rgb(140, 74, 34), rgb(255, 190, 130),
    )

    /** 银：冷灰镜面，双高光带（顶/底亮、中段压暗才有金属厚度；不压到灰就不像银）。 */
    private val SILVER_STOPS = intArrayOf(
        rgb(255, 255, 255), rgb(188, 204, 222),
        rgb(110, 126, 146), rgb(242, 250, 255),
    )

    /** 金：饱和琥珀金（比铂深一档、比铜黄一档）。 */
    private val GOLD_STOPS = intArrayOf(
        rgb(255, 238, 176), rgb(240, 188, 44),
        rgb(156, 104, 12), rgb(255, 212, 96),
    )

    /** 铂：maimai 官方「プラチナ」的亮金色——偏白的浅金，整体比金更亮更冷。 */
    private val PLATINUM_STOPS = intArrayOf(
        rgb(255, 255, 250), rgb(255, 243, 194),
        rgb(232, 190, 96), rgb(255, 252, 232),
    )

    /**
     * 段位金属渐变 stops；非金属段返回 null（用 [ratingColorArgb] 平色）。
     * 铜/银/金/铂 = 竖向渐变，虹段 = 横向彩虹 + 金属带 + 辉光（见 [drawRatingValue]）。
     */
    private fun ratingStops(rating: Int): IntArray? = when {
        rating >= 15000 -> RAINBOW_STOPS
        rating >= 14500 -> PLATINUM_STOPS
        rating >= 14000 -> GOLD_STOPS
        rating >= 13000 -> SILVER_STOPS
        rating >= 12000 -> COPPER_STOPS
        else -> null
    }

    /**
     * Rating 大数字：15000+ 虹段 = 横向彩虹 × 竖向金属带 + 轻微光晕；
     * 铜/银/金/铂竖向金属渐变（顶部亮 → 中段暗 → 底部亮，等于给数字镀了一层）；
     * 其余段位平色。
     */
    private fun drawRatingValue(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        rating: Int,
        typeface: Typeface?,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            color = ratingColorArgb(rating)
        }
        val stops = ratingStops(rating)
        val baseline = y - paint.fontMetrics.ascent
        // 字的墨迹盒：y 是 ascender 顶，字底 = y - ascent + descent
        val inkBottom = y - paint.fontMetrics.ascent + paint.fontMetrics.descent
        if (stops == null) {
            canvas.drawText(value, x, baseline, paint)
            return
        }
        if (rating < 15000) {
            paint.shader = LinearGradient(
                x, y, x, inkBottom, stops, null, Shader.TileMode.CLAMP,
            )
            canvas.drawText(value, x, baseline, paint)
            return
        }

        // ---- 虹段：彩虹本身只是底色，还要"镀层"和一点辉光 ----
        val rainbow = LinearGradient(
            x, y, x + paint.measureText(value).coerceAtLeast(1f), y,
            RAINBOW_STOPS, null, Shader.TileMode.CLAMP,
        )
        paint.shader = rainbow
        // 1) 光晕：同一彩虹色压到 alpha 70 再模糊 4px，烘在字下面（别做成霓虹灯）
        val halo = Paint(paint).apply {
            alpha = 70
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(value, x, baseline, halo)
        // 2) 彩虹本体
        canvas.drawText(value, x, baseline, paint)
        // 3) 金属带：中段压暗（MULTIPLY），上下边缘混白（SCREEN）——细笔画字号下
        //    只能靠这两条竖画上的明暗读出"金属"，压太狠整串会发灰
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            // 白 → 灰 → 白：MULTIPLY 只压中段，等于一条金属暗带
            shader = LinearGradient(
                0f, y, 0f, inkBottom,
                intArrayOf(rgb(255, 255, 255), rgb(179, 179, 179), rgb(255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        canvas.drawText(value, x, baseline, band)
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            shader = LinearGradient(
                0f, y, 0f, inkBottom,
                intArrayOf(
                    rgbAlpha(140, 255, 255, 255), 0,
                    0, rgbAlpha(140, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        canvas.drawText(value, x, baseline, spec)
    }

    // ---------- 单格（对应 _drawBestList 的单首绘制）----------

    private suspend fun drawTile(
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

        // 背景封面（走 UI 同款 Coil 候选链 + 磁盘缓存，含自定义图床 / lxns / EasyMai / 镜像）
        val cover = fetchCover(context, r.songId)
        val bg = if (cover != null) {
            val scaled = scaleToWidth(cover, ITEM_W)
            // 深色玻璃卡片上封面要能透出来，压暗比上游 0.72 轻一档
            darken(blur(cropCenter(scaled, ITEM_W, ITEM_H)), 0.64f)
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
        // 用原版游戏素材（白底 + 彩色 DX / 蓝底 SD），x 回到上游的 170：
        // 0.6 倍后 113×32 → 68×19，右缘 238，与旧版分享图一致。
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

        // 深色玻璃配色：主文字冷白、次文字灰蓝、ra 用青色强调
        val ink = rgb(240, 246, 255)
        val dim = rgb(190, 204, 224)
        val cyan = rgb(77, 226, 230)

        // 曲号（DX 老曲 +10000）
        val trackId = if (r.songId < 10000 && r.type == "DX") r.songId + 10000 else r.songId
        drawText(c, "$trackId", 48f, 6f, 13f, dim, font(context, "ExoM.ttf"))

        // 歌名（超 12 列截断到 11 列 + ...）
        drawText(c, trimTitle(r.title), 32f, 27f, 16f, ink, font(context, "adobe_simhei.otf"))

        // 达成率
        drawText(c, "%.4f%%".format(r.achievements), 30f, 42f, 26f,
            rgb(245, 252, 255), font(context, "Andrea.otf"))

        // 评级（MaimaiData 高清源 kiba_rank_* 600×267）
        // 旧 86×38 @ y=40 → 底边落在 78，**压过信息带上沿那条 74 的横线**（用户截图点名
        // "Rank 贴图底部和横线贴一起了"）。素材内容 bbox 是 y=12..258（≈1.7px 内边距），
        // 所以收到 80×35.6 @ y=36：内容 37.6..70.4，距横线还有 3.6px，上方距 SD/DX 徽章
        // （16..35.2）也留出 2px 以上。
        val rankIdx = RATE_KEYS.indexOf(r.rate.lowercase())
        if (rankIdx >= 0) {
            decode(context, "kiba_rank_${RANK_PIC[rankIdx].lowercase()}")?.let { img ->
                c.drawBitmap(scale(img, 80f / 600f), 159f, 36f, null)
            }
        }

        // 连击 / 同步（MaimaiData 高清源 kiba_combo_* 144×144，主圆内容 136px；
        // 旧 39×39×0.6 = 23.4px → 按 23.4f/136f 内容直径换算，画布中心对准原内容中心；
        // 无状态时画灰色占位圆 kiba_ui_mss_mbase_icon_blank，与页面显示一致）
        val comboIdx = FC_KEYS.indexOf(r.fc.lowercase()).coerceAtLeast(0)
        val comboAsset = if (comboIdx == 0) "kiba_ui_mss_mbase_icon_blank" else {
            "kiba_combo_${COMBO_PIC[comboIdx].lowercase()}"
        }
        val comboScale = if (comboIdx == 0) 0.6f else 23.4f / 136f
        val comboX = if (comboIdx == 0) 172f else 171.2f
        decode(context, comboAsset)?.let { c.drawBitmap(scale(it, comboScale), comboX, 77f, null) }

        val syncIdx = FS_KEYS.indexOf(r.fs.lowercase()).coerceAtLeast(0)
        val syncAsset = if (syncIdx == 0) "kiba_ui_mss_mbase_icon_blank" else {
            "kiba_combo_${SYNC_PIC[syncIdx].lowercase()}"
        }
        val syncScale = if (syncIdx == 0) 0.6f else 23.4f / 136f
        val syncX = if (syncIdx == 0) 201f else 200.2f
        decode(context, syncAsset)?.let { c.drawBitmap(scale(it, syncScale), syncX, 77f, null) }

        // DX 分 / 定数 / 百分比 / ra / 序号（信息带是深色玻璃，旧版黑字已不可读）
        drawText(c, "${r.dxScore} / $fullScore", 82f, 83f, 11f, dim, font(context, "HOS.ttf"))
        // 三个数值的间距收窄到 16/12px：旧值 36/90/135 中间空得发慌，像三列没对齐。
        // 而且必须**按基线**落字：13.6 / 101% / 286 是三档字号，用 ascender 顶对齐
        // 会各差 2~3px，看着就是「三个数字不平行」。
        val base = 114f
        drawTextBase(c, "${r.ds}", 34f, base, 18f, ink, font(context, "Exo.ttf"))
        val percent = if (fullScore > 0) (r.dxScore * 100 / fullScore) else 0
        drawTextBase(c, "$percent%", 84f, base, 13f, dim, font(context, "Exo.ttf"))
        drawTextBase(
            c, "${computeRa(r.ds, r.achievements, true)}", 124f, base, 21f,
            cyan, font(context, "ExoM.ttf"),
        )
        // 小字说明：光三个数字看不出哪个是定数、哪个是 DX 率、哪个是单曲 ra。
        // 9px 贴在同一条基线下 12px 处，不占新行，也不压信息带底边。
        val faint = rgbAlpha(150, 190, 204, 224)
        val fontZh = font(context, "adobe_simhei.otf")
        drawTextBase(c, "定数", 34f, 126f, 9f, faint, fontZh)
        drawTextBase(c, "DX率", 84f, 126f, 9f, faint, fontZh)
        drawTextBase(c, "单曲ra", 124f, 126f, 9f, faint, fontZh)
        // 序号基线与「定数 / DX率 / 单曲ra」同一条（126）：右列于是和左列共用三条网格线
        // —— 图标对压在「DX SCORE」那行（77..100.4），序号压在说明行（字形 116..127）。
        // 旧值（图标 81 / 基线 118）把右列挤成中间一坨，上不着顶下不着底，就是用户说的
        // 「视觉不平衡」；顺带也彻底解决「#33/35 和上面的元素贴太近」。
        drawTextBase(
            c, "#${num + 1}/$listSize", if (num >= 9) 177f else 181f, 126f, 13f,
            dim, font(context, "Andrea.otf"),
        )

        // alpha 反向遮罩
        applyAlphaMask(context, tile)
        canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
    }

    /**
     * 空位：底板 kiba_b50 已为每个槽位画了统一的玻璃底衬与描边，
     * 不再贴 noimage / 模糊块，留白比旧版干净，也不会出现半透明脏块。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawEmptyTile(context: Context, canvas: Canvas, x: Int, y: Int) = Unit

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

    /** 两趟轻量盒式模糊；深色玻璃卡片上封面要保留轮廓，半径比上游小一档。 */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        var pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { pixels = boxBlur(pixels, w, h, 1) }
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

    /**
     * 按**基线**落字：`y` 直接就是 baseline。
     *
     * 混排不同字号（13.6 = 18px / 101% = 13px / 286 = 21px）时，[drawText] 那种
     * 「y = ascender 顶」的写法会让三个数字各差 2~3px，肉眼看就是「不平行」。
     */
    private fun drawTextBase(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        typeface: Typeface?,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = typeface
        }
        canvas.drawText(text, x, baseline, paint)
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

    /** 封面：按 UI 同款候选链（自定义图床 → 水鱼 → lxns → EasyMai → 镜像）取位图，
     * 命中 Coil 磁盘缓存即秒回；全部失败退 noimage。 */
    private suspend fun fetchCover(context: Context, songId: Int): Bitmap? {
        val imageUrl = runCatching {
            AppDataBase.getInstance().songDao().getSongById(songId)?.imageUrl.orEmpty()
        }.getOrDefault("")
        return CoverFetcher.fetch(context, CoverSources.candidates(songId, imageUrl))
    }

    /**
     * 上游 computeRa —— **按机台口径**：`floor(ds × min(100.5, 达成率)/100 × 系数)`。
     *
     * 系数表以达成率（万分之一为单位）分档，与上游 MaimaiData 的
     * `ConvertUtils.achievementToRating` 逐档对齐：
     *
     * | 达成率 | 系数 |
     * |---|---|
     * | ≥100.5% | 22.4 |
     * | 100.4999% | 22.2 |
     * | 100.0% ~ 100.4998% | 21.6 |
     * | 99.9999% | 21.4 |
     * | 99.5% ~ 99.9998% | 21.1 |
     * | 99.0% ~ 99.4999% | 20.8 |
     * | 98.9999% | 20.6 |
     * | 98.0% ~ 98.9998% | 20.3 |
     * | 97.0% ~ 97.9999% | 20.0 |
     * | 96.9999% | 17.6 |
     * | 94.0% ~ 96.9998% | 16.8 |
     * | 90.0% ~ 93.9999% | 15.2 |
     * | 80.0% ~ 89.9999% | 13.6 |
     * | 79.9999% | 12.8 |
     * | 75.0% ~ 79.9998% | 12.0 |
     * | 70.0% ~ 74.9999% | 11.2 |
     * | 60.0% ~ 69.9999% | 9.6 |
     * | 50.0% ~ 59.9999% | 8.0 |
     * | 40.0% ~ 49.9999% | 6.4 |
     * | 30.0% ~ 39.9999% | 4.8 |
     * | 20.0% ~ 29.9999% | 3.2 |
     * | 10.0% ~ 19.9999% | 1.6 |
     * | <10% | 0 |
     *
     * 历史坑（2.3.0 修）：旧实现把整张表**错位了一档**——S(97~98) 给了 16.8（应为 20.0）、
     * SSS(100) 给了 21.1（应为 21.6）、≥100.5 只给到 21.6，还漏了 100.4999 / 98.9999
     * 这类「差一点点」的档位。结果机台上分并上传后，Best 50 里每首歌的 ra 与机台显示的
     * 对不上（差 1~8 点不等），汇总 Rating 也跟着偏小 —— 用户反馈第 4 条。
     *
     * @param spp 兼容旧调用点的占位参数（旧实现用它切换「旧曲」另一套系数）。
     *   机台对旧曲 / 新曲**用同一张表**，所以这里不再区分。
     */
    @Suppress("UNUSED_PARAMETER")
    fun computeRa(ds: Double, achievement: Double, spp: Boolean): Int {
        // 换算成「万分之一百分比」整数档位，与机台 / 上游一致（避免浮点边界踩错档）
        val achi = (achievement * 10_000).roundToLong().toInt()
        val baseRa = when {
            achi >= 1_005_000 -> 22.4
            achi == 1_004_999 -> 22.2
            achi >= 1_000_000 -> 21.6
            achi == 999_999 -> 21.4
            achi >= 995_000 -> 21.1
            achi >= 990_000 -> 20.8
            achi == 989_999 -> 20.6
            achi >= 980_000 -> 20.3
            achi >= 970_000 -> 20.0
            achi == 969_999 -> 17.6
            achi >= 940_000 -> 16.8
            achi >= 900_000 -> 15.2
            achi >= 800_000 -> 13.6
            achi == 799_999 -> 12.8
            achi >= 750_000 -> 12.0
            achi >= 700_000 -> 11.2
            achi >= 600_000 -> 9.6
            achi >= 500_000 -> 8.0
            achi >= 400_000 -> 6.4
            achi >= 300_000 -> 4.8
            achi >= 200_000 -> 3.2
            achi >= 100_000 -> 1.6
            else -> 0.0
        }
        if (baseRa <= 0.0 || ds <= 0.0) return 0
        val capped = minOf(1_005_000, achi)
        // achi 单位是「万分之一百分比」 → /1e4 得百分比，再 /100 得比例
        return (capped.toDouble() * ds * baseRa / 1_000_000.0).toInt()
    }
}
