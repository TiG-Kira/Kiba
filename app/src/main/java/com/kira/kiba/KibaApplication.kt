package com.kira.kiba

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.network.MaimaiDataClient
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.core.utils.SpUtil

class KibaApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        SpUtil.init(this)
        AppDataBase.init(this)
        MaimaiDataClient.instance.init()
        // 启动引导：恢复查分器登录态 + 静默同步曲库（内部全部降级，不阻塞启动）
        KibaState.bootstrap(this)
    }

    /**
     * 全局图片加载器（列表封面 / 歌曲详情顶栏封面共用）：
     * - 磁盘缓存 256MB，位于 cacheDir/image_cache——系统「应用详情 → 清除缓存」即可清空，
     *   不占用「清除数据」之外的持久空间。
     * - respectCacheHeaders(false)：封面 CDN 常带 no-cache / 短 TTL 响应头，默认策略会让
     *   磁盘缓存立刻失效，导致断网/重进后封面重新走网络甚至 404。关闭后只要下载成功就
     *   落盘，列表图片二次进入秒显，详情页封面也能离线命中。
     * - 内存缓存沿用 Coil 默认（25% 可用内存）。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
