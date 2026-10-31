package com.kira.kiba.core.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.data.NetHtmlParser.NetScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 把 [NetScore] 映射成落雪个人 API 的 Score JSON。
 *
 * ## 为什么需要这一层
 * NET 页面里**只有曲名，没有曲目 ID**：中二的成绩页根本不暴露 id，舞萌虽然有
 * `music_name_block`，但同一首曲子的标准 / DX 两个谱面共用一个 id，页面也不给。
 * 落雪的写入接口要的是 `id`，所以必须用本地曲库把「曲名（+ 谱面类型）」翻回 id。
 *
 * 曲库来源就是 App 已经同步好的 Room 表：舞萌 `song_data`、中二 `chuni_song`。
 * 曲库没同步过 → 索引为空 → 所有成绩都会被跳过，日志里会明确提示。
 *
 * ## 曲目 ID 规则
 * 落雪文档：同一首曲目的标准 / DX 谱面 id 一致，**不存在大于 10000 的 id，
 * 如出现请先对 10000 取余**。水鱼曲库里 DX 谱面的 id 常常是 `10000 + sdId`，
 * 所以这里统一 `% 10000` 之后再发。
 */
object LxnsScoreMapper {

    /** 曲名索引。舞萌要带谱面类型（同名 SD / DX 是两行）；中二只有曲名。 */
    data class SongIndex(
        val maimai: Map<String, Int>,
        val chuni: Map<String, Int>,
    ) {
        val isEmpty: Boolean get() = maimai.isEmpty() && chuni.isEmpty()
    }

    /** 一次映射的结果：能发出去的载荷 + 映射失败的统计（用于日志）。 */
    data class BuildResult(
        val payload: JsonObject,
        val mapped: Int,
        val skippedTitles: List<String>,
        /** 中二 WORLD'S END 之类不支持的成绩条数。 */
        val unsupported: Int,
    ) {
        val skipped: Int get() = skippedTitles.size
        val isNotEmpty: Boolean get() = mapped > 0
    }

    /** 索引缓存 60 秒：一次抓包会话会连续上传 5～7 页，没必要每次全表扫。 */
    @Volatile
    private var cachedIndex: SongIndex? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_TTL_MS = 60_000L

    /**
     * 读取曲名索引（带缓存）。曲库没同步时返回空索引，调用方据此给用户明确提示。
     *
     * 数据库访问用 [runBlocking] —— 本类只在上传线程（IO）里被调用，
     * 不存在主线程阻塞问题。
     */
    fun index(forceReload: Boolean = false): SongIndex {
        val now = System.currentTimeMillis()
        val hit = cachedIndex
        if (!forceReload && hit != null && now - cachedAt < CACHE_TTL_MS) return hit

        val built = runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val db = AppDataBase.getInstance()
                    val maimai = HashMap<String, Int>(4096)
                    db.songDao().getAllSongsList().forEach { song ->
                        val type = song.type.lowercase()
                        val normalized = when {
                            type.contains("dx") -> TYPE_DX
                            type.contains("sd") || type.contains("standard") -> TYPE_STANDARD
                            else -> TYPE_DX
                        }
                        // 同一 (曲名, 类型) 出现多次时保留 id 较小的标准谱面
                        val key = maimaiKey(song.title, normalized)
                        val id = song.id % 10000
                        val old = maimai[key]
                        if (old == null || id < old) maimai[key] = id
                    }
                    val chuni = HashMap<String, Int>(2048)
                    db.chuniSongWithChartDao().getAllChuniSongsList().forEach { song ->
                        chuni[song.title] = song.id % 10000
                    }
                    SongIndex(maimai, chuni)
                }
            }
        }.getOrElse { SongIndex(emptyMap(), emptyMap()) }

        cachedIndex = built
        cachedAt = now
        return built
    }

    /** 曲库重新导入后调用，避免 60 秒内继续用旧曲名。 */
    fun invalidate() {
        cachedIndex = null
        cachedAt = 0L
    }

    /**
     * 构造舞萌上传载荷。
     *
     * 落雪 Score 里 `id` / `type` / `level_index` 必填，`achievements` 是达成率（百分比数值），
     * `fc` / `fs` 可空。
     */
    fun buildMaimaiPayload(scores: List<NetScore>, index: SongIndex = index()): BuildResult {
        val arr = JsonArray()
        val missed = ArrayList<String>()
        var unsupported = 0
        for (score in scores) {
            if (score.songType == null) {
                unsupported++
                continue
            }
            val id = index.maimai[maimaiKey(score.title, score.songType)]
            if (id == null) {
                missed.add(score.title)
                continue
            }
            arr.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("type", score.songType)
                addProperty("level_index", score.levelIndex)
                addProperty("achievements", score.achievements)
                score.fc?.let { addProperty("fc", it) }
                score.fs?.let { addProperty("fs", it) }
                if (score.dxScore > 0) addProperty("dx_score", score.dxScore)
            })
        }
        return BuildResult(
            payload = JsonObject().apply { add("scores", arr) },
            mapped = arr.size(),
            skippedTitles = missed,
            unsupported = unsupported,
        )
    }

    /**
     * 构造中二上传载荷。
     *
     * ## 关于 `clear`
     * 中二 NET 的**列表页不显示 CLEAR 灯**（那在单曲详情页里），所以本地解析拿不到
     * HARD / ABSOLUTE / BRAVE / CATASTROPHY 这些真实值。这里按官方记分规则退化：
     * 500000 及以上按 `clear`，以下按 `failed`。
     *
     * 这**只会影响牌子进度**，分数与 Rating 完全准确；宁可退化也不要整条不传。
     * `full_combo` 只在页面确实有图标时才写，避免把玩家已有的 AJ 记成空。
     */
    fun buildChuniPayload(scores: List<NetScore>, index: SongIndex = index()): BuildResult {
        val arr = JsonArray()
        val missed = ArrayList<String>()
        var unsupported = 0
        for (score in scores) {
            // level_index 5 = WORLD'S END：页面上只有原曲名 + 星级，没有可直接引用的谱面 id，
            // 落雪那边是独立 difficulty 条目，乱猜会把成绩写到错的谱面上。
            if (score.levelIndex > 4) {
                unsupported++
                continue
            }
            if (score.score <= 0) {
                unsupported++
                continue
            }
            val id = index.chuni[score.title]
            if (id == null) {
                missed.add(score.title)
                continue
            }
            arr.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("level_index", score.levelIndex)
                addProperty("score", score.score)
                addProperty("clear", if (score.score >= 500_000) "clear" else "failed")
                score.fc?.let { addProperty("full_combo", it) }
            })
        }
        return BuildResult(
            payload = JsonObject().apply { add("scores", arr) },
            mapped = arr.size(),
            skippedTitles = missed,
            unsupported = unsupported,
        )
    }

    // ==================== 内部 ====================

    private const val TYPE_STANDARD = "standard"
    private const val TYPE_DX = "dx"

    /** 用 `\u0000` 拼键，避免曲名里刚好有分隔符导致串味。 */
    internal fun maimaiKey(title: String, type: String): String = "$title\u0000$type"
}
