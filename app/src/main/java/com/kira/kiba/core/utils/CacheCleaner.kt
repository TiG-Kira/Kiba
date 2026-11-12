package com.kira.kiba.core.utils

import android.content.Context
import android.util.Log
import coil.imageLoader
import com.kira.kiba.core.listen.AgentPlaylistStore
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
 * ⚠️ 图片缓存（Coil image_cache + Agent 歌单封面）【永不】被自动清理：离线查分依赖已缓存的封面，
 * 清掉会导致离线状态下歌曲/谱面无图。图片缓存只经 [clearImageCache] 由用户显式清理
 * （设置页「数据 → 清理图片缓存」入口）。因此本类的自动清理范围严格限定在
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
     * 图片缓存（Coil image_cache / Agent 歌单封面）刻意不在名单内——见类注释，保离线封面。
     */
    private fun transientDirs(context: Context): List<Pair<File, Long>> = listOf(
        CacheDirs.share(context) to TRANSIENT_MAX_AGE_MS,
        CacheDirs.generated(context) to TRANSIENT_MAX_AGE_MS,
        CacheDirs.updates(context) to UPDATES_MAX_AGE_MS,
    )

    /**
     * 清理 transient 缓存（share / generated / updates 白名单）。
     * 图片缓存（Coil image_cache / Agent 歌单封面）永远不在这里清，见类注释。
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
     * 手动清理**图片缓存**（设置页「数据 → 清理图片缓存」入口），三块一起清：
     *
     * 1. Coil 内存缓存（当场生效，列表里已加载的封面立刻释放）；
     * 2. Coil 磁盘缓存（`cacheDir/image_cache`，歌曲封面 / 分享图底图都在这里）；
     * 3. Agent 歌单**未使用**的历史封面（`filesDir/playlist_covers` 里没有任何歌单引用的 PNG）。
     *
     * 第 3 项是「图片缓存」而非「数据」：正在用的歌单封面必须留着（详情页顶栏直接读文件），
     * 所以只清孤儿 —— 判定见 [PlaylistCover.pruneOrphans]。
     *
     * 自动清理【不会】触碰以上任何一项，所以清完后离线封面需要重新联网加载一次。
     *
     * @return 释放的字节数（Coil 磁盘缓存 + 孤儿封面的实际大小）
     */
    suspend fun clearImageCache(context: Context): Long = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        // diskCache.directory 类型随 Coil 版本在 File / okio.Path 之间摇摆，统一归一化为 File
        val dir = loader.diskCache?.directory?.let { File(it.toString()) }
            ?: File(context.cacheDir, "image_cache")
        val coilSize = dirSize(dir)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        // 孤儿封面：不是「缓存」而是真占 filesDir 的垃圾，一并收掉
        val orphanSize = runCatching { AgentPlaylistStore.cleanupCovers(context) }.getOrDefault(0L)
        val freed = coilSize + orphanSize
        Log.i(TAG, "image cache cleared, freed ~${freed / 1024} KB (coil $coilSize, orphan covers $orphanSize)")
        freed
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
