package com.kira.kiba.core.account

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kira.kiba.BuildConfig
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.repository.RecordRepository
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 查分器（水鱼 DivingFish）账户层 —— OAuth 设备码流程。
 *
 * 水鱼已弃用旧的开发者 token（能按 QQ 号读任意用户成绩、用户无法撤销）。
 * 现行流程（应用在 https://auth.diving-fish.com/apps 申请，接入方式「Bot/工具」）：
 *
 * 1. [startDeviceAuth]：POST /oauth/device_authorization 拿到 user_code + verification_uri；
 * 2. 用户用浏览器打开授权链接，点「同意授权」，页面显示一次性确认码；
 * 3. [finishDeviceAuth]：用户回填确认码，POST /oauth/token 完成绑定（授权关系存水鱼服务端）；
 * 4. [refreshRecords]：POST /oauth/token（on-behalf-of）换 5 分钟有效的 access_token，
 *    Bearer 调 /player/records 拉全量成绩、/query/player 拿官方 B50 与 Rating。
 *
 * 应用不保存任何用户口令；access_token 仅内存缓存，过期自动重换。
 */
object DivingFishAccount {

    private const val TAG = "DivingFishAccount"

    private const val AUTH_URL = "https://auth.diving-fish.com"
    private const val API_URL = "https://maimai.diving-fish.com/api/maimaidxprober"

    /** 与应用获批权限一致；写权限审核通过后可加 prober.records.write。 */
    private const val SCOPE = "openid profile prober.profile.read prober.records.read"

    private const val GRANT_CONFIRMATION_CODE =
        "urn:diving-fish:params:oauth:grant-type:confirmation-code"
    private const val GRANT_ON_BEHALF_OF =
        "urn:diving-fish:params:oauth:grant-type:on-behalf-of"

    /** token 提前 30s 失效，避免边界时刻 401。 */
    private const val TOKEN_EXPIRE_MARGIN_SEC = 30L

    data class AccountState(
        val bound: Boolean = false,
        val nickname: String = "",
        val rating: Int = 0,
        val additionalRating: Int = 0,
        val recordCount: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
    )

    /** 设备授权结果：交给 UI 展示授权链接与确认码输入框。 */
    data class DeviceAuth(
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** 最近一次拉到的全量成绩（内存缓存，供 Agent 本地逻辑使用）。 */
    @Volatile
    private var recordsCache: List<RecordEntity> = emptyList()

    /** 官方 B50（sd 35 + dx 15，服务端算好，ra 之和即 rating）。 */
    @Volatile
    private var best50Cache: List<RecordEntity> = emptyList()

    // ── token 缓存（5 分钟有效，无 refresh，过期重换） ──
    private var cachedToken: String? = null
    private var tokenExpireAtMs: Long = 0L

    /** 当前挂起的设备授权 device_code（完成绑定前有效）。 */
    @Volatile
    private var pendingDeviceCode: String? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── 网络响应 DTO（字段可空 + 默认值，容忍服务端增删） ──

    private data class RawRecord(
        @SerializedName("song_id") val songId: Int = 0,
        @SerializedName("level_index") val levelIndex: Int = 0,
        @SerializedName("level_label") val levelLabel: String = "",
        @SerializedName("fc") val fc: String = "",
        @SerializedName("fs") val fs: String = "",
        @SerializedName("rate") val rate: String = "",
        @SerializedName("ra") val ra: Int = 0,
        @SerializedName("dx_score") val dxScore: Int = 0,
        @SerializedName("achievements") val achievements: Double = 0.0,
        @SerializedName("ds") val ds: Double = 0.0,
        @SerializedName("level") val level: String = "",
        @SerializedName("title") val title: String = "",
        @SerializedName("type") val type: String = "SD",
    )

    private data class RecordsEnvelope(
        val nickname: String? = null,
        val rating: Int? = null,
        @SerializedName("additional_rating") val additionalRating: Int? = null,
        val records: List<RawRecord>? = null,
    )

    private data class QueryPlayerResponse(
        val nickname: String? = null,
        val username: String? = null,
        val rating: Int? = null,
        @SerializedName("additional_rating") val additionalRating: Int? = null,
        val charts: QueryCharts? = null,
    )

    private data class QueryCharts(
        val sd: List<RawRecord>? = null,
        val dx: List<RawRecord>? = null,
    )

    private data class DeviceAuthResponse(
        @SerializedName("device_code") val deviceCode: String = "",
        @SerializedName("user_code") val userCode: String = "",
        @SerializedName("verification_uri") val verificationUri: String = "",
        @SerializedName("expires_in") val expiresIn: Int = 600,
        @SerializedName("interval") val interval: Int = 5,
    )

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String = "",
        @SerializedName("expires_in") val expiresIn: Int = 300,
    )

    // ── 配置 ──

    private val clientId: String get() = BuildConfig.DIVINGFISH_CLIENT_ID
    private val clientSecret: String get() = BuildConfig.DIVINGFISH_CLIENT_SECRET

