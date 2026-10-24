package com.kira.kiba.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * DX Rating 段位色（舞萌官方口径，maimai DX）：
 * 白 0-999 / 蓝 1000-1999 / 绿 2000-3999 / 黄 4000-6999 / 红 7000-9999 /
 * 紫 10000-11999 / 铜 12000-12999 / 银 13000-13999 / 金 14000-14499 /
 * 铂 14500-14999 / 虹 15000+（★ 子段：15000 起每 250 一星至 16750+ 四星）。
 */
enum class DxRatingTier(val range: IntRange, val label: String) {
    WHITE(0..999, "白"),
    BLUE(1000..1999, "蓝"),
    GREEN(2000..3999, "绿"),
    YELLOW(4000..6999, "黄"),
    RED(7000..9999, "红"),
    PURPLE(10000..11999, "紫"),
    COPPER(12000..12999, "铜"),
    SILVER(13000..13999, "银"),
    GOLD(14000..14499, "金"),
    PLATINUM(14500..14999, "铂"),
    RAINBOW(15000..Int.MAX_VALUE, "虹"),
    ;

    companion object {
        fun of(rating: Int): DxRatingTier = entries.first { rating in it.range }
    }
}

/** 段位 → 徽章底色（null 表示用彩虹渐变）。 */
private fun DxRatingTier.badgeColor(): Color? = when (this) {
    DxRatingTier.WHITE -> Color(0xFFECEFF1)
    DxRatingTier.BLUE -> Color(0xFF1565C0)
    DxRatingTier.GREEN -> Color(0xFF2E7D32)
    DxRatingTier.YELLOW -> Color(0xFFF9A825)
    DxRatingTier.RED -> Color(0xFFC62828)
    DxRatingTier.PURPLE -> Color(0xFF6A1B9A)
    DxRatingTier.COPPER -> Color(0xFF9C5A24)
    DxRatingTier.SILVER -> Color(0xFF90A4AE)
    DxRatingTier.GOLD -> Color(0xFFD4AC0D)
    DxRatingTier.PLATINUM -> Color(0xFFCFD8DC)
    DxRatingTier.RAINBOW -> null
}

/** 段位 → 徽章文字色（浅色徽章配深字，保证可读性）。 */
private fun DxRatingTier.textColor(): Color = when (this) {
    DxRatingTier.WHITE, DxRatingTier.PLATINUM -> Color(0xFF37474F)
    DxRatingTier.YELLOW -> Color(0xFF3E2723)
    else -> Color(0xFFFFFFFF)
}

/** 15000+ 的 ★ 子段（每 250 一星，16750 起四星）。 */
fun dxRatingStars(rating: Int): Int = when {
    rating >= 16750 -> 4
    rating >= 16500 -> 3
    rating >= 16250 -> 2
    rating >= 16000 -> 1
    rating >= 15000 -> 0
    else -> -1
}

/**
 * DX Rating 徽章（段位底色 + 数值），用于「我」页面首卡。
 * rating <= 0 时显示灰色「未绑定 / 暂无」态。
 */
@Composable
fun DxRatingTag(
    rating: Int,
    modifier: Modifier = Modifier,
) {
    val tier = DxRatingTier.of(rating.coerceAtLeast(0))
    val solid = tier.badgeColor()
    val textColor = tier.textColor()
    val brush = if (solid != null) {
        Brush.horizontalGradient(listOf(solid, solid))
    } else {
        // 虹段：红橙黄绿蓝紫渐变
        Brush.horizontalGradient(
            listOf(
                Color(0xFFE53935),
                Color(0xFFFB8C00),
                Color(0xFFFDD835),
                Color(0xFF43A047),
                Color(0xFF1E88E5),
                Color(0xFF8E24AA),
            )
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(brush)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "DX RATING",
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = 0.85f),
        )
        val stars = dxRatingStars(rating)
        val starSuffix = if (stars > 0) " " + "★".repeat(stars) else ""
        Text(
            text = if (rating > 0) "$rating$starSuffix" else "—",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

/** 段位中文名 + 颜色，供详情文本用。 */
fun dxRatingTierLabel(rating: Int): String {
    val tier = DxRatingTier.of(rating.coerceAtLeast(0))
    val stars = dxRatingStars(rating)
    val starSuffix = if (stars > 0) " " + "★".repeat(stars) else ""
    return tier.label + "段" + starSuffix
}
