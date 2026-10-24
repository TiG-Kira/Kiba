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
 *
 * 保底通道（原版 MaimaiData 登录方式）：[loginWithPassword] 直接 POST
 * username/password 换长效 jwt_token（Set-Cookie），请求以 Cookie 头发送。
 * 用于 OAuth 设备码未获批 / 不可用的场景（例如应用为 public client 无
 * client_secret 时服务端不放行设备码流程）。jwt 等价于口令，仅存本机私有 SP。
 */
object DivingFishAccount {

    private const val TAG = "DivingFishAccount"

    private const val AUTH_URL = "https://auth.diving-fish.com"
    private const val API_URL = "https://maimai.diving-fish.com/api/maimaidxprober"

    /** 原版通道域名（login / records / query/player 与传统 prober API 一致）。 */
    private const val LEGACY_API_URL = "https://www.diving-fish.com/api/maimaidxprober"

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

    /**
     * Best 50（官方 Festival 口径，本地按成绩计算）：
     * - 旧曲 35（isNew=false，按 ra 降序取前 35）
     * - 新曲 15（isNew=true，按 ra 降序取前 15）
     * Rating = 两部分 ra 之和。
     */
    data class Best50(
        val old: List<RecordEntity> = emptyList(),
        val new: List<RecordEntity> = emptyList(),
    ) {
        val oldRa: Int get() = old.sumOf { it.ra }
        val newRa: Int get() = new.sumOf { it.ra }
        val rating: Int get() = oldRa + newRa
        val all: List<RecordEntity> get() = new + old
    }

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** 全量成绩（内存缓存，供 Agent 本地逻辑使用）。 */
    @Volatile
    private var recordsCache: List<RecordEntity> = emptyList()

    /** 最近一次拉取到的昵称（records 响应体携带）。 */
    @Volatile
    private var recordsNickname: String = ""

    /** Best 50（本地按官方口径计算：旧曲 35 + 新曲 15）。 */
    @Volatile
    private var best50Cache: Best50 = Best50()

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

    private data class LegacyLoginError(val message: String? = null)

    /** API 请求凭证：OAuth 通道走 Authorization Bearer，原版 jwt 通道走 Cookie。 */
    private class ApiCredential(val headerName: String, val headerValue: String)

    // ── 配置 ──

    private val clientId: String get() = BuildConfig.DIVINGFISH_CLIENT_ID
    private val clientSecret: String get() = BuildConfig.DIVINGFISH_CLIENT_SECRET

    /**
     * 应用未配置 client_id（local.properties 未填 / Secrets 未注入）时的统一提示。
     *
     * 设备码流程是 public client：**只需 client_id**。
     * client_secret 仅机密客户端（自有服务端）需要，水鱼侧尚未生成时留空即可，
     * 表单中也不会发送空值，避免服务端按空 secret 校验拒绝。
     */
    val configMissing: Boolean
        get() = clientId.isBlank()

    /** 表单公共字段：client_id 必填，client_secret 仅在已配置时附加。 */
    private fun FormBody.Builder.addClientCredentials(): FormBody.Builder {
        add("client_id", clientId)
        if (clientSecret.isNotBlank()) add("client_secret", clientSecret)
        return this
    }

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

    /** 启动恢复：已登录（OAuth 绑定或原版 jwt）则静默拉一次成绩；否则展示未登录引导。 */
    suspend fun restore() {
        if (SpUtil.isDivingFishLoggedIn()) {
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
     * 保底通道（原版 MaimaiData 方式）：POST username/password 换长效 jwt_token。
     *
     * 用于 OAuth 设备码不可用的场景（如 public client 无 client_secret、
     * 服务端未放行设备码流程）。成功响应头 `Set-Cookie: jwt_token=...` 整串留存，
     * 之后以 `Cookie:` 头发往 www.diving-fish.com 的 prober API。
     * jwt 等价于口令，仅存本机私有 SP；用户改密或在水鱼端注销即失效。
     */
    suspend fun loginWithPassword(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val user = username.trim()
            if (user.isBlank() || password.isBlank()) {
                return@withContext Result.failure(IllegalStateException("请输入用户名和密码"))
            }
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val json = """{"username": "${escapeJson(user)}", "password": "${escapeJson(password)}"}"""
                val request = Request.Builder()
                    .url("$LEGACY_API_URL/login")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        val errText = response.body?.string().orEmpty()
                        val msg = runCatching {
                            gson.fromJson(errText, LegacyLoginError::class.java)?.message
                        }.getOrNull()
                        throw IllegalStateException(
                            msg?.takeIf { it.isNotBlank() } ?: "登录失败（HTTP ${response.code}）",
                        )
                    }
                    val cookie = response.header("Set-Cookie").orEmpty()
                    if (cookie.isBlank()) {
                        throw IllegalStateException("登录响应缺少令牌，请重试或换用 OAuth 授权")
                    }
                    cookie
                }
            }.onSuccess { cookie ->
                SpUtil.saveDivingFishJwt(cookie)
                SpUtil.setDivingFishBound(true)
                // 同步写入原版登录凭据：机台成绩更新（VPN 抓包上传）需要用户名 + 密码，
                // 否则「我」页显示已登录但机台页显示"未登录"，两处状态不一致。
                SpUtil.putLoginInfo(user, password, cookie)
                _state.value = AccountState(bound = true, loading = true)
                refreshRecords()
            }.onFailure { e ->
                Log.w(TAG, "loginWithPassword failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "登录失败")
            }.map { }
        }

