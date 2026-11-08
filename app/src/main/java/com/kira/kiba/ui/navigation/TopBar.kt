package com.kira.kiba.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kira.kiba.core.model.GameType
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kira.kiba.ui.material.LocalMaterialFamily
import com.kira.kiba.ui.material.MaterialFamily
import com.kira.kiba.ui.material.liquid.LiquidIconButton
import com.kira.kiba.ui.material.liquid.LiquidTopBarSkin
import com.kira.kiba.ui.material.liquid.LocalInLiquidTopBar
import com.kira.kiba.ui.material.liquid.LocalLiquidBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 当前顶栏本身是否要用液态玻璃实现。
 *
 * `LocalGlassTopAppBarBackdrop` 在 KibaMainScreen 里承担「顶栏材质可用」的**总开关**语义：
 * 真·高级材质关闭、或该页豁免（关于页）时都会被置为 null。液态玻璃直接复用它做闸门，
 * 保证两套材质的启用/豁免矩阵完全一致。
 */
@Composable
internal fun useLiquidBar(): Boolean =
    LocalMaterialFamily.current == MaterialFamily.LIQUID &&
        LocalGlassTopAppBarBackdrop.current != null &&
        LocalLiquidBackdrop.current != null

/**
 * Kiba 顶栏：按材质风格分派。
 * - 柔光玻璃+（默认）：原生 miuix-glass 的 [GlassTopAppBar]，backdrop 取自
 *   [LocalGlassTopAppBarBackdrop]（MainScreen 共享的 miuix-blur 取景层）；
 * - 液态玻璃：miuix-glass 骨架（backdrop 置 null 关掉其玻璃）+ [LiquidTopBarSkin] 皮肤
 *   （折叠时整块玻璃包住状态栏）+ 液态圆钮，采样 [LocalLiquidBackdrop]；
 * - 材质关闭 / 关于页豁免：两条路径都拿到 null backdrop，回落传统纯色顶栏。
 *
 * 大标题 + 滚动塌缩由 [scrollBehavior] 驱动，页面 LazyColumn 需挂 nestedScroll。
 */
@Composable
fun KibaTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    navigationIcon: @Composable () -> Unit = {},
    /**
     * 展开态大标题行尾插槽（曲目页的「舞萌 / 中二」开关）。
     *
     * 与大标题**同一行**、垂直居中；折叠时随大标题的同一个 graphicsLayer 一起淡出 / 上移，
     * 展开时一起回来 —— 与文字折叠动画严格同频，不需要调用方再维护一套进度。
     * 传 null（默认）时大标题行与原生 miuix 完全一致。
     */
    titleTrailing: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (useLiquidBar()) {
        // 液态：miuix-glass 骨架（折叠逻辑 / 大标题左对齐 / 小标题居中 / 按钮槽）+
        // 液态皮肤（折叠时整块玻璃包住状态栏与标题栏、底部 28dp 圆角）+ 液态圆钮。
        // 骨架的 backdrop 传 null：miuix 自己的玻璃与按钮材质不渲染，band 笔刷透明化，
        // 内容之下只剩液态皮肤；KibaActionButton 经 LocalInLiquidTopBar 渲染为液态圆钮。
        // 按钮材质与 miuix 骨架的 `isContentScrolled` **同源**（contentOffset < 0）：
        // 内容没滚到顶栏下时按钮是裸图标，滚离原位即换成液态圆钮。
        //
        // 不能用 collapsedFraction：它的分母 heightOffsetLimit 是 TopAppBarState 的普通字段
        // （非 snapshot state），封面详情页的 limit 远大于列表页。从详情页返回时 limit 变小
        // 却不会广播失效，restoreTopBar 的同值补写也救不回来，这个 derivedStateOf 会永久
        // 卡在 false —— 表现成「返回后上一级按钮变回 miuix-glass 圆底」（用户实证）。
        // BlurTopAppBar 内部用 heightOffsetLimitVersion 修了自己的 smallTitleVisible，
        // 但那枚计数器是它的局部变量，外部读不到；contentOffset 是 snapshot state，
        // 页面切换恢复时一定触发重算，与骨架的 floating / 按钮底板严格同步。
        val liquidButtons by remember(scrollBehavior) {
            derivedStateOf { scrollBehavior.state.contentOffset < 0f }
        }
        Box(modifier.fillMaxWidth()) {
            LiquidTopBarSkin(
                backdrop = LocalLiquidBackdrop.current,
                scrollBehavior = scrollBehavior,
                modifier = Modifier.matchParentSize(),
            )
            CompositionLocalProvider(LocalInLiquidTopBar provides liquidButtons) {
                GlassTopAppBar(
                    title = title,
                    largeTitle = largeTitle,
                    backdrop = null,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = navigationIcon,
                    largeTitleTrailing = titleTrailing,
                    actions = actions,
                    bandBrush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent),
                    ),
                )
            }
        }
    } else {
        GlassTopAppBar(
            title = title,
            largeTitle = largeTitle,
            modifier = modifier,
            backdrop = LocalGlassTopAppBarBackdrop.current,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            largeTitleTrailing = titleTrailing,
            actions = actions,
        )
    }
}

