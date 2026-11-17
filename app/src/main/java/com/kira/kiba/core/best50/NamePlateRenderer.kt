package com.kira.kiba.core.best50

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.kira.kiba.core.account.loadAccountAvatarBitmap
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.DxRatingTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * maimai 姓名框（nameplate）渲染器 —— **严格按 Sinmai 的贴法复刻**。
 *
 * 坐标全部来自游戏 prefab `UI_UserInformation / UI_User`（`Sub/BackGroundCanvas` 一支），
 * 换算成名牌底图的像素坐标（720×116，原点左下）后为：
 *
 * | 节点            | 尺寸    | x     | y(下起) |
 * |-----------------|---------|-------|---------|
 * | IMG_NamePlate   | 720×116 |   0.0 |   0.0   |
 * | IMG_Icon        | 100×100 |   8.6 |   8.7   |
 * | IMG_Bg（名字底）| 276× 48 | 109.0 |  30.0   |
 * | IMG_DXRating    | 176× 40 | 109.0 |  72.7   |
 * | NUM_Rateing     | 150× 34 | 128.0 |  75.7   |
 * | IMG_Title       | 276× 36 | 108.0 |  -0.7   |
 * | UI_Title(文字)  | 255× 33 | 120.5 |   3.3   |
 * | TMP_Name        | 175× 36 | 118.5 |  35.0   |
 *
 * **所有素材等比缩放，绝不拉伸**：底图 720×116 原生，徽章 `badge_xx`(176×40) 原生，
 * 称号条 `title_gold`(276×36) 原生 —— 目标尺寸与素材同尺寸，只是乘一个统一的 scale。
 *
 * 名字底板不是一块纯白圆角矩形，而是精灵 `UI_UPE_Name_Base_01`（36×48，九宫格
 * 边界 16/23/16/23）：贴图 32×44 四周留 2px 透明边、白底圆角半径仅约 3.5px，
 * `IMG_Bg` 上还挂着一个 `Shadow`（灰 0.62、偏移 (1,-1)、useGraphicAlpha）。
 * 所以这里按「内缩 2px + 半径 3.5 + 1px 灰阴影」还原 —— 之前用 9px 大圆角纯白块，
 * 白框看着和图案糊在一起，就是因为丢了这两点。
 *
 * 名字用等宽单元格排字：游戏里 `TMP_Name` 是 Morisawa UD 新ゴ，每个字符占一个
 * 全角格（`TiG†Kira` 的字距接近字身一样宽）。这里用 MiSans Normal 逐字落进
 * `1em` 单元格，粗细与节奏才和游戏对得上（旧实现用黑体 Bold 挤在一起）。
 *
 * 与游戏的两点有意的差异（用户确认过）：
 * 1. 不做 396 宽遮罩裁切 —— 整张 720 宽的名牌都要露出来（截图里也是整张）；
 * 2. 跳过 SSS5 / 真八段这类段位徽章，称号条内文改为「B35 / B15」独立 Rating。
 */
object NamePlateRenderer {

    /** 名牌底图原生尺寸（也是整套坐标系的单位）。 */
    const val PLATE_W = 720f
    const val PLATE_H = 116f

    /** 卡片宽高比：直接沿用素材比例，UI 侧按它锁 aspectRatio。 */
    const val ASPECT = PLATE_W / PLATE_H

    // ── prefab 矩形（x, y下起, w, h）──
    private const val ICON_X = 8.6f
    private const val ICON_Y = 8.7f
    private const val ICON_S = 100f

    private const val NAME_BG_X = 109f
    private const val NAME_BG_Y = 30f
    private const val NAME_BG_W = 276f
    private const val NAME_BG_H = 48f

    private const val BADGE_X = 109f
    private const val BADGE_Y = 72.7f
    private const val BADGE_W = 176f
    private const val BADGE_H = 40f

