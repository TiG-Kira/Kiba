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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.kira.kiba.R
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.network.MaimaiDataClient
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
    val candidates = remember(songId, imageUrl) { coverCandidates(songId, imageUrl) }
    var index by remember(candidates) { mutableStateOf(0) }
    val painter = rememberAsyncImagePainter(
        model = candidates.getOrNull(index),
        onError = {
            if (index < candidates.lastIndex) index = index + 1
        },
    )
    val failed = (painter.state as? AsyncImagePainter.State)?.let {
        it is AsyncImagePainter.State.Error
    } ?: false
    val loaded = (painter.state as? AsyncImagePainter.State)?.let {
        it is AsyncImagePainter.State.Success
    } ?: false

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, stroke, RoundedCornerShape(10.dp))
    ) {
        if (loaded && !failed) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            CoverPlaceholder(stroke = stroke, size = size)
        }
    }
}

/** 封面候选 URL 链（水鱼 CDN 多种编号形态 → 官网图床）。 */
private fun coverCandidates(songId: Int, imageUrl: String): List<String> {
    val list = mutableListOf<String>()
    fun pushCd(id: Int) {
        val url = MaimaiDataClient.DIVING_FISH_COVER_URL + "%05d".format(id) + ".png"
        if (url !in list) list.add(url)
    }
    pushCd(songId)
    val mod10k = songId % 10000
    if (mod10k != songId) pushCd(mod10k)
    val mod100k = songId % 100000
    if (mod100k != songId && mod100k != mod10k) pushCd(mod100k)
    if (imageUrl.isNotBlank()) {
        list.add(MaimaiDataClient.IMAGE_BASE_URL + imageUrl)
    }
    return list
}

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

/** DX / SD 谱面类型徽标。 */
@Composable
fun ChartTypeChip(type: String, modifier: Modifier = Modifier) {
    val color = if (type == "DX") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    DifficultyChip(text = type, color = color, modifier = modifier)
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
