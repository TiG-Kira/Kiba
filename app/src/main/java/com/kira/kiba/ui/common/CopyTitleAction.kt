package com.kira.kiba.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kira.kiba.ui.navigation.KibaActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 复制文本到系统剪贴板（无统一封装前的散点写法沉淀）。
 *
 * @return 是否写入成功（ClipboardManager 拿不到时返回 false）
 */
fun Context.copyTextToClipboard(text: String, label: String = "kiba"): Boolean {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}

/**
 * 顶栏「复制歌名」按钮：点击后把歌名写入系统剪贴板并 Toast 提示。
 *
 * 供舞萌 / 中二歌曲详情页顶栏共用；tint 由调用方按大封面取色传入。
 */
@Composable
fun CopyTitleAction(
    title: String,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurface,
    contentDescription: String = "复制歌名",
) {
    val context = LocalContext.current
    KibaActionButton(
        onClick = {
            if (title.isBlank()) return@KibaActionButton
            val ok = context.copyTextToClipboard(title, "歌名")
            Toast.makeText(
                context,
                if (ok) "已复制歌名：$title" else "无法访问剪贴板",
                Toast.LENGTH_SHORT,
            ).show()
        },
        modifier = modifier,
    ) {
        Icon(
            MiuixGlassIcons.Regular.Copy,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}
