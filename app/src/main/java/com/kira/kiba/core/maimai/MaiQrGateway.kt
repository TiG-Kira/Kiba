package com.kira.kiba.core.maimai

import android.util.Log
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 舞萌官方登录二维码 → 成绩数据 的取数网关客户端。
 *
 * 对接公开的第三方解析服务（互操作实现）：客户端把官方登录二维码（SGWCMAID 串）POST
 * 给服务端，服务端完成「解密二维码 → 拉取官方游玩数据 → 转成查分器格式」并存入账号
 * 云端；客户端随后可经 [pullScores] 拉取账号云端的同步成绩。Kiba 只做协议对接，
 * 所有实现均为本项目原创代码。
 *
 * 接口约定（互操作所必需的协议参数）：服务基址、协议密钥与各端点路径均构建期注入
 * （见 app/build.gradle.kts，真实值存 local.properties / GitHub Secrets，不进开源仓库）：
 * - 登录 / 注册：body `{username, password}`（均为对称加密后的密文），成功返回
 *   `{code:1, data:{token}}`，token 为 JWT（注册成功时 `code != 0`）；
 * - 续期：header `Authorization: <裸 JWT>`，成功返回 `{code:1, data:{token}}`；
     * - 取数：body `{qr_code, lxCode, dfCode}`，header `Authorization: <对称加密后的 JWT>`
     *   与 `update-async: use`，成功为 HTTP 200 且 `message == "成绩已获取"`；
     *   响应体为**扁平结构** `{message, preview, playCount, b50, records}`（无 data 包裹层，
     *   与登录端点不同），records 异步落库时可能缺席，需再经拉取端点补取；
     * - 拉取同步成绩：header `Authorization: <裸 JWT>`，响应体同为扁平结构 `{records, playCount}`。
 *
 * 接入策略：用户在「我」页显式登录/注册（用户名密码），凭据与 JWT 仅存本机私有 SP；
 * 取数时 token 过期自动续期/重登，对用户透明。授权码 `lxCode`（其他查分平台）Kiba
 * 不使用，恒传空串；`dfCode` 传本机水鱼账号密码登录态（可选，服务端可能用于附加校验）。
 *
 * 隐私铁律：二维码字符串只作为函数参数在内存中流转，不落盘、不写日志。
 */
object MaiQrGateway {

    private const val TAG = "MaiQrGateway"

    /**
     * 取数服务接入点与协议密钥：不入开源仓库，构建期从 local.properties / CI Secrets
     * 注入（见 app/build.gradle.kts）。构建时缺失则运行时报错（避免静默打出坏包）。
     */
    private val GATEWAY_BASE: String =
        BuildConfig.QR_GATEWAY_BASE.ifBlank {
            throw IllegalStateException("构建配置缺失 QR_GATEWAY_BASE（见 local.properties / CI Secrets）")
        }

    private val GATEWAY_SECRET: String =
        BuildConfig.QR_GATEWAY_SECRET.ifBlank {
            throw IllegalStateException("构建配置缺失 QR_GATEWAY_SECRET（见 local.properties / CI Secrets）")
        }
    // 各端点路径同样构建期注入（不进开源仓库），缺失时运行时报错。
    private val PATH_LOGIN: String = BuildConfig.QR_PATH_LOGIN
    private val PATH_REGISTER: String = BuildConfig.QR_PATH_REGISTER
    private val PATH_REFRESH: String = BuildConfig.QR_PATH_REFRESH
    private val PATH_QR_BIND: String = BuildConfig.QR_PATH_QR_BIND
    private val PATH_SCORE: String = BuildConfig.QR_PATH_SCORE

    private fun requirePath(path: String, name: String): String = path.ifBlank {
        throw IllegalStateException("构建配置缺失端点路径 $name（见 local.properties / CI Secrets）")
    }

    /** SP 键。 */
    private const val SP_USERNAME = "qr_gateway_username"
    private const val SP_PASSWORD = "qr_gateway_password"
    private const val SP_TOKEN = "qr_gateway_token"

