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
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.share.ShareService
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.coverCandidateUrls
import com.kira.kiba.ui.navigation.KibaBackAction
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import com.kira.kiba.ui.share.ShareImageAction
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
 * 歌曲详情页魔改顶栏（基于 miuix-glass GlassTopAppBar）：
 * - 展开态：封面原图铺满顶栏区域作背景（**不模糊、不暗化**，封面偏白时自动切黑字、
 *   偏暗时切白字）；大标题 = 歌名，小标题 = 艺术家；返回按钮沿用现有 glass 返回样式
 *   （未滚动裸图标、滚动后背板浮现）；actions = 收藏 star 按钮（代替原收藏开关）。
 * - 折叠：沿用现有 [ScrollBehavior] 折叠逻辑，折叠后立刻显示现有渐隐 TopBar 背板
 *   （封面背景与背板 band 共用同一 mask 时钟，此消彼长）。
 * - [GlassTopAppBar.background] 槽位为本页在 vendored miuix-glass 中新增的扩展。
 */
@Composable
fun SongDetailTopBarSlot(
    songId: Int,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
) {
    val db = remember { AppDataBase.getInstance() }

    val song by produceState<SongDataEntity?>(initialValue = null, songId) {
        value = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }.getOrNull()
    }
    val current = song

    var favor by remember(songId) { mutableStateOf(SpUtil.isFavorite(songId.toString())) }

    // 封面亮度探测：加载首个命中的候选封面算平均亮度决定字色（白底→黑字，暗底→白字）。
    // 只负责取色，不做显示；显示走下方 TopBarCoverImage 候选链（Coil 自身状态驱动刷新）。
    val context = LocalContext.current
    val probe by produceState<CoverProbe?>(initialValue = null, songId, current?.imageUrl) {
        val s = current ?: return@produceState
        for (url in coverCandidateUrls(s.id, s.imageUrl)) {
            val bmp = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // 要读像素算亮度，禁用 hardware bitmap
                        .build()
                ).drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
            }.getOrNull()
            if (bmp != null) {
                value = CoverProbe(bmp, averageLuminance(bmp))
                break
            }
        }
    }

    // 封面候选链 URL（与列表 CoverFrame 同源同顺序 → 命中同一份缓存）
    val candidates = remember(songId, current?.imageUrl) {
        coverCandidateUrls(songId, current?.imageUrl ?: "")
    }

    // 占位图：真封面到位前（含完全无封面图的歌曲）始终显示，本地资源同步解码。
    val placeholder = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.kiba_noimage)
        }.getOrNull()
    }
    val placeholderProbe = remember { placeholder?.let { CoverProbe(it, averageLuminance(it)) } }

    // 最终铺底的封面：真图优先，否则占位图（不因异步加载留空）。
    val probeValue = probe ?: placeholderProbe

    // 折叠状态：与背板浮现同一触发点（内容离开原位即开始淡入背板、淡出封面）。
    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }

    // 字色：展开态跟随封面平均亮度——按**压暗 15% 后**的亮度判定（偏亮→黑字，偏暗→白字），
    // 与视觉上的压暗层保持一致；折叠后按正常 TopBar 行为回归主题色，
    // 否则亮色封面页面滑出封面后会残留黑字在浅色背板上。
    // 注意：这里**不做颜色动画**——渐隐本身就是视觉过渡，若再叠一层 200ms 的
    // animateColorAsState，会让整个顶栏在渐隐期间逐帧重组（正文 + 大标题 + 按钮全部
    // 重新求值），滑动中正好撞上封面大图淡出，表现成「卡一下再继续滑」。
    val themeOnSurface = MiuixTheme.colorScheme.onSurface
    val coverColor = when {
        probeValue == null -> themeOnSurface
        probeValue.luminance * COVER_DARKEN_FACTOR >= 0.60f -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val onCoverColor = if (collapsed) themeOnSurface else coverColor

    // 状态栏图标外观：封面可见时跟随字色（黑字→深色图标，白字→白色图标），
    // 滑出封面（折叠）后恢复主题默认行为；离开本页时同样恢复。
    // 折叠方向的切换延迟到封面渐隐（100ms mask）结束后再执行：insets 外观切换本身
    // 会触发一次系统 dispatch，与渐隐首帧叠加是详情页滑动卡顿的来源之一；
    // 渐隐期间封面仍占大头，延后切换在视觉上也是更正确的时机。
    val themeIsDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val themeDefaultLightBars = !themeIsDark
    val coverLightBars = coverColor != Color.White // 黑字 → 状态栏图标也用深色
    val activity = remember { (context as? ContextWrapper).findActivity() }
    LaunchedEffect(activity, collapsed, coverLightBars) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        if (collapsed) delay(STATUS_BAR_SWITCH_DELAY_MS)
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

    // 展开态高度：保证完整露出正方形封面（顶栏宽 ≈ 屏宽，高度 = 状态栏 + 屏宽）。
    // minExpansion = 屏宽 - 状态栏 - CollapsedHeight（collapsed 部分 BlurTopAppBar 自带）。
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
                SpUtil.setFavorite(songId.toString(), favor)
            }) {
                // 注意：miuix-glass 图标库命名与形状相反——Starred 画的是空心星（双轮廓），
                // Unstar 画的是实心星（单轮廓），这里按实际形状取用。
                Icon(
                    if (favor) MiuixGlassIcons.Regular.Unstar else MiuixGlassIcons.Regular.Starred,
                    contentDescription = if (favor) "取消收藏" else "收藏",
                    tint = onCoverColor,
                )
            }
            // 分享歌曲详情图（生成后可选保存相册 / 分享给好友）
            ShareImageAction(
                fileName = ShareService.defaultName("song_$songId"),
                build = { ShareService.buildSongPng(context, songId) },
                tint = onCoverColor,
            )
        },
        background = {
            Box(modifier = Modifier.fillMaxSize()) {
                // 封面显示走候选链 SubcomposeAsyncImage：与列表 CoverFrame 同机制，
                // 加载完成由 Coil 自身状态立刻刷新（无需任何滑动交互），且不触碰滚动状态。
                // 列表加载过的封面直接命中内存/磁盘缓存，进入详情页秒显。
                TopBarCoverImage(
                    candidates = candidates,
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxSize(),
                )
                // 统一压暗 15%（含占位图与加载中状态），保证白底封面上的白字也可读；
                // 字色判定按 luminance × [COVER_DARKEN_FACTOR] 与此对齐。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 1f - COVER_DARKEN_FACTOR)),
                )
            }
        },
        minExpansionPx = minExpansionPx,
        // 展开态：歌名加粗，标题/副标题贴到顶栏底部（封面下部），距底边留空白
        largeTitleBottomAligned = true,
        largeTitleFontWeight = FontWeight.Bold,
        // 关闭大标题折叠模糊：模糊半径随折叠进度逐帧变化，首帧要创建
        // RenderEffect（shader 编译/分层分配），在滑动中正好撞上封面渐隐，
        // 表现成「渐隐替换瞬间卡一下再继续流畅」；标题本身有位移+渐隐，无需模糊。
        largeTitleBlurRadius = 0.dp,
    )
}

