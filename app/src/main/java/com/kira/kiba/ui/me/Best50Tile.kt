package com.kira.kiba.ui.me

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.core.best50.Best50ImageBuilder
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.ui.common.coverCandidateUrls
import java.util.concurrent.ConcurrentHashMap

/**
 * Best 50 单曲卡片（宽卡版 / Kiba Dark Glass）。
 *
 * 旧实现把 246×132 的导出图贴图（`kiba_mb_ui` + 难度框）做九宫格水平拉伸，
 * 左段 132dp、右段 98dp、中间一大片空白只塞两个小 tag —— 元素全是小卡时代的
 * 原生素材，拉宽后比例失调、信息密度极低。
 *
 * 新版是**为整行宽卡重新设计**的三段式布局，不依赖九宫格：
 * - 难度色竖条（20dp）：官方完整难度名整词旋转 90° 竖排，颜色与导出图素材一一对应；
 * - 主信息区（weight 弹性）：序号徽章 / 曲号 / 流派·版本 pill / 歌名 / 达成率
 *   / 定数 / DX 分；屏幕越宽歌名可显示的列数越多；
 * - 右侧信息区（150dp 固定）：SD·DX 徽章、DX 星标、评级贴图、FC/FS 状态、
 *   ra 与榜单序号，全部右对齐成一块稳定的「成绩面板」。
 *
 * 上下两段固定高度（TOP_H + BAND_H = TILE_H）：早期版本用整卡 SpaceBetween
 * 排四行，达成率行的顶部会越过 74dp 的信息带上沿、压到封面区（真机截图反证）。
 * 现在「达成率 / LV / DX 分 / ra」全部锁在下半成绩带内，与字体度量无关。
 *
 * 视觉语言与导出图同源（深色玻璃 + 冷白文字 + 青色 ra 强调），但排版按宽卡重排；
 * 游戏内正式徽章（SSS+ 评级 / FC / FS）沿用 MaimaiData 高清素材，其余老贴图
 * （SD·DX 图标、星标、灰色占位圆）改为 Compose 原生绘制。
 */
private val TILE_H = 132.dp
private val TOP_H = 66.dp
private val BAND_H = 66.dp
private val STRIP_W = 20.dp
private val SIDE_W = 150.dp

/** 竖条里的难度徽章厚度：条宽 20dp，左右各留 2dp（与分享图 `strip_w - 4` 对齐）。 */
private val PLATE_THICK = 16.dp

/** 难度徽章素材：与 `build_assets.py` 的 `DIFF_PLATE` 一一对应（0..4 = BSC..Re:Master）。 */
private val DIFF_PLATE = listOf(
    "kiba_diff_basic",
    "kiba_diff_advanced",
    "kiba_diff_expert",
    "kiba_diff_master",
    "kiba_diff_remaster",
)

