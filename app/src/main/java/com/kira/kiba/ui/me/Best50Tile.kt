package com.kira.kiba.ui.me

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.core.best50.Best50ImageBuilder
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.ui.common.coverCandidateUrls
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Best 50 单曲贴图（Compose 版，UI 专用拉长布局，一行一首）。
 *
 * 与 [Best50ImageBuilder]（B50 图片生成器，导出图片用）同源素材但**独立副本**：
 * res/drawable 里 `kiba_mb_ui` / `kiba_ui_pfc_ms_info02_*_ui` 是导出素材的
 * 1:1 复制品，仅 UI 页使用；对它们做**九宫格水平拉伸**（中缝 132..149 设计 px
 * 为纯空白竖带，相邻列全相似，拉伸无接缝），左段（彩条 + ID / 歌名 / 达成率 +
 * 底部定数 / DX% / ra）与右段（评级 / 模式图标 / 连击同步凹槽 / 序号）1:1 原样，
 * 凹槽、凹槽阴影、圆角一律不变形；四角用 kiba_alpha 蒙版抹边（与导出 applyAlphaMask
 * 同口径），不露底板下背景。
 *
 * 布局：一行一首占满整行宽，高度固定 133 设计 px（u = 1dp/设计px，文字按密度
 * 实时渲染放大不糊）；拉伸多出的空白在上方显示分类（DX/SD）与版本两个 tag。
 *
 * 坐标口径（与上游 PIL 绘制一致，y 为 ascender 顶；`R` = 右段元素的水平偏移量）：
 * 模式图标 (170+R, 6/16)、星标 (170+R, 4)、曲号 (48, 6) 13px、歌名 (32, 27) 16px、
 * 达成率 (30, 42) 26px、评级 (156+R, 40)、连击 (171.2+R, 81)、同步 (200.2+R, 81)、
 * DX 分 (82, 76) 11px、定数 (36, 102) 18px、DX% (90, 105) 13px、ra (135, 99) 21px、
 * 序号 (177/181+R, 105) 13px。
 */
/** UI 卡固定缩放：1 设计 px = 1 dp。 */
private val TILE_U: Dp = 1.dp

/** 底板素材设计尺寸（247×133，含 1px 边）。 */
private const val TILE_SRC_W = 247f
private const val TILE_SRC_H = 133f

/** 九宫格中缝（设计 px）：实测全高唯一干净竖缝 x129..152（水印止于 x127，圆洞起于 x152），
 *  上部 y0..72 为透明区、下部白区纯白；水印/绿标签/圆洞全部留在左右段不变形。 */
private const val SLICE_START = 132f
private const val SLICE_END = 149f

