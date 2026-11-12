package com.kira.kiba.ui.me

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.core.account.AccountAvatar
import com.kira.kiba.core.account.CustomAvatar
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.material.LocalAdvancedMaterialEnabled
import com.kira.kiba.ui.material.LocalMaterialFamily
import com.kira.kiba.ui.material.MaterialFamily
import com.kira.kiba.ui.material.liquid.LiquidTopBarSkin
import com.kira.kiba.ui.material.liquid.LocalInLiquidTopBar
import com.kira.kiba.ui.material.liquid.LocalLiquidBackdrop
import com.kira.kiba.ui.navigation.KibaActionButton
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import com.kira.kiba.ui.navigation.useLiquidBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账户页顶栏 —— 与歌曲详情 / 集合页**完全同一套**大封面顶栏。
 *
 * ## 为什么换成这套
 *
 * 旧版账户页在 `KibaTopBar` 之上自己维护了一整套折叠动画（右上角羽化头像、
 * 刷新钮左右迁移、折叠态底色带），这些动画的进度全部压缩在折叠前 1/3，而顶栏
 * 高度收缩却铺满全程，视觉上就成了「动画先播完、折叠才慢半拍继续」的假卡顿；
 * 想修它又极易把内容 padding 推过头、让卡片钻进大标题区。
 *
 * 现在直接复用 [GlassTopAppBar] 的展开→折叠机制：背景（用户头像）随
 * `GlassTopAppBarDefaults.RampDistance` 的同一个 mask 时钟淡出，折叠后由玻璃背板
 * 接管。折叠与背景淡出同源同帧，不再有任何自定义定时动画。
 *
 * ## 与封面页的差别（都是刻意的）
 *
 * - **封面位是用户头像**：自定义 > 自动抓取 > 默认，铺满展开区；
 *   底部叠一层渐变到 `MiuixTheme.colorScheme.background`，让贴底标题有可读底色、
 *   也让头像下缘无缝接上页面背景。
 * - **不做封面亮度取色**：文字一律走系统色 `onSurface`，状态栏图标与按钮沿用
 *   账户页原 TopBar 的判定（即主题默认），不随头像明暗切换。
 * - **刷新按钮固定在右侧 actions 槽**，不再做左右迁移。
 * - 折叠态不带副标题：56dp 折叠行排不下，实测会和内容叠字。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MeTopBarSlot(
    gameType: GameType,
    scrollBehavior: ScrollBehavior,
    topAppBarState: TopAppBarState,
    onRefresh: () -> Unit,
) {
    val dfAccount by DivingFishAccount.state.collectAsState()
    val lxns by LxnsAccount.state.collectAsState()
    val avatars by AccountAvatar.state.collectAsState()
    // 自定义头像保存后 StateFlow 立刻推新版本号，顶栏下一帧就换图。
    val customAvatar by CustomAvatar.state.collectAsState()
    val maimaiSource by SpUtil.maimaiSourceFlow.collectAsState()
    val chuniSource by SpUtil.chuniSourceFlow.collectAsState()
    val chuniState by ChuniRecordSync.state.collectAsState()

    val isChuni = gameType == GameType.CHUNITHM
    val source = if (isChuni) chuniSource else maimaiSource

    val nickname = if (isChuni) {
        remember(chuniState) { ChuniRecordSync.getLocalNickname() }
    } else {
        dfAccount.nickname.ifBlank { SpUtil.getDivingFishOAuthNickname() }
    }
    val loggedIn = if (isChuni) {
        if (source == ScoreSource.LXNS) lxns.bound else SpUtil.getDivingFishJwt().isNotBlank()
    } else {
        dfAccount.bound
    }
    val avatarUrl = avatars.forAccount(gameType, source)
    val largeTitle = nickname.ifBlank { "账户" }
    val subtitle = if (loggedIn) "当前登录：${source.shortLabel}查分器" else "当前未登录查分器"

    // 取图优先级：自定义 > 自动抓取 > 默认。自定义命中时不再触发远程解码。
    val customVersion = if (customAvatar.hasImage) customAvatar.version else 0L
    val customBitmap = rememberCustomAvatarBitmap(customVersion)
    val remoteBitmap = rememberAvatarBitmap(if (customBitmap == null) avatarUrl else "")
    val avatarBitmap = customBitmap ?: remoteBitmap

    val onSurface = MiuixTheme.colorScheme.onSurface
    val pageBackground = MiuixTheme.colorScheme.background

    // 展开态高度：与歌曲详情 / 集合页同口径，完整露出正方形背景。
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val minExpansionPx = (screenWidthPx - statusBarPx - collapsedPx).coerceAtLeast(0)

    val liquidSkin = useLiquidBar()
    var liquidSkinCollapsedPx by remember { mutableIntStateOf(0) }
    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }
    val liquidActions = LocalMaterialFamily.current == MaterialFamily.LIQUID &&
        LocalAdvancedMaterialEnabled.current && collapsed

    Box {
        if (liquidSkin) {
            LiquidTopBarSkin(
                backdrop = LocalLiquidBackdrop.current,
                scrollBehavior = scrollBehavior,
                fullRangeProgress = true,
                collapsedHeight = if (liquidSkinCollapsedPx > 0) {
                    with(density) { liquidSkinCollapsedPx.toDp() }
                } else {
                    TopAppBarDefaults.CollapsedHeight
                },
            )
        }
        CompositionLocalProvider(LocalInLiquidTopBar provides liquidActions) {
            GlassTopAppBar(
                title = largeTitle,
                largeTitle = largeTitle,
                subtitle = subtitle,
                smallSubtitle = "",
                titleColor = onSurface,
                largeTitleColor = onSurface,
                subtitleColor = onSurface.copy(alpha = 0.72f),
                backdrop = if (liquidSkin) null else LocalGlassTopAppBarBackdrop.current,
                scrollBehavior = scrollBehavior,
                bandBrush = if (liquidSkin) {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                } else {
                    GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
                },
                navigationIcon = {},
                actions = {
                    MeRefreshAction(onClick = onRefresh, tint = onSurface)
                },
                background = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            DefaultAvatarBackground(nickname = nickname, isChuni = isChuni)
                        }
                        // 底部渐变到系统底色：贴底的系统色标题靠这层保证可读，
                        // 头像下缘也不会和页面内容之间留一条硬边。
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.00f to Color.Transparent,
                                        0.42f to Color.Transparent,
                                        0.74f to pageBackground.copy(alpha = 0.55f),
                                        1.00f to pageBackground,
                                    ),
                                ),
                        )
                    }
                },
                minExpansionPx = minExpansionPx,
                onCollapsedHeightPx = { liquidSkinCollapsedPx = it },
                largeTitleBottomAligned = true,
                largeTitleFontWeight = FontWeight.Bold,
                // 封面页同款：折叠时逐帧变化的 RenderEffect 会在渐隐首帧撞上 shader 编译，
                // 表现为「卡一下再继续」。标题本身有位移 + 淡出，不需要模糊。
                largeTitleBlurRadius = 0.dp,
            )
        }
    }
}

