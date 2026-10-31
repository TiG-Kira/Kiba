package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 中二节奏成绩同步（水鱼 chunithmprober，**账号密码 jwt 通道**）。
 *
 * 水鱼 OAuth 的 scope 仅覆盖舞萌（prober.*），**不支持中二节奏**，故中二成绩
 * 只能走原版登录方式：POST /maimaidxprober/login 换长效 `jwt_token`（舞萌登录
 * 时已由 [com.kira.kiba.core.account.DivingFishAccount.loginWithPassword] 持久化，
 * 两游戏共用同一水鱼账号），再以 `Cookie: jwt_token=...` 调
 * `GET /chunithmprober/player/records`。
 *
 * 响应真实形态（以官方免鉴权联调 `player/test_data` 实测为准）：
 * `{"rating":0.0,"records":{"best":[],"r10":[]},"username":"DivingFish"}`
 * - **`rating` 是浮点**（0.0），不是整数；**且这个服务端值不可信**（见下）；
 * - 顶层只有 `username`，**没有 `nickname`**（有成绩时才有）；
 * - `records` 是对象，含 `best` 与 `r10`；账号无中二成绩时 `best` 为空数组 ——
 *   这是「同步成功但无数据」，**不是错误**，UI 需区分展示。
 * `best[]` 字段：cid(字符串) / ds / fc(fullcombo|alljustice) / level / level_index(0..5，
 * 5 = World's End) / level_label / mid / ra(浮点) / score(上限 1010000) / title。
 *
 * ## Rating 由本地自算，不取服务端 `rating`
 * 2026 版本后水鱼 `records.r10` 恒为空数组，其服务端的 Rating 是按
 * 「Best 30 均值 + Recent 10 均值」再除以 2 得到，Recent 一空就整体腰斩 ——
 * 玩家机台 14.x，接口只返回 7.x。本类改为用本地成绩按官方口径重算，见 [ChuniRating]。
 *
 * **解析一律走 [JsonParser] 手工取值，不用 Gson 反射（`fromJson` + DTO）**：
 * 反射实例化在 R8 优化后会翻车（本项目已知坑：Gson 反射可达的类/签名被删或改名，
 * 实测抛 "Unable to create instance of class ... Registering an InstanceCreator"）；
 * 且 `rating` 的浮点/整数差异用反射 DTO 无法兼容。手工解析同时容忍缺字段与类型漂移。
 */
object ChuniRecordSync {

    private const val TAG = "ChuniRecordSync"

    private const val RECORDS_URL =
        "https://www.diving-fish.com/api/chunithmprober/player/records"

    sealed class SyncState {
        data object Idle : SyncState()
        data object Syncing : SyncState()

        /**
         * @param count 入库的成绩条数（Best + Recent）。
         * @param rating 本地按官方口径算出的 Rating（两位小数）。
         * @param empty 同步成功但账号无任何中二成绩（best 为空数组）。
         * @param recentCount Recent 枠条数；0 表示水鱼未返回 r10，Rating 按 Best 30 口径兜底。
         */
        data class Done(
            val count: Int,
            val rating: Double,
            val empty: Boolean,
            val recentCount: Int,
        ) : SyncState()

        data class Failed(val message: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── 本地缓存读取（SpUtil 持久化，进程重启后仍可展示） ──

    fun getLocalRating(): Double = SpUtil.getChuniRating()

    fun getLocalNickname(): String = SpUtil.getChuniNickname()

    fun getLocalRecordCount(): Int = SpUtil.getChuniRecordCount()

    /** 水鱼是否返回了 Recent 枠（r10）；0 = 缺失，Rating 走 Best 30 兜底口径。 */
    fun getLocalRecentCount(): Int = SpUtil.getChuniRecentCount()

    fun hasLocalData(): Boolean = SpUtil.getChuniRecordCount() > 0

    /** 本进程是否已拉过一次成绩（避免每次切 Tab 都打接口；顶栏手动刷新不受限）。 */
    @Volatile
    private var fetchedThisSession = false

    fun shouldAutoRefresh(): Boolean = !fetchedThisSession

    /**
     * 拉取中二成绩。
     *
     * **按数据源分发**（[com.kira.kiba.core.model.ScoreSource]，两游戏各自独立）：
     * - 落雪 LXNS → [fetchFromLxns]：全量成绩 + 官方分池（`new_bests` = 当前版本 B20）。
     *   这是唯一能填满当前版本枠的来源，水鱼根本不给这份数据。
     * - 水鱼 → [fetchFromDivingFish]：jwt Cookie 调 `player/records`，只回约 30 条 Top30。
     *
     * 两条路都落到同一个 [commit]：入库 → [ChuniStats] 现算 → 写昵称/条数。
     * 落库（Room）与网络请求**必须整体待在 Dispatchers.IO**（本项目已知坑：主线程访问数据库闪退）。
     */
    suspend fun refresh() {
        if (_state.value is SyncState.Syncing) return
        _state.value = SyncState.Syncing
        val source = SpUtil.getScoreSource(GameType.CHUNITHM)
        val outcome: Result<Parsed> = withContext(Dispatchers.IO) {
            runCatching {
                val parsed = when (source) {
                    ScoreSource.LXNS -> fetchFromLxns()
                    ScoreSource.WATER_FISH -> fetchFromDivingFish()
                }
                commit(parsed)
            }
        }
        outcome.onSuccess { parsed ->
            fetchedThisSession = true
            Log.i(
                TAG,
                "chuni records synced (${source.key}): ${parsed.records.size}, " +
                    "rating=${parsed.rating}, recent=${parsed.recentCount}",
            )
            _state.value = SyncState.Done(
                count = parsed.records.size,
                rating = SpUtil.getChuniRating(),
                empty = parsed.records.isEmpty(),
                recentCount = parsed.recentCount,
            )
        }.onFailure { e ->
            Log.w(TAG, "chuni records refresh failed", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "成绩同步失败"
            _state.value = SyncState.Failed(
                if (msg.length > 200) msg.take(200) + "…" else msg,
            )
        }
    }

    /** 落雪源：全量成绩 + 官方分池，见 [ChuniLxnsSync]。 */
    private suspend fun fetchFromLxns(): Parsed {
        val result = ChuniLxnsSync.fetch()
        return Parsed(
            rating = 0.0,
            serverRating = result.serverRating,
            nickname = result.nickname,
            records = result.records,
            recentCount = 0,
        )
    }

    /** 水鱼源：jwt Cookie 拉 Top30。 */
    private fun fetchFromDivingFish(): Parsed {
        val jwt = SpUtil.getDivingFishJwt()
        if (jwt.isBlank()) {
            throw IllegalStateException("水鱼登录方式不支持中二成绩拉取，请改用账号密码登录或切换落雪源")
        }
        val request = Request.Builder()
            .url(RECORDS_URL)
            .header("Cookie", jwt)
            .build()
        val text = client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 401 / 403 等多半是登录态失效，带上响应体片段便于定位
                throw IllegalStateException("HTTP ${resp.code}: ${body.take(120)}")
            }
            body
        }
        return parseRecords(text)
    }

    /**
     * 入库 + 统计 + 缓存回写（两条数据源共用）。
     *
     * Rating / 两个枠统一由 [ChuniStats] 从库里现算 —— 同步时算好写死进 Sp 的老做法，
     * 会在「版本标记晚到 / 曲库重导入」时永久失真（1.8.1 当前 TOP20 恒 0.00 就是这么来的）。
     */
    private suspend fun commit(parsed: Parsed): Parsed {
        val ok = AppDataBase.getInstance().chuniRecordDao()
            .replaceAllChuniRecord(parsed.records)
        if (!ok) throw IllegalStateException("成绩入库失败")

        val stats = ChuniStats.compute()
        ChuniStats.persist(stats)
        // 本地算不出（账号无成绩）时才退用服务端 rating 兜底
        if (stats.rating <= 0.0 && parsed.serverRating > 0.0) {
            SpUtil.setChuniRating(ChuniRating.truncate2(parsed.serverRating))
        }

        SpUtil.setChuniRecentCount(parsed.recentCount)
        SpUtil.setChuniNickname(parsed.nickname)
        SpUtil.setChuniRecordCount(parsed.records.size)
        return parsed
    }

    private data class Parsed(
        val rating: Double,
        val serverRating: Double,
        val nickname: String,
        val records: List<ChuniRecordEntity>,
        val recentCount: Int,
    )

    /**
     * 手工解析响应：兼容 `records` 为对象（{best, r10}）与扁平数组两种形态。
     * 取值全部走 [optString]/[optInt]/[optDouble]，缺字段、浮点/整数差异都不会炸。
     *
     * `best` 与 `r10` 分开收集：**r10 条目标记 isRecent = true**（Recent 枠），
     * 供 [ChuniRating] 按官方公式分别取 Best 30 / Recent 10 —— 混在一起会把
     * 最近一次手滑的低分算进历史最佳，Rating 直接失真。
     */
    private fun parseRecords(text: String): Parsed {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        val obj = root?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("成绩响应格式异常（非 JSON 对象）")

        // 服务端 rating 仅作兜底：r10 缺失时它会腰斩，本地能算就用本地的
        val serverRating = obj.optDouble("rating")
        // 无成绩时只有 username，有成绩时才有 nickname
        val nickname = obj.optString("nickname").ifBlank { obj.optString("username") }

        val bestList = mutableListOf<ChuniRecordEntity>()
        val recentList = mutableListOf<ChuniRecordEntity>()

        fun collect(node: JsonElement?, isRecent: Boolean) {
            val array = node?.takeIf { it.isJsonArray }?.asJsonArray ?: return
            val target = if (isRecent) recentList else bestList
            for (i in 0 until array.size()) {
                val elem = array.get(i)
                if (!elem.isJsonObject) continue
                parseRecord(elem.asJsonObject, isRecent)?.let { target.add(it) }
            }
        }

        val recordsNode = obj.get("records")
        when {
            recordsNode == null || recordsNode.isJsonNull -> Unit
            // 扁平数组形态：全是历史最佳
            recordsNode.isJsonArray -> collect(recordsNode, isRecent = false)
            recordsNode.isJsonObject -> {
                val box = recordsNode.asJsonObject
                collect(box.get("best"), isRecent = false)
                // r10（最近游玩）：2026 版本后水鱼侧恒空，空了就走 Best 30 兜底口径
                collect(box.get("r10"), isRecent = true)
            }
        }

        // Best 枠按官方口径是「谱面唯一」，防水鱼回传重复谱面（同一首多难度也要保留，
        // 故按 歌曲id + 难度 复合键去重，不用 Pair —— 避免 R8 把 kotlin.Pair 优化掉）
        val best = bestList.distinctBy { it.songId * 10 + it.levelIndex }

        // Rating 不在解析阶段计算：谱面 version 需查库，真正的 VERSE 起 /50 公式在 refresh() 内完成。
        return Parsed(
            rating = 0.0,
            serverRating = serverRating,
            nickname = nickname,
            records = best + recentList,
            recentCount = recentList.size,
        )
    }

    private fun parseRecord(o: JsonObject, isRecent: Boolean): ChuniRecordEntity? {
        val mid = o.optInt("mid")
        if (mid <= 0) return null
        return ChuniRecordEntity(
            id = 0,
            songId = mid,
            cid = o.optString("cid").toIntOrNull() ?: 0,
            title = o.optString("title"),
            levelIndex = o.optInt("level_index"),
            levelLabel = o.optString("level_label"),
            level = o.optString("level"),
            score = o.optInt("score"),
            fc = o.optString("fc"),
            // ra 是浮点（如 14.53），必须保留小数，否则 Rating 会被整体截断偏低
            ra = o.optDouble("ra"),
            ds = o.optDouble("ds"),
            isRecent = isRecent,
        )
    }

    // ── JsonObject 安全取值 ──

    private fun JsonObject.optString(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }

    private fun JsonObject.optInt(name: String): Int {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0
        return runCatching { el.asInt }.getOrDefault(0)
    }

    private fun JsonObject.optDouble(name: String): Double {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0.0
        return runCatching { el.asDouble }.getOrDefault(0.0)
    }
}
