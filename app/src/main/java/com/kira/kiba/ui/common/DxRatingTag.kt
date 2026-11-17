package com.kira.kiba.ui.common

import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.kiba.core.best50.NamePlateRenderer

/**
 * DX Rating 段位色（舞萌官方口径，maimai DX）：
 * 白 0-999 / 蓝 1000-1999 / 绿 2000-3999 / 黄 4000-6999 / 红 7000-9999 /
 * 紫 10000-11999 / 铜 12000-12999 / 银 13000-13999 / 金 14000-14499 /
 * 铂 14500-14999 / 虹 15000+。
 *
 * ⚠️ 游戏里 16000+ 还有一档「虹极」（Rainbow 极），但当前 Sinmai 资源里没有那张
 * 底图，所以 **15000+ 一律按虹**，等新版本资源出现再拆档。
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

/** 段位 → 徽章底色（素材缺失时的兜底）。 */
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

/** 旧版 174×51 底板名（maimai NET 口径），仅供非 UI 侧取素材名时兜底。 */
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

/** 徽章素材尺寸（Sinmai `UI_CMN_DXRating_S_xx`，176×40 原生）。 */
private const val BADGE_W = 176f
private const val BADGE_H = 40f

/**
 * DX Rating 徽章 —— 直接用 **Sinmai 抽出来的素材**（`assets/nameplate/badge_01..11.webp`，
 * 11 档：白→虹），数字按槽位逐格落位、字号与颜色跟段位走，和姓名框里的徽章完全同一套。
 *
 * 已经废弃：>16000 的「虹极」296×86 底框与 >15500 的发光贴图 —— 那套是 maimai NET 口径，
 * 游戏本体里 16000+ 目前并没有独立的极底图，先统一按虹处理。
 */
@Composable
fun DxRatingTag(
    rating: Int,
    modifier: Modifier = Modifier,
    width: Dp = 116.dp,
) {
    val context = LocalContext.current
    val safeRating = rating.coerceAtLeast(0)
    val tier = DxRatingTier.of(safeRating)
    val badge = remember(tier) {
        NamePlateRenderer.badgeBitmap(context, tier.ordinal + 1)?.asImageBitmap()
    }
    val boxH = width * BADGE_H / BADGE_W

    Box(modifier = modifier.size(width = width, height = boxH)) {
        if (badge != null) {
            Image(
                bitmap = badge,
                contentDescription = "DX Rating",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                // 数字走游戏本体那套 UI_CMN_Num_26p 精灵（与姓名框同一个函数）
                NamePlateRenderer.drawRatingDigits(
                    context,
                    drawContext.canvas.nativeCanvas,
                    RectF(0f, 0f, size.width, size.height),
                    safeRating,
                )
            }
        } else {
            // 素材缺失兜底：沿用纯色/彩虹渐变徽章
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
                    .clip(RoundedCornerShape(6.dp))
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

/** 段位中文名 + 颜色，供详情文本用。 */
fun dxRatingTierLabel(rating: Int): String {
    val tier = DxRatingTier.of(rating.coerceAtLeast(0))
    val stars = dxRatingStars(rating)
    val starSuffix = if (stars > 0) " " + "★".repeat(stars) else ""
    return tier.label + "段" + starSuffix
}