/** 刷新按钮：固定在右侧 actions 槽，不做位置迁移。 */
@Composable
private fun RowScope.MeRefreshAction(onClick: () -> Unit, tint: Color) {
    KibaActionButton(onClick = onClick) {
        Icon(
            imageVector = MiuixGlassIcons.Refresh,
            contentDescription = "刷新成绩",
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
    }
}

/**
 * 无头像时的占位「封面」：与旧版默认头像同一套暖 / 冷配色（舞萌暖橙、中二青蓝），
 * 昵称首字放大居中。铺满展开区而不是贴在右上角。
 */
@Composable
private fun DefaultAvatarBackground(nickname: String, isChuni: Boolean) {
    val initial = nickname.trim().firstOrNull()
        ?.let { if (it.isLetter() && it.code < 128) it.uppercaseChar() else it }
        ?: 'K'
    val tint = if (isChuni) CHUNI_TINT else MAIMAI_TINT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.first, tint.second))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 默认头像的暖色（舞萌）与冷色（中二）双色渐变。 */
private val MAIMAI_TINT = Color(0xFFFF9A48) to Color(0xFFFF3E78)
private val CHUNI_TINT = Color(0xFF4DE2E6) to Color(0xFF7C5CFF)

/**
 * 自定义头像（本地文件）。
 *
 * **同步读**：图是 512² 的 PNG，且 [CustomAvatar.loadBitmap] 内部有进程级内存缓存，
 * 命中时就是取个引用；走异步反而会先闪一下默认头像再换图。
 * `version` 是 remember key —— 保存后顶栏立刻换图，不用切查分器。
 */
@Composable
private fun rememberCustomAvatarBitmap(version: Long): Bitmap? =
    remember(version) { if (version <= 0L) null else CustomAvatar.loadBitmap() }

/**
 * 取自动抓取的头像位图。
 *
 * 落雪给的是 `data:image/png;base64,...`：自己解，不交给 Coil（data URI 在 Coil 各版本
 * 支持程度不一致）；机台抓包给的是官网图片 URL：走 Coil。解码最长边 384px 取样。
 */
@Composable
private fun rememberAvatarBitmap(url: String): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { loadAvatarBitmap(context, url) }.getOrNull() }
        }
    }
    return bitmap
}

private suspend fun loadAvatarBitmap(context: android.content.Context, url: String): Bitmap? {
    if (url.startsWith("data:", ignoreCase = true)) {
        val payload = url.substringAfter(',', "")
        if (payload.isBlank()) return null
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= AVATAR_DECODE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
    return context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build(),
    ).drawable?.let { (it as? BitmapDrawable)?.bitmap }
}

private const val AVATAR_DECODE_PX = 384
