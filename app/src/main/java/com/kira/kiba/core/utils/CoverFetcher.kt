package com.kira.kiba.core.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面位图获取器（供 Best50 / 分享图等"图片生成"路径使用）。
 *
 * 关键设计 —— **复用 UI 同一套 Coil 磁盘缓存**：
 * - 按 [CoverSources.candidates] 候选链顺序请求，第一个成功即返回；
 * - 走全局 `ImageLoader`（`KibaApplication` 配置，`respectCacheHeaders(false)`），
 *   因此 UI 列表/详情页已经缓存过的封面（含自定义图床 / lxns / EasyMai / 镜像任一源）
 *   在这里**直接命中磁盘缓存、秒回**，无需重新下载，也天然完成"新封面源的缓存"。
 * - 之前生成图硬编码只取水鱼 CDN，导致自定义图床等封面在生成图里缺失且从不缓存；
 *   本类统一了 UI 与生成图的封面源。
 *
 * 密度铁律：返回位图统一 `setDensity(DENSITY_NONE)`，与底板（mdpi）混合绘制时
 * 不被 Canvas 按设备密度缩放（踩过的坑，246px 格宽画成 82px）。
 */
object CoverFetcher {

    /**
     * 取封面位图。
     * @param candidates [CoverSources.candidates] 返回的候选链（已按优先级排序）
     * @return 软件位图（density=DENSITY_NONE）；全部失败返回 null
     */
    suspend fun fetch(context: Context, candidates: List<String>): Bitmap? = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        for (url in candidates) {
            val result = runCatching {
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // 生成路径要在软件 Canvas 上绘制，禁用硬件位图
                        .build(),
                )
            }.getOrNull()

            if (result is ErrorResult) continue
            if (result !is SuccessResult) continue

            val drawable = result.drawable
            val bmp = (drawable as? BitmapDrawable)?.bitmap ?: continue
            // 兜底：极少数情况下仍可能是硬件位图，复制为软件位图
            val safe = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bmp
            }
            safe.setDensity(Bitmap.DENSITY_NONE)
            return@withContext safe
        }
        null
    }

    /**
     * 解码本地资源字节（drawable 等），统一 density=DENSITY_NONE，供生成器复用。
     * 保留在此便于与封面解码保持一致的密度处理。
     */
    fun decodeBytes(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.copy(Bitmap.Config.ARGB_8888, true)
            ?.apply { setDensity(Bitmap.DENSITY_NONE) }
}