private val BG = Color(0xFF0B1020)
private val BAND = Color(0xFF0A0F1C)
private val INK = Color(0xFFF0F6FF)
private val INK_SOFT = Color(0xFFF5FCFF)
private val DIM = Color(0xFFBECCE0)
private val FAINT = Color(0xFF8092B0)
private val CYAN = Color(0xFF4DE2E6)
private val MAGENTA = Color(0xFFFF5FA2)
private val VIOLET = Color(0xFFA98BFF)
private val AMBER = Color(0xFFFFBE78)

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
    val diffColor = tileDifficultyColor(record.levelIndex)
    val dxStar = dxStarLevel(record.dxScore, fullScore)
    val ra = Best50ImageBuilder.computeRa(record.ds, record.achievements, true)
    val percent = if (fullScore > 0) record.dxScore * 100 / fullScore else 0

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(TILE_H)) {
        // 右侧成绩面板按比例收窄：它内部最宽的元素是 80dp 的评级贴图与
        // 「ra 333 / #1/15」行（实测 ~100dp），150dp 在宽屏是余量、在 360dp 屏
        // 就是挤占 —— 旧版窄屏中间只剩 128dp，达成率只能缩到 16sp，
        // 和宽屏完全两个观感。122~150dp 之间线性取值，宽屏保持原样。
        val sideW = (maxWidth * 0.38f).coerceIn(122.dp, SIDE_W)

        // 主信息区随屏幕变宽 → 歌名可显示更多字符（半角约 8dp / 列）
        val titleCols = ((maxWidth.value - STRIP_W.value - 20f - sideW.value) / 8f)
            .toInt().coerceIn(10, 44)

        // 字号不再靠 em 系数拍脑袋，直接用 TextMeasurer 量真实字宽：
        // 「99.1234%」和「100.5548%」差一个字符，写死系数必然一头留白一头溢出。
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        fun textW(text: String, style: TextStyle): Float = with(density) {
            measurer.measure(text, style, softWrap = false, maxLines = 1)
                .size.width.toDp().value
        }

        val idText = "ID ${trackIdOf(record)}"
        val pctText = "%.4f%%".format(record.achievements)
        val lvText = "LV ${record.ds}"
        val pctUnit = (textW(pctText, TextStyle(fontSize = 27.sp, fontFamily = fonts.andrea)) / 27f)
            .coerceAtLeast(0.1f)
        val lvUnit = (textW(lvText, TextStyle(fontSize = 13.sp, fontFamily = fonts.exo)) / 13f)
            .coerceAtLeast(0.1f)

        // 达成率 / 定数：按同一比例整体缩放，宽窄屏的视觉关系才一致。
        // 旧版用「窄屏 11 / 宽屏 13」两档绝对值，窄屏相当于 0.69 倍、宽屏 0.53 倍，
        // 同一张卡换台机器 LV 就"变大"了（用户截图点名）。
        val bandW = (maxWidth - STRIP_W - 20.dp - sideW).value
        val fitScale = (bandW - 13f) / (pctUnit * 27f + lvUnit * 13f)
        val lvSize = (13f * fitScale).coerceIn(9.5f, 13f)
        val pctSize = ((bandW - 13f - lvUnit * lvSize) / pctUnit).coerceIn(13f, 27f)

        // 标签行：ID + 流派 + 版本。窄屏（<372dp）主区只剩 ~158dp，序号徽章
        // 和右下角的「#n/15」信息重复，先让它出局；版本 tag 放不下就按用户口径
        // 用「…」截断 —— 它挂 weight(1f, fill = false)，先按内容宽度要，最多拿到
        // 行内剩余，BasicText 自己打省略号，绝不会把行撑出卡片。
        val idStyle = TextStyle(fontSize = 11.sp, fontFamily = fonts.exoM)
        fun pillW(text: String, size: Float, padH: Float): Float {
            if (text.isBlank()) return 0f
            return (textW(text, TextStyle(fontSize = size.sp)) + padH * 2f).coerceAtMost(96f)
        }
        val narrowRow = maxWidth < 372.dp
        val contentW = (maxWidth - STRIP_W - sideW - 20.dp).value
        val idW = textW(idText, idStyle)
        // 版本 tag 之外的全部固定宽度：徽章(窄屏无) + ID + 流派及两处间距
        val fixedW = (if (narrowRow) 0f else 30f) +
            idW + (if (genre.isNotBlank()) 8f else 0f) + pillW(genre, 10f, 6f) +
            (if (version.isNotBlank()) 6f else 0f)
        // 标准档放不下就整体退一档（9sp / 4dp），再不够才交给省略号
        val compactPill = contentW - fixedW < pillW(version, 10f, 6f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(BG),
        ) {
            // ── 背景：封面 + 压暗 + 底部信息带 ──
            val candidates = remember(record.songId) { coverCandidateUrls(record.songId, "") }
            TileCoverImage(candidates, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.44f)))
            // 下半成绩带：加高到 66dp，所有成绩文字都在带内
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(BAND_H)
                    .background(BAND.copy(alpha = 0.96f)),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.16f)))
            // 信息带上沿分隔线（顶边 y = TOP_H = 66dp）
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .align(Alignment.TopStart)
                    .offset(y = TOP_H)
                    .background(Color.White.copy(alpha = 0.10f)),
            )
            Box(
                Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart)
                    .background(diffColor.copy(alpha = 0.55f)),
            )

            Row(Modifier.fillMaxSize()) {
                // ── 难度徽章条：底 / 菱形 / 刻度保留，难度名换官方徽章贴图 ──
                DifficultyStrip(
                    color = diffColor,
                    index = record.levelIndex,
                    modifier = Modifier.width(STRIP_W).fillMaxHeight(),
                )

                // ── 主信息区：上半封面信息 / 下半成绩带，各自固定高度 ──
                Column(Modifier.weight(1f)) {
                    Column(
                        Modifier.height(TOP_H)
                            .padding(start = 12.dp, end = 8.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!narrowRow) {
                                RankBadge(rankNum, fonts)
                                Spacer(Modifier.width(8.dp))
                            }
                            BasicText(
                                idText,
                                style = idStyle.copy(color = DIM),
                                maxLines = 1,
                                softWrap = false,
                            )
                            if (genre.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                TilePill(genre, CYAN, compact = compactPill)
                            }
                            if (version.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                // weight(fill = false)：最多拿到行内剩余宽度，内容更窄时
                                // 按内容宽度收；放不下就由 BasicText 打「…」。
                                TilePill(
                                    version, VIOLET, compact = compactPill,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                        BasicText(
                            trimTitle(record.title, titleCols),
                            style = TextStyle(fontSize = 18.sp, fontFamily = fonts.simHei, color = INK),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Column(
                        Modifier.height(BAND_H)
                            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 达成率与定数**按基线对齐**（alignByBaseline），字号再变也是
                        // 同一条基线；旧版用 Alignment.Bottom + 4dp 底距，字号一缩
                        // LV 就浮到半空 —— 窄屏被看成「对齐顶部」，宽屏却是齐底的。
                        Row {
                            BasicText(
                                pctText,
                                style = TextStyle(fontSize = pctSize.sp, fontFamily = fonts.andrea, color = INK_SOFT),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                lvText,
                                style = TextStyle(fontSize = lvSize.sp, fontFamily = fonts.exo, color = DIM),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                "DX ${record.dxScore} / $fullScore",
                                style = TextStyle(fontSize = 11.sp, fontFamily = fonts.hos, color = DIM),
                            )
                            Spacer(Modifier.width(12.dp))
                            BasicText(
                                "$percent%",
                                style = TextStyle(fontSize = 11.sp, fontFamily = fonts.exo, color = FAINT),
                            )
                        }
                    }
                }

                // ── 右侧成绩面板：同样锁成上下两段 ──
                Column(Modifier.width(sideW).fillMaxHeight()) {
                    Column(
                        Modifier.height(TOP_H).fillMaxWidth()
                            .padding(end = 10.dp, top = 8.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (dxStar > 0) StarBadge(dxStar)
                            // SD / DX 用原版游戏素材（113×32），0.6 倍 = 68×19dp，与导出图同比例。
                            // 之前改成 Compose 自绘的深色 pill，主页一眼就跟分享图对不上了。
                            val modeIcon = when (record.type) {
                                "SD" -> "kiba_ui_upe_infoicon_standardmode"
                                "DX" -> "kiba_ui_upe_infoicon_deluxemode"
                                else -> null
                            }
                            if (modeIcon != null) {
                                ScaledAsset(modeIcon, Modifier.size(68.dp, 19.dp))
                            }
                        }
                        // 评级：保留 MaimaiData 高清徽章。
                        // 尺寸与导出图对齐（80×35.6 → 80×35）：旧 86×38 在
                        // 「top 8 + 徽章行 ~18 + 间距 3」之后会顶到 TOP_H=66 的信息带
                        // 上沿横线（导出图侧同一个毛病，用户截图点名）。
                        val rankIdx = RATE_KEYS.indexOf(record.rate.lowercase())
                        if (rankIdx >= 0) {
                            ScaledAsset(
                                "kiba_rank_${RANK_PIC[rankIdx].lowercase()}",
                                Modifier.size(80.dp, 35.dp),
                            )
                        } else {
                            Spacer(Modifier.height(35.dp))
                        }
                    }

                    Column(
                        // top = 4dp：图标行原本紧贴成绩带上沿（SpaceBetween 的首行贴顶），
                        // 真机上看着吊在分隔线下面；下移 4dp，与下方 ra 行的间距也更匀。
                        Modifier.height(BAND_H).fillMaxWidth()
                            .padding(end = 10.dp, top = 4.dp, bottom = 7.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val comboIdx = FC_KEYS.indexOf(record.fc.lowercase()).coerceAtLeast(0)
                            val syncIdx = FS_KEYS.indexOf(record.fs.lowercase()).coerceAtLeast(0)
                            StatusIcon(
                                if (comboIdx > 0) "kiba_combo_${COMBO_PIC[comboIdx].lowercase()}" else null,
                            )
                            StatusIcon(
                                if (syncIdx > 0) "kiba_combo_${SYNC_PIC[syncIdx].lowercase()}" else null,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicText(
                                "ra $ra",
                                style = TextStyle(fontSize = 17.sp, fontFamily = fonts.exoM, color = CYAN),
                            )
                            BasicText(
                                "#$rankNum/$listSize",
                                style = TextStyle(fontSize = 11.sp, fontFamily = fonts.andrea, color = DIM),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- 卡片内元素（全部为宽卡重新设计，替代旧贴图） ----------

/** 榜单序号徽章：圆角小方块 + 青色数字，比旧版挤在右下角的 "#n/50" 更好扫读。 */
/**
 * 侧边难度徽章条 —— 与导出图单格素材 `kiba_ui_pfc_ms_info02_*` 同一套设计。
 *
 * 素材里那套装饰是 20×133 的位图，而宽卡条宽 20dp、卡片高 132dp，几乎 1:1，
 * 所以这里用 Canvas 按同一组比例重绘，而不是把位图横向拉长（会糊边）：
 * - 纵向光衰减渐变（上限 1.04，别过曝，否则浅紫档的白菱形/白描边会被吃掉）；
 * - 左侧 2dp 玻璃高光带 + 右侧 1dp 内阴影，把徽章从封面上"立"起来；
 * - 顶部深墨菱形 + 白心（呼应 B50 表头），底部双刻度收尾；
 * - 冷白 1dp 外描边。
 *
 * 难度名不再是字体拼字：直接贴官方难度徽章素材（`kiba_diff_basic` 等），
 * 整块顺时针转 90° 后从上往下读。素材原生是横向徽章（高 23~24px、长 86~151px），
 * 旋转后「厚度」正好对应 20dp 的竖条宽度。
 */
@Composable
private fun DifficultyStrip(
    color: Color,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val ink = Color(0xFF0C101A)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                Brush.verticalGradient(
                    listOf(tint(color, 1.04f), tint(color, 0.64f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 素材坐标系 20×133，换算成实际像素
            val u = size.width / 20f
            val v = size.height / 133f

            // 左侧玻璃高光带：随高度衰减
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.31f), Color.Transparent),
                ),
                topLeft = Offset(1f * u, 0f),
                size = Size(2f * u, size.height),
            )
            // 右侧内阴影：**直接不要**。旧版是 1dp 实色竖线，20dp 宽的竖条上就是一根
            // 从头通到底的"多余的竖线"（用户连问两次）；改成渐变也照样被读成竖线，
            // 轮廓交给外面那圈白描边就够。
            // 顶部菱形徽记：深墨底 + 白心
            drawPath(
                Path().apply {
                    moveTo(10f * u, 3f * v)
                    lineTo(16f * u, 9f * v)
                    lineTo(10f * u, 15f * v)
                    lineTo(4f * u, 9f * v)
                    close()
                },
                Color(0xFF080C16).copy(alpha = 0.84f),
            )
            drawPath(
                Path().apply {
                    moveTo(10f * u, 6f * v)
                    lineTo(13f * u, 9f * v)
                    lineTo(10f * u, 12f * v)
                    lineTo(7f * u, 9f * v)
                    close()
                },
                Color.White.copy(alpha = 0.92f),
            )
            // 底部双刻度
            drawRect(ink.copy(alpha = 0.63f), Offset(6f * u, 123f * v), Size(9f * u, 2f * v))
            drawRect(ink.copy(alpha = 0.43f), Offset(8f * u, 128f * v), Size(5f * u, 1.2f * v))
        }
        // 冷白外描边
        Box(
            Modifier.fillMaxSize()
                .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(9.dp)),
        )
        // 难度名：官方难度徽章贴图（`kiba_diff_*`）整块转到竖直方向。
        // 徽章本身是横向的（绿底 BASIC / 黄底 ADVANCED / …），先按 alpha 墨迹
        // 裁掉透明边距再等比缩放，否则素材自带的上下留白会让它看起来比竖条窄。
        DiffPlate(index, PLATE_THICK)
    }
}

/**
 * 竖条上的难度名 —— 官方难度徽章素材，顺时针转 90° 后从上往下读。
 *
 * 素材是横向徽章（例如 151×32，四周带透明边距），直接贴会把边距也算进厚度，
 * 视觉上比竖条窄一圈。所以先按 alpha 墨迹裁掉空白，再等比缩到厚度 [thickness]，
 * 最后 `rotate(90f)`：旋转只改绘制、不改布局，`wrapContentSize(unbounded)` 允许
 * 它横着占满 60~90dp 而不撑破 20dp 宽的竖条，父级 Center 保证视觉居中。
 */
@Composable
private fun DiffPlate(index: Int, thickness: Dp) {
    val name = DIFF_PLATE.getOrNull(index) ?: return
    val context = LocalContext.current
    val bmp = remember(name) { loadDiffPlate(context, name) } ?: return
    val ratio = bmp.width.toFloat() / bmp.height
    Image(
        painter = BitmapPainter(bmp, filterQuality = FilterQuality.Medium),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .wrapContentSize(unbounded = true, align = Alignment.Center)
            .width(thickness * ratio)
            .height(thickness)
            .rotate(90f),
    )
}

/** 已按墨迹裁好的难度徽章（全进程共享，避免列表滚动时反复解码）。 */
private val diffPlateCache = ConcurrentHashMap<String, Bitmap>()

private fun loadDiffPlate(context: Context, name: String): ImageBitmap? {
    diffPlateCache[name]?.let { return it.asImageBitmap() }
    val id = drawableId(context, name)
    if (id == 0) return null
    val raw = BitmapFactory.decodeResource(context.resources, id) ?: return null
    val bmp = trimTransparent(raw)
    diffPlateCache[name] = bmp
    return bmp.asImageBitmap()
}

/** 裁到 alpha 非零的包围盒；全透明时原样返回。 */
private fun trimTransparent(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (px[row + x] ushr 24 != 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX || maxY < minY) return src
    return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/** 按比例调整亮度：素材里 `color * factor`，各通道钳在 0..1。 */
private fun tint(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)

@Composable
private fun RankBadge(rankNum: Int, fonts: TileFonts) {
    Box(
        Modifier.size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "$rankNum",
            style = TextStyle(fontSize = 11.sp, fontFamily = fonts.exoM, color = CYAN),
        )
    }
}

/** 流派 / 版本 pill：彩色细边 + 半透明底。
 *
 *  宽度由父级实测字宽决定（见调用点），这里只负责画；[compact] 是窄屏退让档
 *  （9sp + 4dp 内边距），不到万不得已不会用上 —— 真正的兜底是整块不画。 */
@Composable
private fun TilePill(
    text: String,
    accent: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.widthIn(max = 96.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
    ) {
        BasicText(
            text,
            style = TextStyle(fontSize = if (compact) 9.sp else 10.sp, color = accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * DX 星标：用小菱形色块替代 `kiba_dxstar_*` 五张贴图。
 * 刻意不用「★」字符 —— 部分 ROM 字体缺该字形会渲染成豆腐块。
 */
@Composable
private fun StarBadge(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) {
            Box(
                Modifier.size(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AMBER),
            )
        }
    }
}

/** FC / FS 状态圆：有状态用高清徽章，无状态画原生灰色空心圆（替代占位贴图）。
 *
 *  两者**画框不同高**是刻意的：`kiba_combo_*` 是 144×144 的游戏素材，圆本体（含花边）
 *  只占画布约 0.83，透明边距再吃掉一圈，所以塞进 23dp 画框时徽章看着比 23dp 的灰圆
 *  小一整圈（用户真机点名）。徽章给 27dp、灰圆给 23dp，视觉直径才对得上。
 */
@Composable
private fun StatusIcon(asset: String?) {
    if (asset != null) {
        ScaledAsset(asset, Modifier.size(27.dp))
    } else {
        Box(
            Modifier.size(23.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.07f)),
        )
    }
}

// ---------- 贴图内部组件 ----------

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

/** 高清徽章按固定显示尺寸绘制（素材本身 600×267 / 144×144，只用来保证清晰度）。 */
@Composable
private fun ScaledAsset(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val id = remember(name) { drawableId(context, name) }
    if (id == 0) return
    Image(
        painter = painterResource(id),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

// ---------- 字体（与 B50 位图同源：assets/fonts） ----------

private data class TileFonts(
    val exoM: FontFamily,
    val exo: FontFamily,
    val simHei: FontFamily,
    val hos: FontFamily,
    val hosBold: FontFamily,
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
        // 难度徽章条用粗体：与导出图素材 STRIP_EN 的选型一致，缺字体时退回 HOS
        hosBold = runCatching {
            FontFamily(Typeface.createFromAsset(context.assets, "fonts/HOS_Bold.ttf"))
        }.getOrElse { asset("HOS.ttf") },
        andrea = asset("Andrea.otf"),
    ).also { tileFontsCache = it }
}

@Composable
private fun rememberTileFonts(): TileFonts {
    val context = LocalContext.current
    return remember { tileFontsSingleton(context) }
}

// ---------- 工具（与 Best50ImageBuilder 同口径） ----------

/** 难度色 / 缩写：与 build_assets.py 的 DIFF 表一一对应。 */
private fun tileDifficultyColor(index: Int): Color = when (index) {
    0 -> Color(0xFF43D17F)
    1 -> Color(0xFFF5C542)
    2 -> Color(0xFFFF5D6C)
    3 -> Color(0xFFB07CFF)
    4 -> Color(0xFFE6D6FF)
    else -> Color(0xFFB12876)
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

/** 上游 _trimTitle：宽度 > [maxCols] 列时截断到 maxCols-1 列 + '...'（宽卡随可用宽度放宽）。 */
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
