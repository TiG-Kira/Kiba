// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.material.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.UnevenRoundedRectangle
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏单行高度：大标题、标题、按钮**全在这一行里**，折叠前后高度不变。 */
val LiquidTopBarPillHeight: Dp = 56.dp

/**
 * 折叠所需的滚动距离。
 *
 * 顶栏高度不变，所以这里只是给 miuix 的 nested-scroll 连接一个「可收起量」——
 * 连接按 `heightOffsetLimit` 分配手势，登记 0 会让它认为无处可收而把滚动全部吃掉。
 * 滚过这段距离后大标题完全换成小标题、玻璃完全成形。
 */
private val LiquidTopBarCollapseDistance: Dp = 36.dp

/** 顶栏左右内缩（内容留白用；玻璃块本身是通栏的，不内缩）。 */
val LiquidTopBarHorizontalInset: Dp = 12.dp

/** 玻璃块底部圆角：顶部贴屏幕边不圆角，底部 28dp 收口像一块垂下来的玻璃。 */
private val LiquidTopBarGlassRadius: Dp = 28.dp

/**
 * 顶栏动作按钮的**外**间距（每个按钮左右各占这么多）。
 *
 * 必须与 miuix `GlassTopAppBarDefaults.ButtonPadding`（6dp，相邻按钮合计 12dp）保持一致：
 * 歌曲详情 / 播放页在展开态用 miuix 按钮、折叠后换成液态按钮，两者占位不一致会出现
 * 「同一组按钮平滑一下间距就变了」的跳变（用户实测指出）。按钮本体仍是 44dp 圆钮，
 * 这里只撑开占位，圆钮不变形。
 */
private val LiquidActionButtonPadding: Dp = 6.dp

/** 顶栏高度（不含状态栏）。折叠前后一致。 */
val LiquidTopBarCollapsedHeight: Dp
    get() = LiquidTopBarPillHeight

/**
 * 液态玻璃版顶栏（Kyant0/AndroidLiquidGlass 独立实现）。
 *
 * 形体（用户三轮口径收敛后的最终版）：
 * - **单行**：大标题 / 标题、动作按钮全在同一行里，顶栏高度恒为 [LiquidTopBarPillHeight]。
 *   之前"大标题一行 + 工具栏一行"会让没有按钮的页面（听 / Agent / 我）也白占两行高，
 *   顶上多出一大条空白，很难看——现在无论有没有按钮都是同一行高度。
 * - **置顶态不画玻璃**：大标题直接压在页面内容上方，右侧只有玻璃圆钮；
 * - 滚动后一整块玻璃从屏幕顶边淡入，**同时包住状态栏 + 顶栏**，底部 28dp 圆角收口
 *   （不是浮动胶囊——浮动胶囊在展开态会变成一条空荡荡的横条）。
 * - 折叠的视觉表现只剩「大标题缩小淡出 ↔ 小标题淡入」，高度不变，所以滚动不会引起
 *   列表重新布局，手感比收缩式顶栏更稳。
 *
 * 材质：`vibrancy + blur(8dp) + lens(16dp, 18dp, depthEffect)`；玻璃块通栏贴边，所以不加
 * 边缘高光和投影（那两个是浮动胶囊的语法，贴边玻璃加了会在屏幕边糊出一条亮线）。
 *
 * ## 为什么还要碰 `heightOffsetLimit`
 *
 * 页面把 `scrollBehavior.nestedScrollConnection` 挂在 LazyColumn 上，miuix 的连接按
 * `state.heightOffsetLimit` 判断「顶栏还能收起多少」。这个字段**只有顶栏自己会登记**
 * （`BlurTopAppBar` 在测量阶段写入）。自建顶栏如果忘了写，连接会认为无处可收 →
 * 把上滑手势全部吃掉 → 页面**完全划不动**（AVD 实证：整屏冻结，只有点击有效）。
 * 这里登记 [LiquidTopBarCollapseDistance]，只借用它换取折叠进度，不拿它算高度。
 *
 * @param backdrop 液态取景层；null 时落成实色背景（无着色器/材质关闭的兜底）
 * @param scrollBehavior 驱动折叠的滚动行为；null 视为已完全收窄
 */