@Composable
fun Best50Tile(
    record: RecordEntity,
    fullScore: Int,
    rankNum: Int,
    listSize: Int,
    version: String = "",
    genre: String = "",
    modifier: Modifier = Modifier,
) {
    val fonts = rememberTileFonts()
    // 外层只做尺寸测量；抹角放在内层（kiba_alpha 蒙版，见 AlphaMaskLayer）
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height((TILE_SRC_H * TILE_U.value).dp),
    ) {
        // 设计稿坐标系：u = 1dp；右段元素水平偏移 = 卡片实际宽 - 素材设计宽
        val u = TILE_U
        val extraDp = (maxWidth - (TILE_SRC_W * u.value).dp).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 离屏合成：先把全部内容画进独立图层，再用 kiba_alpha 蒙版抹角
                // （与位图管线 applyAlphaMask 同口径，圆角形状与素材完全一致，不露底）
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .background(Color(0xFF101010)),
        ) {
            TileContent(record, fullScore, rankNum, listSize, version, genre, fonts, u, extraDp)
            AlphaMaskLayer(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TileContent(
    record: RecordEntity,
    fullScore: Int,
    rankNum: Int,
    listSize: Int,
    version: String,
    genre: String,
    fonts: TileFonts,
    u: Dp,
    extraDp: Dp,
) {
    // 右段元素（评级 / 模式图标 / 连击同步 / 序号）随九宫格拉伸整体右移的设计 px 数
    val r = extraDp.value
    // tag 水平位置：右移到 x200 腾出中段给歌名；小屏（extra 小）回退到评级左侧
    val tagX = minOf(200f, 146f + r)
    // 歌名可延伸到 tag 左缘 - 6（半角 8px/列）
    val titleMaxCols = ((tagX - 38f) / 8f).toInt().coerceIn(12, 24)

        // ── 背景封面：压暗层（不做模糊，降低滚动开销）──
        val candidates = remember(record.songId) { coverCandidateUrls(record.songId, "") }
        TileCoverImage(
            candidates = candidates,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        // 底板（UI 专用副本，九宫格拉伸：中缝拉长，圆角与凹槽不变形、四角不露底）
        NineSliceAsset("kiba_mb_ui", Modifier.fillMaxSize())

        // ── 难度角标整框（含左彩条与底部 DX 分数条底；同样九宫格拉伸）──
        NineSliceAsset(diffFrameAsset(record.levelIndex) + "_ui", Modifier.fillMaxSize())

        // ── 拉伸空白区（达成率右侧偏右）的流派 / 版本 tag：上下两个、小字号，
        //    底部与达成率字形底（baseline ≈ y63）对齐，DX 分数条在 y73 起、不会压到；
        //    小屏（extra 小）自动回退到评级左侧，永不重叠 ──
        Column(
            modifier = Modifier.offset(x = u * tagX, y = u * 35f),
            verticalArrangement = Arrangement.spacedBy(u * 2f),
        ) {
            val tagMaxW = (156f + r - tagX - 10f).coerceAtLeast(60f)
            if (genre.isNotBlank()) {
                TileTag(text = genre, u = u, modifier = Modifier.widthIn(max = u * tagMaxW))
            }
            if (version.isNotBlank()) {
                TileTag(text = version, u = u, modifier = Modifier.widthIn(max = u * tagMaxW))
            }
        }

        // ── SD/DX 模式图标 + DX 星标（右段：x += extra）──
        val dxStar = dxStarLevel(record.dxScore, fullScore)
        val modeAsset = when (record.type) {
            "SD" -> "kiba_ui_upe_infoicon_standardmode"
            "DX" -> "kiba_ui_upe_infoicon_deluxemode"
            else -> null
        }
        modeAsset?.let {
            ScaledAsset(
                it, 0.6f, u,
                Modifier.offset(x = u * (170f + r), y = u * (if (dxStar == 0) 6f else 16f)),
            )
        }
        if (dxStar > 0) {
            ScaledAsset("kiba_dxstar_$dxStar", 0.65f, u, Modifier.offset(u * (170f + r), u * 4f))
        }

        // ── 文本（白字区，左段：位置不变）──
        TileText(trackIdOf(record).toString(), 48f, 6f, 13f, fonts.exoM, Color.White, u)
        TileText(
            trimTitle(record.title, titleMaxCols), 32f, 27f, 16f, fonts.simHei, Color.White, u,
            softWrap = false,
        )
        TileText("%.4f%%".format(record.achievements), 30f, 42f, 26f, fonts.andrea, Color.White, u)

        // ── 评级 / 连击 / 同步图标（右段：x += extra）──
        val rankIdx = RATE_KEYS.indexOf(record.rate.lowercase())
        if (rankIdx >= 0) {
            // MaimaiData 高清源（600×267）。ScaledAsset 的 intrinsicSize 返回的是像素，
            // 旧素材 108px(mdpi)×0.8 = 86.4u；新素材 600px×0.144 = 86.4u，位置尺寸不变仅更清晰
            ScaledAsset(
                "kiba_rank_${RANK_PIC[rankIdx].lowercase()}",
                0.144f, u, Modifier.offset(u * (156f + r), u * 40f),
            )
        }
        val comboIdx = FC_KEYS.indexOf(record.fc.lowercase()).coerceAtLeast(0)
        if (comboIdx > 0) {
            // MaimaiData 高清源（144×144）：主圆内容 136px，旧 39px×0.6 = 23.4u
            // → 0.172f 按内容直径换算（画布 24.8u），画布中心对准原内容中心
            ScaledAsset(
                "kiba_combo_${COMBO_PIC[comboIdx].lowercase()}",
                0.172f, u, Modifier.offset(u * (171.2f + r), u * 81f),
            )
        } else {
            // 无 FC 状态：灰色占位圆（与分享图逻辑一致）
            ScaledAsset("kiba_ui_mss_mbase_icon_blank", 0.6f, u, Modifier.offset(u * (172f + r), u * 81f))
        }
        val syncIdx = FS_KEYS.indexOf(record.fs.lowercase()).coerceAtLeast(0)
        if (syncIdx > 0) {
            ScaledAsset(
                "kiba_combo_${SYNC_PIC[syncIdx].lowercase()}",
                0.172f, u, Modifier.offset(u * (200.2f + r), u * 81f),
            )
        } else {
            ScaledAsset("kiba_ui_mss_mbase_icon_blank", 0.6f, u, Modifier.offset(u * (201f + r), u * 81f))
        }

        // ── 文本（黑字区，画在难度框的 DX 分数条上；左段位置不变）──
        TileText("${record.dxScore} / $fullScore", 82f, 76f, 11f, fonts.hos, Color.Black, u)
        TileText("${record.ds}", 36f, 102f, 18f, fonts.exo, Color.Black, u)
        val percent = if (fullScore > 0) record.dxScore * 100 / fullScore else 0
        TileText("$percent%", 90f, 105f, 13f, fonts.exo, Color.Black, u)
        TileText(
            "${Best50ImageBuilder.computeRa(record.ds, record.achievements, true)}",
            135f, 99f, 21f, fonts.exoM, Color.Black, u,
        )
        TileText(
            "#$rankNum/$listSize",
            if (rankNum >= 9) 177f + r else 181f + r, 105f, 13f, fonts.andrea, Color.Black, u,
        )
}

/** 拉伸空白区的 tag pill：半透明黑底白字，小号（总高 ≈13u，两个塞进 y29..57 空隙）。 */
@Composable
private fun TileTag(text: String, u: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape((6f * u.value).dp))
            // 半透明黑底：亮/暗封面上白字都可读（白底在亮封面上对比不足）
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(horizontal = u * 6f, vertical = u * 1.5f),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = (9f * u.value).sp,
                lineHeight = (10f * u.value).sp,
                fontFamily = FontFamily.SansSerif,
                color = Color.White,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * kiba_alpha 蒙版抹角（UI 版）：上游 putalpha(invert(alpha.png))——素材 alpha 全
 * 不透明、靠**亮度反相当蒙版**（角白→透明、主体黑→不透明）。这里先把亮度反相
 * 成真 alpha 位图，再与底板同样九宫格拉伸（圆角 1:1 不变形）+ DstIn 抹角。
 */
@Composable
private fun AlphaMaskLayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val id = remember("kiba_alpha") { drawableId(context, "kiba_alpha") }
    if (id == 0) return
    val bmp = remember(id) {
        val src = BitmapFactory.decodeResource(
            context.resources, id,
            BitmapFactory.Options().apply { inScaled = false },
        )?.apply { setDensity(Bitmap.DENSITY_NONE) } ?: return@remember null
        val w = src.width
        val h = src.height
        val inp = IntArray(w * h)
        src.getPixels(inp, 0, w, 0, 0, w, h)
        val outp = IntArray(w * h)
        for (i in inp.indices) {
            val c = inp[i]
            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            outp[i] = ((255 - lum) shl 24) or 0x00FFFFFF
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setDensity(Bitmap.DENSITY_NONE)
            setPixels(outp, 0, w, 0, 0, w, h)
        }
    } ?: return
    NineSliceBmp(bmp.asImageBitmap(), bmp.height, modifier, BlendMode.DstIn)
}

/**
 * 九宫格水平拉伸素材（UI 专用）：左段 1:1 → 中缝 [SLICE_START, SLICE_END) 拉伸
 * 补齐卡片多出的宽度 → 右段 1:1；垂直方向 1:1。凹槽、阴影、圆角全部不变形。
 */
@Composable
private fun NineSliceAsset(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val id = remember(name) { drawableId(context, name) }
    if (id == 0) return
    val bmp = remember(name, id) {
        BitmapFactory.decodeResource(
            context.resources, id,
            BitmapFactory.Options().apply { inScaled = false },
        )?.apply { setDensity(Bitmap.DENSITY_NONE) }
    } ?: return
    NineSliceBmp(bmp.asImageBitmap(), bmp.height, modifier)
}

/** 九宫格三段绘制本体（[BlendMode.DstIn] 用于蒙版抹角）。 */
@Composable
private fun NineSliceBmp(
    image: ImageBitmap,
    srcH: Int,
    modifier: Modifier = Modifier,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    Canvas(modifier = modifier) {
        // dst 每设计 px 对应的画布像素（垂直 1:1：卡片高 = 素材设计高）
        val scale = size.height / TILE_SRC_H
        // 源位图坐标是设计像素（0..247），只有 dst 才乘 scale；
        // src 误乘 scale 会越界（150×density > 247），右段被源图尾部拉宽变形
        val srcW = image.width.toFloat()
        val extra = (size.width / scale - srcW).coerceAtLeast(0f) // 拉伸的设计 px
        val a = SLICE_START
        val b = SLICE_END
        val leftDst = a * scale
        val midDst = (b - a + extra) * scale
        val rightDst = leftDst + midDst
        fun drawSeg(sx: Float, sw: Float, dx: Float, dw: Float) {
            if (sw < 1f || dw < 1f) return
            drawImage(
                image,
                srcOffset = IntOffset(sx.roundToInt(), 0),
                srcSize = IntSize(sw.roundToInt(), srcH),
                dstOffset = IntOffset(dx.roundToInt(), 0),
                dstSize = IntSize(dw.roundToInt(), size.height.roundToInt()),
                blendMode = blendMode,
                filterQuality = FilterQuality.High,
            )
        }
        drawSeg(0f, a, 0f, leftDst)
        drawSeg(a, b - a, leftDst, midDst)
        drawSeg(b, srcW - b, rightDst, size.width - rightDst)
    }
}

// ---------- 贴图内部组件 ----------

/** 贴图内文本：设计像素坐标 → offset，字号随缩放（sp 数值 = 设计 px × dp/px）。 */
@Composable
private fun TileText(
    text: String,
    x: Float,
    y: Float,
    size: Float,
    family: FontFamily,
    color: Color,
    u: Dp,
    softWrap: Boolean = true,
) {
    Box(modifier = Modifier.offset(x = u * x, y = u * y)) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = (size * u.value).sp,
                fontFamily = family,
                color = color,
            ),
            softWrap = softWrap,
        )
    }
}

