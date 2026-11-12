package com.kira.kiba.ui.me

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import com.kira.kiba.core.account.CustomAvatar
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
 * | 小标题 | 隐藏（miuix 自己的 alpha 管） | 账户昵称（与展开态同名，只是字号变小） |
 * | 大标题 | 账户昵称（没昵称才退回「账户」） | 隐藏 |
 * | 副标题 | 当前登录的查分器 | 隐藏（折叠行只有 56dp，装不下第二行） |
 * | 右上角头像 | 方形大图，从状态栏上沿起画，左 / 下两侧渐隐到没有边 | 缩小 + 淡出 |
 * | 刷新按钮 | 停在左侧 | 滑到右侧（中途整颗淡出，落地前才重新淡入） |
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
    // 用户自定义头像（全局一份）：保存后 StateFlow 立刻推新版本号，顶栏下一帧就换图，
    // 不需要切查分器、也不需要重启 Activity。
    val customAvatar by CustomAvatar.state.collectAsState()
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

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val barWidth = maxWidth
        // 折叠态的下沿填充（见 [CollapsedBottomFill]）：必须画在顶栏**之下**、内容**之上**，
        // 所以放在 KibaTopBar 之前。
        CollapsedBottomFill(collapse = collapse)
        KibaTopBar(
            // 折叠后的那一行仍旧是昵称：前后同名，滚上去只是「大字缩成小字」，
            // 不会有半路换标题的跳感（miuix 用 alpha 做大标题↔小标题的交叉淡出，换值即硬切）。
            title = largeTitle,
            largeTitle = largeTitle,
            subtitle = subtitle,
            // 折叠态不显示副标题：56dp 的折叠行装不下它，它会排到玻璃底板下沿之外，
            // 内容滚上来时和它叠成一团（真机实测，材质开着也一样）。
            collapsedSubtitle = "",
            scrollBehavior = scrollBehavior,
            // 大标题行尾留出头像位：昵称过长时在头像左边就截断，别让文字钻进被图片
            // 压住的那一块（图片右上方是实的，字压上去会变脏）。折叠态标题走 miuix
            // 自己的居中排版，不受这个插槽影响。
            titleTrailing = { Spacer(Modifier.width(AVATAR_TITLE_INSET)) },
            actions = {
                // 刷新按钮：展开态在左、折叠态在右 —— 边右移边淡出，快落地时再淡回来。
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
                customVersion = if (customAvatar.hasImage) customAvatar.version else 0L,
                nickname = nickname,
                isChuni = isChuni,
                collapse = collapse,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * 折叠态顶栏下沿的填充带。
 *
 * ## 为什么需要它
 *
 * miuix 的玻璃底板在竖直方向是一条**淡出**的渐变（0.55 之后开始掉、底边归零），这是为了
 * 让玻璃和内容之间没有硬边。曲目 / 畅听这些页面底下是彩色封面，淡出带看起来就是「玻璃变薄」；
 * 但账户页底下是白底灰字的设置列表，淡出带里那几行字会被半透明地透出来，正好和折叠行的
 * 小标题挤在一起，读起来像「字叠字」。
 *
 * 所以这一页自己在顶栏下沿垫一层页面底色：从顶栏顶一路铺到折叠行下沿再多 [COLLAPSED_FILL_TAIL]，
 * 最后一小段再淡出。折叠进度为 0 时整体透明（展开态顶栏本来就是纯底色，不需要它）。
 *
 * 画在 [KibaTopBar] **之前**：玻璃底板与标题都在它上面，它只负责把下沿垫实。
 * alpha 只在 `graphicsLayer` 里读折叠进度，滚动时不触发重组。
 */
@Composable
private fun BoxScope.CollapsedBottomFill(collapse: State<Float>) {
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val fillHeight = with(density) { statusBarTop.toDp() } + COLLAPSED_ROW + COLLAPSED_FILL_TAIL
    val bg = MiuixTheme.colorScheme.background
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(fillHeight)
            // 折叠到 20% 就完全实了：再往后内容已经滚到顶栏底下，晚一点就露字。
            .graphicsLayer { alpha = (collapse.value * 5f).coerceIn(0f, 1f) }
            .background(
                Brush.verticalGradient(
                    0.00f to bg,
                    0.74f to bg,
                    0.90f to bg.copy(alpha = 0.55f),
                    1.00f to Color.Transparent,
                ),
            ),
    )
}

/** miuix 折叠行高度（与 `TopAppBarDefaults.CollapsedHeight` 一致）。 */
private val COLLAPSED_ROW = 56.dp

/** 折叠态下沿填充带在折叠行之外多铺的一段，供最后 26% 的淡出用。 */
private val COLLAPSED_FILL_TAIL = 18.dp

/**
 * 头像边长：从状态栏上沿一路盖到副标题一带，是全页最大的一块装饰。
 *
 * 尺寸上限由**展开态顶栏高度**约束（> 这个值的部分会被下面的内容盖住，出现一条硬边）：
 * 顶栏 = 状态栏 + 56dp 折叠行 + 大标题块（约 62dp）+ 底距，合计 150dp 上下。
 * 168dp 的图靠「下侧很早就开始羽化」+ 折叠时同步缩到 76% 才站得住：
 * 视觉上真正还能看清内容的只有上面 3/4（约 126dp），底部那 1/4 已经化进页面底色了。
 */
private val AVATAR_SIZE = 168.dp

/**
 * 头像向右溢出的量：右竖边落到屏幕外，配合左 / 下两侧的渐隐，图片的四条边一条都不露。
 * （上边贴着屏幕顶，也不需要额外处理。）
 */
private val AVATAR_BLEED_RIGHT = 16.dp

/**
 * 大标题行尾给头像让出的宽度。
 *
 * 头像虽然整体是渐隐的，但右上那一块接近不透明；昵称过长时会一直排到屏幕右缘，
 * 末几个字正好落在图片上，看起来像被弄脏了。让出 108dp 后文字在自己的安全区里省略，
 * 视觉上「标题止于头像之前」。
 */
private val AVATAR_TITLE_INSET = 108.dp

/**
 * 头像收完时的折叠进度。
 *
 * 不只是「淡出看着舒服」：顶栏的可见高度随折叠线性缩，头像底部一旦越过顶栏下沿，
 * 就会被下面的卡片硬切掉一条。168dp 的图按 36% 收起（同时缩到 91%）算，
 * 收完时缩后高约 153dp、肉眼还能分辨内容的部分到 78% 处约 119dp，
 * 而顶栏此时还有 128dp 上下，留得住。再拖晚就会被切。
 */
private const val AVATAR_FADE_END = 0.36f

/** 刷新按钮在两个位置之间的行程余量：右侧圆钮中心距顶栏右缘 40dp，左侧同理。 */
private val REFRESH_TRAVEL_INSET = 80.dp

/**
 * 刷新按钮的迁移曲线（入参都是 0..1 的归一位移 t）：
 *
 * ```
 * alpha 1 ┤╮
 *         │ ╰╮             ← 边右移边淡出（前 36% 行程）
 *       0 ┤   ╰──────────╮  ← 中间一大段完全看不见（不在两个落点上抢注意力）
 *         │             ╰─╮ ← 落地前 38% 行程重新淡入
 *       1 ┤               ╰
 *         └──┬──────┬──────┬──▶ t
 *            0.36   0.62   1
 * ```
 *
 * 之前是「一路平移 + 全程可见」，横穿整条标题会跟大标题抢视线；淡出再淡入之后，
 * 视觉上只有一个「从左边消失、在右边出现」的迁移，而不是一颗按钮横穿屏幕。
 */
private const val REFRESH_FADE_OUT_END = 0.36f
private const val REFRESH_FADE_IN_START = 0.62f

private fun easeInOut(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * 顶栏上的刷新按钮。
 *
 * 位置由折叠进度驱动：`0` → 停在左侧，`1` → 回到 `actions` 槽的自然位置（右侧）。
 * 用 `Modifier.offset { }` 而不是 `graphicsLayer.translationX`：前者走布局阶段，
 * 命中区跟着一起走；后者只挪绘制，按钮会「看得见点不着」。
 * 透明度另走 `graphicsLayer`（绘制阶段），两者都只读 [collapse]，滚动时不触发重组。
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
        modifier = Modifier
            .offset {
                // 前 12% 的折叠先让头像退场，按钮再起步 —— 两者不在同一片区域抢注意力。
                val t = refreshTravelFraction(collapse.value)
                IntOffset(x = (-travelPx * (1f - t)).roundToInt(), y = 0)
            }
            .graphicsLayer { alpha = refreshAlpha(collapse.value) },
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

/** 归一位移：0 = 左侧起点（展开态），1 = 右侧落点（折叠态）。 */
private fun refreshTravelFraction(collapse: Float): Float =
    ((collapse - 0.12f) / 0.88f).coerceIn(0f, 1f)

/** 与 [refreshTravelFraction] 同步的透明度：先淡出、中间全隐、落地前淡回。 */
private fun refreshAlpha(collapse: Float): Float {
    val t = refreshTravelFraction(collapse)
    return when {
        t <= 0f -> 1f
        t < REFRESH_FADE_OUT_END -> 1f - easeInOut(t / REFRESH_FADE_OUT_END)
        t < REFRESH_FADE_IN_START -> 0f
        else -> easeInOut((t - REFRESH_FADE_IN_START) / (1f - REFRESH_FADE_IN_START))
    }
}

/**
 * 顶栏右上角的账户头像 —— **方形**，左上角对齐屏幕顶（状态栏上沿）。
 *
 * 位置与形状：
 * - 纵向从 `y = 0` 开始画，也就是压在状态栏那一层（系统图标由 SystemUI 画在我们之上，
 *   不会被盖掉，只是底色变复杂 —— 所以图片最「实」的一角留给右上，那里本来就只有一条边）；
 * - 横向往右溢出 [AVATAR_BLEED_RIGHT]：右竖边落到屏幕外，看不见；
 * - 不做圆角、不描边、不裁圆：一条弧线或一条直边都会让人看出「贴了一块图」。
 *
 * 四条边全部不露的做法是「三条边交给屏幕边界，剩下两条交给蒙版」：
 * 左边与下边各一张 DstIn 线性渐变，两次相乘 —— 左边从 26% 处开始化、下边从 34% 处开始化，
 * 到各自边缘时 alpha 已经精确归零，所以既没有硬边也没有半透明的「残影框」。
 *
 * 折叠时整块以右上角为锚点缩小并淡出；[collapse] 直接来自滚动量，跟手（只在绘制阶段读）。
 */
@Composable
private fun MeTopBarAvatar(
    url: String,
    customVersion: Long,
    nickname: String,
    isChuni: Boolean,
    collapse: State<Float>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .offset(x = AVATAR_BLEED_RIGHT, y = 0.dp)
            .size(AVATAR_SIZE)
            .graphicsLayer {
                val c = (collapse.value / AVATAR_FADE_END).coerceIn(0f, 1f)
                alpha = 1f - c
                // 以右上角为锚点收缩：视觉上是「缩回屏幕角」，而不是整体飘走
                transformOrigin = TransformOrigin(1f, 0f)
                // 收缩比例给到 0.24：图本身变大到 168dp 后，光靠淡出已经赶不上顶栏缩高的速度，
                // 得让它同时变小，才不会被下方卡片硬切出一条直边。
                val s = 1f - 0.24f * c
                scaleX = s
                scaleY = s
            },
    ) {
        AvatarPicture(
            url = url,
            customVersion = customVersion,
            nickname = nickname,
            isChuni = isChuni,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * 头像本体，取图优先级：**自定义 > 自动抓取 > 默认**。
 *
 * 自动抓取那条（机台抓包 / 落雪 icon）常常抓不到（水鱼中二根本没有头像接口），
 * 所以它只是第二顺位；用户自己在设置页裁的图永远优先。
 * `customVersion` 非 0 时不再去请求 [url] —— 传空串让 [rememberAvatarBitmap] 直接返回 null，
 * 免得白白走一次 Coil 网络请求。
 */
@Composable
private fun AvatarPicture(
    url: String,
    customVersion: Long,
    nickname: String,
    isChuni: Boolean,
    modifier: Modifier = Modifier,
) {
    val custom = rememberCustomAvatarBitmap(customVersion)
    val bitmap = custom ?: rememberAvatarBitmap(if (custom == null) url else "")
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // ① 右 → 左：越靠左越透。
                //
                // 曲线 = **平台 + 余弦收尾**：前 34% 保持全实（右上那一块是主体，
                // 淡太早整张图会糊成一层白雾），之后 alpha = 0.5·(1+cos)，在平台边缘
                // 和左边缘两处的斜率都是 0 —— 既没有拐点留下的「分界线」，也没有
                // 「到边才归零」的硬切。分段直线两头都有折角，靠得越近越像方框边。
                //
                // 注意：右端 16dp 溢到屏幕外，所以「全实区」里有一截是看不见的，
                // 平台不能压太窄，否则屏幕上几乎找不到不透明的像素。
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White,
                            0.10f to Color.White,
                            0.20f to Color.White,
                            0.34f to Color.White,
                            0.44f to Color.White.copy(alpha = 0.94f),
                            0.54f to Color.White.copy(alpha = 0.79f),
                            0.64f to Color.White.copy(alpha = 0.57f),
                            0.74f to Color.White.copy(alpha = 0.34f),
                            0.84f to Color.White.copy(alpha = 0.14f),
                            0.92f to Color.White.copy(alpha = 0.04f),
                            1.00f to Color.Transparent,
                        ),
                        start = Offset(size.width, 0f),
                        end = Offset(0f, 0f),
                    ),
                    blendMode = BlendMode.DstIn,
                )
                // ② 上 → 下：越靠下越透，同一条「平台 + 余弦」曲线（平台略窄一点，
                // 因为上边贴着屏幕顶、没有溢出的部分，可见面积本来就更少）。
                // 与①相乘后，左下角彻底化进页面底色。
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White,
                            0.10f to Color.White,
                            0.20f to Color.White,
                            0.26f to Color.White,
                            0.36f to Color.White.copy(alpha = 0.96f),
                            0.46f to Color.White.copy(alpha = 0.83f),
                            0.56f to Color.White.copy(alpha = 0.65f),
                            0.66f to Color.White.copy(alpha = 0.44f),
                            0.76f to Color.White.copy(alpha = 0.24f),
                            0.86f to Color.White.copy(alpha = 0.09f),
                            0.94f to Color.White.copy(alpha = 0.02f),
                            1.00f to Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "账户头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            DefaultAvatar(
                nickname = nickname,
                isChuni = isChuni,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * 默认头像：方形色块 + 昵称首字，字摆在右上（唯一不被蒙版削掉的那一块）。
 *
 * 没有头像时不能留一个灰洞 —— 账户页顶栏右上角空着，看起来像整页没加载完。
 * 形状跟真头像一致（方形、贴角、左下渐隐由外层蒙版统一处理），只是没有照片内容。
 * 取昵称首字符（中文取第一个字，英文取首个字母并转大写），没有昵称就用「K」。
 *
 * 底色按**游戏**分暖 / 冷两套（与分享图的曲目色一致）：舞萌暖橙、中二青蓝。
 * 中二 + 水鱼永远没有真实头像，如果默认头像两游戏长得一模一样，切游戏时右上角
 * 毫无变化，用户会以为「头像没跟着游戏走」。
 */
@Composable
private fun DefaultAvatar(nickname: String, isChuni: Boolean, modifier: Modifier = Modifier) {
    val initial = nickname.trim().firstOrNull()
        ?.let { if (it.isLetter() && it.code < 128) it.uppercaseChar() else it }
        ?: 'K'
    val tint = if (isChuni) CHUNI_TINT else MAIMAI_TINT
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(tint.first.copy(alpha = 0.96f), tint.second.copy(alpha = 0.62f)),
            ),
        ),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontSize = (AVATAR_SIZE.value * 0.30f).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 34.dp, end = 26.dp),
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
 * 命中时就是取个引用；没命中时解一次约 5~10ms，只在进页面 / 换头像那一帧发生。
 * 走异步反而会让顶栏先闪一下默认头像再换图，比这点开销难看得多。
 *
 * `version` 是 key：设置页保存后版本号 +1，这里立刻重算 —— 这就是「不用切查分器
 * 也能马上看到新头像」的实现点。
 */
@Composable
private fun rememberCustomAvatarBitmap(version: Long): Bitmap? =
    remember(version) { if (version <= 0L) null else CustomAvatar.loadBitmap() }

/**
 * 取头像位图。
 *
 * 两种来源两条路：
 * - 落雪给的是 `data:image/png;base64,...`：**自己解**，不交给 Coil —— data URI 在 Coil 各版本里
 *   的支持程度不一致，解失败只会得到一张裂图，而这里解失败退默认头像，行为确定；
 * - 机台抓包给的是官网图片 URL：走 Coil（与全 App 共享磁盘缓存）。
 *
 * 解码按最长边 384px 取样：头像槽 132dp，3x 屏也就 396px，原图动辄 512px 以上，
 * 没必要吃满内存；再小就会在这种大尺寸下看出糊。
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

private const val AVATAR_DECODE_PX = 384
