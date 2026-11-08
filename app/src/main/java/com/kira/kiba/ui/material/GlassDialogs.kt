package com.kira.kiba.ui.material

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kira.kiba.ui.material.liquid.LiquidGlassDialog
import com.kira.kiba.ui.material.liquid.LocalLiquidBackdrop
import com.kira.kiba.ui.navigation.LocalGlassDialogBackdrop
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassOverlayDefaults
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassShadows
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import top.yukonga.miuix.kmp.utils.getRoundedCorner

/**
 * 弹层圆角：**照抄 miuix 的对话框圆角逻辑**，不再写死 32dp。
 *
 * miuix 的 `DialogContentLayout` 分两档（0.9.4 起）：
 * - 居中（大屏）呈现 → `DialogDefaults.CornerRadius`（32dp）；
 * - 贴底呈现 → 与设备**屏幕圆角同心**：`getRoundedCorner() - outsideMargin.width`，
 *   再夹到 32dp..48dp（屏幕圆角 ≤ 44dp 时就是 32dp，≥ 60dp 才顶到 48dp）。
 *
 * Kiba 的弹层容器 `enableAutoLargeScreen = false`，永远是贴底那档，所以只取第二档。
 * 之前液态 / 柔光两条玻璃分支都写死 `DialogDefaults.CornerRadius`，屏幕圆角大的机器上
 * 会比 miuix 自己的 `OverlayDialog` 明显更方——开「真·高级材质」的弹层比关掉时更方，
 * 一眼就能看出来是两套圆角。
 */
@Composable
fun miuixDialogCornerRadius(): Dp =
    (getRoundedCorner() - DialogDefaults.outsideMargin.width)
        .coerceIn(DialogDefaults.CornerRadius, 48.dp)

/**
 * 弹层统一入口。
 *
 * 「真·高级材质」关闭时原样回落 miuix [OverlayDialog]，视觉与改动前**完全一致**；
 * 开启时渲染为玻璃弹层：半透明面板透过取景层折射页面内容 + Scrim + 缩放弹入，
 * 并沿用 miuix 的对话框排版规范（与 miuix 同源的圆角、420dp 上限、居中标题/说明）。
 *
 * 关键约束：玻璃弹层必须**渲染在取景层记录的子树之外**，否则背景捕获会成环。
 * 这里借 miuix 的 [DialogLayout] 把它送进根 Scaffold 的 overlay 容器
 * （与 [OverlayDialog] 同一机制），而注册取景层的是内容 Box，两者互不包含。
 *
 * @param show 是否显示
 * @param title 标题（居中，title4/Medium）
 * @param summary 说明（居中，body1）
 * @param onDismissRequest 点击 Scrim / 返回键时回调
 * @param onDismissFinished 退场动画结束后回调
 * @param maxWidth 内容宽度上限，防止在平板/桌面窗口被拉成长条
 * @param forceDefault 豁免开关：true 时无论材质开关如何，一律回落默认 [OverlayDialog]
 * （如关于页的软件更新窗口——该页本身即豁免页，弹层保持默认观感）
 * @param content 弹层主体内容
 */
@Composable
fun KibaGlassDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    forceDefault: Boolean = false,
    content: @Composable () -> Unit,
) {
    val enabled = LocalAdvancedMaterialEnabled.current && !forceDefault
    val family = LocalMaterialFamily.current
    val liquidBackdrop = LocalLiquidBackdrop.current
    val backdrop = LocalGlassDialogBackdrop.current

    val fallback = !enabled ||
        (family == MaterialFamily.LIQUID && liquidBackdrop == null) ||
        (family != MaterialFamily.LIQUID && backdrop == null)
    if (fallback) {
        OverlayDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            content = content,
        )
        return
    }

    // 返回键（与预测式返回手势互补：*GlassDialog 内部处理手势，这里处理按键）
    BackHandler(enabled = show) { onDismissRequest?.invoke() }

    // 与 miuix 对话框同源的圆角（贴底呈现 → 屏幕圆角推导，32..48dp）
    val dialogCornerRadius = miuixDialogCornerRadius()

    // 与 miuix OverlayDialog 同款：先以 false 起步，避免首帧直接以终态出现
    val visibleState = remember { mutableStateOf(false) }
    visibleState.value = show
    val dismiss: () -> Unit = { onDismissRequest?.invoke() }
    val finish: () -> Unit = { onDismissFinished?.invoke() }

    MiuixPopupUtils.DialogLayout(
        visible = visibleState,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableAutoLargeScreen = false,
        renderInRootScaffold = true,
        onDismissFinished = if (onDismissFinished == null) null else finish,
    ) {
        if (family == MaterialFamily.LIQUID) {
            // 液态玻璃弹层：圆角与 miuix 对话框同源 + 强折射色散（Kyant0/AndroidLiquidGlass）
            LiquidGlassDialog(
                visible = show,
                onDismissRequest = dismiss,
                backdrop = liquidBackdrop!!,
                alignment = Alignment.BottomCenter,
                cornerRadius = dialogCornerRadius,
            ) {
                DialogBody(title = title, summary = summary, maxWidth = maxWidth, content = content)
            }
        } else {
            val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
            GlassDialog(
                visible = show,
                onDismissRequest = dismiss,
                backdrop = backdrop!!,
                alignment = Alignment.BottomCenter,
                scrimAlpha = if (isDark) {
                    GlassOverlayDefaults.ScrimAlphaDark
                } else {
                    GlassOverlayDefaults.ScrimAlphaLight
                },
                style = GlassDefaults.style(isDark),
                // 与 miuix 对话框同源的圆角（不是 glass 默认的 28dp，也不再写死 32dp）
                shape = GlassShape(dialogCornerRadius),
                stroke = GlassStrokes.BigLight,
                shadow = GlassShadows.ExtraHigh,
            ) {
                DialogBody(title = title, summary = summary, maxWidth = maxWidth, content = content)
            }
        }
    }
}

/** 弹层正文：标题 / 说明 / 内容，两套材质共用同一套排版（圆角与 miuix 同源 + 420dp 上限 + 居中）。 */
@Composable
private fun DialogBody(
    title: String?,
    summary: String?,
    maxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.widthIn(max = maxWidth)) {
        if (title != null) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                text = title,
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = DialogDefaults.titleColor(),
            )
        }
        if (summary != null) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                text = summary,
                style = MiuixTheme.textStyles.body1,
                textAlign = TextAlign.Center,
                color = DialogDefaults.summaryColor(),
            )
        }
        content()
    }
}
