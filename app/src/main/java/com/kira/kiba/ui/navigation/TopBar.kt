package com.kira.kiba.ui.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
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

/** 顶栏归类按钮（glass 系，曲目页用）。 */
@Composable
fun KibaSortAction(onClick: () -> Unit) {
    KibaActionButton(onClick = onClick) {
        Icon(
            MiuixGlassIcons.Regular.Sort,
            contentDescription = "归类方式",
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}
