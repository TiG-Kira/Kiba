// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.agent

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动图（GIF）展示。
 *
 * 为什么不用 Coil：`coil-gif` 是独立构件，本项目离线构建拿不到依赖，`AsyncImage`
 * 只会画第一帧。系统自带的 [ImageDecoder] 从 API 28 起就能解 `AnimatedImageDrawable`，
 * 直接塞进 ImageView 即可循环播放，零新依赖。
 *
 * 解码放在 IO 线程，避免大 GIF 卡住首帧。
 */
@Composable
fun GifImage(
    uri: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
                )
            }.getOrNull()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                adjustViewBounds = true
                scaleType = contentScale.toScaleType()
            }
        },
        update = { view ->
            val d = drawable ?: return@AndroidView
            if (view.drawable !== d) {
                view.setImageDrawable(d)
            }
            (d as? AnimatedImageDrawable)?.start()
        },
    )
}

/**
 * 只读 GIF 头里的画布尺寸（`inJustDecodeBounds`，不解码像素，几十微秒）。
 *
 * 气泡里的动图需要按原始比例定尺寸：上游不少梗图只有 70×95 / 112×112，
 * 若一律 `fillMaxWidth` 会被拉到整屏宽，放大 4~6 倍糊成色块。
 */
@Composable
fun rememberImageSize(uri: String): IntSize? {
    val context = LocalContext.current
    val size by produceState<IntSize?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    IntSize(opts.outWidth, opts.outHeight)
                } else null
            }.getOrNull()
        }
    }
    return size
}

private fun ContentScale.toScaleType(): ImageView.ScaleType = when (this) {
    ContentScale.Fit -> ImageView.ScaleType.FIT_CENTER
    ContentScale.FillWidth -> ImageView.ScaleType.FIT_CENTER
    ContentScale.FillHeight -> ImageView.ScaleType.FIT_CENTER
    ContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
    else -> ImageView.ScaleType.CENTER_CROP
}

/** 是否是本 App 生成的 GIF（文件名以 .gif 结尾，FileProvider URI 保留扩展名）。 */
fun isGifUri(uri: String): Boolean = uri.substringBefore('?').endsWith(".gif", ignoreCase = true)
