package com.kira.kiba.ui.songdetail

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.R
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.utils.CoverSources
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniCoverPlaceholder
import com.kira.kiba.ui.navigation.KibaBackAction
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 中二节奏歌曲详情页魔改顶栏（对标舞萌 [SongDetailTopBarSlot]）：
 * - 展开态：封面（水鱼 chunithm CDN）铺满顶栏作背景，大标题 = 歌名，小标题 = 艺术家；
 *   返回按钮沿用现有 glass 返回样式；actions = 收藏 star（按游戏命名空间隔离）。
 * - 中二节奏不支持分享，故**不提供分享入口**。
 * - 字色 / 状态栏图标逻辑与舞萌一致（按封面亮度切换黑/白字，折叠后回归主题色）。
 */
@Composable
fun ChuniSongDetailTopBarSlot(
    songId: Int,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
) {
    val db = remember { AppDataBase.getInstance() }

    val song by produceState<ChuniSongEntity?>(initialValue = null, songId) {
        value = runCatching { db.chuniSongWithChartDao().getChuniSongById(songId) }.getOrNull()
    }
    val current = song

    var favor by remember(songId) {
        mutableStateOf(SpUtil.isFavorite(GameType.CHUNITHM, songId.toString()))
    }

    // 封面亮度探测（与舞萌同一机制）：命中首个候选封面算平均亮度决定字色。
    val context = LocalContext.current
    val probe by produceState<ChuniCoverProbe?>(initialValue = null, songId) {
        val url = CoverSources.chuniCandidates(songId).firstOrNull() ?: return@produceState
        val bmp = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
            ).drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
        }.getOrNull()
        value = bmp?.let { ChuniCoverProbe(it, chuniAverageLuminance(it)) }
    }

    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }

    val themeOnSurface = MiuixTheme.colorScheme.onSurface
    val coverColor = when {
        probe == null -> Color.White
        probe!!.luminance * CHUNI_COVER_DARKEN_FACTOR >= 0.60f -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val onCoverColor = if (collapsed) themeOnSurface else coverColor

    // 状态栏图标外观：封面可见时跟随字色；滑出封面（折叠）后恢复主题默认。
    val themeIsDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val themeDefaultLightBars = !themeIsDark
    val coverLightBars = coverColor != Color.White
    val activity = remember { (context as? ContextWrapper).chuniFindActivity() }
    LaunchedEffect(activity, collapsed, coverLightBars) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        if (collapsed) delay(CHUNI_STATUS_BAR_SWITCH_DELAY_MS)
        controller.isAppearanceLightStatusBars =
            if (collapsed) themeDefaultLightBars else coverLightBars
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars =
                    themeDefaultLightBars
            }
        }
    }

    // 展开态高度：保证完整露出正方形封面。
    val density = LocalDensity.current
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val minExpansionPx = (screenWidthPx - statusBarPx - collapsedPx).coerceAtLeast(0)

    GlassTopAppBar(
        title = current?.title ?: "",
        largeTitle = current?.title ?: "",
        subtitle = current?.artist ?: "",
        titleColor = onCoverColor,
        largeTitleColor = onCoverColor,
        subtitleColor = onCoverColor.copy(alpha = 0.72f),
        backdrop = LocalGlassTopAppBarBackdrop.current,
        scrollBehavior = scrollBehavior,
        navigationIcon = { KibaBackAction(onClick = onBack, tint = onCoverColor) },
        actions = {
            GlassIconButton(onClick = {
                favor = !favor
                SpUtil.setFavorite(GameType.CHUNITHM, songId.toString(), favor)
            }) {
                Icon(
                    if (favor) MiuixGlassIcons.Regular.Unstar else MiuixGlassIcons.Regular.Starred,
                    contentDescription = if (favor) "取消收藏" else "收藏",
                    tint = onCoverColor,
                )
            }
            // 中二节奏不支持分享，不提供分享入口
        },
        background = {
            Box(modifier = Modifier.fillMaxSize()) {
                ChuniCoverBackground(songId = songId, modifier = Modifier.fillMaxSize())
                // 统一压暗 15%，保证白底封面上的白字可读（字色判定与视觉压暗共用系数）。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 1f - CHUNI_COVER_DARKEN_FACTOR)),
                )
            }
        },
        minExpansionPx = minExpansionPx,
        largeTitleBottomAligned = true,
        largeTitleFontWeight = FontWeight.Bold,
        largeTitleBlurRadius = 0.dp,
    )
}

/** 封面背景：逐个尝试候选链，全部失败落回流派色占位图。 */
@Composable
private fun ChuniCoverBackground(songId: Int, modifier: Modifier = Modifier) {
    val candidates = remember(songId) { CoverSources.chuniCandidates(songId) }
    ChuniCoverChainBackground(candidates = candidates, index = 0, modifier = modifier)
}

@Composable
private fun ChuniCoverChainBackground(
    candidates: List<String>,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val url = candidates.getOrNull(index)
    if (url == null) {
        ChuniCoverPlaceholder(
            stroke = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            size = 100.dp,
            modifier = modifier,
        )
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = {
            ChuniCoverPlaceholder(
                stroke = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                size = 100.dp,
                modifier = modifier,
            )
        },
        error = {
            ChuniCoverChainBackground(candidates = candidates, index = index + 1, modifier = modifier)
        },
        modifier = modifier,
    )
}

/** 封面统一压暗系数（黑色 15% 叠层）。 */
private const val CHUNI_COVER_DARKEN_FACTOR = 0.85f

/** 折叠后状态栏外观切换延迟。 */
private const val CHUNI_STATUS_BAR_SWITCH_DELAY_MS = 130L

/** 封面背景探测结果：位图 + 平均亮度（0~1）。 */
private data class ChuniCoverProbe(val bitmap: Bitmap, val luminance: Float)

/** 沿 Context 链向上找宿主 Activity。 */
private tailrec fun ContextWrapper?.chuniFindActivity(): Activity? = when (this) {
    null -> null
    is Activity -> this
    else -> (baseContext as? ContextWrapper).chuniFindActivity()
}

/** 24×24 下采样平均亮度（忽略透明像素），用于白底封面切黑字。 */
private fun chuniAverageLuminance(src: Bitmap): Float {
    val small = Bitmap.createScaledBitmap(src, 24, 24, true)
    val px = IntArray(24 * 24)
    small.getPixels(px, 0, 24, 0, 0, 24, 24)
    var total = 0f
    var count = 0
    for (p in px) {
        val a = (p ushr 24) and 0xFF
        if (a < 128) continue
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        total += 0.2126f * r + 0.7152f * g + 0.0722f * b
        count++
    }
    if (count == 0) return 0f
    return (total / count).coerceIn(0f, 1f)
}
