package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.network.LxnsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 中二节奏成绩同步（落雪 LXNS 个人 API）。
 *
 * ## 为什么水鱼源填不满「当前版本 B20」
 * 水鱼 `chunithmprober/player/records` 只回约 30 条**去重 Best**（官方 Top30 口径）。
 * 当前版本新曲只要没挤进你的 Top30，响应里就根本没有这条成绩 —— 本地再怎么改版本判定
 * 也变不出来，B20 只能长期空着。这不是算错，是数据源缺数据。
 *
 * 落雪 `GET /user/chunithm/player/bests` 直接返回**官方分池**：
 * - `bests` → 旧版本 Best 30
 * - `selections` → 候选（不算分池，忽略）
 * - `new_bests` → **当前版本 Best 20** ← 这正是我们缺的那份
 *
 * ## 取数策略
 * 1. `/user/chunithm/player` 拿昵称 / 服务端 Rating / 好友码；
 * 2. `/user/chunithm/player/bests` 拿官方分池（带 `rating` 单曲值，权威）；
 * 3. `/user/chunithm/player/scores` 拿全量成绩（可能是不含 rating 的 SimpleScore，
 *    此时用 [ChuniRating.raOf] 按分数 + 定数本地补算）。
 *
 * 合并规则：以 `(song_id, level_index)` 为键，**先放全量、再用分池覆盖**，
 * 这样分池里那些一定带 rating 的条目优先，同时保留全量里的其它谱面。
 *
 * 失败一律抛异常，由 [ChuniRecordSync] 统一包装成 UI 状态；本类不直接写 SpUtil 的 Rating
 * （统一走 [ChuniStats] 现算，避免再次出现「同步时算好写死、之后永久失真」的老问题）。
 */
object ChuniLxnsSync {

    private const val TAG = "ChuniLxnsSync"

    private const val PATH_PLAYER = "/user/chunithm/player"
    private const val PATH_SCORES = "/user/chunithm/player/scores"
    private const val PATH_BESTS = "/user/chunithm/player/bests"

    /** 落雪返回的单条成绩（内部结构，字段缺失一律给默认值）。 */
    private data class LxnsScore(
        val songId: Int,
        val title: String,
        val levelIndex: Int,
        val levelLabel: String,
        val score: Int,
        val rating: Double,
        val fc: String,
        /** 来自 bests.new_bests —— 官方认定的「当前版本枠」成员。 */
        val isCurrentVersion: Boolean,
    )

    /** 一次拉取的完整结果。 */
    data class Result(
        val nickname: String,
        val serverRating: Double,
        val records: List<ChuniRecordEntity>,
        /** 官方分池是否可用（`new_bests` 拿到了）。false 时由 [ChuniStats] 退回版本推断。 */
        val hasOfficialSplit: Boolean,
        /** 当前版本枠条数（0 = 玩家确实还没打当前版本曲目，不是取数失败）。 */
        val currentCount: Int,
    )

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        if (!LxnsAccount.isBound()) {
            throw IllegalStateException("尚未绑定落雪 API Key，请到「设置 → 查分器数据源」绑定")
        }

        // 1) 玩家信息（昵称 / 服务端 Rating）；失败不致命，昵称留空由 UI 兜底
        val player = runCatching { LxnsApi.getObject(PATH_PLAYER) }.getOrNull()
        val nickname = player?.str("name").orEmpty()
        val serverRating = player?.dbl("rating") ?: 0.0

        // 2) 官方分池：bests + new_bests（个人 API）。
        //    1.8.4 去掉了原来「退回开发者 API /chunithm/player/{fc}/bests」的兜底：
        //    落雪明确要求**个人密钥不能用于开发者 API**，那条路径只会拿到 401，
        //    属于无效重试。真的拿不到分池时交给 [com.kira.kiba.core.data.ChuniStats]
        //    按曲库 version 推断（hasOfficialSplit=false），不影响成绩入库。
        val bestsRoot = runCatching { LxnsApi.getObject(PATH_BESTS) }
            .onFailure { Log.i(TAG, "lxns personal bests unavailable: ${it.message}") }
            .getOrNull()
        val currentKeys = mutableSetOf<Int>()
        val poolScores = mutableListOf<LxnsScore>()
        if (bestsRoot != null) {
            collectScores(bestsRoot, "bests", markCurrent = false)?.let(poolScores::addAll)
            collectScores(bestsRoot, "new_bests", markCurrent = true)?.let { list ->
                poolScores.addAll(list)
                list.forEach { currentKeys.add(recordKey(it.songId, it.levelIndex)) }
            }
        }

        // 3) 全量成绩（详情页/收藏页需要看到所有谱面，而不只是 50 条）。
        //    兼容两种成功形态：顶层数组，或 {scores:[...]} 包裹。
        val allResult = runCatching { LxnsApi.get(PATH_SCORES) }
            .onFailure { Log.i(TAG, "lxns scores unavailable: ${it.message}") }
        val allScores: List<LxnsScore> = when (val raw = allResult.getOrNull()) {
            is JsonArray -> parseScoreArray(raw, markCurrent = false)
            is JsonObject -> (raw.get("scores") as? JsonArray)
                ?.let { parseScoreArray(it, markCurrent = false) }
                .orEmpty()
            else -> emptyList()
        }

