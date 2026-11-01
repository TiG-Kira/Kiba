package com.kira.kiba.core.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 落雪（LXNS）API 客户端 —— **用户级接口**（`/api/v0/user/...`）。
 *
 * ## 鉴权
 * 落雪有三套完全不同的鉴权头，混用一律 401：
 * - 个人 API（`/api/v0/user/...`）→ **`X-User-Token: <个人 API 密钥>`**
 * - 开发者 API（`/api/v0/{maimai,chunithm}/...`）→ `Authorization: <开发者 API 密钥>`
 * - OAuth API → `Authorization: Bearer <access_token>`（访问的也是 `/api/v0/user/...`）
 *
 * 1.8.3 把个人 API 也写成了 `Authorization`，结果**任何合法的个人密钥都被判成无效**
 * （实测：`Authorization` → 401；`X-User-Token` → 200/404 player not found）。
 * 1.8.7 起 OAuth 与个人 Key 并存，具体发哪个头由 [LxnsAuth] 统一决定（OAuth 优先）。
 * Key 在落雪「账号详情」（`/user/profile`）生成/重置，可在站内随时撤销。
 *
 * ## 响应形态
 * 落雪有**两种**成功响应：
 * 1. 包了一层 `{"success":true,"code":200,"data":{...}}`；
 * 2. 直接把业务对象放在最外层（公共曲库 `song/list` 就是这样，实测无 data 包裹）。
 * 这里统一解包：有 `data` 且 `success != false` 就取 `data`，否则原样返回。
 * 失败统一 `{"success":false,"code":401,"message":"unauthorized"}`。
 *
 * 注意 OAuth 的**令牌接口**（`/api/v0/oauth/token`）不遵守上面这套外壳，见 [LxnsOAuth]。
 *
 * ## 线程
 * **一律在 IO 线程执行**（内部 `withContext(Dispatchers.IO)`）。落雪调用若不离开主线程，
 * 会直接 NetworkOnMainThreadException（本项目已踩过同类坑，见 CrawlerCaller 那次）。
 */
object LxnsApi {

    private const val TAG = "LxnsApi"

    const val BASE_URL = "https://maimai.lxns.net/api/v0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 落雪返回的业务错误（code / message 来自响应体）。 */
    class LxnsException(
        val code: Int,
        override val message: String,
    ) : Exception(message) {
        /** Key 无效 / 被撤销 / 过期 —— UI 需要引导重新绑定，而不是当作普通网络错误。 */
        val isAuthError: Boolean get() = code == 401 || code == 403
    }

    /**
     * GET 落雪个人 API，返回已解包的业务 JSON。
     *
     * @param path 相对 [BASE_URL] 的路径，例如 `/user/chunithm/player/scores`。
     */
    suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        // 请求头统一由 LxnsAuth 决定（OAuth Bearer 优先、个人 Key 兜底），
        // 这里不要再自己读 SpUtil，否则两处判定不一致会出现「某个接口突然 401」
        val (name, value) = LxnsAuth.header()
        val request = Request.Builder()
            .url(BASE_URL + path)
            .header(name, value)
            .header("Accept", "application/json")
            .build()

        val text = client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toException(resp.code, body)
            body
        }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: throw LxnsException(-1, "落雪响应不是合法 JSON")

        // 失败也可能是 HTTP 200 包在 body 里
        if (root.isJsonObject) {
            val obj = root.asJsonObject
            val success = obj.optBool("success")
            if (success == false) {
                val code = obj.optInt("code").takeIf { it > 0 } ?: -1
                throw LxnsException(code, obj.optString("message").ifBlank { "落雪返回失败" })
            }
            obj.get("data")?.takeIf { !it.isJsonNull }?.let { return@withContext it }
        }
        root
    }

    suspend fun getObject(path: String): JsonObject =
        get(path) as? JsonObject ?: throw LxnsException(-1, "落雪响应不是对象")

    suspend fun getArray(path: String): JsonArray =
        get(path) as? JsonArray ?: throw LxnsException(-1, "落雪响应不是数组")

    /** 把 HTTP 错误翻成人话；401/403 单独标出，UI 据此提示重新绑定 Key。 */
    private fun toException(httpCode: Int, body: String): LxnsException {
        val parsed = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val code = parsed?.optInt("code")?.takeIf { it > 0 } ?: httpCode
        val message = when {
            httpCode == 401 || httpCode == 403 ->
                "落雪 API Key 无效（常见原因：复制不完整、已重置，或把开发者密钥当个人密钥用）。" +
                    "请打开「账号详情」重新复制，注意区分大小写"
            // 纯文本 `404 page not found` = 路由不存在；JSON `player not found` = 玩家没绑定。
            // 两者都是 404，混着报会把「接口变更」误导成「去网页同步一次」。
            httpCode == 404 && parsed == null ->
                "落雪没有这个接口（${body.take(60)}），接口可能已变更"
            httpCode == 404 -> "落雪没有这项数据（请先在落雪同步一次，把玩家绑定到该账号）"
            httpCode == 429 -> "落雪请求过于频繁（每秒最多 5 次），请稍后再试"
            httpCode >= 500 -> "落雪服务器异常（HTTP $httpCode），请稍后再试"
            else -> parsed?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "落雪请求失败（HTTP $httpCode）"
        }
        return LxnsException(code, message)
    }

    /** 日志用：只在调试构建里打印响应片段，避免成绩数据落日志。 */
    internal fun logLabel(path: String): String = "$TAG $path"
}

// ── JsonObject 安全取值（本文件私有，避免与同包其它文件的顶层扩展重名）──

private fun JsonObject.optInt(name: String): Int {
    val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0
    return runCatching { el.asInt }.getOrDefault(0)
}

private fun JsonObject.optBool(name: String): Boolean? {
    val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return null
    return runCatching { el.asBoolean }.getOrNull()
}

private fun JsonObject.optString(name: String): String {
    val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
    return runCatching { el.asString }.getOrDefault("")
}
