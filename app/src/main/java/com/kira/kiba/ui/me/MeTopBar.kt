package com.kira.kiba.ui.me

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.core.account.AccountAvatar
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.navigation.KibaActionButton
import com.kira.kiba.ui.navigation.KibaTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 「账户」页顶栏（3.3.0）。
 *
 * 与其余页面**共用同一个**顶栏实例与折叠机制（[KibaTopBar] → miuix `GlassTopAppBar`），
 * 只是换了内容、加了几个只属于本页的装饰：
 *
 * | | 展开态 | 折叠态 |
 * |---|---|---|
 * | 小标题 | 隐藏（miuix 自己的 alpha 管） | 「账户」 |
 * | 大标题 | 账户昵称（没昵称才退回「账户」） | 隐藏 |
 * | 副标题 | 当前登录的查分器 | 同一行继续显示（miuix 的小副标题槽） |
 * | 右上角头像 | 显示，向左 / 向下渐隐融回顶栏底色 | 收缩 + 淡出 |
 * | 刷新按钮 | 停在左侧 | 滑到右侧 |
 * | 高度 | 比别的页多一段顶部留白（`minExpansionPx`） | 收窄成常规 56dp 一行 |
 *
 * ## 为什么头像挂在顶栏之外
 *
 * 头像比顶栏一行更高、要溢出右上角、还要带羽化蒙版；塞进 `actions` 行会被行高与裁切牵着走。
 * 它画在 [KibaTopBar] **之上**的一层 `Box` 里（`align(TopEnd)`），尺寸与位置自己说了算，
 * `actions` 槽则留给刷新按钮 —— 两者在折叠时的行为完全不同，混在一个槽里必然打架。
 *
 * ## 折叠进度为什么要自己算
 *
 * 大标题的折叠由 miuix 内部用同一个 `heightOffset` 驱动，取 `heightOffset / heightOffsetLimit`
 * 才能**跟手**：手指拖多少，头像就淡多少、按钮就滑多少，停在半途也是半途状态。
 * 不用 `contentOffset / RampDistance`：那只覆盖最前面 32dp，再往后拖头像就不动了，
 * 看起来像动画卡住。
 *
 * `heightOffsetLimit` 不是 snapshot state，但 [derivedStateOf] 每次随 `heightOffset` 重算都会
 * 读到它的当前值，不会像缓存那样卡在旧 limit 上（顶栏换页时 limit 会变）。
 *
 * ## 性能
 *
 * 这个 composable 处在 `glass()` 子树的**上游**，滚动时每帧都会重算折叠进度，所以进度一律以
 * `State<Float>` 形式往下传，只在 `offset { }` / `graphicsLayer { }` 这两个**布局 / 绘制阶段**
 * 读值 —— 组合阶段一次都不读，滚动过程中不触发任何重组（项目里已踩过同类坑）。
 */
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
    val maimaiSource by SpUtil.maimaiSourceFlow.collectAsState()
    val chuniSource by SpUtil.chuniSourceFlow.collectAsState()
    // 中二没有 account 层，昵称与就绪状态都存在 ChuniRecordSync 的本地缓存里；
    // 订阅它的状态，同步完成后顶栏标题会跟着更新，而不是停在进页时那一帧。
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
    // 头像按「游戏 × 数据源」取：舞萌与中二是两套玩家，同一份落雪凭据下也是两张不同的图。
    val avatarUrl = avatars.forAccount(gameType, source)

    // 大标题：昵称；未登录 / 还没同步出昵称时退回页面名，别留一行空白。
    val largeTitle = nickname.ifBlank { "账户" }
    val subtitle = if (loggedIn) "当前登录：${source.shortLabel}查分器" else "当前未登录查分器"

    val collapse = remember(topAppBarState) {
        derivedStateOf {
            val limit = topAppBarState.heightOffsetLimit
            if (limit >= 0f) 0f else (topAppBarState.heightOffset / limit).coerceIn(0f, 1f)
        }
    }
    // 「头像已经淡没了」用布尔量表达：只在跨过阈值时重组一次，避免每帧读进度。
    val avatarGone = remember(collapse) { derivedStateOf { collapse.value >= AVATAR_FADE_END } }

    val density = LocalDensity.current
    val extraPx = with(density) { ME_EXTRA_EXPANSION.roundToPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val barWidth = maxWidth
        KibaTopBar(
            // 折叠后的那一行恒为「账户」：miuix 内部用 alpha 做大标题↔小标题的交叉淡出，
            // 这里换值会变成硬切；昵称只出现在大标题上。
            title = "账户",
            largeTitle = largeTitle,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            // 只在本页撑高：头像与副标题都需要呼吸空间，其余页面的顶栏高度一点不变。
            minExpansionPx = extraPx,
            actions = {
                // 刷新按钮：展开态在左、折叠态在右 —— 用 offset 一路平移过去。
                // 两颗按钮交叉淡入淡出看起来像「跳」，位移才是用户说的「迁移」。
                MeRefreshAction(
                    collapse = collapse,
                    travel = barWidth - REFRESH_TRAVEL_INSET,
                    onClick = onRefresh,
                )
            },
        )
        if (!avatarGone.value) {
            MeTopBarAvatar(
                url = avatarUrl,
                nickname = nickname,
                collapse = collapse,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** 头像直径。比折叠后的 56dp 标题栏略大，贴右上角时有一点「溢出感」。 */
private val AVATAR_SIZE = 72.dp

/** 头像淡到看不见时的折叠进度：再往后收就只剩一个半透明的影子挂在角上。 */
private const val AVATAR_FADE_END = 0.58f

/**
 * 账户页展开态比常规顶栏多出来的高度。
 *
 * 传的是 `minExpansionPx`（展开高度的**下限**），不是「额外叠加量」：miuix 取
 * `max(大标题自然高度, minExpansionPx)`。昵称 + 副标题两行约 60dp，这里给到 72dp，
 * 多出来的约 12dp 就是副标题与首张卡片之间那段落白量（再加上顶栏自身 16dp 底距）。
 *
 * 只给到 72dp 而不是更大：再高就让「副标题 → 首卡」之间空成一片，反而看不出是同一个头部。
 */
private val ME_EXTRA_EXPANSION = 72.dp

/** 刷新按钮在两个位置之间的行程余量：右侧圆钮中心距顶栏右缘 40dp，左侧同理。 */
private val REFRESH_TRAVEL_INSET = 80.dp

/**
 * 顶栏上的刷新按钮。
 *
 * 位置由折叠进度驱动：`0` → 停在左侧，`1` → 回到 `actions` 槽的自然位置（右侧）。
 * 用 `Modifier.offset { }` 而不是 `graphicsLayer.translationX`：前者走布局阶段，
 * 命中区跟着一起走；后者只挪绘制，按钮会「看得见点不着」。
 *
 * @param travel 从右侧自然位到左侧目标位的水平距离（含按钮自身宽度与两侧内边距）
 */
@Composable
private fun MeRefreshAction(
    collapse: State<Float>,
    travel: Dp,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val travelPx = with(density) { travel.toPx() }
    Box(
        modifier = Modifier.offset {
            // 前 12% 的折叠先让头像退场，按钮再起步 —— 两者不在同一片区域抢注意力。
            val t = ((collapse.value - 0.12f) / 0.88f).coerceIn(0f, 1f)
            IntOffset(x = (-travelPx * (1f - t)).roundToInt(), y = 0)
        },
    ) {
        KibaActionButton(onClick = onClick) {
            Icon(
                imageVector = MiuixGlassIcons.Regular.Refresh,
                contentDescription = "刷新成绩",
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 顶栏右上角的账户头像。
 *
 * 三层叠出来的「融进顶栏」：
 * 1. 头像本体（圆形裁切）；
 * 2. 一张从**右上角到左下角**的线性蒙版（DstIn）：右上满不透明，往左下越来越透 ——
 *    这就是「右上最清楚，向左侧和下侧渐变回原本顶栏颜色」。注意方向是斜的而不是纯横 / 纯竖：
 *    顶栏底色在展开态本来就透明，往左下淡出等于让底下的页面色接管，比再糊一层 surface
 *    更稳（滚动到内容下面时也不会突兀）。
 * 3. 不做外圈描边 / 内阴影：任何一圈硬边都会让人看成「贴了个圆片」，而不是画面的一部分。
 *
 * 折叠时整块向右上收缩并淡出；[collapse] 直接来自滚动量，所以是跟手的（只在绘制阶段读）。
 */
@Composable
private fun MeTopBarAvatar(
    url: String,
    nickname: String,
    collapse: State<Float>,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            // 往右偏一点：像从屏幕角上「长出来」，而不是规规矩矩摆在安全区里。
            // 纵向从状态栏下沿起步 —— 压到状态栏会把 WiFi / 电量图标糊住，那是系统的地方。
            .offset(x = 10.dp, y = statusBarTop + 2.dp)
            .size(AVATAR_SIZE)
            .graphicsLayer {
                val c = collapse.value.coerceIn(0f, AVATAR_FADE_END)
                alpha = (1f - c / AVATAR_FADE_END).coerceIn(0f, 1f)
                // 以右上角为锚点收缩：视觉上是「缩回角落」，而不是整体飘走
                transformOrigin = TransformOrigin(1f, 0f)
                val s = 1f - 0.16f * (c / AVATAR_FADE_END)
                scaleX = s
                scaleY = s
            },
    ) {
        AvatarPicture(
            url = url,
            nickname = nickname,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 头像本体：有图走 Bitmap，没图（或图炸了）走 [DefaultAvatar]。 */
@Composable
private fun AvatarPicture(url: String, nickname: String, modifier: Modifier = Modifier) {
    val bitmap = rememberAvatarBitmap(url)
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 右上一角满不透明 → 左下化进底色的斜向渐隐
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White,
                            0.30f to Color.White,
                            0.75f to Color.White.copy(alpha = 0.55f),
                            1.00f to Color.Transparent,
                        ),
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "账户头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape),
            )
        } else {
            DefaultAvatar(nickname = nickname, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * 默认头像：主题色渐变圆 + 昵称首字。
 *
 * 没有头像时不能留一个灰洞 —— 账户页顶栏右上角空着，看起来像整页没加载完。
 * 取昵称首字符（中文取第一个字，英文取首个字母并转大写），没有昵称就用「K」。
 */
@Composable
private fun DefaultAvatar(nickname: String, modifier: Modifier = Modifier) {
    val initial = nickname.trim().firstOrNull()
        ?.let { if (it.isLetter() && it.code < 128) it.uppercaseChar() else it }
        ?: 'K'
    val primary = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.92f), primary.copy(alpha = 0.52f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 取头像位图。
 *
 * 两种来源两条路：
 * - 落雪给的是 `data:image/png;base64,...`：**自己解**，不交给 Coil —— data URI 在 Coil 各版本里
 *   的支持程度不一致，解失败只会得到一张裂图，而这里解失败退默认头像，行为确定；
 * - 机台抓包给的是官网图片 URL：走 Coil（与全 App 共享磁盘缓存）。
 *
 * 解码按最长边 256px 取样：头像槽只有 76dp，原图动辄 512px，没必要吃满内存。
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
            .allowHardware(false) // 要被 DstIn 蒙版处理，必须走软件位图
            .build(),
    ).drawable?.let { (it as? BitmapDrawable)?.bitmap }
}

private const val AVATAR_DECODE_PX = 256
