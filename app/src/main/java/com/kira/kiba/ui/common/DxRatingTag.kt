package com.kira.kiba.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** 段位 → Bot 的 DX Rating 底板（UI_CMN_DXRating_S_01…10，b50 分档）。 */
fun dxRatingPlateRes(rating: Int): String {
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

/**
 * DX Rating 徽章 —— 与 Bot 生成 B50 图时完全同一套素材与版式：
 * 段位底板 `UI_CMN_DXRating_S_XX`（174×51）+ 逐位数字 `UI_NUM_Drating_N`
 * （30×34，0.7 倍后贴在 x=74/92/110/129/147、y=14 五个列位，右对齐）。
 *
 * 数值为 0（未绑定）时只显示白段位底板，数字区留空。
 */
@Composable
fun DxRatingTag(
    rating: Int,
    modifier: Modifier = Modifier,
    width: Dp = 116.dp,
) {
    val context = LocalContext.current
    val safeRating = rating.coerceAtLeast(0)
    val plateId = runCatching {
        context.resources.getIdentifier(dxRatingPlateRes(safeRating), "drawable", context.packageName)
    }.getOrDefault(0)

    val boxH = width * 51f / 174f
    // 数字图在底板内的相对位置（上游 _drawRating：0.7 倍，(col-2, 14)）
    val digitW = width * 21f / 174f
    val digitH = boxH * 23.8f / 51f
    val digitY = boxH * 14f / 51f

    Box(
        modifier = modifier
            .size(width = width, height = boxH)
            .clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (plateId != 0) {
            Image(
                painter = painterResource(plateId),
                contentDescription = "DX Rating",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(width = width, height = boxH),
            )
            var value = safeRating
            var i = 4
            while (value > 0 && i >= 0) {
                val digit = value % 10
                value /= 10
                val digitId = runCatching {
                    context.resources.getIdentifier("kiba_ui_num_drating_$digit", "drawable", context.packageName)
                }.getOrDefault(0)
                if (digitId != 0) {
                    val col = RATING_COLUMNS[i]
                    Image(
                        painter = painterResource(digitId),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .size(width = digitW, height = digitH)
                            .offset(
                                x = width * (col - 2) / 174f - width / 2 + digitW / 2,
                                y = digitY - boxH / 2 + digitH / 2,
                            ),
                    )
                }
                i--
            }
            if (safeRating == 0) {
                Text(
                    text = "—",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF37474F),
                    modifier = Modifier.offset(x = width * 0.28f),
                )
            }
        } else {
            // 素材缺失兜底：沿用纯色徽章
            val tier = DxRatingTier.of(safeRating)
            val solid = tier.badgeColor()
            val brush = if (solid != null) {
                Brush.horizontalGradient(listOf(solid, solid))
            } else {
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
                        Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(width = width, height = boxH)
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (safeRating > 0) "$safeRating" else "—",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = tier.textColor(),
                )
            }
        }
    }
}

/** 上游 _drawRating 的五个数字列位。 */
private val RATING_COLUMNS = listOf(74, 92, 110, 129, 147)

/** 段位中文名 + 颜色，供详情文本用。 */
fun dxRatingTierLabel(rating: Int): String {
    val tier = DxRatingTier.of(rating.coerceAtLeast(0))
    val stars = dxRatingStars(rating)
    val starSuffix = if (stars > 0) " " + "★".repeat(stars) else ""
    return tier.label + "段" + starSuffix
}
