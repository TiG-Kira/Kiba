package com.kira.kiba.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.R
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.network.MaimaiDataClient
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.navigation.LocalNavBarBottomPadding
import com.kira.kiba.ui.navigation.LocalSystemNavBarBottom
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Kiba 页面通用组件：全部走 miuix 规范（Card / BasicComponent / 官方配色字段）。
 */

/** 分组小标题（miuix 设置页风格，置于 Card 上方）。 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/**
 * 页面滚动容器的 contentPadding：顶部顶栏 clearance + 底部悬浮底栏留白 + 两侧 16dp。
 * 挂在 LazyColumn 的 contentPadding 上（可被滚走），不是页面根 padding（死带）。
 *
 * @param withNavBar false 用于二级页面（无悬浮底栏）：底部只留系统导航栏 inset。
 */
@Composable
fun pageContentPadding(
    topExtra: androidx.compose.ui.unit.Dp = 8.dp,
    bottomExtra: androidx.compose.ui.unit.Dp = 16.dp,
    withNavBar: Boolean = true,
): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = LocalTopBarClearance.current + topExtra,
    bottom = if (withNavBar) {
        LocalNavBarBottomPadding.current + bottomExtra
    } else {
        LocalSystemNavBarBottom.current + bottomExtra
    },
)

/** 流派 → 描边色资源（舞萌特色分类色）。 */
private fun genreStrokeRes(genre: String): Int = when (genre) {
    "流行&动漫" -> R.color.pop_stroke
    "niconico & VOCALOID" -> R.color.vocal_stroke
    "东方Project" -> R.color.touhou_stroke
    "其他游戏" -> R.color.variety_stroke
    "舞萌" -> R.color.maimai_stroke
    "宴会場" -> R.color.utage_stroke
    else -> R.color.gekichuni_stroke
}

/**
 * 歌曲封面加载（多候选链）：
 * 1. 水鱼 CDN （原版同源，文件名是去掉版本前缀的曲号，五位数补零）；
 * 2. 水鱼 CDN 其余候选号（DX 2024+ 曲用版本前缀 id 存图，需再试序号形态）；
 * 3. 舞萌官网图床 （DX 新曲唯一来源，
 *    国内可能不可达，此时落回占位图）。
 * 全部失败 → 流派色块 + 音符占位。
 */