    /**
     * 5 位数的落位 —— 相对 **176×40 徽章贴图**（自左上）。
     *
     * prefab 的 NUM_Rateing 是个 `SpriteCounter`（不是文字！），它把
     * `UI_CMN_Num_26p_0..9` 这套数字精灵逐位贴出来；徽章贴图
     * `UI_CMN_DXRating_S_*` 里那 5 个暗槽位实测在 x 85..161 / y 9..30，
     * 格间还有 0x46 的分隔线，5 格各 15.2 宽。
     *
     * ⚠️ DX 评分数字 **不是** SEGA_NewRodinN 排出来的 —— 那套字体是达成率/曲名用的。
     */
    private const val NUM_SLOT_X = 85f
    private const val NUM_SLOT_TOP = 9f
    private const val NUM_SLOT_W = 76f
    private const val NUM_SLOT_H = 21f

    /** 数字精灵 `UI_CMN_Num_26p_*` 原生 34×40，其中墨迹高 28（上 5 / 下 6 的留白）。 */
    private const val NUM_SPRITE_W = 34f
    private const val NUM_SPRITE_H = 40f
    private const val NUM_INK_H = 28f

    /**
     * 数字墨迹高 / 槽高。
     *
     * 游戏截图里槽 32px、数字墨迹 20px → 0.625；换算到名牌坐标就是槽 21 → 墨迹 ≈13.6。
     * 5 位按 15.2 的格距排，正好和游戏里「数字几乎贴在一起」的观感一致。
     */
    private const val NUM_INK_RATIO = 0.625f

    /** `NUM_Rateing` 的 `SpriteCounter.m_Color`（prefab 原值 1 / 0.796 / 0.004）。 */
    internal const val NUM_COLOR = 0xFFFFCB01.toInt()

    /**
     * 数字底下那层 `UI_CMN_Num_26p_Outline_*` 的颜色。
     *
     * 精灵本身是比数字外扩约 4px 的白色实心剪影，游戏把它染成近黑垫在下面，
     * 于是金色数字外缘压着一条细黑边 —— 少了这层，金数字在深灰槽里像直接抠出来的贴纸。
     */
    private const val NUM_OUTLINE_COLOR = 0xFF0E1014.toInt()

    /** 没数据时的占位符（未登录查分器）。 */
    private const val PLACEHOLDER = "—"

    private const val TITLE_X = 108f
    private const val TITLE_Y = -0.7f
    private const val TITLE_W = 276f
    private const val TITLE_H = 36f

    private const val TITLE_TEXT_X = 120.5f
    private const val TITLE_TEXT_Y = 3.3f
    private const val TITLE_TEXT_W = 255f
    private const val TITLE_TEXT_H = 33f

    /**
     * 称号条字号 / 条高。
     *
     * 原来把 TMP_Title（Morisawa R）的 0.58 搬过来，换成 HOS 后字面更宽、x-height 更高，
     * 同样的比例显得挤 —— 落到 0.52 才是「比名字小一号、又不空」的观感。
     */
    private const val TITLE_TEXT_RATIO = 0.52f

    private const val NAME_TEXT_X = 118.5f
    private const val NAME_TEXT_Y = 35f
    /** prefab 的 TMP_Name 就是 175 宽；超长昵称按它缩字号，别越界写到右侧图案上。 */
    private const val NAME_TEXT_W = 175f
    private const val NAME_TEXT_H = 36f
    /**
     * 名字字号（底图像素）。游戏 TMP_Name 里 175 宽正好排 8 个 1em 槽（8 × 22 ≈ 176），
     * 量游戏截图 cap 高约 16 → 字号 22.5 一致。
     */
    private const val NAME_TEXT_SIZE = 22.5f

    /** 头像圆角（底图像素）。 */
    private const val ICON_RADIUS = 8f

    /**
     * 名字底板 `UI_UPE_Name_Base_01` 的实测几何：贴图 32×44 四周 2px 透明边，
     * 白底圆角约 3.5px；`IMG_Bg` 另挂 `Shadow`（灰 0.62、偏移 (1,-1)）。
     */
    private const val NAME_BG_INSET = 2f
    private const val NAME_BG_RADIUS = 3.5f
    private const val NAME_BG_SHADOW = 1f
    private const val NAME_BG_SHADOW_COLOR = 0xFF9E9E9E.toInt()

    private const val DIR = "nameplate"

    /** 渲染参数。 */
    data class Spec(
        val plateAsset: String,
        val nickname: String,
        val rating: Int,
        val oldRa: Int,
        val newRa: Int,
        val avatar: Bitmap? = null,
        /**
         * 称号条里写什么：
         * - `null`（默认）：页面卡片用，写「B35 <旧曲 ra>」「B15 <新曲 ra>」两段；
         * - 非空：整条居中写这一句 —— 导出图用它标注「Best 列表仅供参考使用」。
         */
        val titleText: String? = null,
    )