    /** token 提前判过期的余量（毫秒）。 */
    private const val TOKEN_EXPIRE_MARGIN_MS = 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // 服务端全量取数较慢，给足读超时
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── 对称加密（AES-256-CBC + PKCS7 + 随机 IV 前置 + Base64，平台标准库实现） ──

    private val cipherKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(GATEWAY_SECRET.toByteArray(StandardCharsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private val random = SecureRandom()

    /**
     * 加密：16 字节随机 IV 前置于密文，整体 Base64。
     * 等价的解密端约定为「取前 16 字节作 IV，AES-256-CBC 解密余下部分，去 PKCS7 填充」。
     */
    fun seal(plaintext: String): String {
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey, IvParameterSpec(iv))
        val cipherText = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val out = iv + cipherText
        return Base64.getEncoder().encodeToString(out)
    }

    /** [seal] 的逆操作（可用于自检/调试，主流程不需要）。 */
    fun unseal(sealed: String): String? = runCatching {
        val data = Base64.getDecoder().decode(sealed)
        if (data.size <= 16) return null
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey, IvParameterSpec(data.copyOfRange(0, 16)))
        String(cipher.doFinal(data.copyOfRange(16, data.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    // ── JWT 过期判断（自解析 payload 的 exp 字段） ──

    /** 判断 JWT 是否无效或已过期（畸形 token 一律视为过期）。 */
    internal fun isTokenExpired(jwt: String): Boolean {
        val parts = jwt.split('.')
        if (parts.size != 3) return true
        val payload = runCatching {
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return true
        val expSec = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return true
        return expSec * 1000L <= System.currentTimeMillis() + TOKEN_EXPIRE_MARGIN_MS
    }

    // ── DTO ──

    @Serializable
    data class AuthEnvelope(val code: Int = 0, val message: String? = null, val data: AuthData? = null)

    @Serializable
    data class AuthData(val token: String? = null)

    /** 玩家概要（取数成功后返回给调用方展示）。 */
    @Serializable
    data class Preview(
        @kotlinx.serialization.SerialName("userName") val userName: String? = null,
        @kotlinx.serialization.SerialName("userId") val userId: Long = -1L,
        @kotlinx.serialization.SerialName("iconId") val iconId: Int = -1,
    )

/**
 * qr-bind / score 端点的**扁平**响应体（无包裹层）：字段直接位于响应体根部，
 * 与登录/续期端点的 `{code, message, data}` 包裹结构不同，注意区分。
 * records 原样保留为 JSON 元素，交宽容解析处理。
 */
@Serializable
data class GatewayDataResponse(
    val message: String? = null,
    val preview: Preview? = null,
    val playCount: kotlinx.serialization.json.JsonElement? = null,
    val b50: kotlinx.serialization.json.JsonElement? = null,
    val records: List<kotlinx.serialization.json.JsonElement> = emptyList(),
)

    /** 取数结果（不含二维码任何数据）。 */
    data class QrFetchResult(
        val preview: Preview,
        val rawRecords: List<kotlinx.serialization.json.JsonElement>,
    )

    // ── 账号接入（用户显式登录/注册，凭据与 token 仅存本机） ──

    private val authMutex = Mutex()

    /** 本机是否已接入账号（有登录凭据即视为已接入）。 */
    fun isAccountBound(): Boolean =
        SpUtil.getQrGatewayUsername().isNotBlank() && SpUtil.getQrGatewayPassword().isNotBlank()

    /** 当前登录用户名（未接入返回空串，供 UI 展示）。 */
    fun boundUsername(): String = SpUtil.getQrGatewayUsername()

    /**
     * 注册新账号。成功即返回（服务端注册后可直接登录）。
     * 服务端约定：HTTP 200 且 `code != 0` 为成功，`code == 0` 携带失败 message。
     */
    suspend fun register(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    AuthBody.serializer(),
                    AuthBody(username = seal(username), password = seal(password)),
                )
                val request = Request.Builder()
                    .url("$GATEWAY_BASE/${requirePath(PATH_REGISTER, "QR_PATH_REGISTER")}")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("注册失败（HTTP ${response.code}）：${text.take(100)}")
                    }
                    val envelope = runCatching {
                        json.decodeFromString(AuthEnvelope.serializer(), text)
                    }.getOrElse { throw IllegalStateException("注册响应无法解析：${text.take(100)}") }
                    if (envelope.code == 0) {
                        throw IllegalStateException(envelope.message?.takeIf { it.isNotBlank() } ?: "注册失败")
                    }
                }
            }
        }

    /**
     * 用用户名密码登录，成功后凭据与 token 存本机（后续取数自动续期，无需重复登录）。
     */
    suspend fun login(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = requestLogin(username.trim(), password)
                SpUtil.putQrGatewayCredentials(username.trim(), password)
                SpUtil.putQrGatewayToken(token)
            }
        }