/** 封面统一压暗系数（黑色 15% 叠层）：字色判定与视觉压暗共用，保证一致。 */
private const val COVER_DARKEN_FACTOR = 0.85f

/** 折叠后状态栏外观切换的延迟：略大于渐隐 mask 时长（100ms），避开渐隐关键帧。 */
private const val STATUS_BAR_SWITCH_DELAY_MS = 130L

/** 封面背景探测结果：位图（直接用于背景显示）+ 平均亮度（0~1）。 */
private data class CoverProbe(val bitmap: Bitmap, val luminance: Float)

/** 顶栏封面候选链：逐个尝试，任一成功即显示；全部失败落回占位图。
 *  与列表 [com.kira.kiba.ui.common.CoverFrame] 同机制（SubcomposeAsyncImage 的
 *  error slot 递归）——加载完成时 Coil 自身绘制状态失效立即重绘，无需父级重组，
 *  因此用户未滑动时图片到位也会立刻刷新，且完全不影响滚动操作。 */
@Composable
private fun TopBarCoverImage(
    candidates: List<String>,
    placeholder: Bitmap?,
    modifier: Modifier = Modifier,
) {
    TopBarCoverImage(candidates, index = 0, placeholder = placeholder, modifier = modifier)
}

@Composable
private fun TopBarCoverImage(
    candidates: List<String>,
    index: Int,
    placeholder: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val url = candidates.getOrNull(index)
    if (url == null) {
        // 候选全失败（含完全无图歌曲）：本地占位图铺底
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
            // 加载中先铺占位图（内存/磁盘缓存命中时 loading 一闪而过或根本不出现）
            if (placeholder != null) {
                Image(
                    bitmap = placeholder.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                )
            }
        },
        error = {
            TopBarCoverImage(candidates, index + 1, placeholder, modifier)
        },
        modifier = modifier,
    )
}

/** 沿 Context 链向上找宿主 Activity（Compose LocalContext 可能包着 ContextThemeWrapper）。 */
private tailrec fun ContextWrapper?.findActivity(): Activity? = when (this) {
    null -> null
    is Activity -> this
    else -> (baseContext as? ContextWrapper).findActivity()
}

/** 24×24 下采样平均亮度（忽略透明像素），用于白底封面切黑字。 */
private fun averageLuminance(src: Bitmap): Float {
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
