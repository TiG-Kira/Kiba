package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.kira.kiba.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 落雪**开发者 API**（`Authorization: <开发者密钥>`），与用户身份无关，按**好友码**寻址。
 *
 * ## 它比个人 API 多出来的能力
 * | 能力 | 端点 | 权限（玩家侧开关） |
 * |---|---|---|
 * | NET 页面 HTML 直传（**本类已实现**） | `POST /{game}/player/{fc}/html` | `allow_third_party_write_data` |
 * | 按好友码查玩家（未实现，留作扩展） | `GET /{game}/player/{fc}` | `allow_third_party_fetch_player` |
 * | 按好友码查 Best50 / 全量成绩（未实现） | `GET /{game}/player/{fc}/bests` `/scores` | `allow_third_party_fetch_scores` |
 *
 * ## 为什么 HTML 直传重要（1.8.7）
 * 1.8.6 之前落雪侧只能「本地解析 NET 页面 → JSON 上传」，因为 HTML 直传属开发者 API。
 * 现在开发者资质下来了，直传有两个实打实的收益：
 * 1. **解析交给落雪自己**，与官方同步器的口径 100% 一致，不再怕页面改版 / 曲名歧义；
 * 2. **中二 WORLD'S END 有解了** —— 官方支持 `record/worldsEndList`，
 *    而本地 JSON 通道拿不到可引用的谱面 id，以前只能整页跳过。
 *
 * 官方明确支持的页面（别传别的，会 400/501）：
 * - 舞萌：`friend/userFriendCode/`、`collection/…`、`record/`、
 *   `record/musicSort/search/?search=V&sort=1&playCheck=on&diff={n}`、`record/musicGenre/search/?genre=99&diff=10`
 * - 中二：`home/playerData`、`collection/…`、`record/playlog`、`record/musicGenre`、`record/worldsEndList`
 *
 * ## 最容易踩的坑
 * 玩家在落雪设置里关掉「允许第三方写入数据」后，这里一律 **403**
 * （`message = allow third party write data is disabled`）。这**不是**密钥错、
 * 也不是 App 错，必须回落到自己的凭据通道（个人 Key / OAuth 的 write_player）。
 *
 * ## 密钥暴露面
 * 开发者密钥是**全账号**级别的，打进 APK 后理论上可被反编译取出。
 * 因此：① 只从构建密钥注入，公共仓库里是空串；② 为空时整个通道自动关闭；
 * ③ 解析失败一律静默回落 JSON 通道，不给用户看密钥相关报错。
 *
 * ## 线程
 * 全部阻塞式，**禁止在主线程调用**。
 */
object LxnsDevApi {

    private const val TAG = "LxnsDevApi"

    private const val BASE_URL = "https://maimai.lxns.net/api/v0"

    private val HTML_TYPE = "text/html; charset=utf-8".toMediaType()
    private val PLAIN_TYPE = "text/plain; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 打包时是否带了开发者密钥。为空 → 整个通道关闭，调用方走本地解析兜底。 */
    fun isConfigured(): Boolean = BuildConfig.LXNS_DEV_KEY.isNotBlank()

    /**
     * HTML 直传结果。
     *
     * @param ok 落雪接受了这一页
     * @param changed 本次发生变化的成绩条数（响应体 `ScoreChanges[]` 长度，0 表示无变化）
     * @param message 人话状态（失败时是原因），直接写进机台更新日志
     */
    data class HtmlResult(val ok: Boolean, val changed: Int, val message: String)

    /**
     * 直传一页 NET 源码。
     *
     * @param game [NetHtmlParser.MAIMAI_GAME] / [NetHtmlParser.CHUNI_GAME]
     */
    fun uploadHtmlBlocking(game: String, friendCode: Long, html: String): HtmlResult {
        if (!isConfigured()) return HtmlResult(false, 0, "未配置开发者密钥")
        if (friendCode <= 0) return HtmlResult(false, 0, "缺少好友码")
        if (html.isBlank()) return HtmlResult(false, 0, "页面内容为空")

        val url = "$BASE_URL/$game/player/$friendCode/html"

        // Content-Type 官方没写死。先按 text/html（水鱼中二直传就是这个），
        // 被 400/415 挡回来再用 text/plain 试一次（水鱼舞萌 pageparser 的写法）——
        // 两种都是「原样把源码放进 body」，不改内容，只是标头不同。
        var result = postHtml(url, html, HTML_TYPE)
        val badContentType = !result.ok &&
            (result.message.contains("HTTP 400", true) || result.message.contains("HTTP 415", true))
        if (badContentType) {
            Log.i(TAG, "html upload rejected with html content-type, retry as text/plain")
            result = postHtml(url, html, PLAIN_TYPE)
        }
        return result
    }

    private fun postHtml(
        url: String,
        html: String,
        mediaType: okhttp3.MediaType,
    ): HtmlResult {
        val request = Request.Builder()
            .url(url)
            // 开发者 API 用裸 Authorization（不是 Bearer、也不是 X-User-Token）
            .header("Authorization", BuildConfig.LXNS_DEV_KEY)
            .header("Accept", "application/json")
            .post(html.toRequestBody(mediaType))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val message = root?.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

                when {
                    resp.isSuccessful && root?.get("success")?.asBoolean != false -> {
                        val changed = (root?.get("data") as? JsonArray)?.size() ?: 0
                        HtmlResult(true, changed, "落雪已接收（官方直传），变化 $changed 条")
                    }
                    resp.code == 403 -> HtmlResult(
                        false, 0,
                        "玩家未允许第三方写入（落雪的「允许其他查分器写入数据」需要打开）",
                    )
                    resp.code == 401 -> HtmlResult(
                        false, 0,
                        "开发者密钥无效（本安装包的构建密钥有问题，请联系开发者）",
                    )
                    resp.code == 404 -> HtmlResult(
                        false, 0,
                        "落雪没有这个好友码，玩家可能还没在落雪同步过",
                    )
                    resp.code == 429 -> HtmlResult(false, 0, "落雪限流（每秒 5 次），稍后重试")
                    else -> HtmlResult(
                        false, 0,
                        "HTTP ${resp.code} ${message.ifBlank { body.take(120) }}",
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dev html upload failed", e)
            HtmlResult(false, 0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