    /** 退出接入：清空本机凭据与 token。 */
    fun unbind() {
        SpUtil.putQrGatewayCredentials("", "")
        SpUtil.putQrGatewayToken("")
        Log.i(TAG, "qr gateway account unbound")
    }

    /**
     * 确保拿到可用 JWT（供取数调用）：本地 token 未过期直接用；过期则尝试续期；
     * 续期失败但本机有凭据则静默重登；都没有则失败并提示用户先接入账号。
     */
    suspend fun ensureToken(): Result<String> = withContext(Dispatchers.IO) {
        authMutex.withLock {
            runCatching {
                val cached = SpUtil.getQrGatewayToken()
                if (cached.isNotBlank() && !isTokenExpired(cached)) return@runCatching cached

                if (cached.isNotBlank()) {
                    runCatching { refreshToken(cached) }.getOrNull()?.let {
                        SpUtil.putQrGatewayToken(it)
                        return@runCatching it
                    }
                }

                val username = SpUtil.getQrGatewayUsername()
                val password = SpUtil.getQrGatewayPassword()
                if (username.isBlank() || password.isBlank()) {
                    throw IllegalStateException(
                        "尚未接入账号：请先到「我」页的「接入 Easymai 账号」完成登录或注册",
                    )
                }
                val token = requestLogin(username, password)
                SpUtil.putQrGatewayToken(token)
                token
            }
        }
    }

