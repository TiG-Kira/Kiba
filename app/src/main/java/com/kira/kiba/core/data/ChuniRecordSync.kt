package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
 * 响应结构（官方文档口径）：
 * `{username, nickname, rating, records: {best: [...], r10: []}}`；
 * `best[]` 字段：cid(字符串) / ds / fc(fullcombo|alljustice) / level / level_index(0..5，
 * 5 = World's End) / level_label / mid / ra / score(上限 1010000) / title。
 * `r10`（最近游玩）自中二 2026 版本后恒为空数组，Rating 直接采用响应顶层官方值。
 */
object ChuniRecordSync {

    private const val TAG = "ChuniRecordSync"

    private const val RECORDS_URL =
        "https://www.diving-fish.com/api/chunithmprober/player/records"

    sealed class SyncState {
        data object Idle : SyncState()
        data object Syncing : SyncState()
        data class Done(val count: Int, val rating: Int) : SyncState()
        data class Failed(val message: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

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
     * 拉取中二成绩：jwt Cookie 调 /player/records → 解析 → replaceAllChuniRecord 落库
     * → rating/nickname/条数写 SpUtil。未登录 jwt 时返回明确的「换登录方式」提示。
     */
    suspend fun refresh() {
        if (_state.value is SyncState.Syncing) return
        val jwt = SpUtil.getDivingFishJwt()
        if (jwt.isBlank()) {
            _state.value = SyncState.Failed("水鱼 OAuth 暂不支持中二节奏，请使用账号密码登录")
            return
        }
        _state.value = SyncState.Syncing
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(RECORDS_URL)
                    .header("Cookie", jwt)
                    .build()
                val text = client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}: ${body.take(120)}")
                    }
                    body
                }
                parseRecords(text)
            }
        }
        result.onSuccess { parsed ->
            val ok = AppDataBase.getInstance().chuniRecordDao()
                .replaceAllChuniRecord(parsed.records)
            if (!ok) {
                _state.value = SyncState.Failed("成绩入库失败")
                return
            }
            SpUtil.setChuniRating(parsed.rating)
            SpUtil.setChuniNickname(parsed.nickname)
            SpUtil.setChuniRecordCount(parsed.records.size)
            fetchedThisSession = true
            Log.i(TAG, "chuni records synced: ${parsed.records.size}, rating=${parsed.rating}")
            _state.value = SyncState.Done(parsed.records.size, parsed.rating)
        }.onFailure { e ->
            Log.w(TAG, "chuni records refresh failed", e)
            _state.value = SyncState.Failed(e.message ?: "成绩同步失败")
        }
    }

    // ── 网络响应 DTO（字段可空 + 默认值，容忍服务端增删） ──

    private data class RawChuniRecord(
        val mid: Int = 0,
        val cid: String = "",
        val title: String = "",
        @SerializedName("level_index") val levelIndex: Int = 0,
        @SerializedName("level_label") val levelLabel: String = "",
        val level: String = "",
        val score: Int = 0,
        val fc: String = "",
        val ra: Double = 0.0,
        val ds: Double = 0.0,
    )

    private data class ChuniRecordsBox(
        val best: List<RawChuniRecord>? = null,
        val r10: List<RawChuniRecord>? = null,
    )

    private data class ChuniRecordsEnvelope(
        val username: String? = null,
        val nickname: String? = null,
        val rating: Int? = null,
        val records: ChuniRecordsBox? = null,
    )

    private data class Parsed(
        val rating: Int,
        val nickname: String,
        val records: List<ChuniRecordEntity>,
    )

    /** 兼容两种返回形态：{records:{best:[...]}} 或 records 直接是数组。 */
    private fun parseRecords(text: String): Parsed {
        val trimmed = text.trimStart()
        val best: List<RawChuniRecord>
        val envelope = if (trimmed.startsWith("{")) {
            gson.fromJson(trimmed, ChuniRecordsEnvelope::class.java)
        } else null
        best = if (envelope?.records != null) {
            // records 是对象：取 best（r10 自 2026 版本后恒空，一并并入以容错）
            (envelope.records.best.orEmpty() + envelope.records.r10.orEmpty())
        } else {
            // records 是扁平数组（旧版形态）
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, RawChuniRecord::class.java,
            ).type
            gson.fromJson<List<RawChuniRecord>>(
                com.google.gson.JsonParser.parseString(trimmed)
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("records")
                    ?.takeIf { it.isJsonArray }?.toString() ?: "[]",
                type,
            )
        }
        val rating = envelope?.rating ?: 0
        val nickname = envelope?.nickname.orEmpty()
        val entities = best.map { r ->
            ChuniRecordEntity(
                id = 0,
                songId = r.mid,
                cid = r.cid.toIntOrNull() ?: 0,
                title = r.title,
                levelIndex = r.levelIndex,
                levelLabel = r.levelLabel,
                level = r.level,
                score = r.score,
                fc = r.fc,
                ra = r.ra.toInt(),
                ds = r.ds,
                isRecent = false,
            )
        }.filter { it.songId > 0 }
        return Parsed(rating = rating, nickname = nickname, records = entities)
    }
}