    /** 素材缓存：底图 96 张 + 徽章 11 张 + 称号条 1 张，命中率极高，直接进程级持有。 */
    private val cache = HashMap<String, Bitmap>()

    /**
     * 按 [spec] 画一张姓名框。
     *
     * @param scale 1f = 720×116；UI 卡片按屏幕宽度给 2f 左右，导出图给 128/116。
     */
    suspend fun render(context: Context, spec: Spec, scale: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching { draw(context, spec, scale) }.getOrNull()
        }

    /**
     * 账户口径的便捷入口：名牌从 [NamePlatePicker] 按达成情况取，头像走
     * 「自定义 > 抓取 > 无」三档（与账户页顶栏一致）。
     */
    suspend fun renderForAccount(
        context: Context,
        nickname: String,
        rating: Int,
        oldRa: Int,
        newRa: Int,
        scale: Float,
        plateAsset: String? = null,
    ): Bitmap? {
        val plate = plateAsset ?: NamePlatePicker.pick(nickname)
        val avatar = runCatching {
            loadAccountAvatarBitmap(context, GameType.MAIMAI, SpUtil.maimaiSourceFlow.value)
        }.getOrNull()
        return render(
            context,
            Spec(plate, nickname, rating, oldRa, newRa, avatar),
            scale,
        )
    }

    // ---------- 绘制 ----------

