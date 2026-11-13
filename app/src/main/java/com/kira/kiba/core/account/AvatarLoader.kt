package com.kira.kiba.core.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 供**生成类图片**（Best 50 分享图等）取当前账户头像位图，口径与账户页顶栏一致：
 *
 * 1. 自定义头像（本地 512² PNG，[CustomAvatar.loadBitmap] 内有进程级内存缓存）；
 * 2. 自动抓取头像（[AccountAvatar] 按游戏 + 查分器取 URL）：
 *    - `data:` URI 自行 Base64 解码 —— 不交给 Coil（data URI 在 Coil 各版本支持程度不一致）；
 *    - http(s) 走 Coil（命中顶栏已有的内存/磁盘缓存）。
 *
 * 拿不到任何头像返回 null，调用方按「无头像」画默认样式，不影响生成主流程。
 */
suspend fun loadAccountAvatarBitmap(
    context: Context,
    game: GameType,
    source: ScoreSource,
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (CustomAvatar.current().hasImage) return@runCatching CustomAvatar.loadBitmap()
        val url = AccountAvatar.current(game, source)
        if (url.isBlank()) return@runCatching null
        if (url.startsWith("data:", ignoreCase = true)) {
            val payload = url.substringAfter(',', "")
            if (payload.isBlank()) return@runCatching null
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        }
    }.getOrNull()
}
