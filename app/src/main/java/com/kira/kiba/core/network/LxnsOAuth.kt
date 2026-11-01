package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.security.LxnsVault
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 落雪 OAuth 2.0 客户端（授权码模式，**无回调地址**）。
 *
 * ## 为什么是无回调地址
 * 落雪开发者面板创建应用时可以勾「无回调地址」，此时授权成功后**不重定向**，
 * 而是直接在页面上把授权码显示出来（形如 `JVJ6-VPTM-MGHZ`），
 * 请求里的 `redirect_uri` 固定为 OOB 约定的 `urn:ietf:wg:oauth:2.0:oob`。
 * 对 App 来说这反而是最省事的：不用起本地 HTTP 监听、不用登记多台设备的回调，
 * 用户复制一串码粘进来即可（与「个人 API 密钥」的操作成本相当，但权限更细、可随时撤销）。
 *
 * ## 令牌生命周期（照抄官方文档，别自己发明）
 * - `access_token` 是 JWT，**有效期 15 分钟**；
 * - `refresh_token` **有效期 30 天**，且**每次刷新都会换新、旧的立即失效** ——
 *   所以刷新必须串行（本类用 `@Synchronized` 保证），否则并发刷新会把彼此踢下线；
 * - 令牌接口的响应体字段在**顶层**（`access_token` / `refresh_token` / `expires_in`），
 *   **不套** `{success,code,data}`；错误也是扁平的 `{error,error_description}`。
 *   这两点和落雪其它接口完全不同，混用解析会把成功当失败。
 *
 * ## 状态码
 * 授权码/刷新令牌失效统一 `invalid_grant`；客户端认证失败 `invalid_client`。
 * 前者说明必须重新授权（刷新令牌已死），后者说明打包时的 client_secret 不对。
 *
 * ## 与个人 API 密钥的关系
 * OAuth 拿到的 Bearer 令牌访问的是**同一批** `/api/v0/user/...` 接口
 * （官方 OAuth 文档明确「参考个人 API」），所以同步链路代码一行都不用改，
 * 只要换请求头 —— 由 [LxnsAuth] 统一决定。
 */
object LxnsOAuth {

    private const val TAG = "LxnsOAuth"

    const val AUTHORIZE_URL = "https://maimai.lxns.net/oauth/authorize"
    const val TOKEN_URL = "https://maimai.lxns.net/api/v0/oauth/token"
    const val USERINFO_URL = "https://maimai.lxns.net/api/v0/oauth/userinfo"

    /**
     * 无回调地址模式下的固定 redirect_uri。
     * 授权链接和应用里登记的「无回调地址」必须一致，落雪按 OOB 约定校验。
     */
    const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    /**
     * 申请到的权限：
     * - `read_user_profile` 读用户信息（昵称等）
     * - `read_player` 读玩家信息 / 谱面成绩 / 历史成绩
     * - `write_player` 写入玩家信息、上传成绩（机台上分必需）
     * - `openid` OIDC 身份（拿稳定的 `sub`，用于区分「换了昵称还是同一个人」）
     * 刻意**不含** `read_user_token`（能读个人 API 密钥，泄露面太大）。
     */
    const val SCOPE = "read_user_profile read_player write_player openid"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 打包时的 OAuth 应用凭据是否齐全（公开仓库里占位为空 → 功能自动隐藏）。 */
    fun isConfigured(): Boolean = LxnsVault.hasOAuth()

    /**
     * 拼授权链接。
     *
     * 不发 `state`：OOB 模式下没有回调地址可携带它，服务端也就没地方回显，
     * 校验无从谈起（授权码是用户手动复制的，不存在跨站注入面）。
     * 若将来改成真实回调，必须补上 `state` 并在回调里比对。
     */
    fun authorizeUrl(): String {
        if (!isConfigured()) {
            throw IllegalStateException("这个安装包没有打包落雪 OAuth 应用凭据，请改用「个人 API 密钥」登录")
        }
        val scope = SCOPE.split(' ').joinToString("%20") { enc(it) }
        return "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${enc(LxnsVault.oauthClientId())}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&scope=$scope"
    }

    /** 用户粘贴的授权码可能带空格/连字符/引号，统一洗一遍。 */
    fun normalizeCode(raw: String): String = raw
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .replace("\n", "")
        .replace("\r", "")
        .trim()

    /**
     * 用授权码换令牌（并落盘）。
     *
     * @return 授权范围（`scope` 字段），供日志/UI 展示
     */
    suspend fun exchangeCode(code: String): String = withContext(Dispatchers.IO) {
        exchangeCodeBlocking(code)
    }

    fun exchangeCodeBlocking(code: String): String {
        if (!isConfigured()) {
            throw IllegalStateException("这个安装包没有打包落雪 OAuth 应用凭据，请改用「个人 API 密钥」登录")
        }
        val c = normalizeCode(code)
        if (c.isBlank()) throw IllegalStateException("请先粘贴授权码")
        // 授权码形如 JVJ6-VPTM-MGHZ（12 位 + 2 连字符）；给个下限兜住「粘了半截」的情况
        if (c.length < 8) throw IllegalStateException("授权码看起来太短了，请确认复制完整")

        val body = JsonObject().apply {
            addProperty("client_id", LxnsVault.oauthClientId())
            addProperty("client_secret", LxnsVault.oauthClientSecret())
            addProperty("grant_type", "authorization_code")
            addProperty("code", c)
            addProperty("redirect_uri", REDIRECT_URI)
        }
        val token = postToken(body.toString())
        persist(token)
        Log.i(TAG, "oauth code exchanged, scope=${token.scope}")
        return token.scope
    }

