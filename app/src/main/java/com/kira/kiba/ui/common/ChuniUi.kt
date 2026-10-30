package com.kira.kiba.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.kira.kiba.R
import java.util.Locale
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.utils.CoverSources
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 中二节奏封面加载（仅水鱼 chunithm CDN，失败退化为流派色块 + 音符占位）。
 */
@Composable
fun ChuniCoverFrame(
    songId: Int,
    genre: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val stroke = colorResource(genreStrokeRes(genre))
    val candidates = remember(songId) { CoverSources.chuniCandidates(songId) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, stroke, RoundedCornerShape(10.dp))
    ) {
        ChuniCoverChain(
            candidates = candidates, index = 0, stroke = stroke, size = size,
            modifier = Modifier.fillMaxSize().padding(2.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun ChuniCoverChain(
    candidates: List<String>, index: Int, stroke: Color, size: Dp, modifier: Modifier,
) {
    val url = candidates.getOrNull(index)
    if (url == null) {
        ChuniCoverPlaceholder(stroke = stroke, size = size, modifier = modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        loading = { ChuniCoverPlaceholder(stroke = stroke, size = size, modifier = modifier) },
        error = {
            ChuniCoverChain(candidates = candidates, index = index + 1, stroke = stroke, size = size, modifier = modifier)
        },
        modifier = modifier,
    )
}

@Composable
fun ChuniCoverPlaceholder(stroke: Color, size: Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stroke.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = MiuixGlassIcons.Regular.Music, contentDescription = null,
            tint = stroke, modifier = Modifier.size(size / 2.6f),
        )
    }
}

/** 中二节奏难度索引 → 配色（社区惯例：绿 / 橙 / 粉红 / **MASTER 紫 / ULTIMA 黑**；World's End 灰）。 */
fun chuniDifficultyColor(index: Int): Color = when (index) {
    0 -> Color(0xFF4FC26B)
    1 -> Color(0xFFFF8F3C)
    2 -> Color(0xFFF2547C)
    3 -> Color(0xFFB452E8)
    4 -> Color(0xFF2B2B2B)
    5 -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}

/** 中二节奏难度索引 → 短标签。 */
fun chuniDifficultyLabel(index: Int): String = ChuniDiffType.fromIndex(index).label

/** 中二节奏难度等级胶囊（复用舞萌 DifficultyChip 的视觉）。 */
@Composable
fun ChuniDifficultyChip(text: String, color: Color, modifier: Modifier = Modifier) {
    DifficultyChip(text = text, color = color, modifier = modifier)
}

/** 中二节奏小信息胶囊（等级 / 连击等）。 */
@Composable
fun ChuniMetaChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 中二节奏连击（FC）标签缩写 → 友好全称；空串返回 null（无连击）。
 *  水鱼 records 的 fc 值为 fullcombo / alljustice（官方口径）。 */
fun chuniFcDisplay(fc: String): String? {
    val v = fc.trim()
    if (v.isEmpty()) return null
    return when (v.lowercase()) {
        "fc", "full combo", "fullcombo" -> "FULL COMBO"
        "aj", "all justice", "alljustice" -> "ALL JUSTICE"
        "ajc", "all justice critical", "alljusticecritical" -> "ALL JUSTICE CRITICAL"
        else -> v.uppercase()
    }
}

/** 中二节奏连击（FC）徽章。 */
@Composable
fun ChuniFcBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 中二节奏分数（0–1009000）千分位格式化。 */
fun chuniScoreText(score: Int): String = String.format("%,d", score)

/**
 * 中二节奏 Rating / 单曲 ra 展示文本：两位小数。
 *
 * 官方就是两位小数（机台显示 14.53），Locale 固定 US，避免部分语言环境下
 * 小数点被格式化成逗号（德语等）导致宽度抖动。
 */
fun chuniRatingText(value: Double): String = String.format(Locale.US, "%.2f", value)

/**
 * 中二谱面满连击（谱面物量）展示文本。
 *
 * 水鱼 music_data 的 `combo` 字段**大量缺失**（实测 6657 个谱面里 2244 个为 0，
 * ULTIMA 更是 83% 缺）—— 那是「曲库没数据」，不是「连击真的是 0」，直接显示 0 会误导。
 * 缺失统一显示「暂缺」。
 */
fun chuniComboText(combo: Int): String = if (combo > 0) "$combo" else "暂缺"

/** 中二节奏 Rating 徽章（舞萌 DxRatingTag 为官方底板图，不适用中二，这里用主题色胶囊）。 */
@Composable
fun ChuniRatingTag(rating: Double, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = "RATING ${chuniRatingText(rating.coerceAtLeast(0.0))}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
