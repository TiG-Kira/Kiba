package com.kira.kiba.core.utils

import android.content.Context
import android.util.Log
import coil.imageLoader
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 图片类 transient 缓存清理器。
 *
 * 背景：分享图 / 生成图 / 更新包都落在 [CacheDirs] 的 cacheDir 子目录下，
 * 系统「清除缓存」即可清空；但这些都是"生成即产生、用完即弃"的产物，若从不主动清理，
 * 长期堆积会造成"异常占用"。本类在应用空闲时定期清掉过期的 transient 文件。
 *
 * 守卫条件（需求：在用户未使用图片生成功能时清理）：
 * - [markGenerationActive] 在分享 / Best50 生成 / 梗图生成开始时打点；
 * - 距最近一次打点不足 [ACTIVE_GRACE_MS] 视为"正在使用"，跳过本次清理，避免误删用户手头文件。
 *
 * ⚠️ 封面缓存（Coil image_cache）【永不】被自动清理：离线查分依赖已缓存的封面，
 * 清掉会导致离线状态下歌曲/谱面无图。封面缓存只经 [clearCoverCache] 由用户显式清理
 * （设置页「数据 → 清理封面缓存」入口）。因此本类的清理范围严格限定在
 * [transientDirs] 白名单内，后续新增自动清理目标必须先确认不影响封面可用性。
 */
object CacheCleaner {

    private const val TAG = "CacheCleaner"

    /** 分享图 / 生成图保留窗口：超过该时长且空闲才清理。 */
    private val TRANSIENT_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

    /** 更新包保留窗口：下载即装、装完即删；这里兜底清理异常残留（1 天足够）。 */
    private val UPDATES_MAX_AGE_MS = TimeUnit.DAYS.toMillis(1)

    /** 距最近一次图片生成/分享不足此时长，视为正在使用，跳过清理。 */
    private val ACTIVE_GRACE_MS = TimeUnit.MINUTES.toMillis(1)

    /** 定期清理最小间隔（启动节流 + 前台周期都按这个节拍）。 */
    private val MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    /** 图片生成/分享功能最近活跃时间（进程级，供空闲判断）。 */
    @Volatile
    var lastGenerationActiveMs: Long = 0L
        private set

    /** 由分享 / Best50 / 梗图等入口在开始前调用，标记"用户正在使用图片功能"。 */
    fun markGenerationActive() {
        lastGenerationActiveMs = System.currentTimeMillis()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("cache-cleaner"))

    /**
     * 启动后调度周期性清理：先延迟 [STARTUP_DELAY_MS] 跑一次（受节流约束），
     * 之后每 [MIN_INTERVAL_MS] 检查一次。前台存活即按节拍运行；进程被杀后由下次启动补偿。
     */
    fun schedule(context: Context) {
        scope.launch {
            delay(STARTUP_DELAY_MS)
            maybeClean(context)
            while (true) {
                delay(MIN_INTERVAL_MS)
                maybeClean(context)
            }
        }
    }

    /** 受节流的清理：距上次清理不足 [MIN_INTERVAL_MS] 直接返回。 */
    private suspend fun maybeClean(context: Context) {
        val now = System.currentTimeMillis()
        if (now - SpUtil.getLastCacheClean() < MIN_INTERVAL_MS) return
        val did = cleanIfIdle(context, force = false)
        // 仅在确实清过、或明确空闲时才更新时间戳，避免"正用着却被节流跳过"导致长期不清理
        if (did || now - lastGenerationActiveMs >= ACTIVE_GRACE_MS) {
            SpUtil.setLastCacheClean(now)
        }
    }

    /**
     * 自动清理白名单：只允许清这些 transient 目录。
     * 封面缓存（Coil image_cache）刻意不在名单内——见类注释，保离线封面。
     */
    private fun transientDirs(context: Context): List<Pair<File, Long>> = listOf(
        CacheDirs.share(context) to TRANSIENT_MAX_AGE_MS,
        CacheDirs.generated(context) to TRANSIENT_MAX_AGE_MS,
        CacheDirs.updates(context) to UPDATES_MAX_AGE_MS,
    )

    /**
     * 清理 transient 缓存（share / generated / updates 白名单）。
     * 封面缓存（Coil image_cache）永远不在这里清，见类注释。
     * @param force 跳过"用户正在使用"的空闲判断（手动清理入口用）
     * @return 是否删除过任何文件
     */
    suspend fun cleanIfIdle(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - lastGenerationActiveMs < ACTIVE_GRACE_MS) {
            Log.i(TAG, "skip: image generation recently active")
            return@withContext false
        }
        var freed = 0L
        transientDirs(context).forEach { (dir, maxAge) -> freed += prune(dir, maxAge) }
        Log.i(TAG, "cleaned ${(freed / 1024).coerceAtLeast(0)} KB transient cache")
        freed > 0
    }

    /**
     * 手动清理封面缓存（设置页「数据 → 清理封面缓存」入口）：
     * 清空 Coil 内存缓存 + 磁盘缓存。自动清理【不会】触碰封面缓存，
     * 所以清完后离线封面需要重新联网加载一次。
     * @return 释放的字节数（按清理前磁盘缓存目录大小估算）
     */
    suspend fun clearCoverCache(context: Context): Long = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        // diskCache.directory 类型随 Coil 版本在 File / okio.Path 之间摇摆，统一归一化为 File
        val dir = loader.diskCache?.directory?.let { File(it.toString()) }
            ?: File(context.cacheDir, "image_cache")
        val size = dirSize(dir)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        Log.i(TAG, "cover cache cleared, freed ~${size / 1024} KB")
        size
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 删除目录下修改时间早于 [maxAgeMs] 的文件（不递归子目录，这些目录都是扁平的）。 */
    private fun prune(dir: File, maxAgeMs: Long): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > maxAgeMs) {
                val size = f.length()
                if (f.delete()) freed += size
            }
        }
        return freed
    }

    private const val STARTUP_DELAY_MS = 20_000L
}
