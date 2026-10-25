package com.kira.kiba.ui.me

import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.core.best50.Best50ImageBuilder
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.ui.common.coverCandidateUrls

/**
 * Best 50 单曲贴图（Compose 版）。
 *
 * 与 [Best50ImageBuilder]（B50 图片生成器的单格 246×132）同一套素材与坐标：
 * - 背景：封面铺满裁切 → 高斯模糊 → 亮度 ×0.72（黑 28% 叠层）→ `kiba_mb` 蒙版；
 * - 难度角标整框（左彩条 + 底部 DX 分数条）、SD/DX 模式图标、DX 星标；
 * - 曲号 / 歌名 / 达成率（白字）；DX 分 / 定数 / DX% / ra / 序号（黑字，画在分数条上）；
 * - 评级 / 连击 / 同步图标。
 *
 * 全部元素按「设计稿 246×132」的坐标系统一随卡片宽度等比缩放。文字与 drawable
 * 资源按屏幕密度实时渲染，放大不糊（区别于位图直接拉伸）。
 *
 * 坐标口径（与上游 PIL 绘制一致，y 为 ascender 顶）：
 * 模式图标 (170, 6/16)、星标 (170, 4)、曲号 (48, 6) 13px、歌名 (32, 27) 16px、
 * 达成率 (30, 42) 26px、评级 (156, 40)、连击 (172, 81)、同步 (201, 81)、
 * DX 分 (82, 76) 11px、定数 (36, 102) 18px、DX% (90, 105) 13px、ra (135, 99) 21px、
 * 序号 (177/181, 105) 13px。
 */
@Composable
fun Best50Tile(
    record: RecordEntity,
    fullScore: Int,
    rankNum: Int,
    listSize: Int,
    modifier: Modifier = Modifier,
) {
    val fonts = rememberTileFonts()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(246f / 132f)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF101010)),
    ) {
        // 设计稿 246×132 → 每个设计像素对应的 Dp
        val u = maxWidth / 246f

        // ── 背景封面：模糊 + 压暗（与位图管线一致：GaussianBlur(2) + ×0.72）──
        val candidates = remember(record.songId) { coverCandidateUrls(record.songId, "") }
        TileCoverImage(
            candidates = candidates,
            modifier = Modifier
                .fillMaxSize()
                .blur((2f * u.value).dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        AssetImage("kiba_mb", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

        // ── 难度角标整框（含左彩条与底部 DX 分数条底）──
        AssetImage(
            diffFrameAsset(record.levelIndex),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )

        // ── SD/DX 模式图标 + DX 星标 ──
        val dxStar = dxStarLevel(record.dxScore, fullScore)
        val modeAsset = when (record.type) {
            "SD" -> "kiba_ui_upe_infoicon_standardmode"
            "DX" -> "kiba_ui_upe_infoicon_deluxemode"
            else -> null
        }
        modeAsset?.let {
            ScaledAsset(
                it, 0.6f,
                Modifier.offset(x = u * 170f, y = u * (if (dxStar == 0) 6f else 16f)),
            )
        }
        if (dxStar > 0) {
            ScaledAsset("kiba_dxstar_$dxStar", 0.65f, Modifier.offset(u * 170f, u * 4f))
        }

        // ── 文本（白字区）──
        TileText(trackIdOf(record).toString(), 48f, 6f, 13f, fonts.exoM, Color.White, u)
        TileText(
            trimTitle(record.title), 32f, 27f, 16f, fonts.simHei, Color.White, u,
            softWrap = false,
        )
        TileText("%.4f%%".format(record.achievements), 30f, 42f, 26f, fonts.andrea, Color.White, u)

        // ── 评级 / 连击 / 同步图标 ──
        val rankIdx = RATE_KEYS.indexOf(record.rate.lowercase())
        if (rankIdx >= 0) {
            ScaledAsset(
                "kiba_ui_gam_rank_${RANK_PIC[rankIdx].lowercase()}",
                0.8f, Modifier.offset(u * 156f, u * 40f),
            )
        }
        val comboIdx = FC_KEYS.indexOf(record.fc.lowercase()).coerceAtLeast(0)
        if (comboIdx > 0) {
            ScaledAsset(
                "kiba_ui_mss_mbase_icon_${COMBO_PIC[comboIdx].lowercase()}_s",
                0.6f, Modifier.offset(u * 172f, u * 81f),
            )
        }
        val syncIdx = FS_KEYS.indexOf(record.fs.lowercase()).coerceAtLeast(0)
        if (syncIdx > 0) {
            ScaledAsset(
                "kiba_ui_mss_mbase_icon_${SYNC_PIC[syncIdx].lowercase()}_s",
                0.6f, Modifier.offset(u * 201f, u * 81f),
            )
        }

        // ── 文本（黑字区，画在难度框的 DX 分数条上）──
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
            if (rankNum >= 9) 177f else 181f, 105f, 13f, fonts.andrea, Color.Black, u,
        )
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

/** 按资源名取 drawable（资源缺失时整块跳过；mdpi 资源按密度实时渲染，放大不糊）。 */
@Composable
private fun AssetImage(
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val id = remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
    if (id == 0) return
    Image(
        painter = painterResource(id),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** 名义尺寸 × [factor] 的素材图标（对应位图管线里的 scale()；无 dpi 限定 → intrinsic dp == 原始 px）。 */
@Composable
private fun ScaledAsset(
    name: String,
    factor: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val id = remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
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
        modifier = modifier.size((w * factor).dp, (h * factor).dp),
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

@Composable
private fun rememberTileFonts(): TileFonts {
    val context = LocalContext.current
    return remember {
        fun asset(name: String): FontFamily = runCatching {
            FontFamily(Typeface.createFromAsset(context.assets, "fonts/$name"))
        }.getOrDefault(FontFamily.SansSerif)
        TileFonts(
            exoM = asset("ExoM.ttf"),
            exo = asset("Exo.ttf"),
            simHei = asset("adobe_simhei.otf"),
            hos = asset("HOS.ttf"),
            andrea = asset("Andrea.otf"),
        )
    }
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