@Composable
fun LiquidTopAppBar(
    title: String,
    largeTitle: String,
    backdrop: Backdrop?,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val collapsePx = with(density) { LiquidTopBarCollapseDistance.toPx() }
    val state = scrollBehavior?.state

    // 登记可收起量：不写这一步，nested-scroll 连接会把滚动全部吞掉（页面划不动）
    SideEffect {
        if (state != null && state.heightOffsetLimit != -collapsePx) {
            state.heightOffsetLimit = -collapsePx
        }
    }

    // 折叠进度 0..1；在 draw 阶段读取，滚动时不会重组顶栏子树
    val collapse: () -> Float = remember(scrollBehavior) {
        if (scrollBehavior == null) {
            ({ 1f })
        } else {
            ({ scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f) })
        }
    }

    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val glassColor = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.55f) else Color(0xFFFAFAFA).copy(alpha = 0.62f)
    }
    val glassShape: () -> Shape = remember {
        {
            UnevenRoundedRectangle(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomEnd = LiquidTopBarGlassRadius,
                bottomStart = LiquidTopBarGlassRadius,
            )
        }
    }
    val glassEffects: BackdropEffectScope.() -> Unit = remember { { liquidBarEffects() } }
    // 贴边玻璃只有底边露在屏幕内：给它一道极淡的落影，玻璃块的边界才看得出来
    // （不加的话亮色主题下玻璃和背景几乎融成一片，看不出「一整块」）。
    // 高光仍不加——顶部/左右都贴着屏幕边，加了会在边缘糊出一条亮线。
    val glassShadow = remember { { Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.07f)) } }
    val glassSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(glassColor) { { drawRect(glassColor) } }

    Box(modifier = modifier.fillMaxWidth()) {
        // ── 玻璃块：matchParentSize 自动等于「状态栏 + 单行顶栏」的总高 ──
        // alpha 由折叠进度驱动：置顶时完全透明（不画玻璃），滚起来后迅速补满。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    // 折叠到 1/3 时玻璃已经完全成形，避免半透明玻璃糊在标题后面
                    alpha = (collapse() * 3f).coerceIn(0f, 1f)
                }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = glassShape,
                            effects = glassEffects,
                            highlight = null,
                            shadow = glassShadow,
                            onDrawSurface = glassSurface,
                        )
                    } else {
                        Modifier.background(MiuixTheme.colorScheme.surface)
                    },
                ),
        )

        // ── 单行内容：导航图标 / 标题 / 动作按钮 ──
        // 这里 provide 一个标记：只有真在这块液态顶栏里的按钮才用液态玻璃实现。
        // 详情页 / 播放页 / 谱面预览页的顶栏是 miuix glass 或实色，它们的返回按钮
        // 必须继续走 GlassIconButton，否则会出现材质混搭（详见 LocalInLiquidTopBar）。
        CompositionLocalProvider(LocalInLiquidTopBar provides true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(LiquidTopBarPillHeight)
                    .padding(horizontal = LiquidTopBarHorizontalInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationIcon()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    // 展开态大标题：淡出时略微缩小 + 上移，观感上像被玻璃收走
                    Text(
                        text = largeTitle,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                val f = collapse()
                                alpha = 1f - f
                                val s = 1f - 0.12f * f
                                scaleX = s
                                scaleY = s
                                translationY = -6.dp.toPx() * f
                            },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 收窄态标题：从下方一点点浮上来
                    Text(
                        text = title,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                val f = collapse()
                                alpha = f
                                translationY = 6.dp.toPx() * (1f - f)
                            },
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 动作按钮：不再额外加 Arrangement 间距——按钮自带各 6dp 外间距，
                // 相邻合计 12dp，与 miuix 顶栏完全一致（折叠态换按钮时不会跳间距）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}

/**
 * 液态玻璃图标按钮（顶栏 action 用）：44dp 圆形胶囊。
 *
 * 折射高度取 demo `LiquidButton` 的 12dp / 24dp——按钮面积小，折射量给大才有「水滴」的
 * 隆起感；底色补一层很淡的白色（暗色 12% / 亮色 42%）保证图标在任何背景下都读得清。
 */
@Composable
fun LiquidIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalLiquidBackdrop.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val surface = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.42f)
    }
    val shape: () -> Shape = remember { { Capsule() } }
    val effects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(2.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        }
    }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val onDrawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(surface) { { drawRect(surface) } }

    Box(
        modifier = modifier
            // 外间距对齐 miuix 的 ButtonPadding（见 LiquidActionButtonPadding）
            .padding(horizontal = LiquidActionButtonPadding)
            .size(44.dp)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                },
            )
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 通用液态玻璃胶囊容器：`vibrancy + blur(8dp) + lens(16dp, 18dp)` + 边缘高光 + 24dp 投影。
 *
 * 底栏导航条、播放底栏都走这个形体，保证两处材质一致。
 * 形体和效果 lambda 一律 remember——`drawBackdrop` 的节点元素按 lambda 身份判等，
 * 每次重组新建 lambda 会触发 invalidateDrawCache（整套取景重录 → 滚动细抖）。
 */
@Composable
fun LiquidGlassPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val container = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.40f) else Color(0xFFFAFAFA).copy(alpha = 0.40f)
    }
    val shape: () -> Shape = remember { { Capsule() } }
    val effects: BackdropEffectScope.() -> Unit = remember { { liquidBarEffects() } }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val fill = remember(container, containerColor) { containerColor ?: container }
    val onDrawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(fill) { { drawRect(fill) } }

    Box(
        modifier = modifier
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface, Capsule())
                },
            )
            .clip(Capsule()),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
