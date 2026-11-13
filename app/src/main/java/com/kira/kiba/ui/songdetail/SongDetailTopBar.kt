package com.kira.kiba.ui.songdetail

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.kira.kiba.ui.common.COVER_DARK_FOREGROUND
import com.kira.kiba.ui.common.COVER_LIGHT_LUMINANCE
import com.kira.kiba.ui.common.coverCandidateUrls
import com.kira.kiba.ui.common.rememberCoverTint
import com.kira.kiba.ui.material.LocalAdvancedMaterialEnabled
import com.kira.kiba.ui.material.LocalMaterialFamily
import com.kira.kiba.ui.material.MaterialFamily
import com.kira.kiba.ui.material.liquid.LocalInLiquidTopBar
import com.kira.kiba.ui.navigation.KibaActionButton
import com.kira.kiba.ui.navigation.KibaBackAction
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import com.kira.kiba.ui.material.liquid.LiquidTopBarSkin
import com.kira.kiba.ui.material.liquid.LocalLiquidBackdrop
import com.kira.kiba.ui.navigation.useLiquidBar
import com.kira.kiba.ui.common.CopyTitleAction
import com.kira.kiba.ui.share.ShareImageAction
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
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
    /**
     * 标题 / 副标题覆盖（非空时替换歌名 / 艺术家显示）。
     * 播放页的「播放列表面板」用：顶栏收窄后标题改「播放列表」、副标题改「当前播放: xxx」。
     */
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    /**
     * 程序化折叠（面板显示时强制收窄到收缩态，封面渐隐）。
     *
     * 播放页内容不挂 nestedScroll，顶栏原本永不折叠；这里用 Animatable 驱动
     * [TopAppBarState.heightOffset] 与 [TopAppBarState.contentOffset] 一起插值，
     * 与真实滚动走同一套 mask / 背板时钟，收窄与恢复都有非线性缓动
     * （快起慢收，与 Tab 切换同款贝塞尔）。
     */
    programCollapsed: Boolean = false,
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

    // 展开态高度：保证完整露出正方形封面（顶栏宽 ≈ 屏宽，高度 = 状态栏 + 屏宽）。
    // minExpansion = 屏宽 - 状态栏 - CollapsedHeight（collapsed 部分 BlurTopAppBar 自带）。
    val density = LocalDensity.current
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val minExpansionPx = (screenWidthPx - statusBarPx - collapsedPx).coerceAtLeast(0)

    // 前景色与折叠进度同源：展开态按封面亮度取白字/黑字，折叠全程线性插值到系统
    // onSurface。旧版用 `contentOffset < 0` 判折叠，而官方 behavior 在顶栏折叠阶段
    // 独占滚动量、列表一帧都不滚，这个值恒为 0 —— 暗封面折成浅色蒙版后字仍是白的，
    // 浅底白字彻底读不出来。现在字色、按钮 tint、状态栏图标、液态按钮材质共用
    // 同一条 collapsedFraction，封面淡出多少就转过去多少。
    val themeOnSurface = MiuixTheme.colorScheme.onSurface
    val coverColor = when {
        probeValue == null -> themeOnSurface
        probeValue.luminance * COVER_DARKEN_FACTOR >= COVER_LIGHT_LUMINANCE -> COVER_DARK_FOREGROUND
        else -> Color.White
    }
    val tint = rememberCoverTint(
        scrollBehavior = scrollBehavior,
        coverColor = coverColor,
        systemColor = themeOnSurface,
        limitKey = minExpansionPx,
    )
    val onCoverColor by tint.color
    val systemAppearance by tint.systemAppearance
    val lightStatusBars by tint.lightStatusBars

    // 状态栏图标绑定按钮 / 标题的 onCoverColor：按钮白 → 图标白，按钮深 → 图标深，
    // 折叠中随插值同帧翻转。不再用独立的折叠进度阈值，否则会出现
    // 「按钮还偏白、状态栏图标已经变黑」的脱节。
    val themeIsDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val themeDefaultLightBars = !themeIsDark
    val activity = remember { (context as? ContextWrapper).findActivity() }
    LaunchedEffect(activity, lightStatusBars) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        controller.isAppearanceLightStatusBars = lightStatusBars
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars =
                    themeDefaultLightBars
            }
        }
    }

    // ── 程序化折叠（播放列表面板）：不经过滚动，直接驱动折叠状态。
    // heightOffset 控制收窄量（负），contentOffset 同步置负驱动 collapsed（封面渐隐）
    // ——两者必须一起动，只写 heightOffset 时封面不消失、只写 contentOffset 时高度不变。
    // 结束值精确落在 0 / limit，避免恢复后残留半折叠的中间态。
    LaunchedEffect(programCollapsed, scrollBehavior) {
        val st = scrollBehavior.state
        val limit = st.heightOffsetLimit
        if (limit >= 0f) return@LaunchedEffect // 材质关闭 / 未测量：没有可折叠的大标题
        val target = if (programCollapsed) 1f else 0f
        val current = (-st.heightOffset / limit).coerceIn(0f, 1f)
        androidx.compose.animation.core.Animatable(current)
            .animateTo(
                targetValue = target,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 380,
                    easing = PanelCollapseEasing,
                ),
            ) {
            st.heightOffset = limit * value
            st.contentOffset = -limit * value
        }
    }

    // 液态玻璃模式下：封面还在时按钮保持 miuix（裸图标压在封面上才对），
    // 折叠后顶栏变成一条玻璃带 → 按钮随之换成液态玻璃圆钮（见 KibaActionButton）。
    // 液态材质下：收缩态（封面消失）顶栏的玻璃底板换成液态玻璃（其余材质保持原 miuix 玻璃）
    val liquidSkin = useLiquidBar()
    // 折叠态顶栏真实高度（含副标题行，px）：液态皮肤底板用它对齐 miuix 骨架的底边
    var liquidSkinCollapsedPx by remember { mutableIntStateOf(0) }
    val liquidActions = LocalMaterialFamily.current == MaterialFamily.LIQUID &&
        LocalAdvancedMaterialEnabled.current && systemAppearance

    Box {
        if (liquidSkin) {
            // 液态皮肤垫在最底层：收缩态封面淡出后由它接管顶栏底板
            LiquidTopBarSkin(
                backdrop = LocalLiquidBackdrop.current,
                scrollBehavior = scrollBehavior,
                fullRangeProgress = true,
                collapsedHeight = if (liquidSkinCollapsedPx > 0) {
                    with(LocalDensity.current) { liquidSkinCollapsedPx.toDp() }
                } else {
                    TopAppBarDefaults.CollapsedHeight
                },
            )
        }
        CompositionLocalProvider(LocalInLiquidTopBar provides liquidActions) {
        GlassTopAppBar(
            title = titleOverride ?: current?.title ?: "",
            largeTitle = titleOverride ?: current?.title ?: "",
            subtitle = subtitleOverride ?: current?.artist ?: "",
            titleColor = onCoverColor,
            largeTitleColor = onCoverColor,
            subtitleColor = onCoverColor.copy(alpha = 0.72f),
            backdrop = if (liquidSkin) null else LocalGlassTopAppBarBackdrop.current,
            scrollBehavior = scrollBehavior,
            bandBrush = if (liquidSkin) {
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
            } else {
                GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
            },
            navigationIcon = { KibaBackAction(onClick = onBack, tint = onCoverColor) },
            actions = {
                KibaActionButton(onClick = {
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
                // 复制歌名到剪贴板
                CopyTitleAction(
                    title = current?.title ?: "",
                    tint = onCoverColor,
                )
                // 分享歌曲详情图（生成后可选保存相册 / 分享给好友）
                ShareImageAction(
                    fileName = ShareService.defaultName("song_$songId"),
                    build = { ShareService.buildSongPng(context, songId) },
                    tint = onCoverColor,
                )
            },
            onCollapsedHeightPx = { liquidSkinCollapsedPx = it },
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
    }
}

/** 封面统一压暗系数（黑色 15% 叠层）：字色判定与视觉压暗共用，保证一致。 */
private const val COVER_DARKEN_FACTOR = 0.85f

/**
 * 程序化折叠（播放列表面板开合）的缓动：快起慢收三次贝塞尔，与 Tab 切换
 * （KibaMainScreen 的 TabSwitchEasing）同款——非线性是定稿口径，线性会显得机械。
 */
private val PanelCollapseEasing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 0.85f, 0.16f, 1f)

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