    /**
     * 取一个**可用**的 access_token（必要时先刷新）。阻塞版，调用方必须已在 IO 线程。
     *
     * 单飞：`@Synchronized` 让并发调用排队，避免多个请求同时拿着同一个 refresh_token
     * 去刷新（落雪每次刷新都换新令牌，并发刷新会出现「后到的用旧 refresh → invalid_grant」）。
     */
    @Synchronized
    fun accessTokenBlocking(): String {
        val refreshToken = SpUtil.getLxnsOAuthRefresh()
        if (refreshToken.isBlank()) throw notAuthorized()
        val access = SpUtil.getLxnsOAuthAccess()
        val expiresAt = SpUtil.getLxnsOAuthExpiresAt()
        // 提前 60s 视为过期：15 分钟的有效期里，请求在路上的那点延迟足够把边界踩穿
        if (access.isNotBlank() && expiresAt > 0 && System.currentTimeMillis() < expiresAt - 60_000) {
            return access
        }
        return refreshBlocking(refreshToken).access
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) { accessTokenBlocking() }

    /**
     * 刷新一次（仅内部用：[accessTokenBlocking] 在令牌临期时调用）。
     *
     * 返回私有类型 [Token]，所以不能是 public —— Kotlin 不允许公开函数暴露私有类型。
     */
    @Synchronized
    private fun refreshBlocking(refreshToken: String): Token {
        val body = JsonObject().apply {
            addProperty("client_id", LxnsVault.oauthClientId())
            addProperty("client_secret", LxnsVault.oauthClientSecret())
            addProperty("grant_type", "refresh_token")
            addProperty("refresh_token", refreshToken)
        }
        val token = try {
            postToken(body.toString())
        } catch (e: LxnsApi.LxnsException) {
            // 刷新令牌死了（过期 / 已被下一次刷新顶掉）→ 清掉本地令牌，让 UI 走重新授权
            if (e.code == 401) {
                SpUtil.clearLxnsOAuth()
                throw LxnsApi.LxnsException(
                    401,
                    "落雪授权已失效（刷新令牌过期或已被撤销），请重新授权登录",
                )
            }
            throw e
        }
        persist(token)
        Log.i(TAG, "oauth token refreshed, scope=${token.scope}")
        return token
    }

    /** OIDC UserInfo：鉴权是否真的生效、以及稳定的用户标识 `sub`。 */
    suspend fun userInfo(): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(USERINFO_URL)
            .header("Authorization", "Bearer ${accessTokenBlocking()}")
            .header("Accept", "application/json")
            .build()
        val text = client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toOAuthException(resp.code, body)
            body
        }
        runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse {
            throw LxnsApi.LxnsException(-1, "落雪 UserInfo 不是合法 JSON")
        }
    }

    // ────────────────────────── 内部 ──────────────────────────

    private class Token(
        val access: String,
        val refresh: String,
        val expiresIn: Long,
        val scope: String,
    )

    private fun persist(token: Token) {
        val expiresAt = System.currentTimeMillis() + token.expiresIn.coerceAtLeast(0) * 1000
        SpUtil.saveLxnsOAuthToken(token.access, token.refresh, expiresAt)
    }

    private fun postToken(json: String): Token {
        val req = Request.Builder()
            .url(TOKEN_URL)
            // 落雪两种 Content-Type 都收；JSON 少一次 urlencode，出错面更小
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_TYPE))
            .build()

        val text = client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toOAuthException(resp.code, body)
            body
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw LxnsApi.LxnsException(-1, "落雪令牌响应不是合法 JSON")

        val access = root.str("access_token")
        if (access.isBlank()) {
            // 有的实现会包一层 {success,data}，兜一下再判失败
            val err = root.str("error").ifBlank { "missing access_token" }
            throw LxnsApi.LxnsException(-1, "落雪没有返回访问令牌（$err）")
        }
        val expiresIn = root.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 900L
        return Token(
            access = access,
            // 刷新响应一定会带新的 refresh_token；万一没有就沿用旧的（少数实现如此）
            refresh = root.str("refresh_token").ifBlank { SpUtil.getLxnsOAuthRefresh() },
            expiresIn = expiresIn,
            scope = root.str("scope"),
        )
    }

    private fun notAuthorized() = LxnsApi.LxnsException(401, "尚未登录落雪账号")

    /**
     * OAuth 错误体是**扁平**的 `{error, error_description}`，不是落雪的 `{success,code,message}`。
     * 按错误码翻成人话，避免用户看到 `invalid_grant` 这种天书。
     */
    private fun toOAuthException(httpCode: Int, body: String): LxnsApi.LxnsException {
        val o = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val error = o?.str("error").orEmpty()
        val desc = o?.str("error_description").orEmpty()
        val message = when (error) {
            "invalid_client" ->
                "落雪 OAuth 应用凭据无效（打包时的 client_id / client_secret 不对）"
            "invalid_grant" ->
                "授权码无效或已过期（授权码只能换一次，且很快失效），请重新打开授权页复制新的"
            "invalid_request" -> "授权请求参数不合法${if (desc.isBlank()) "" else "：$desc"}"
            "unsupported_grant_type" -> "落雪不支持该授权类型，客户端版本可能过旧"
            "" -> if (httpCode == 401) "落雪授权无效或已过期" else desc.ifBlank { "落雪令牌请求失败（HTTP $httpCode）" }
            else -> "落雪授权失败：$error${if (desc.isBlank()) "" else "（$desc）"}"
        }
        // 401 让上层按「鉴权失败」处理（引导重新授权），其余按普通错误
        return LxnsApi.LxnsException(if (error == "invalid_grant" || httpCode == 401) 401 else httpCode, message)
    }

    private fun JsonObject.str(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