/**
 * 顶栏动作按钮：材质**跟随所在的那块顶栏**，而不是跟随全局开关。
 *
 * [LocalInLiquidTopBar] 由两处置 true：
 * - [KibaTopBar] 的液态分支：内容滚离原位（contentOffset < 0，与 miuix 骨架同信号）后置 true；
 * - 歌曲详情 / 播放页 / 中二详情这些"封面大图 + 折叠"的顶栏，在**折叠之后**才置 true
 *   （封面还在时按钮必须是 miuix 的裸图标，压在封面上的玻璃圆钮很难看）。
 *
 * 这样同一颗按钮在展开态是 miuix 玻璃、折叠后自动变成液态圆钮，不需要调用方判断。
 */
@Composable
fun KibaActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalInLiquidTopBar.current) {
        LiquidIconButton(onClick = onClick, modifier = modifier, content = content)
    } else {
        GlassIconButton(onClick = onClick, modifier = modifier) { content() }
    }
}

/** 顶栏返回按钮（glass 系，二级页面用）。[tint] 供封面背景等自定义底色场景切换字色。
 *  图标对齐 Termux Ultra 的 BackButton 口径：ChevronBackward · 24dp · onSurface。 */
@Composable
fun KibaBackAction(
    tint: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.ChevronBackward,
            contentDescription = "返回",
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
    }
}

/** 顶栏搜索按钮（glass 系）。 */
@Composable
fun KibaSearchAction(onClick: () -> Unit) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Search,
            contentDescription = "搜索",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 顶栏刷新按钮（glass 系）。 */
@Composable
fun KibaRefreshAction(onClick: () -> Unit) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Refresh,
            contentDescription = "刷新",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 顶栏排序 / 归类按钮（glass 系，曲目页用；舞萌与中二节奏都能排序）。 */
@Composable
fun KibaSortAction(onClick: () -> Unit) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Sort,
            contentDescription = "排序与归类",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 顶栏「功能」按钮（glass 系，Kiba 对话页用；技能二级级联菜单入口）。 */
@Composable
fun KibaFunctionAction(onClick: () -> Unit) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.GridView,
            contentDescription = "功能",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 顶栏「舞萌 / 中二」游戏切换开关（2.4.0）。
 *
 * 设计约束（用户定稿）：
 * - 塞进顶栏大标题**同一行**，与大标题文字视觉平行（垂直居中对齐），不是标题下方的独立条；
 * - 收窄：总宽 88dp、高 28dp，两段等宽，只留「舞萌」「中二」四字；
 * - 折叠态隐藏由 miuix 大标题自己的 alpha 负责——本组件不监听滚动，挂在大标题行里即可同频。
 *
 * 选中位移用 spring（无回弹过冲），点按命中区是整段，重复点当前项不下发。
 *
 * ## [interactive] 为什么必须存在（2.4.10 修复）
 *
 * 折叠后大标题只是被 miuix **画成透明**，组件本身还在原位、两个 44dp 段各挂着一颗
 * `Modifier.clickable`。Compose 的命中测试在命中带 pointerInput 的节点后就不再往下探
 * （`clickable` 的 `sharePointerInputWithSiblings` 为 false），于是这颗**看不见**的开关
 * 会把同一区域顶栏按钮的点击整块吃掉 —— 表现成「折叠态所有按钮都点不动、展开就正常」。
 *
 * 视觉隐藏对命中链无效，所以折叠态必须干脆**不组合 clickable**：这块区域彻底不参与命中，
 * 事件穿透给下面的按钮。判定沿用顶栏自己的折叠信号，与按钮材质切换同频。
 *
 * @param interactive 是否接收点击；折叠态传 false（视觉隐藏由大标题 alpha 负责）
 */
@Composable
fun KibaGameSwitch(
    current: GameType,
    onSelect: (GameType) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val options = listOf(
        GameType.MAIMAI to "舞萌",
        GameType.CHUNITHM to "中二",
    )
    val selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val segmentWidth = 44.dp
    val barHeight = 28.dp
    val inset = 2.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (selectedIndex == 0) 0.dp else segmentWidth,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "kibaGameSwitchThumb",
    )
    Box(
        modifier = modifier
            // 宽度必须显式给：matchParentSize 的 Row 不参与尺寸计算，
            // 只靠滑块（44dp）撑宽会变成「只有半截」的胶囊（AVD 实测踩过）。
            .width(segmentWidth * 2)
            .height(barHeight)
            .clip(RoundedCornerShape(barHeight / 2))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
    ) {
        // 选中滑块：主色调低饱和底，避免在浅色顶栏上过于跳
        Box(
            modifier = Modifier
                .offset(x = thumbOffset + inset, y = inset)
                .width(segmentWidth)
                .height(barHeight - inset * 2)
                .clip(RoundedCornerShape((barHeight - inset * 2) / 2))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)),
        )
        Row(modifier = Modifier.matchParentSize()) {
            options.forEachIndexed { index, (type, label) ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }
                // 折叠态（interactive = false）**不组合 clickable**：仅靠 alpha 隐藏挡不住命中，
                // 见 KDoc。留下 clickable 会让这块透明区域继续吃掉顶栏按钮的点击。
                val tapModifier = if (interactive) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        if (type != current) onSelect(type)
                    }
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .then(tapModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
