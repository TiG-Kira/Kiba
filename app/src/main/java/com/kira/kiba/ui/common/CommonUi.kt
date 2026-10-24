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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kira.kiba.R
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.network.MaimaiDataClient
import com.kira.kiba.ui.navigation.LocalNavBarBottomPadding
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.basic.Card
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
 */
@Composable
fun pageContentPadding(
    topExtra: androidx.compose.ui.unit.Dp = 8.dp,
    bottomExtra: androidx.compose.ui.unit.Dp = 16.dp,
): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = LocalTopBarClearance.current + topExtra,
    bottom = LocalNavBarBottomPadding.current + bottomExtra,
)

/** 舞萌特色：方形封面框（genre 描边 + 圆角内嵌封面）。 */
@Composable
fun CoverFrame(
    songId: Int,
    genre: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    val stroke = when (genre) {
        "流行&动漫" -> R.color.pop_stroke
        "niconico & VOCALOID" -> R.color.vocal_stroke
        "东方Project" -> R.color.touhou_stroke
        "其他游戏" -> R.color.variety_stroke
        "舞萌" -> R.color.maimai_stroke
        "宴会場" -> R.color.utage_stroke
        else -> R.color.gekichuni_stroke
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, colorResource(stroke), RoundedCornerShape(10.dp))
    ) {
        AsyncImage(
            model = MaimaiDataClient.DIVING_FISH_COVER_URL + "$songId.png",
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

/** 难度枚举 → 舞萌标准色。 */
fun difficultyColor(type: DifficultyType): Color = when (type) {
    DifficultyType.BASIC -> Color(0xFF1FAF60)
    DifficultyType.ADVANCED -> Color(0xFFFF8A00)
    DifficultyType.EXPERT -> Color(0xFFFF4D6A)
    DifficultyType.MASTER -> Color(0xFF9B4DFF)
    DifficultyType.REMASTER -> Color(0xFFF06292)
    else -> Color(0xFF9E9E9E)
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

/** 难度 / 等级 / 定数 胶囊（舞萌难度等级分类样式）。 */
@Composable
fun DifficultyChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
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