@Composable
fun CoverFrame(
    songId: Int,
    imageUrl: String,
    genre: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    val stroke = colorResource(genreStrokeRes(genre))
    val candidates = remember(songId, imageUrl) { coverCandidateUrls(songId, imageUrl) }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, stroke, RoundedCornerShape(10.dp))
    ) {
        CoverImageChain(
            candidates = candidates,
            index = 0,
            stroke = stroke,
            size = size,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * 封面候选链加载：逐个尝试，任一成功即显示；全部失败落回占位。
 * 用 SubcomposeAsyncImage 的 error slot 递归实现，避免依赖 painter 状态判断。
 */
@Composable
private fun CoverImageChain(
    candidates: List<String>,
    index: Int,
    stroke: Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val url = candidates.getOrNull(index)
    if (url == null) {
        CoverPlaceholder(stroke = stroke, size = size, modifier = modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = { CoverPlaceholder(stroke = stroke, size = size, modifier = modifier) },
        error = {
            CoverImageChain(
                candidates = candidates,
                index = index + 1,
                stroke = stroke,
                size = size,
                modifier = modifier,
            )
        },
        modifier = modifier,
    )
}

/** 封面候选 URL 链（公开给 TopBar 封面背景等处复用）：
 * 1. 自定义图床前缀（设置页可填，命中自己的镜像最快）；
 * 2. 水鱼 CDN `covers/%05d.png` 三种编号形态（songId / mod 10000 / mod 100000，
 *    水鱼只收录到 DX 2024 曲库，DX2025+ 新曲没有）；
 * 3. 官网图床 maimaidx.jp + 若干镜像（DX 新曲唯一来源，国内可能需镜像）。
 */
fun coverCandidateUrls(songId: Int, imageUrl: String): List<String> {
    val list = mutableListOf<String>()

    fun push(url: String) {
        if (url.isNotBlank() && url !in list) list.add(url)
    }

    // 自定义图床
    val custom = SpUtil.getCoverSource()
    if (custom.isNotBlank() && imageUrl.isNotBlank()) {
        push(custom.trimEnd('/') + "/" + imageUrl)
    }

    // 水鱼 CDN：多种编号形态
    fun pushCd(id: Int) {
        push(MaimaiDataClient.DIVING_FISH_COVER_URL + "%05d".format(id) + ".png")
    }
    pushCd(songId)
    val mod10k = songId % 10000
    if (mod10k != songId) pushCd(mod10k)
    val mod100k = songId % 100000
    if (mod100k != songId && mod100k != mod10k) pushCd(mod100k)

    // 官网图床 + 镜像（DX 新曲靠它们）
    if (imageUrl.isNotBlank()) {
        MIRRORS.forEach { base -> push(base.trimEnd('/') + "/" + imageUrl) }
    }
    return list
}

/** 已知镜像（同一路径结构 /maimai-mobile/img/Music/{hash}.png）。 */
private val MIRRORS = listOf(
    "https://maimaidx.jp/maimai-mobile/img/Music/",
    "https://maimai-mobile.jp/maimai-mobile/img/Music/",
    "https://maimai.sakura.party/maimai-mobile/img/Music/",
    "https://maimai.wmflabs.com/maimai-mobile/img/Music/",
    "https://maimai.sega.jp/maimai-mobile/img/Music/",
)

/** 封面占位：流派色半透明底 + 音符。 */
@Composable
private fun CoverPlaceholder(
    stroke: Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(stroke.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixGlassIcons.Regular.Music,
            contentDescription = null,
            tint = stroke,
            modifier = Modifier.size(size / 2.6f),
        )
    }
}

/** 难度枚举 → 舞萌标准色。 */
fun difficultyColor(type: DifficultyType): Color = when (type) {
    DifficultyType.BASIC -> Color(0xFF6FD43D)      // 原版 basic
    DifficultyType.ADVANCED -> Color(0xFFF8B709)   // 原版 advanced
    DifficultyType.EXPERT -> Color(0xFFFF818D)     // 原版 expert
    DifficultyType.MASTER -> Color(0xFF9F51DC)     // 原版 master
    DifficultyType.REMASTER -> Color(0xFFCB5EEA)   // 原版 remaster
    else -> Color(0xFFB12876)                     // 原版 utage
}

/** 难度枚举 → 短标签。 */
fun difficultyLabel(type: DifficultyType): String = when (type) {
    DifficultyType.BASIC -> "BASIC"
    DifficultyType.ADVANCED -> "ADVANCED"
    DifficultyType.EXPERT -> "EXPERT"
    DifficultyType.MASTER -> "MASTER"
    DifficultyType.REMASTER -> "Re:MASTER"
    else -> "UTAGE"
}

/** levelIndex（RecordEntity 口径）→ 短标签：0=BASIC … 4=Re:MASTER，其余 UTAGE。 */
fun difficultyLabel(index: Int): String = when (index) {
    0 -> "BASIC"
    1 -> "ADVANCED"
    2 -> "EXPERT"
    3 -> "MASTER"
    4 -> "Re:MASTER"
    else -> "UTAGE"
}

/** 难度 / 等级 / 定数 胶囊（舞萌难度等级分类样式）。 */
@Composable
fun DifficultyChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // 原版等级徽章样式（MaimaiData level_*_bg）：实色底 + 白色内描边
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(1.5.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * DX / SD 谱面类型徽标 —— 用舞萌原版贴图（UI_UPE_Infoicon_StandardMode / DeluxeMode）。
 *
 * 贴图原始尺寸 113×32，其中「标准 / DX」文字高 19px。原文字 tag 字号为 11.sp
 * （密度 3 的设备上约 33px），故缩放比 ≈ 33/19 ≈ 1.74：宽 64dp × 高 18dp，
 * 贴图内文字与原本 tag 的字号基本一致。
 */
@Composable
fun ChartTypeChip(type: String, modifier: Modifier = Modifier) {
    val icon = if (type == "DX") {
        R.drawable.kiba_ui_upe_infoicon_deluxemode
    } else {
        R.drawable.kiba_ui_upe_infoicon_standardmode
    }
    Image(
        painter = painterResource(icon),
        contentDescription = type,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width = 64.dp, height = 18.dp),
    )
}

/**
 * 详情页信息列表行：标签在左（次要色）、值在右（主色）。
 * 用于歌曲详情的 ID / BPM / 流派 / 版本等键值对展示（替代原来的 tag 堆叠）。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** 空状态卡片（数据未就绪时引导用户）。 */
@Composable
fun EmptyStateCard(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Row(horizontalArrangement = Arrangement.Center) { action() }
            }
        }
    }
}

