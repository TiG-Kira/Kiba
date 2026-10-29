package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniRecordEntity
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
 * - **`rating` 是浮点**（0.0），不是整数；
 * - 顶层只有 `username`，**没有 `nickname`**（有成绩时才有）；
 * - `records` 是对象，含 `best` 与 `r10`；账号无中二成绩时 `best` 为空数组 ——
 *   这是「同步成功但无数据」，**不是错误**，UI 需区分展示。
 * `best[]` 字段：cid(字符串) / ds / fc(fullcombo|alljustice) / level / level_index(0..5，
 * 5 = World's End) / level_label / mid / ra / score(上限 1010000) / title。
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
        /** @param empty 同步成功但账号无任何中二成绩（best 为空数组）。 */
        data class Done(val count: Int, val rating: Int, val empty: Boolean) : SyncState()
        data class Failed(val message: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── 本地缓存读取（SpUtil 持久化，进程重启后仍可展示） ──

    fun getLocalRating(): Int = SpUtil.getChuniRating()

    fun getLocalNickname(): String = SpUtil.getChuniNickname()

    fun getLocalRecordCount(): Int = SpUtil.getChuniRecordCount()

    fun hasLocalData(): Boolean = SpUtil.getChuniRecordCount() > 0

    /** 本进程是否已拉过一次成绩（避免每次切 Tab 都打接口；顶栏手动刷新不受限）。 */
    @Volatile
    private var fetchedThisSession = false

    fun shouldAutoRefresh(): Boolean = !fetchedThisSession

    /**
     * 拉取中二成绩：jwt Cookie 调 /player/records → 手工解析 → replaceAllChuniRecord 落库
     * → rating/昵称/条数写 SpUtil。
     *
     * 账号无中二成绩（best 为空）时**按成功处理**（count=0，empty=true），由 UI
     * 展示「暂无中二节奏成绩数据」；未登录 jwt 返回明确的「换登录方式」提示。
     */
    suspend fun refresh() {
        if (_state.value is SyncState.Syncing) return
        val jwt = SpUtil.getDivingFishJwt()
        if (jwt.isBlank()) {
            _state.value = SyncState.Failed("水鱼 OAuth 暂不支持中二节奏，请使用账号密码登录")
            return
        }
        _state.value = SyncState.Syncing
        // 关键：落库（Room）**必须**和 IO 一起待在 Dispatchers.IO 里。
        // 之前把 replaceAllChuniRecord 写在 withContext 返回后的 onSuccess 里 —— 那已经在
        // 主线程，Room 会抛「Cannot access database on the main thread」，
        // 而该处又在 runCatching 之外 → 未捕获异常直接把「同步中二成绩」打成闪退。
        val outcome: Result<Parsed> = withContext(Dispatchers.IO) {
            runCatching {
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
                val parsed = parseRecords(text)

                val ok = AppDataBase.getInstance().chuniRecordDao()
                    .replaceAllChuniRecord(parsed.records)
                if (!ok) throw IllegalStateException("成绩入库失败")

                SpUtil.setChuniRating(parsed.rating)
                SpUtil.setChuniNickname(parsed.nickname)
                SpUtil.setChuniRecordCount(parsed.records.size)
                parsed
            }
        }
        outcome.onSuccess { parsed ->
            fetchedThisSession = true
            Log.i(TAG, "chuni records synced: ${parsed.records.size}, rating=${parsed.rating}")
            _state.value = SyncState.Done(
                count = parsed.records.size,
                rating = parsed.rating,
                empty = parsed.records.isEmpty(),
            )
        }.onFailure { e ->
            Log.w(TAG, "chuni records refresh failed", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "成绩同步失败"
            _state.value = SyncState.Failed(
                if (msg.length > 200) msg.take(200) + "…" else msg,
            )
        }
    }

    private data class Parsed(
        val rating: Int,
        val nickname: String,
        val records: List<ChuniRecordEntity>,
    )

    /**
     * 手工解析响应：兼容 `records` 为对象（{best, r10}）与扁平数组两种形态。
     * 取值全部走 [optString]/[optInt]/[optDouble]，缺字段、浮点/整数差异都不会炸。
     */
    private fun parseRecords(text: String): Parsed {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        val obj = root?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("成绩响应格式异常（非 JSON 对象）")

        // rating 实测为浮点（0.0 / 16.16），按 double 读再取整，避免类型漂移报错
        val rating = obj.optDouble("rating").toInt()
        // 无成绩时只有 username，有成绩时才有 nickname
        val nickname = obj.optString("nickname").ifBlank { obj.optString("username") }

        val bestObjects = mutableListOf<JsonObject>()
        val recordsNode = obj.get("records")
        when {
            recordsNode == null || recordsNode.isJsonNull -> Unit
            recordsNode.isJsonArray -> {
                recordsNode.asJsonArray.forEach { elem ->
                    if (elem.isJsonObject) bestObjects.add(elem.asJsonObject)
                }
            }
            recordsNode.isJsonObject -> {
                val box = recordsNode.asJsonObject
                // r10（最近游玩）自 2026 版本后恒空，一并并入以容错
                listOf("best", "r10").forEach { key ->
                    box.get(key)
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.forEach { elem ->
                            if (elem.isJsonObject) bestObjects.add(elem.asJsonObject)
                        }
                }
            }
        }

        val entities = bestObjects.mapNotNull { o ->
            val mid = o.optInt("mid")
            if (mid <= 0) {
                null
            } else {
                ChuniRecordEntity(
                    id = 0,
                    songId = mid,
                    cid = o.optString("cid").toIntOrNull() ?: 0,
                    title = o.optString("title"),
                    levelIndex = o.optInt("level_index"),
                    levelLabel = o.optString("level_label"),
                    level = o.optString("level"),
                    score = o.optInt("score"),
                    fc = o.optString("fc"),
                    ra = o.optDouble("ra").toInt(),
                    ds = o.optDouble("ds"),
                    isRecent = false,
                )
            }
        }
        return Parsed(rating = rating, nickname = nickname, records = entities)
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
