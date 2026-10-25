package com.kira.kiba.ui.share

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.share.ShareImageBuilder
import com.kira.kiba.core.share.ShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 详情页分享图入口（顶栏 glass 按钮）。
 *
 * 点击 → 立即弹「正在准备图片」进度弹层（生成在 IO 异步进行）→ 生成完成后自动切换到
 * 「保存到相册 / 分享给好友」对话框：
 * - 保存：写入 Pictures/Kiba（MediaStore，无需存储权限）；
 * - 分享：经 FileProvider 以 image/png 发出，系统选择器可挑 QQ / 微信等。
 *
 * 生成期间可点「取消」（或返回手势）中止任务，不弹失败提示。
 *
 * @param fileName 保存/分享用的文件名（如 `Kiba_song_123_1698….png`）
 * @param build    生成逻辑（挂起，返回 PNG 字节；null = 失败）
 */
@Composable
fun ShareImageAction(
    fileName: String,
    build: suspend () -> ByteArray?,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurface,
    contentDescription: String = "分享图片",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var building by remember { mutableStateOf(false) }
    var buildJob by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    fun cancelBuild() {
        buildJob?.cancel()
        buildJob = null
        building = false
    }

    GlassIconButton(
        onClick = {
            if (building) return@GlassIconButton
            building = true
            buildJob = scope.launch {
                val bytes = try {
                    withContext(Dispatchers.IO) { build() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                building = false
                if (bytes != null) {
                    pending = bytes
                } else {
                    Toast.makeText(
                        context,
                        "生成失败：${ShareImageBuilder.lastError.ifBlank { "图片绘制异常" }}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(
            MiuixGlassIcons.Regular.Share,
            contentDescription = contentDescription,
            tint = tint,
        )
    }

    // ── 生成中：进度弹层（可取消）──
    if (building) {
        OverlayDialog(
            show = true,
            title = "分享图片",
            summary = "正在准备图片用于分享…",
            onDismissRequest = { cancelBuild() },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    progress = null,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = { cancelBuild() })
                }
            }
        }
    }

    // ── 生成完成：分享方式选择弹层 ──
    val bytes = pending
    if (bytes != null) {
        ShareImageDialog(
            onSave = {
                val uri = ShareService.saveToGallery(context, bytes, fileName)
                Toast.makeText(
                    context,
                    if (uri != null) "已保存到相册：Pictures/Kiba/$fileName" else "保存失败：无法写入相册",
                    Toast.LENGTH_SHORT,
                ).show()
                pending = null
            },
            onShare = {
                val ok = ShareService.sharePng(context, bytes, fileName)
                if (!ok) Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

/** 「保存到相册 / 分享给好友」选择弹层（miuix OverlayDialog）。 */
@Composable
private fun ShareImageDialog(
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "分享图片",
        summary = "保存到相册，或发送给 QQ / 微信好友",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "图片已生成，选择分享方式：",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "保存到相册", onClick = onSave)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "分享", onClick = onShare)
            }
        }
    }
}