    private fun draw(context: Context, spec: Spec, scale: Float): Bitmap {
        val s = scale
        val w = (PLATE_W * s).toInt()
        val h = (PLATE_H * s).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setDensity(Bitmap.DENSITY_NONE)
        val canvas = Canvas(out)

        /** prefab 矩形 → 画布 RectF（画布原点左上，y 向下）。 */
        fun rect(x: Float, y: Float, rw: Float, rh: Float) = RectF(
            x * s,
            (PLATE_H - y - rh) * s,
            (x + rw) * s,
            (PLATE_H - y) * s,
        )

        // ── 1. 名牌底图：等比铺满整块（画布比例 = 素材比例，不变形）──
        val plate = asset(context, "${spec.plateAsset}.webp")
        val body = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val outer = Path().apply {
            addRoundRect(body, 10f * s, 10f * s, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(outer)
        if (plate != null) {
            canvas.drawBitmap(plate, null, body, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        // ── 2. 名字底板 276×48：prefab 里是 IMG_Bg（白底 + Shadow），内缩 2px、半径 3.5 ──
        val nameBg = rect(NAME_BG_X, NAME_BG_Y, NAME_BG_W, NAME_BG_H)
        val bgInset = NAME_BG_INSET * s
        val bgRadius = NAME_BG_RADIUS * s
        val bgShadow = NAME_BG_SHADOW * s
        val bgInner = RectF(
            nameBg.left + bgInset,
            nameBg.top + bgInset,
            nameBg.right - bgInset,
            nameBg.bottom - bgInset,
        )
        // Shadow 组件：灰 0.62、偏移 (1,-1)、跟图形 alpha 走
        canvas.drawRoundRect(
            RectF(bgInner.left + bgShadow, bgInner.top + bgShadow, bgInner.right + bgShadow, bgInner.bottom + bgShadow),
            bgRadius,
            bgRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAME_BG_SHADOW_COLOR },
        )
        canvas.drawRoundRect(
            bgInner,
            bgRadius,
            bgRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() },
        )

        // ── 3. DX 评分徽章 176×40（原生素材，按段位取档）──
        val tier = DxRatingTier.of(spec.rating)
        val badge = asset(context, "badge_%02d.webp".format(tier.ordinal + 1))
        val badgeRect = rect(BADGE_X, BADGE_Y, BADGE_W, BADGE_H)
        if (badge != null) {
            canvas.drawBitmap(badge, null, badgeRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        // ── 4. Rating 数字：游戏 NUM_Rateing 的 5 位数字精灵，逐格落位 ──
        drawRatingDigits(context, canvas, badgeRect, spec.rating)

        // ── 5. 称号条 276×36（原生素材）──
        val title = asset(context, "title_gold.webp")
        val titleRect = rect(TITLE_X, TITLE_Y, TITLE_W, TITLE_H)
        if (title != null) {
            canvas.drawBitmap(title, null, titleRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        // ── 6. 头像 100×100：等比正方形裁切 + 圆角（在圆角裁切内，避免顶出卡片边角）──
        drawIcon(canvas, spec.avatar, rect(ICON_X, ICON_Y, ICON_S, ICON_S), s)
        canvas.restore()

        // ── 6. 称号条内文：字体走 App 自带的 HOS（和整套 UI 同一套字），
        //      页面卡片写「B35 x / B15 y」，导出图整条写一句免责说明 ──
        val titleText = rect(TITLE_TEXT_X, TITLE_TEXT_Y, TITLE_TEXT_W, TITLE_TEXT_H)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = hosTypeface(context)
            color = 0xFF6E4200.toInt()
            textAlign = Paint.Align.CENTER
        }
        val half = titleText.width() / 2f
        // 竖排居中：按字体度量把「视觉中线」落到条的几何中线上
        fun drawTitleLine(text: String, cx: Float, maxW: Float) {
            var size = TITLE_TEXT_H * TITLE_TEXT_RATIO * s
            titlePaint.textSize = size
            while (titlePaint.measureText(text) > maxW && size > 6f) {
                size -= 0.5f
                titlePaint.textSize = size
            }
            val fm = titlePaint.fontMetrics
            canvas.drawText(text, cx, titleText.centerY() - (fm.ascent + fm.descent) / 2f, titlePaint)
        }
        val override = spec.titleText
        if (override != null) {
            drawTitleLine(override, titleText.centerX(), titleText.width() - 14f * s)
        } else {
            drawTitleLine("B35 ${spec.oldRa}", titleText.left + half * 0.5f, half - 8f * s)
            drawTitleLine("B15 ${spec.newRa}", titleText.left + half * 1.5f, half - 8f * s)
        }

        // ── 7. 玩家名：游戏 TMP_Name 是等宽的 1em 槽（拉丁走 LiberationSans fallback）──
        val nameRect = rect(NAME_TEXT_X, NAME_TEXT_Y, NAME_TEXT_W, NAME_TEXT_H)
        val nameFont = TmpFont.namePlate(context)
        val nick = spec.nickname
        if (nameFont != null && nick.isNotEmpty()) {
            val n = nick.length
            var size = NAME_TEXT_SIZE * s
            var cell = size
            // 整串超过 TMP_Name 的 175 宽就整体压缩，别写到右侧图案上
            if (cell * n > nameRect.width()) {
                cell = nameRect.width() / n
                size = minOf(size, cell)
            }
            val ink = nameFont.inkBounds(nick, size)
            val baselineY = nameRect.centerY() + (ink[0] + ink[1]) / 2f
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1E.toInt() }
            nameFont.draw(canvas, nick, nameRect.left, baselineY, size, namePaint, cell)
        }

        // ── 9. 外框：上青下蓝，对应游戏里那圈描边 ──
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * s
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0xFF6FE0FF.toInt(), 0xFF0B4FD8.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(outer, borderPaint)
        return out
    }

    private fun drawIcon(canvas: Canvas, src: Bitmap?, box: RectF, s: Float) {
        val radius = ICON_RADIUS * s
        val layer = Bitmap.createBitmap(box.width().toInt(), box.height().toInt(), Bitmap.Config.ARGB_8888)
        layer.setDensity(Bitmap.DENSITY_NONE)
        val c = Canvas(layer)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, layer.width.toFloat(), layer.height.toFloat()), radius, radius, Path.Direction.CW)
        }
        c.save()
        c.clipPath(path)
        if (src != null) {
            // 等比：取正方形中心裁切，再铺满（不变形）
            val side = minOf(src.width, src.height)
            val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            c.drawBitmap(crop, null, RectF(0f, 0f, layer.width.toFloat(), layer.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawColor(0xFF38466A.toInt())
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x60FFFFFF }
            val iw = layer.width.toFloat()
            c.drawCircle(iw * 0.5f, iw * 0.36f, iw * 0.14f, white)
            c.drawRoundRect(RectF(iw * 0.26f, iw * 0.56f, iw * 0.74f, iw * 1.06f), iw * 0.16f, iw * 0.16f, white)
        }
        c.restore()
        canvas.drawBitmap(layer, box.left, box.top, null)
        // 一圈深色细描边：白描边会和紧贴的名字白底连成一片（游戏里头像外是底色）
        canvas.drawRoundRect(
            box,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * s
                color = 0x66303A5C
            },
        )
    }

    // ---------- 小工具 ----------

    private fun asset(context: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        return runCatching {
            context.assets.open("$DIR/$name").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache[name] = it }
    }

    private val typefaceCache = HashMap<String, Typeface?>()

    private fun typeface(context: Context, assetName: String): Typeface? =
        typefaceCache.getOrPut(assetName) {
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$assetName") }.getOrNull()
        }

    /** 称号条文字字体：App 自带的 HOS（Medium 优先），拿不到就退回默认粗体。 */
    private fun hosTypeface(context: Context): Typeface =
        typeface(context, "HOS_Med.ttf")
            ?: typeface(context, "HOS.ttf")
            ?: Typeface.DEFAULT_BOLD

    /**
     * 段位徽章贴图（`badge_01..11.webp`，176×40 原生），供 Compose 侧（[com.kira.kiba.ui.common.DxRatingTag]）
     * 直接复用，和姓名框里那张徽章是同一份素材。
     */
    fun badgeBitmap(context: Context, tierNo: Int): Bitmap? =
        asset(context, "badge_%02d.webp".format(tierNo.coerceIn(1, 11)))

    /**
     * 在 [badge]（徽章的**显示**矩形，任意缩放）里画出 5 位 DX 评分数字。
     *
     * 数字用的是游戏本体 `UI_CMN_Num_26p_*` 那套精灵（34×40，白字），按
     * `SpriteCounter.m_Color = #FFCB01` 染成金色 —— 姓名框、账户页卡片、导出图
     * 全都走这一个函数，保证和游戏里的数字长得一模一样。
     */
    fun drawRatingDigits(context: Context, canvas: Canvas, badge: RectF, rating: Int) {
        if (badge.width() <= 0f) return
        val unit = badge.width() / BADGE_W
        val slotH = NUM_SLOT_H * unit
        // 先定墨迹高，再按精灵的 34:40 反推整张贴图的显示尺寸（等比缩放，绝不拉伸）
        val boxH = slotH * NUM_INK_RATIO * (NUM_SPRITE_H / NUM_INK_H)
        val boxW = boxH * NUM_SPRITE_W / NUM_SPRITE_H
        val pitch = NUM_SLOT_W * unit / 5f
        val left = badge.left + NUM_SLOT_X * unit
        val cy = badge.top + (NUM_SLOT_TOP + NUM_SLOT_H / 2f) * unit

        // 0 = 未绑定 / 无数据：画一根「—」占位，别显示成 00000（那看着像真的零分）
        if (rating <= 0) {
            val font = TmpFont.namePlate(context) ?: return
            val size = slotH * 1.05f
            val w = font.advance(PLACEHOLDER, size)
            val ink = font.inkBounds(PLACEHOLDER, size)
            val cx = left + NUM_SLOT_W * unit / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
            font.draw(canvas, PLACEHOLDER, cx - w / 2f, cy + (ink[0] + ink[1]) / 2f, size, paint)
            return
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(NUM_COLOR, PorterDuff.Mode.SRC_IN)
        }
        // 黑边层：同一尺寸框、同一落位，只是染成近黑先画一遍
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(NUM_OUTLINE_COLOR, PorterDuff.Mode.SRC_IN)
        }
        val text = rating.coerceIn(0, 99999).toString().padStart(5, '0').takeLast(5)
        text.forEachIndexed { i, ch ->
            val digit = ch.digitToIntOrNull() ?: return@forEachIndexed
            val bmp = asset(context, "num_$digit.webp") ?: return@forEachIndexed
            val cx = left + pitch * (i + 0.5f)
            val dst = RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
            asset(context, "num_out_$digit.webp")?.let { canvas.drawBitmap(it, null, dst, outlinePaint) }
            canvas.drawBitmap(bmp, null, dst, paint)
        }
    }
}
