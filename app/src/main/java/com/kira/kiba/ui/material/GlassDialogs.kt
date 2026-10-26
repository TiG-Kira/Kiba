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

/**
 * 弹层统一入口。
 *
 * 「真·高级材质」关闭时原样回落 miuix [OverlayDialog]，视觉与改动前**完全一致**；
 * 开启时渲染为玻璃弹层：半透明面板透过取景层折射页面内容 + Scrim + 缩放弹入，
 * 并沿用 miuix 的对话框排版规范（32dp 圆角、420dp 上限、居中标题/说明）。
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
    content: @Composable () -> Unit,
) {
    val enabled = LocalAdvancedMaterialEnabled.current
    val backdrop = LocalGlassDialogBackdrop.current
    if (!enabled || backdrop == null) {
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

    // 返回键（与预测式返回手势互补：GlassDialog 内部处理手势，这里处理按键）
    BackHandler(enabled = show) { onDismissRequest?.invoke() }

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
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        GlassDialog(
            visible = show,
            onDismissRequest = dismiss,
            backdrop = backdrop,
            alignment = Alignment.BottomCenter,
            scrimAlpha = if (isDark) {
                GlassOverlayDefaults.ScrimAlphaDark
            } else {
                GlassOverlayDefaults.ScrimAlphaLight
            },
            style = GlassDefaults.style(isDark),
            // 与 miuix 对话框一致的 32dp 圆角（不是 glass 默认的 28dp）
            shape = GlassShape(DialogDefaults.CornerRadius),
            stroke = GlassStrokes.BigLight,
            shadow = GlassShadows.ExtraHigh,
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
    }
}