/** 通用竖直流排：LazyColumn 的 item 间距 8dp。 */
val PageItemSpacing = 8.dp

/** 小竖间距工具。 */
@Composable
fun VGap(height: androidx.compose.ui.unit.Dp = PageItemSpacing) = Spacer(Modifier.height(height))

/** 小横间距工具。 */
@Composable
fun HGap(width: androidx.compose.ui.unit.Dp = 6.dp) = Spacer(Modifier.width(width))

/** 信息小胶囊（成就率 / DX 分 / 判定标记等），FlowRow 承载时自动换行。 */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── 官方贴图徽标（与 Best50 图片生成同一套素材）──

/** drawable 名 → 资源 id（素材名全小写，取不到返回 0，调用方自行跳过）。 */
@Composable
private fun drawableId(name: String): Int {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

/**
 * 评级贴图（UI_GAM_Rank_*）：d/c/b/bb/bbb/a/aa/aaa/s/sp/ss/ssp/sss/sssp。
 * 取不到素材时退回文字，保证不空白。
 */
@Composable
fun RankBadge(rate: String, height: androidx.compose.ui.unit.Dp = 40.dp) {
    val file = when (rate.lowercase()) {
        "d" -> "d"; "c" -> "c"; "b" -> "b"; "bb" -> "bb"; "bbb" -> "bbb"
        "a" -> "a"; "aa" -> "aa"; "aaa" -> "aaa"
        "s" -> "s"; "sp" -> "sp"; "s+" -> "sp"
        "ss" -> "ss"; "ssp" -> "ssp"; "ss+" -> "ssp"
        "sss" -> "sss"; "sssp" -> "sssp"; "sss+" -> "sssp"
        else -> ""
    }
    val id = drawableId("kiba_ui_gam_rank_$file")
    if (file.isBlank() || id == 0) {
        Text(
            text = rate.uppercase(),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        return
    }
    Image(
        painter = androidx.compose.ui.res.painterResource(id),
        contentDescription = "评级 $rate",
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(height),
    )
}

/**
 * 连击贴图（UI_MSS_MBase_Icon_*_S）：fc / fcp（FC+）/ ap / app（AP+）。
 * 空值不渲染（Bot 的同位置会贴 Blank，这里直接留白更干净）。
 */
@Composable
fun ComboBadge(fc: String, height: androidx.compose.ui.unit.Dp = 32.dp) {
    val file = when (fc.lowercase()) {
        "fc" -> "fc"; "fcp" -> "fcp"; "fc+" -> "fcp"
        "ap" -> "ap"; "app" -> "app"; "ap+" -> "app"
        else -> return
    }
    val id = drawableId("kiba_ui_mss_mbase_icon_${file}_s")
    if (id == 0) return
    Image(
        painter = androidx.compose.ui.res.painterResource(id),
        contentDescription = "连击 $fc",
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(height),
    )
}

/**
 * 同步贴图（UI_MSS_MBase_Icon_*_S）：fs / fsp（FS+）/ fsd（FDX）/ fsdp（FDX+）。
 */
@Composable
fun SyncBadge(fs: String, height: androidx.compose.ui.unit.Dp = 32.dp) {
    val file = when (fs.lowercase()) {
        "fs" -> "fs"; "fsp" -> "fsp"; "fs+" -> "fsp"
        "fsd" -> "fsd"; "fsdp" -> "fsdp"; "fdx" -> "fsd"; "fdx+" -> "fsdp"
        else -> return
    }
    val id = drawableId("kiba_ui_mss_mbase_icon_${file}_s")
    if (id == 0) return
    Image(
        painter = androidx.compose.ui.res.painterResource(id),
        contentDescription = "同步 $fs",
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(height),
    )
}

/** 「已完成」标记（进度明细页）。 */
@Composable
fun DoneTag(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MiuixTheme.colorScheme.primary.copy(alpha = 0.16f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "已完成",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
