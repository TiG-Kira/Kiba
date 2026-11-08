package com.kira.kiba.core.utils

import android.os.SystemClock
import com.kira.kiba.core.network.MaimaiDataClient

/**
 * 歌曲封面候选 URL 链（UI 列表/详情页 与 图片生成 共用同一份逻辑，保证两处封面源完全一致）。
 *
 * 各源实测编号规则（2026-10 用 curl 逐一验证过 200/404）：
 * 1. 自定义图床前缀（设置页可填，命中自己的镜像最快）；
 * 2. 水鱼 CDN `covers/{songId}.png`：**只收录 DX 5 位编号**（11001+，无前导零），
 *    标准曲任何形态都 404（原 `%05d` 补零形态对标准曲是永远打不中的无效请求）；
 * 3. 落雪 lxns `assets2.lxns.net/maimai/jacket/{官方编号}.png`：**唯一同时覆盖
 *    标准曲 + DX 的源**（标准曲原样 1-999，DX 曲 = df id - 10000），实测 200；
 * 4. EasyMai 封面 OSS `oss.lista233.cn/cover/cover/{id}.jpg`（反编译自 EasyMai：
 *    列表页直接用数据 id 拼 `/cover/cover/{id}.jpg`），DX 曲补充源，实测 200；
 * 5. 官网图床 maimaidx.jp + 若干镜像（DX 新曲兜底，国内可能不可达）。
 *
 * 全部失败 → 调用方退化为占位（流派色块 + 音符）。
 */
object CoverSources {

    /** 已知镜像（同一路径结构 /maimai-mobile/img/Music/{hash}.png）。 */
    private val MIRRORS = listOf(
        "https://maimaidx.jp/maimai-mobile/img/Music/",
        "https://maimai-mobile.jp/maimai-mobile/img/Music/",
        "https://maimai.sakura.party/maimai-mobile/img/Music/",
        "https://maimai.wmflabs.com/maimai-mobile/img/Music/",
        "https://maimai.sega.jp/maimai-mobile/img/Music/",
    )

    fun candidates(songId: Int, imageUrl: String): List<String> {
        val list = mutableListOf<String>()

        fun push(url: String) {
            if (url.isNotBlank() && url !in list) list.add(url)
        }

        // 1. 自定义图床
        val custom = SpUtil.getCoverSource()
        if (custom.isNotBlank() && imageUrl.isNotBlank()) {
            push(custom.trimEnd('/') + "/" + imageUrl)
        }

        // 2. 水鱼 CDN（仅 DX 5 位编号会命中，标准曲直接跳过省一次必败请求）
        if (songId >= 10000) {
            push(MaimaiDataClient.DIVING_FISH_COVER_URL + songId + ".png")
        }

        // 3. 落雪 lxns jacket（官方编号：DX 曲 df id 需减 10000；标准曲原样）
        val officialId = if (songId >= 10000) songId - 10000 else songId
        if (officialId > 0) {
            push("https://assets2.lxns.net/maimai/jacket/$officialId.png")
        }

        // 4. EasyMai 封面 OSS（df 数据 id 原样，.jpg；DX 曲的补充源）
        push("https://oss.lista233.cn/cover/cover/$songId.jpg")

        // 5. 官网图床 + 镜像（DX 新曲靠它们兜底）
        if (imageUrl.isNotBlank()) {
            MIRRORS.forEach { base -> push(base.trimEnd('/') + "/" + imageUrl) }
        }
        return CoverHits.order(songId, list)
    }

    /**
     * 中二节奏封面候选链（2026-10 实测）：
     * 1. 落雪 lxns `assets2.lxns.net/chunithm/jacket/{id}.png`（id **不补零**）——
     *    主源，24 首抽样 23 命中，覆盖最稳；
     * 2. 水鱼 `covers/chunithm/{id:05d}.jpg`——兜底（实测大面积 404，仅保留候选）。
     * 全部失败由调用方退化为占位。
     */
    fun chuniCandidates(songId: Int): List<String> = CoverHits.order(
        songId,
        listOf(
            "https://assets2.lxns.net/chunithm/jacket/$songId.png",
            MaimaiDataClient.DIVING_FISH_COVER_URL +
                "chunithm/" + String.format("%05d", songId) + ".jpg",
        ),
    )
}

/**
 * 进程内封面「命中 / 失败」记忆。
 *
 * 背景：`CoverFrame` 用 `SubcomposeAsyncImage` 的 error slot 串行重放候选链，
 * 而 Coil **只缓存 2xx** 响应——前置候选的 404 / 超时永远不进磁盘缓存。
 * 于是每次进列表、滚动回滚、切 Tab 回来都要把必然失败的源重新请求一遍，
 * 视觉上就是「缓存失效、每次等 1-2s」。实测磁盘缓存本身是正常的
 * （`KibaApplication` 已配 256MB DiskCache + `respectCacheHeaders(false)`）。
 *
 * - [hit]：songId → 上次成功的 URL，直接提到候选链第一位（稳态只发 1 个请求）；
 * - [isFailed]：URL → 失败时间，10 分钟内跳过，避免逐次重试同一批死源；
 *   TTL 过期后自动重试，不会把临时网络故障永久固化。
 * 全部为进程内状态，App 重启后重新探一次。
 */
internal object CoverHits {
    private const val MAX_HITS = 2048
    private const val FAIL_TTL_MS = 10 * 60 * 1000L

    /** 访问序 LRU：常用的封面长期留在表里，避免无限增长。 */
    private val hits = object : LinkedHashMap<Int, String>(MAX_HITS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, String>?,
        ): Boolean = size > MAX_HITS
    }
    private val failedAt = HashMap<String, Long>()

    @Synchronized
    fun hit(songId: Int): String? = hits[songId]

    @Synchronized
    fun recordSuccess(songId: Int, url: String) {
        hits[songId] = url
        failedAt.remove(url)
    }

    @Synchronized
    fun isFailed(url: String): Boolean {
        val at = failedAt[url] ?: return false
        if (SystemClock.elapsedRealtime() - at > FAIL_TTL_MS) {
            failedAt.remove(url)
            return false
        }
        return true
    }

    @Synchronized
    fun recordFailure(url: String) {
        failedAt[url] = SystemClock.elapsedRealtime()
    }

    /** 命中源前置 + 过滤短时失败源；命中源若已不在链里（如改了图床）则忽略。 */
    @Synchronized
    fun order(songId: Int, list: List<String>): List<String> {
        val usable = list.filterNot { isFailed(it) }
        val hit = hits[songId]
        return if (hit != null && hit in usable) {
            listOf(hit) + usable.filter { it != hit }
        } else {
            usable
        }
    }
}
