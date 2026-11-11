package com.kira.kiba.ui.songdetail

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
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
import com.kira.kiba.ui.material.LocalAdvancedMaterialEnabled
import com.kira.kiba.ui.material.LocalMaterialFamily
import com.kira.kiba.ui.material.MaterialFamily
import com.kira.kiba.ui.material.liquid.LiquidTopBarSkin
import com.kira.kiba.ui.material.liquid.LocalInLiquidTopBar
import com.kira.kiba.ui.material.liquid.LocalLiquidBackdrop
import com.kira.kiba.ui.navigation.KibaBackAction
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import com.kira.kiba.ui.navigation.useLiquidBar
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 集合类页面（歌单 / Agent 歌单 / 收藏）的大封面顶栏 —— 与歌曲详情页同一套视觉：
 * 展开态封面铺满、字色随封面亮度切换；折叠后封面渐隐、玻璃背板浮现。
 *
 * 与 [SongDetailTopBarSlot] 的差别只在**数据来源**：那个直接读歌曲表（歌名 / 艺术家 / 收藏态），
 * 这个由调用方给「候选封面 URL 列表 + 标题 + 副标题」。歌单封面可能是网络图（首曲封面）
 * 也可能是本地文件（Agent 模板封面），两者都走 Coil，本地文件用 `file://` Uri 传进来即可。
 */
@Composable
fun CollectionTopBarSlot(
    candidates: List<String>,
    title: String,
    subtitle: String,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current

    // 封面亮度探测：决定展开态字色（白底→黑字 / 暗底→白字）。
    // 只取首个能加载的候选，算完即止；失败/为空则按主题色走。
    val probe by produceState<Float?>(initialValue = null, candidates) {
        for (url in candidates) {
            val bmp = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // 要读像素
                        .build(),
                ).drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
            }.getOrNull()
            if (bmp != null) {
                value = averageLuminanceOf(bmp)
                break
            }
        }
    }

    // 占位图：真封面到位前铺底，避免顶栏出现一块纯黑空洞
    val placeholder = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.kiba_noimage)
        }.getOrNull()
    }
    val placeholderLum = remember(placeholder) { placeholder?.let { averageLuminanceOf(it) } }
    val lum = probe ?: placeholderLum

    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }

    val themeOnSurface = MiuixTheme.colorScheme.onSurface
    val coverColor = when {
        lum == null -> themeOnSurface
        lum * 0.85f >= 0.60f -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val onCoverColor = if (collapsed) themeOnSurface else coverColor

    // 状态栏图标外观跟随字色；折叠后延迟到渐隐结束再切回主题默认
    val themeIsDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val themeDefaultLightBars = !themeIsDark
    val coverLightBars = coverColor != Color.White
    val activity = remember { (context as? ContextWrapper).findActivityOrNull() }
    LaunchedEffect(activity, collapsed, coverLightBars) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        if (collapsed) delay(130L)
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

    // 展开高度：完整露出正方形封面（同歌曲详情页口径）
    val density = LocalDensity.current
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val minExpansionPx = (screenWidthPx - statusBarPx - collapsedPx).coerceAtLeast(0)

    val liquidSkin = useLiquidBar()
    // 折叠态顶栏真实高度（含副标题行，px）：液态皮肤底板用它对齐 miuix 骨架的底边。
    // 不接的话玻璃底板只有「状态栏 + CollapsedHeight(56dp)」，而带副标题的折叠态实际是
    // 56dp + 副标题行 + 底部 padding——底板停在标题行下缘，副标题整条露在玻璃外
    // （歌单 / Agent 歌单 / 我的收藏三处共用本槽位，实证于收藏页；修法同 SongDetailTopBar）。
    var liquidSkinCollapsedPx by remember { mutableIntStateOf(0) }
    val liquidActions = LocalMaterialFamily.current == MaterialFamily.LIQUID &&
        LocalAdvancedMaterialEnabled.current && collapsed

    Box {
        if (liquidSkin) {
            LiquidTopBarSkin(
                backdrop = LocalLiquidBackdrop.current,
                scrollBehavior = scrollBehavior,
                collapsedHeight = if (liquidSkinCollapsedPx > 0) {
                    with(density) { liquidSkinCollapsedPx.toDp() }
                } else {
                    TopAppBarDefaults.CollapsedHeight
                },
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalInLiquidTopBar provides liquidActions,
        ) {
            GlassTopAppBar(
                title = title,
                largeTitle = title,
                subtitle = subtitle,
                titleColor = onCoverColor,
                largeTitleColor = onCoverColor,
                subtitleColor = onCoverColor.copy(alpha = 0.72f),
                backdrop = if (liquidSkin) null else LocalGlassTopAppBarBackdrop.current,
                scrollBehavior = scrollBehavior,
                bandBrush = if (liquidSkin) {
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent),
                    )
                } else {
                    GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
                },
                navigationIcon = { KibaBackAction(onClick = onBack, tint = onCoverColor) },
                actions = { actions?.invoke(this) },
                background = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CoverChainImage(
                            candidates = candidates,
                            placeholder = placeholder,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 统一压暗 15%：保证白底封面上的白字也可读（字色判定同系数）
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.15f)),
                        )
                    }
                },
                minExpansionPx = minExpansionPx,
                onCollapsedHeightPx = { liquidSkinCollapsedPx = it },
                largeTitleBottomAligned = true,
                largeTitleFontWeight = FontWeight.Bold,
                largeTitleBlurRadius = 0.dp,
            )
        }
    }
}

/** 封面候选链：逐个尝试，全失败落占位图（与列表 CoverFrame 同机制）。 */
@Composable
private fun CoverChainImage(
    candidates: List<String>,
    index: Int = 0,
    placeholder: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val url = candidates.getOrNull(index)
    if (url.isNullOrBlank()) {
        if (placeholder != null) {
            Image(
                bitmap = placeholder.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = {
            if (placeholder != null) {
                Image(
                    bitmap = placeholder.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                )
            }
        },
        error = { CoverChainImage(candidates, index + 1, placeholder, modifier) },
        modifier = modifier,
    )
}

private tailrec fun ContextWrapper?.findActivityOrNull(): Activity? = when (this) {
    null -> null
    is Activity -> this
    else -> (baseContext as? ContextWrapper).findActivityOrNull()
}

/** 24×24 下采样平均亮度（忽略透明像素）。 */
private fun averageLuminanceOf(src: Bitmap): Float {
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
