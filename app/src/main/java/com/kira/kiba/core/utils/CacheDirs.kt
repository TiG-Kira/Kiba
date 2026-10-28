package com.kira.kiba.core.utils

import android.content.Context
import java.io.File

/**
 * 应用缓存目录集中管理（对齐 Android 标准：transient / 可重建产物一律落 cacheDir，
 * 系统「应用详情 → 清除缓存」即可整体清空，不占用「清除数据」之外的持久空间）。
 *
 * 子目录规划：
 * - [SHARE]      share/        歌曲/谱面分享图的临时 PNG（经 FileProvider 发出）
 * - [GENERATED]  generated/    Best 50 卡片、Agent 梗图等"生成类图片"的暂存
 * - [UPDATES]    updates/      APK 更新包（下载即装、装完即删，这里只兜底残留）
 * - image_cache/ Coil 封面磁盘缓存（在 [KibaApplication] 中配置，256MB，自管理）
 *
 * 落盘统一走本对象的工厂方法，避免各模块散落硬编码路径。
 */
object CacheDirs {

    const val SHARE = "share"
    const val GENERATED = "generated"
    const val UPDATES = "updates"

    fun share(context: Context): File = ensure(File(context.cacheDir, SHARE))
    fun generated(context: Context): File = ensure(File(context.cacheDir, GENERATED))
    fun updates(context: Context): File = ensure(File(context.cacheDir, UPDATES))

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