    /** JSON 字符串字面量转义（用户名 / 密码入 body 前）。 */
    private fun escapeJson(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /**
     * 第一步：发起设备授权，返回授权链接给用户点开。
     */
    suspend fun startDeviceAuth(): Result<DeviceAuth> = withContext(Dispatchers.IO) {
        if (configMissing) {
            return@withContext Result.failure(
                IllegalStateException("未配置水鱼应用凭据（client_id），请检查 local.properties"),
            )
        }
        runCatching {
            val form = FormBody.Builder()
                .addClientCredentials()
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
                    val errorCode = gson.fromJson(text, OAuthError::class.java)?.error.orEmpty()
                    val msg = when (errorCode) {
                        "unauthorized_client" ->
                            "该应用未获批设备码授权流程（unauthorized_client），请改用「账号密码登录」"
                        "invalid_client" -> "应用凭据无效，请检查 client_id 配置"
                        "invalid_scope" -> "授权范围不被允许，请检查应用权限申请"
                        else -> "发起授权失败（HTTP ${response.code}）：$text"
                    }
                    throw IllegalStateException(msg)
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
                    .addClientCredentials()
                    .add("grant_type", GRANT_CONFIRMATION_CODE)
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
        recordsNickname = ""
        best50Cache = Best50()
        SpUtil.setDivingFishBound(false)
        SpUtil.saveDivingFishJwt("")
        SpUtil.saveDivingFishOAuthNickname("")
        // 一并清除原版登录凭据，保证「我」页与机台更新页状态同步
        SpUtil.putLoginInfo("", "", "")
        _state.value = AccountState()
    }

    /**
     * 拉取成绩：取凭证 → 全量 records 落库 → 本地按官方口径算 B50 与 Rating。
     *
     * 注意：**不再调 `/query/player`**——那是"按用户名查任意玩家"的开发者接口，
     * 用自身 Cookie 调会返回 400 `user not exists`。B50 改本地计算（与上游 MaimaiData 一致）：
     * 旧曲（isNew=false）取 ra 最高 35 + 新曲（isNew=true）取 ra 最高 15，ra 之和即 Rating。
     *
     * 双通道：原版 jwt 走 www.diving-fish.com + Cookie；OAuth 走 maimai.diving-fish.com + Bearer。
     */
    suspend fun refreshRecords() {
        if (!SpUtil.isDivingFishLoggedIn()) {
            _state.value = _state.value.copy(loading = false, error = "尚未登录查分器账户")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        val credentialResult = obtainCredential()
        val credential = credentialResult.getOrElse { e ->
            Log.w(TAG, "obtainCredential failed", e)
            _state.value = _state.value.copy(
                loading = false,
                error = "获取授权失败：${e.message}（将展示本地缓存）",
            )
            return
        }
        val baseUrl = if (SpUtil.getDivingFishJwt().isNotBlank()) LEGACY_API_URL else API_URL

        // 全量成绩（/player/records）——推分推荐 / B50 计算 / 本地逻辑的数据源
        val recordsResult = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/player/records")
                    .header(credential.headerName, credential.headerValue)
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

        recordsResult.onSuccess { records ->
            RecordRepository.getInstance(
                AppDataBase.getInstance().recordDao()
            ).replaceAllRecord(records)
            recordsCache = records
            // B50 本地计算：需要曲库的 isNew 标记
            val isNewBySongId = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().songDao().getAllSongsList()
                        .associate { it.id to it.isNew }
                }.getOrDefault(emptyMap())
            }
            val sorted = records.sortedByDescending { it.ra }
            best50Cache = Best50(
                old = sorted.filter { isNewBySongId[it.songId] != true }.take(35),
                new = sorted.filter { isNewBySongId[it.songId] == true }.take(15),
            )
            val nickname = recordsNickname
            if (nickname.isNotBlank()) {
                SpUtil.saveDivingFishOAuthNickname(nickname)
            }
            _state.value = AccountState(
                bound = true,
                nickname = nickname.ifBlank { SpUtil.getDivingFishOAuthNickname() },
                rating = best50Cache.rating,
                additionalRating = 0,
                recordCount = records.size,
                loading = false,
                error = null,
            )
        }.onFailure { e ->
            Log.w(TAG, "fetch records failed", e)
            _state.value = _state.value.copy(
                loading = false,
                error = "成绩获取失败：${e.message}（将展示本地缓存）",
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
            recordsNickname = env?.nickname.orEmpty()
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

    /**
     * 取 API 请求凭证（双通道）：
     * - 原版 jwt（长效，存 SP）→ Cookie 头；
     * - 否则 OAuth on-behalf-of 换 5 分钟短 token（内存缓存）→ Bearer 头。
     */
    private suspend fun obtainCredential(force: Boolean = false): Result<ApiCredential> =
        withContext(Dispatchers.IO) {
            val jwt = SpUtil.getDivingFishJwt()
            if (jwt.isNotBlank()) {
                return@withContext Result.success(ApiCredential("Cookie", jwt))
            }
            obtainToken(force).map { token -> ApiCredential("Authorization", "Bearer $token") }
        }

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
                    .addClientCredentials()
                    .add("grant_type", GRANT_ON_BEHALF_OF)
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

    /** Best 50（官方口径：旧曲 35 + 新曲 15，本地按 ra 排序计算）。 */
    fun best50(): Best50 = best50Cache

    /** 全部本地成绩缓存（Agent 推分推荐用）。 */
    fun allRecords(): List<RecordEntity> = recordsCache
}