    /** 应用未配置 client_id（local.properties 未填 / Secrets 未注入）时的统一提示。 */
    val configMissing: Boolean
        get() = clientId.isBlank() || clientSecret.isBlank()

    /**
     * subject_ref：水鱼服务端用它关联"这台设备的用户"。
     * 只外发 sha256(clientId:installId)，原始 ID 不出设备。
     */
    private fun subjectRef(): String {
        val raw = "${clientId}:${SpUtil.getDivingFishInstallId()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ── 对外状态操作 ──

    /** 启动恢复：已绑定则静默拉一次成绩；未绑定展示未登录引导。 */
    suspend fun restore() {
        if (SpUtil.isDivingFishBound()) {
            _state.value = AccountState(
                bound = true,
                nickname = SpUtil.getDivingFishOAuthNickname(),
                loading = true,
            )
            refreshRecords()
        } else {
            _state.value = AccountState()
        }
    }

    /**
     * 第一步：发起设备授权，返回授权链接给用户点开。
     */
    suspend fun startDeviceAuth(): Result<DeviceAuth> = withContext(Dispatchers.IO) {
        if (configMissing) {
            return@withContext Result.failure(
                IllegalStateException("未配置水鱼应用凭据（client_id / client_secret），请检查 local.properties"),
            )
        }
        runCatching {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("scope", SCOPE)
                .add("subject_ref", subjectRef())
                .add("binding_label", "Kiba")
                .add("handoff", "code")
                .build()
            val request = Request.Builder()
                .url("$AUTH_URL/oauth/device_authorization")
                .post(form)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("发起授权失败（HTTP ${response.code}）：$text")
                }
                gson.fromJson(text, DeviceAuthResponse::class.java)
            }
        }.mapCatching { dto ->
            pendingDeviceCode = dto.deviceCode
            DeviceAuth(
                userCode = dto.userCode,
                verificationUri = dto.verificationUri,
                expiresIn = dto.expiresIn,
                interval = dto.interval,
            )
        }
    }

    /**
     * 第二步：用户回填授权页显示的一次性确认码，完成绑定。
     */
    suspend fun finishDeviceAuth(confirmationCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val code = confirmationCode.trim()
            if (code.isBlank()) {
                return@withContext Result.failure(IllegalStateException("请输入授权页面显示的确认码"))
            }
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", GRANT_CONFIRMATION_CODE)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("confirmation_code", code)
                    .add("subject_ref", subjectRef())
                    .build()
                val request = Request.Builder()
                    .url("$AUTH_URL/oauth/token")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorCode = gson.fromJson(text, OAuthError::class.java)?.error.orEmpty()
                        val msg = when (errorCode) {
                            "subject_mismatch" -> "确认码与本次授权不匹配（可能转发了别人的链接），请重新发起绑定"
                            "invalid_grant" -> "确认码无效（不存在 / 已过期 / 已用过），请重新发起绑定"
                            "consent_required" -> "尚未完成授权，请先在浏览器里点「同意授权」"
                            else -> "绑定失败（HTTP ${response.code}）：$text"
                        }
                        throw IllegalStateException(msg)
                    }
                    gson.fromJson(text, TokenResponse::class.java)
                }
            }.onSuccess { token ->
                cacheToken(token.accessToken, token.expiresIn)
                pendingDeviceCode = null
                SpUtil.setDivingFishBound(true)
                _state.value = AccountState(bound = true, loading = true)
                refreshRecords()
            }.onFailure { e ->
                Log.w(TAG, "finishDeviceAuth failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "绑定失败")
            }.map { }
        }

    /** 解绑：清除本地状态与成绩缓存（远端授权可在 auth.diving-fish.com/apps 撤销）。 */
    fun unbind() {
        cachedToken = null
        tokenExpireAtMs = 0L
        pendingDeviceCode = null
        recordsCache = emptyList()
        best50Cache = emptyList()
        SpUtil.setDivingFishBound(false)
        SpUtil.saveDivingFishOAuthNickname("")
        _state.value = AccountState()
    }

    /**
     * 拉取成绩：换 token → 全量 records 落库 → /query/player 拿官方 B50 + Rating。
     * 任一步失败都降级为 error 状态并保留本地缓存，不崩溃。
     */
    suspend fun refreshRecords() {
        if (!SpUtil.isDivingFishBound()) {
            _state.value = _state.value.copy(loading = false, error = "尚未绑定查分器账户")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        val tokenResult = obtainToken()
        val token = tokenResult.getOrElse { e ->
            Log.w(TAG, "obtainToken failed", e)
            _state.value = _state.value.copy(
                loading = false,
                error = "获取授权失败：${e.message}（将展示本地缓存）",
            )
            return
        }

        // ① 全量成绩（/player/records，Bearer）——推分推荐 / 本地逻辑的数据源
        val recordsResult = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$API_URL/player/records")
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("成绩获取失败（HTTP ${response.code}）：$text")
                    }
                    parseRecords(text)
                }
            }
        }

        // ② 官方 B50 + Rating（/query/player，b50:true）——ra 之和即 rating，权威口径
        val b50Result = withContext(Dispatchers.IO) {
            runCatching {
                val json = """{"b50": true}"""
                val request = Request.Builder()
                    .url("$API_URL/query/player")
                    .header("Authorization", "Bearer $token")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("B50 获取失败（HTTP ${response.code}）：$text")
                    }
                    gson.fromJson(text, QueryPlayerResponse::class.java)
                }
            }
        }

        recordsResult.onSuccess { records ->
            RecordRepository.getInstance(
                AppDataBase.getInstance().recordDao()
            ).replaceAllRecord(records)
            recordsCache = records
        }.onFailure { e ->
            Log.w(TAG, "fetch records failed", e)
        }

        b50Result.onSuccess { parsed ->
            val b50 = buildList {
                parsed.charts?.sd?.let { addAll(it) }
                parsed.charts?.dx?.let { addAll(it) }
            }.map { it.toEntity() }
            best50Cache = b50
            val nickname = parsed.nickname ?: parsed.username.orEmpty()
            if (nickname.isNotBlank()) {
                SpUtil.saveDivingFishOAuthNickname(nickname)
            }
            _state.value = AccountState(
                bound = true,
                nickname = nickname.ifBlank { SpUtil.getDivingFishOAuthNickname() },
                rating = parsed.rating ?: recordsCache.sumOf { it.ra },
                additionalRating = parsed.additionalRating ?: 0,
                recordCount = recordsCache.size,
                loading = false,
                error = null,
            )
        }.onFailure { e ->
            Log.w(TAG, "fetch b50 failed", e)
            val fallbackError = recordsResult.exceptionOrNull()?.let { recErr ->
                "成绩获取失败：${recErr.message}"
            } ?: "B50 获取失败：${e.message}（将展示本地缓存）"
            _state.value = _state.value.copy(loading = false, error = fallbackError)
        }

        if (recordsResult.isFailure && b50Result.isFailure) {
            _state.value = _state.value.copy(
                loading = false,
                error = "网络异常，无法连接查分器（将展示本地缓存）",
            )
        }
    }

    private data class OAuthError(@SerializedName("error") val error: String? = null)

    /** 兼容两种返回形态：{records:[...]} 或顶层扁平数组。 */
    private fun parseRecords(text: String): List<RecordEntity> {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[")) {
            val arr = gson.fromJson(trimmed, Array<RawRecord>::class.java)
            arr.map { it.toEntity() }
        } else {
            val env = gson.fromJson(trimmed, RecordsEnvelope::class.java)
            env?.records?.map { it.toEntity() } ?: emptyList()
        }
    }

    private fun RawRecord.toEntity() = RecordEntity(
        id = 0,
        achievements = achievements,
        ds = ds,
        dxScore = dxScore,
        fc = fc,
        fs = fs,
        level = level,
        levelIndex = levelIndex,
        levelLabel = levelLabel,
        ra = ra,
        rate = rate,
        songId = songId,
        title = title,
        type = type,
    )

    /** 换取代该用户访问的短期令牌（命中缓存直接复用，401 时由调用方刷新）。 */
    private suspend fun obtainToken(force: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            if (!force && cachedToken != null && System.currentTimeMillis() < tokenExpireAtMs) {
                return@withContext Result.success(cachedToken!!)
            }
            if (configMissing) {
                return@withContext Result.failure(
                    IllegalStateException("未配置水鱼应用凭据"),
                )
            }
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", GRANT_ON_BEHALF_OF)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("subject", "ref:${subjectRef()}")
                    .add("scope", SCOPE)
                    .build()
                val request = Request.Builder()
                    .url("$AUTH_URL/oauth/token")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorCode = gson.fromJson(text, OAuthError::class.java)?.error.orEmpty()
                        val msg = when (errorCode) {
                            "consent_required" ->
                                "尚未绑定查分器（或已在水鱼端撤销），请重新授权"
                            else -> "换取令牌失败（HTTP ${response.code}）：$text"
                        }
                        throw IllegalStateException(msg)
                    }
                    val token = gson.fromJson(text, TokenResponse::class.java)
                    cacheToken(token.accessToken, token.expiresIn)
                    token.accessToken
                }
            }
        }

    private fun cacheToken(token: String, expiresInSec: Int) {
        cachedToken = token
        tokenExpireAtMs = System.currentTimeMillis() +
            (expiresInSec - TOKEN_EXPIRE_MARGIN_SEC).coerceAtLeast(0) * 1000L
    }

    // ── 数据访问（供 Agent / UI） ──

    /** Best 50（官方口径 sd 35 + dx 15；未命中缓存时回退本地全量取前 50）。 */
    fun best50(): List<RecordEntity> =
        best50Cache.ifEmpty { recordsCache.sortedByDescending { it.ra }.take(50) }

    /** 全部本地成绩缓存（Agent 推分推荐用）。 */
    fun allRecords(): List<RecordEntity> = recordsCache
}
