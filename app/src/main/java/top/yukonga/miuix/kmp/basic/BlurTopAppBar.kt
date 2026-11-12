// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.basic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.TopAppBarState.Companion.Saver
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/**
 * 顶栏文案折叠是否与手势**全程同步**。
 *
 * 默认 false = miuix 原生：大标题在折叠前 1/3 就淡完（collapsedFraction × 3），小标题
 * 在同一阈值触发一段 0.3s spring 定时淡入。纯文字顶栏这样很跟手，但展开态还叠了
 * 头像 / 迁移按钮 / 底色的页面会把「一堆动画在前 1/3 跑完、后 2/3 只剩高度机械收缩」
 * 看成「动画先播完、折叠才慢半拍跟上」的假卡顿。
 *
 * 置 true 时大标题 / 小标题的 alpha 与位移全部直接由 collapsedFraction 计算，零时长、
 * 与顶栏收缩同帧同量，且铺满整个折叠行程。默认关闭，只有账户页开启。
 */
val LocalSynchronousTitleFade = staticCompositionLocalOf { false }
/**
 * A [BlurTopAppBar] whose large title blurs as it collapses.
 *
 * @param title The title of the [BlurTopAppBar].
 * @param largeTitleBlurRadius How far the large title blurs out as it collapses. It reaches this
 *   radius at the point the title has fully faded.
 * @param modifier The modifier to be applied to the [BlurTopAppBar].
 * @param color The background color of the [BlurTopAppBar].
 * @param titleColor The color of the collapsed small title text.
 * @param largeTitle The large title of the [BlurTopAppBar].
 * @param largeTitleColor The color of the expanded large title text.
 * @param subtitle The subtitle displayed below the title bar area.
 * @param subtitleColor The color of the subtitle text.
 * @param navigationIcon The content that represents the navigation icon.
 * @param actions The content that represents the action icons.
 * @param scrollBehavior The behavior that controls the [BlurTopAppBar].
 * @param defaultWindowInsetsPadding Whether to apply default window insets padding.
 * @param titlePadding The horizontal padding of the title and large title.
 * @param navigationIconPadding The start padding of the navigation icon.
 * @param actionIconPadding The end padding of the action icons.
 * @param titleAlpha Draw-phase opacity applied to both title containers.
 * @param bottomContent Content displayed below the title bar area.
 * @param onBlankTap Tap callback for the blank area of the bar. The bar swallows every tap it
 *   receives so nothing leaks to the content underneath; children (navigation icon, actions) are
 *   hit first and keep their own gestures, so this only fires on the empty stretch between them.
 *   `null` keeps the original swallow-only behaviour.
 */