        if (allScores.isEmpty() && poolScores.isEmpty()) {
            // 两个接口都「成功但为空」= 账号确实没有中二成绩，交给上层按成功处理；
            // 但若两个接口都**报错**，绝不能把当成本地「没有成绩」清空已有数据 ——
            // 这是会直接抹掉用户成绩的危险路径（网络抖动 / Key 失效都可能走到这里）。
            val allFailed = allResult.isFailure || allResult.getOrNull() == null
            if (allFailed && bestsRoot == null) {
                throw IllegalStateException(
                    "落雪没有返回中二成绩：${allResult.exceptionOrNull()?.message ?: "响应为空"}",
                )
            }
            return@withContext Result(
                nickname = nickname,
                serverRating = serverRating,
                records = emptyList(),
                hasOfficialSplit = bestsRoot != null,
                currentCount = 0,
            )
        }

        // 4) 合并：全量打底，分池覆盖（分池带权威 rating）
        val merged = LinkedHashMap<Int, LxnsScore>()
        allScores.forEach { merged[recordKey(it.songId, it.levelIndex)] = it }
        poolScores.forEach { merged[recordKey(it.songId, it.levelIndex)] = it }

        // 5) 曲库信息（定数 / 标题 / 难度标签）本地补齐；曲库没同步时不阻断，缺就缺
        val songs = runCatching {
            AppDataBase.getInstance().chuniSongWithChartDao().getAllChuniSongsList()
                .associateBy { it.id }
        }.getOrDefault(emptyMap())
        val charts = runCatching {
            AppDataBase.getInstance().chuniSongWithChartDao().getAllChuniChartsList()
                .associateBy { recordKey(it.songId, it.difficultyIndex) }
        }.getOrDefault(emptyMap())

        val records = merged.values.map { s ->
            val song: ChuniSongEntity? = songs[s.songId]
            val chart = charts[recordKey(s.songId, s.levelIndex)]
            val ds = chart?.ds ?: 0.0
            ChuniRecordEntity(
                songId = s.songId,
                // 落雪没有 cid（水鱼的谱面 id），合成一个稳定值仅供本地去重/展示
                cid = recordKey(s.songId, s.levelIndex),
                title = song?.title?.takeIf { it.isNotBlank() } ?: s.title,
                levelIndex = s.levelIndex,
                levelLabel = chart?.level ?: s.levelLabel,
                level = chart?.level ?: s.levelLabel,
                score = s.score,
                fc = s.fc,
                // 接口给了 rating 就用接口的；SimpleScore 没给时按官方公式本地补算
                ra = if (s.rating > 0.0) s.rating else ChuniRating.raOf(s.score, ds),
                ds = ds,
                isRecent = false,
                isCurrentVersion = s.isCurrentVersion,
            )
        }.sortedWith(compareByDescending<ChuniRecordEntity> { it.ra }.thenBy { it.songId })

        // 全表 ra 都是 0 只有两种可能：接口只给了 SimpleScore（无 rating/score）且本地曲库
        // 也没有定数可补算。此时入库会让 Rating 直接变 0，比保留旧数据更糟 —— 报错不清库。
        if (records.isNotEmpty() && records.all { it.ra <= 0.0 }) {
            throw IllegalStateException(
                "落雪返回的成绩缺少单曲 Rating / 分数，无法计算（可能数据尚未结算）。" +
                    "可先同步一次中二曲库，或暂时切回水鱼源。",
            )
        }

        Log.i(
            TAG,
            "lxns chuni fetched: total=${records.size}, pool=${poolScores.size}, " +
                "current=${currentKeys.size}, splitAvailable=${bestsRoot != null}",
        )
        Result(
            nickname = nickname,
            serverRating = serverRating,
            records = records,
            hasOfficialSplit = bestsRoot != null && currentKeys.isNotEmpty(),
            currentCount = currentKeys.size,
        )
    }

    // ── 解析 ──

    private fun collectScores(
        root: JsonObject,
        field: String,
        markCurrent: Boolean,
    ): List<LxnsScore>? {
        val arr = root.get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return parseScoreArray(arr, markCurrent)
    }

    private fun parseScoreArray(arr: JsonArray, markCurrent: Boolean): List<LxnsScore> {
        val out = ArrayList<LxnsScore>(arr.size())
        for (i in 0 until arr.size()) {
            val elem = arr.get(i)
            if (!elem.isJsonObject) continue
            val o = elem.asJsonObject
            val id = o.int("id")
            if (id <= 0) continue
            val levelIndex = o.int("level_index")
            if (levelIndex !in 0..ChuniDiffType.WORLD_END.ordinal) continue
            out.add(
                LxnsScore(
                    songId = id,
                    title = o.str("song_name"),
                    levelIndex = levelIndex,
                    levelLabel = o.str("level"),
                    score = o.int("score"),
                    rating = o.dbl("rating"),
                    fc = o.str("full_combo"),
                    isCurrentVersion = markCurrent,
                )
            )
        }
        return out
    }

    /** 与本地既有去重口径一致：歌曲 id * 10 + 难度索引。 */
    private fun recordKey(songId: Int, levelIndex: Int): Int = songId * 10 + levelIndex

    private fun JsonObject.int(name: String): Int {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0
        return runCatching { el.asInt }.getOrDefault(0)
    }

    private fun JsonObject.dbl(name: String): Double {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0.0
        return runCatching { el.asDouble }.getOrDefault(0.0)
    }

    private fun JsonObject.str(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }
}