    /**
     * 续期：POST 续期端点，header `Authorization: <裸 JWT>`。
     * 成功（HTTP 200 且 code==1）返回新 token；否则抛异常（调用方可回退重新登录）。
     */
    private suspend fun refreshToken(jwt: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GATEWAY_BASE/${requirePath(PATH_REFRESH, "QR_PATH_REFRESH")}")
            .header("Authorization", jwt)
            .post(RequestBody.create(null, ByteArray(0)))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("续期失败（HTTP ${response.code}）")
            }
            val envelope = json.decodeFromString(AuthEnvelope.serializer(), text)
            if (envelope.code != 1) {
                throw IllegalStateException("续期被拒绝：${envelope.message.orEmpty()}")
            }
            envelope.data?.token?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("续期响应缺少 token")
        }
    }

    private suspend fun requestLogin(username: String, password: String): String =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                AuthBody.serializer(),
                AuthBody(username = seal(username), password = seal(password)),
            )
            val request = Request.Builder()
                .url("$GATEWAY_BASE/${requirePath(PATH_LOGIN, "QR_PATH_LOGIN")}")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("登录失败（HTTP ${response.code}）：${text.take(100)}")
                }
                val envelope = runCatching {
                    json.decodeFromString(AuthEnvelope.serializer(), text)
                }.getOrElse { throw IllegalStateException("登录响应无法解析：${text.take(100)}") }
                if (envelope.code == 0) {
                    throw IllegalStateException(
                        envelope.message?.takeIf { it.isNotBlank() } ?: "登录失败：用户名或密码错误",
                    )
                }
                envelope.data?.token?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("登录响应缺少 token：${text.take(100)}")
            }
        }

    @Serializable
    private data class AuthBody(val username: String, val password: String)

    // ── 取数：POST /user/mai/qr-bind ──

    /**
     * 把官方登录二维码交给解析服务，返回玩家概要 + 全量成绩原始记录。
     *
     * @param qrCode 已提取的 SGWCMAID 串
     * @param divingFishCode 水鱼账号密码登录态（裸 jwt，可选；服务端可能用于附加校验）
     */
    suspend fun fetchByQrCode(
        qrCode: String,
        divingFishCode: String? = null,
        onLog: (String) -> Unit = {},
    ): Result<QrFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val token = ensureToken().getOrThrow()

            val body = json.encodeToString(
                QrBindBody.serializer(),
                QrBindBody(
                    qrCode = qrCode,
                    lxCode = "", // 其他查分平台授权码，Kiba 不使用
                    dfCode = divingFishCode.orEmpty(),
                ),
            )
            val request = Request.Builder()
                .url("$GATEWAY_BASE/${requirePath(PATH_QR_BIND, "QR_PATH_QR_BIND")}")
                .header("Authorization", seal(token))
                .header("update-async", "use")
                .header("use-mock", "")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val bound = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 403) {
                    throw IllegalStateException("取数被拒绝：${text.take(120)}")
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("取数失败（HTTP ${response.code}）：${text.take(120)}")
                }
                // 注意：qr-bind 响应体是扁平结构（message/preview/records 在根部），
                // 与登录端点的 {code, message, data} 包裹结构不同。
                val parsed = runCatching {
                    json.decodeFromString(GatewayDataResponse.serializer(), text)
                }.getOrElse { throw IllegalStateException("取数响应无法解析：${text.take(120)}") }
                if (parsed.message != "成绩已获取") {
                    throw IllegalStateException(
                        parsed.message?.takeIf { it.isNotBlank() } ?: "取数异常（服务返回未知状态）",
                    )
                }
                parsed
            }
            val preview = bound.preview
                ?: throw IllegalStateException("取数响应缺少玩家概要")

            // 服务端异步落库时 qr-bind 响应可能不带 records，改从账号云端拉取（带重试）。
            var records = bound.records
            if (records.isEmpty()) {
                for (attempt in 1..3) {
                    onLog("第 $attempt 次从账号云端拉取数据…")
                    delay(2_000L)
                    records = pullScores().getOrElse { emptyList() }
                    if (records.isNotEmpty()) break
                }
            }
            QrFetchResult(preview, records)
        }
    }

    @Serializable
    private data class QrBindBody(
        @kotlinx.serialization.SerialName("qr_code") val qrCode: String,
        @kotlinx.serialization.SerialName("lxCode") val lxCode: String,
        @kotlinx.serialization.SerialName("dfCode") val dfCode: String,
    )

    // ── 拉取账号同步数据：POST /user/mai/score ──

    /**
     * 拉取账号云端的同步成绩（此前经 [fetchByQrCode] 同步进去的全量数据）。
     * header `Authorization: <裸 JWT>`，响应 `data.records` 为查分器通用格式
     * （与 [fetchByQrCode] 相同的宽容解析路径）。
     *
     * @return 原始记录元素列表（账号从未同步过时为空列表）
     */
    suspend fun pullScores(): Result<List<kotlinx.serialization.json.JsonElement>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = ensureToken().getOrThrow()
                val request = Request.Builder()
                    .url("$GATEWAY_BASE/${requirePath(PATH_SCORE, "QR_PATH_SCORE")}")
                    .header("Authorization", token)
                    .post(RequestBody.create(null, ByteArray(0)))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("拉取同步数据失败（HTTP ${response.code}）：${text.take(120)}")
                    }
                // 注意：score 端点响应体同样是扁平结构（records/playCount 在根部）。
                val parsed = runCatching {
                    json.decodeFromString(GatewayDataResponse.serializer(), text)
                }.getOrElse { throw IllegalStateException("拉取响应无法解析：${text.take(120)}") }
                parsed.records
                }
            }
        }

    // ── 工具 ──
}