/** 加载失败的封面落回 noimage 占位（候选链递归，与列表 CoverFrame 同源缓存）。 */
@Composable
private fun TileCoverImage(candidates: List<String>, modifier: Modifier = Modifier) {
    val url = candidates.firstOrNull()
    if (url == null) {
        AssetImage("kiba_noimage", modifier = modifier, contentScale = ContentScale.Crop)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = {
            AssetImage("kiba_noimage", modifier = modifier, contentScale = ContentScale.Crop)
        },
        error = { TileCoverImage(candidates.drop(1), modifier) },
        modifier = modifier,
    )
}

/** drawable 资源 ID 进程级缓存（getIdentifier 是逐次反射查表，50 卡滚动每次查太浪费）。 */
private val drawableIdCache = ConcurrentHashMap<String, Int>()

private fun drawableId(context: Context, name: String): Int =
    drawableIdCache.getOrPut(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }

/** 按资源名取 drawable（资源缺失时整块跳过；mdpi 资源按密度实时渲染，放大不糊）。 */
@Composable
private fun AssetImage(
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val id = remember(name) { drawableId(context, name) }
    if (id == 0) return
    Image(
        painter = painterResource(id),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** 名义尺寸 × [factor] × [u]（卡片缩放）的素材图标；对应位图管线里的 scale()。 */
@Composable
private fun ScaledAsset(
    name: String,
    factor: Float,
    u: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val id = remember(name) { drawableId(context, name) }
    if (id == 0) return
    val painter = painterResource(id)
    val w = painter.intrinsicSize.width
    val h = painter.intrinsicSize.height
    if (!w.isFinite() || !h.isFinite()) {
        Image(painter, null, modifier = modifier)
        return
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size((w * factor * u.value).dp, (h * factor * u.value).dp),
    )
}

// ---------- 字体（与 B50 位图同源：assets/fonts） ----------

private data class TileFonts(
    val exoM: FontFamily,
    val exo: FontFamily,
    val simHei: FontFamily,
    val hos: FontFamily,
    val andrea: FontFamily,
)

/** 进程级字体单例：createFromAsset 开销大，50 张卡共享同一份 Typeface。 */
private var tileFontsCache: TileFonts? = null

private fun tileFontsSingleton(context: Context): TileFonts {
    tileFontsCache?.let { return it }
    fun asset(name: String): FontFamily = runCatching {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/$name"))
    }.getOrDefault(FontFamily.SansSerif)
    return TileFonts(
        exoM = asset("ExoM.ttf"),
        exo = asset("Exo.ttf"),
        simHei = asset("adobe_simhei.otf"),
        hos = asset("HOS.ttf"),
        andrea = asset("Andrea.otf"),
    ).also { tileFontsCache = it }
}

@Composable
private fun rememberTileFonts(): TileFonts {
    val context = LocalContext.current
    return remember { tileFontsSingleton(context) }
}

// ---------- 工具（与 Best50ImageBuilder 同口径） ----------

/** 难度角标整框（对应上游 diffpic）。 */
private fun diffFrameAsset(diff: Int): String = when (diff) {
    0 -> "kiba_ui_pfc_ms_info02_bsc"
    1 -> "kiba_ui_pfc_ms_info02_adv"
    2 -> "kiba_ui_pfc_ms_info02_exp"
    3 -> "kiba_ui_pfc_ms_info02_mst"
    4 -> "kiba_ui_pfc_ms_info02_mst_re"
    else -> "kiba_ui_pfc_ms_info02_mst"
}

/** 曲号（DX 老曲 +10000，与位图管线一致）。 */
private fun trackIdOf(r: RecordEntity): Int =
    if (r.songId < 10000 && r.type == "DX") r.songId + 10000 else r.songId

/** 上游 dxStarLevel。 */
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

/** 上游 _trimTitle：宽度 > [maxCols] 列时截断到 maxCols-1 列 + '...'（拉伸布局下随 tag 位置放宽）。 */
private fun trimTitle(title: String, maxCols: Int = 12): String {
    if (columnWidth(title) <= maxCols) return title
    val sb = StringBuilder()
    var w = 0
    for (ch in title) {
        w += charWidth(ch.code)
        if (w > maxCols - 1) break
        sb.append(ch)
    }
    return sb.append("...").toString()
}

// ---------- 常量（与 Best50ImageBuilder 同源） ----------

private val RANK_PIC = listOf(
    "D", "C", "B", "BB", "BBB", "A", "AA", "AAA",
    "S", "Sp", "SS", "SSp", "SSS", "SSSp",
)
private val RATE_KEYS = listOf(
    "d", "c", "b", "bb", "bbb", "a", "aa", "aaa",
    "s", "sp", "ss", "ssp", "sss", "sssp",
)
private val COMBO_PIC = listOf("", "FC", "FCp", "AP", "APp")
private val FC_KEYS = listOf("", "fc", "fcp", "ap", "app")
private val SYNC_PIC = listOf("", "FS", "FSp", "FSD", "FSDp")
private val FS_KEYS = listOf("", "fs", "fsp", "fsd", "fsdp")