@Composable
fun BlurTopAppBar(
    title: String,
    largeTitleBlurRadius: Dp,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    /**
     * 折叠态（小标题下方）那一行副标题，默认跟随 [subtitle]。
     *
     * 传空串即「展开态有副标题、折叠态没有」。折叠行的可见高度只有
     * [TopAppBarDefaults.CollapsedHeight]，副标题会被排在玻璃底板下沿之外：
     * 内容滚上来时直接和它叠字（账户页实测）。需要折叠态干净的页面用空串关掉。
     */
    smallSubtitle: String = subtitle,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    titleAlpha: () -> Float = { 1f },
    bottomContent: @Composable () -> Unit = {},
    onBlankTap: (() -> Unit)? = null,
    /** 展开态最小额外展开高度（ songsDetail 封面背景等场景让顶栏完整露出封面）。 */
    minExpansionPx: Int = 0,
    /** 折叠态总高度回报（px，不含状态栏 insets），详见内层同名参数。 */
    onCollapsedHeightPx: ((Int) -> Unit)? = null,
    /** 展开态把大标题/副标题移到顶栏底部（封面等背景之下），而非紧跟折叠区。 */
    largeTitleBottomAligned: Boolean = false,
    /** 展开态大标题字重。 */
    largeTitleFontWeight: FontWeight = FontWeight.Normal,
    /**
     * 展开态大标题行尾的附加内容（如「舞萌 / 中二」分段开关）。
     *
     * 放在**大标题同一行**、与大标题文字垂直居中对齐：随 largeTitle 的同一个
     * graphicsLayer 淡出 / 上移，滚动折叠时与大标题同频消失，展开时一起回来，
     * 不需要调用方另外维护一套折叠进度。
     */
    largeTitleTrailing: (@Composable () -> Unit)? = null,
) {
    // Wrap the given actions in a Row.
    val actionsRow =
        @Composable {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

    // Compose a Surface with a TopAppBarLayout content.
    // The surface's background color is animated as specified above.
    // The height of the app bar is determined by subtracting the bar's height offset from the
    // app bar's defined constant height value (i.e. the ContainerHeight token).
    TopAppBarLayout(
        title = title,
        titleAlpha = titleAlpha,
        color = color,
        titleColor = titleColor,
        largeTitle = largeTitle,
        largeTitleColor = largeTitleColor,
        largeTitleBlurRadius = largeTitleBlurRadius,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        smallSubtitle = smallSubtitle,
        navigationIcon = navigationIcon,
        actions = actionsRow,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        bottomContent = bottomContent,
        onBlankTap = onBlankTap,
        minExpansionPx = minExpansionPx,
        onCollapsedHeightPx = onCollapsedHeightPx,
        largeTitleBottomAligned = largeTitleBottomAligned,
        largeTitleFontWeight = largeTitleFontWeight,
        largeTitleTrailing = largeTitleTrailing,
    )
}
@Composable
private fun TopAppBarLayout(
    title: String,
    color: Color,
    titleColor: Color,
    largeTitleColor: Color,
    subtitle: String,
    subtitleColor: Color,
    smallSubtitle: String = subtitle,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titlePadding: Dp,
    navigationIconPadding: Dp,
    actionIconPadding: Dp,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    largeTitleBlurRadius: Dp = 0.dp,
    defaultWindowInsetsPadding: Boolean = true,
    titleAlpha: () -> Float = { 1f },
    bottomContent: @Composable () -> Unit = {},
    onBlankTap: (() -> Unit)? = null,
    /**
     * 展开态最小额外展开高度（px 之外再保证的量）。
     * 用于歌曲详情页封面背景：展开高度需要 ≥ 封面完整高度，
     * 仅靠 largeTitle 文字高度不够。折叠完成后仍回到 CollapsedHeight。
     */
    minExpansionPx: Int = 0,
    /**
     * 折叠态总高度回报（px，不含状态栏 insets——那是 windowInsetsPadding 加的）。
     * 折叠态含副标题行时实际高度 > CollapsedHeight，外部玻璃底板（液态皮肤）
     * 需要用这个真实值对齐，否则底边会短一截（停在副标题中间）。
     */
    onCollapsedHeightPx: ((Int) -> Unit)? = null,
    /** 展开态把大标题/副标题块贴到顶栏底部（叠在封面等背景的下部），距底边留 [BottomAlignedTitleGap]。 */
    largeTitleBottomAligned: Boolean = false,
    /** 展开态大标题字重。 */
    largeTitleFontWeight: FontWeight = FontWeight.Normal,
    /** 展开态大标题行尾插槽（与大标题同一行、垂直居中对齐），见公共重载同名参数。 */
    largeTitleTrailing: (@Composable () -> Unit)? = null,
) {
    // Producer lambdas — reads stay in layout/draw phases so scroll never recomposes this subtree.
    val scrolledOffset = remember(scrollBehavior) {
        { scrollBehavior?.state?.heightOffset ?: 0f }
    }
    val currentOnBlankTap = rememberUpdatedState(onBlankTap)
    // The title does not merely fade out — it goes out of focus on the way, the way the source
    // system dissolves it. `progress` is shared by both so the blur peaks exactly as the last of
    // the title goes, instead of stopping short of it or outliving it.
    val synchronousTitleFade = LocalSynchronousTitleFade.current
    val largeTitleFade = remember(scrollBehavior, synchronousTitleFade) {
        {
            val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
            // 原生 ×3：前 1/3 行程就淡完了；同步模式改全程线性，每一帧都能同时看到
            // 顶栏收缩与文字交叉淡出在推进，不再有「动画跑完才轮到折叠」的断层。
            if (synchronousTitleFade) frac.coerceIn(0f, 1f) else (frac * 3f).coerceIn(0f, 1f)
        }
    }
    val largeTitleAlpha = remember(largeTitleFade) {
        {
            1f - largeTitleFade()
        }
    }
    // 展开态贴底标题块与顶栏底边（封面底缘）之间的空白。
    val bottomAlignedGapPx = with(LocalDensity.current) { BottomAlignedTitleGap.roundToPx() }

    // heightOffsetLimit 是 TopAppBarState 的普通字段（非 snapshot state）：顶栏换页重新
    // 测量出新的 limit 时（如歌曲详情 minExpansionPx ↔ 普通页、多级子页互返），仅改这个
    // 字段不会让读 collapsedFraction 的 derivedStateOf 失效。
    // 典型事故：从已折叠的上一级页面进详情页（limit 变得很大）再返回，restoreTopBar 恢复的
    // heightOffset 与当前值相同 → derivedStateOf 不重算 → smallTitleVisible 卡在旧 limit
    // 算出的 false，而大标题 alpha 在 draw phase 按新 limit 实时算已折叠淡出，标题整体消失，
    // 只能等用户手动滑动让 heightOffset 变化才刷新（KibaMainScreen.restoreTopBar 的
    // 两帧补写同样因同值 no-op 而救不回来）。
    // 解法：用一枚计数器把 limit 写入并入 snapshot 依赖，limit 一变即广播失效。
    val heightOffsetLimitVersion = remember(scrollBehavior) { mutableIntStateOf(0) }
    val updateHeightOffsetLimit = remember(scrollBehavior, minExpansionPx) {
        { height: Int ->
            scrollBehavior?.state?.let { state ->
                val limit = -height.toFloat() - minExpansionPx
                if (state.heightOffsetLimit != limit) {
                    state.heightOffsetLimit = limit
                    heightOffsetLimitVersion.intValue++
                }
            }
            Unit
        }
    }

    // Boolean derivedStateOf invalidates only on flip → the spring fires once per crossing, not per frame.
    val smallTitleVisible by remember(scrollBehavior) {
        derivedStateOf {
            // 依赖 limit 写入广播：limit 变化（顶栏换页重新测量）时按新 limit 重算 collapsedFraction。
            heightOffsetLimitVersion.intValue
            scrollBehavior?.state?.let { state ->
                state.collapsedFraction * 3f >= 1f
            } ?: false
        }
    }
    // 原生：阈值触发 + 0.3s spring，动画时长与手指进度无关（快速滑过时它会拖在后面）。
    // 同步：alpha / 位移直接由 collapsedFraction 算，零时长，与顶栏收缩同帧同量。
    val smallTitleAlphaLegacy = remember { Animatable(if (smallTitleVisible) 1f else 0f) }
    val smallTitleTranslationYLegacy = remember { Animatable(if (smallTitleVisible) 0f else 20f) }
    if (!synchronousTitleFade) {
        LaunchedEffect(smallTitleVisible) {
            if (smallTitleVisible) {
                val showSpec = folmeSpring<Float>(damping = 1.0f, response = 0.3f)
                launch { smallTitleAlphaLegacy.animateTo(1f, showSpec) }
                launch { smallTitleTranslationYLegacy.animateTo(0f, showSpec) }
            } else {
                val hideSpec = folmeSpring<Float>(damping = 1.0f, response = 0.15f)
                launch { smallTitleAlphaLegacy.animateTo(0f, hideSpec) }
                launch { smallTitleTranslationYLegacy.animateTo(20f, hideSpec) }
            }
        }
    }
    val smallTitleAlpha: () -> Float = if (synchronousTitleFade) {
        remember(scrollBehavior) {
            {
                val f = scrollBehavior?.state?.collapsedFraction ?: 0f
                // 大标题退到一半再让小标题进来（两行文字不叠），剩下 55% 行程全程淡入。
                ((f - 0.45f) / 0.55f).coerceIn(0f, 1f)
            }
        }
    } else {
        { smallTitleAlphaLegacy.value }
    }
    val smallTitleTranslationY: () -> Float = if (synchronousTitleFade) {
        { 20f * (1f - smallTitleAlpha()) }
    } else {
        { smallTitleTranslationYLegacy.value }
    }

    val animatedTitleColor by animateColorAsState(
        targetValue = titleColor,
        animationSpec = tween(durationMillis = 50),
    )
    val animatedLargeTitleColor by animateColorAsState(
        targetValue = largeTitleColor,
        animationSpec = tween(durationMillis = 50),
    )
    val animatedSubtitleColor by animateColorAsState(
        targetValue = subtitleColor,
        animationSpec = tween(durationMillis = 50),
    )

    Layout(
        {
            Box(
                Modifier
                    .layoutId("navigationIcon")
                    .padding(start = navigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier
                    .layoutId("title")
                    .padding(horizontal = titlePadding)
                    .graphicsLayer {
                        alpha = smallTitleAlpha() * titleAlpha()
                        translationY = smallTitleTranslationY()
                    },
            ) {
                Text(
                    text = title,
                    color = animatedTitleColor,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Box(
                Modifier
                    .layoutId("actionIcons")
                    .padding(end = actionIconPadding),
            ) {
                actions()
            }
            Box(
                Modifier
                    .layoutId("largeTitle")
                    // 贴底模式由 measure 阶段按 barHeight 定位，不再吃折叠区的 top padding。
                    .then(
                        if (!largeTitleBottomAligned) {
                            Modifier.padding(top = TopAppBarDefaults.CollapsedHeight)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = titlePadding)
                    .graphicsLayer {
                        alpha = largeTitleAlpha() * titleAlpha()
                        // Assigned on every path, including the disabled one: the layer keeps
                        // whatever it was last given, so a radius that drops back to zero would
                        // otherwise leave the last blur on the title for good.
                        val radius = if (largeTitleBlurRadius > 0.dp) {
                            largeTitleBlurRadius.toPx() * largeTitleFade()
                        } else {
                            0f
                        }
                        renderEffect = if (radius > 0.1f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            // 贴底模式的位置由 barHeight 收缩带动，再叠加 offset 会双重移动。
                            if (!largeTitleBottomAligned) {
                                Modifier.offset {
                                    val v = scrolledOffset()
                                    IntOffset(0, v.fastRoundToInt())
                                }
                            } else {
                                Modifier
                            }
                        )
                        .onSizeChanged { updateHeightOffsetLimit(it.height) },
                ) {
                    // 大标题行：标题文字 +（可选）行尾插槽。插槽与大标题同一行、垂直居中，
                    // 共用外层 graphicsLayer 的折叠淡出 / 位移，天然与大标题同频。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = largeTitle,
                            color = animatedLargeTitleColor,
                            fontSize = MiuixTheme.textStyles.title1.fontSize,
                            fontWeight = largeTitleFontWeight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = if (largeTitleTrailing != null) {
                                Modifier.weight(1f)
                            } else {
                                Modifier
                            },
                        )
                        if (largeTitleTrailing != null) {
                            Spacer(modifier = Modifier.width(TrailingGap))
                            largeTitleTrailing()
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = animatedSubtitleColor,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            if (smallSubtitle.isNotEmpty()) {
                Box(
                    Modifier
                        .layoutId("smallSubtitle")
                        .graphicsLayer {
                            alpha = smallTitleAlpha() * titleAlpha()
                            translationY = smallTitleTranslationY()
                        },
                ) {
                    Text(
                        text = smallSubtitle,
                        color = animatedSubtitleColor,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.layoutId("bottomContent")) {
                bottomContent()
            }
        },
        modifier = modifier
            .background(color)
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                } else {
                    Modifier
                },
            )
            .clipToBounds()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            // 手势块以 Unit 为 key 只起一次，必须经 remembered state 读回调，
            // 否则捕获的是首帧的 onBlankTap——调用方后续换命令（如展开/收缩切换）不会生效。
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnBlankTap.value?.invoke() })
            },
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .fastFirst { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val actionIconsPlaceable =
            measurables
                .fastFirst { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        // 直接给 title 完整安全区宽度：两侧的 navIcon / actionIcons 已经各自占位，
        // 标题文字在安全区内部自然居中 + ellipsis。之前额外再乘 TITLE_WIDTH_FRACTION=0.9
        // 会让 title 宽度被额外砍 10%，在 actionIcons 很宽时（对话页 3 个玻璃按钮 ≈200dp）
        // 导致 title 永远挤在安全区左侧一小段。
        val titleMaxWidth = maxTitleWidth

        val titlePlaceable =
            measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val largeTitlePlaceable =
            measurables
                .fastFirst { it.layoutId == "largeTitle" }
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )

        // 收缩态副标题只占一行：宽度被两侧控件挤到极限（窄于 SmallSubtitleMinWidth）时
        // 直接不显示——与其留一个纯省略号，不如让位给标题（与收缩态标题的让位逻辑一致）。
        val smallSubtitlePlaceable =
            measurables
                .firstOrNull { it.layoutId == "smallSubtitle" }
                ?.takeIf { titleMaxWidth >= SmallSubtitleMinWidth.roundToPx() }
                ?.measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val bottomContentPlaceable =
            measurables
                .fastFirst { it.layoutId == "bottomContent" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val collapsedHeight = TopAppBarDefaults.CollapsedHeight.roundToPx()
        // largeTitle Box has top padding = collapsedHeight, so subtract it back for the pure expansion.
        // minExpansionPx：封面背景等场景要求展开高度 ≥ 指定量（如完整封面高度）。
        val expansion = maxOf(largeTitlePlaceable.height - collapsedHeight, minExpansionPx)
            .coerceAtLeast(0)
        val barHeight = if (expansion > 0) {
            val offset = scrolledOffset()
            val collapseFraction = if (offset.isNaN()) {
                0f
            } else {
                (abs(offset) / expansion.toFloat()).coerceIn(0f, 1f)
            }
            lerp(
                start = collapsedHeight,
                stop = collapsedHeight + expansion,
                fraction = 1f - collapseFraction,
            )
        } else {
            collapsedHeight
        }

        val verticalCenter = collapsedHeight / 2
        val smallSubtitleHeight = smallSubtitlePlaceable?.height ?: 0
        val smallSubtitleBottom = verticalCenter + titlePlaceable.height / 2 + smallSubtitleHeight
        val expandedBottomPadding = if (smallSubtitlePlaceable != null) {
            TopAppBarDefaults.SubtitleBottomPadding.roundToPx()
        } else {
            TopAppBarDefaults.LargeTitleBottomPadding.roundToPx()
        }
        val contentTop = maxOf(barHeight + expandedBottomPadding, smallSubtitleBottom + expandedBottomPadding)
        val layoutHeight = contentTop + bottomContentPlaceable.height
        // 折叠态总高度（barHeight=collapsedHeight 时的 layoutHeight）：给外部底板对齐用。
        // 每次 measure 都会调（滚动中逐帧），但值只在文本/布局变化时才变，state 去重后无重组抖动
        onCollapsedHeightPx?.invoke(
            maxOf(
                collapsedHeight + expandedBottomPadding,
                verticalCenter + titlePlaceable.height / 2 + smallSubtitleHeight + expandedBottomPadding,
            ) + bottomContentPlaceable.height,
        )

        layout(constraints.maxWidth, layoutHeight) {
            // Navigation icon
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = verticalCenter - navigationIconPlaceable.height / 2,
            )

            // Title
            // baseX 先按屏幕几何中心算，再双向 clamp 到 navIcon / actionIcons 的安全区。
            // 之前是 if/else if 互斥——当 actionIcons 很宽时（对话页 3 个按钮 ≈200dp），
            // title 同时被左右两侧挤，if/else if 只会触发一侧修正，另一侧溢出被"遗忘"。
            var baseX = (constraints.maxWidth - titlePlaceable.width) / 2
            val leftSafe = navigationIconPlaceable.width
            val rightSafe = constraints.maxWidth - actionIconsPlaceable.width - titlePlaceable.width
            baseX = baseX.coerceIn(leftSafe, rightSafe)
            titlePlaceable.placeRelative(
                x = baseX,
                y = verticalCenter - titlePlaceable.height / 2,
            )

            // Small subtitle：居中于 title 的实际中心。title 可能被安全区钳制而偏离屏幕中心，
            // 若按屏幕/安全区中点居中，两侧按钮宽度不同时副标题会与标题错位。
            smallSubtitlePlaceable?.let { sub ->
                val subLeftSafe = navigationIconPlaceable.width
                val subRightSafe = constraints.maxWidth - actionIconsPlaceable.width - sub.width
                val titleCenterX = baseX + titlePlaceable.width / 2
                val subX = (titleCenterX - sub.width / 2).coerceIn(subLeftSafe, subRightSafe)
                sub.placeRelative(
                    x = subX,
                    y = verticalCenter + titlePlaceable.height / 2,
                )
            }

            // Action icons
            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = verticalCenter - actionIconsPlaceable.height / 2,
            )

            // Large title (includes large subtitle in a Column)
            // 贴底模式：展开态吸在顶栏底部（封面下缘之上留一段空白），随 barHeight 收缩上移淡出；
            // 普通模式：固定 y=0，靠 Box 自带的 top padding 定在折叠区下方，Column 内部再随滚动上移。
            largeTitlePlaceable.placeRelative(
                x = 0,
                y = if (largeTitleBottomAligned) {
                    barHeight - largeTitlePlaceable.height - bottomAlignedGapPx
                } else {
                    0
                },
            )

            // Bottom content (pinned, below bar and subtitle)
            bottomContentPlaceable.placeRelative(x = 0, y = contentTop)
        }
    }
}

// 上游私有常量，原与 TopAppBarLayout 同处 TopAppBar.kt，vendor 拆文件后需一并带走。
private const val TITLE_WIDTH_FRACTION = 0.9

// 贴底标题块（largeTitleBottomAligned）与顶栏底边之间的空白。
private val BottomAlignedTitleGap: Dp = 16.dp

// 大标题文字与行尾插槽（分段开关等）之间的间距。
private val TrailingGap: Dp = 12.dp

// 收缩态副标题的可见下限：安全区窄于此宽度直接不显示（只留一个省略号没有意义）。
private val SmallSubtitleMinWidth: Dp = 96.dp
