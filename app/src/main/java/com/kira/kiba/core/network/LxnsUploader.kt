package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonParser
import com.kira.kiba.core.utils.SpUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把机台抓到的 NET 页面 HTML 上传到落雪（个人 API）。
 *
 * ## 为什么复用 HTML 而不是解析成 JSON
 * 机台上分链路抓到的是 NET 的**原始页面**。落雪官方支持两条写入路径：
 * 1. `POST /api/v0/user/{game}/player/html`（个人 API，body 就是页面源码）—— 本类采用；
 * 2. `POST /api/v0/{game}/player/{friend_code}/scores`（要先把 HTML 解析成 Score 结构）。
 *
 * 路径 1 与水鱼 `pageparser/page` 的输入完全一致，抓什么传什么，解析口径由服务端负责，
 * 避免自己再实现一套容错解析（一旦官方页面改版就崩）。
 *
 * ## 上传前置条件（很容易被误判成 bug）
 * 落雪的数据挂在「玩家」上，**新账号在落雪同步过一次之前没有 player**，
 * 所有写入接口都回 `404 player not found`。这不是 Key 错，必须先去落雪同步一次。
 *
 * ## 线程
 * 阻塞式接口（供 [com.kira.kiba.core.vpn.crawler.WechatCrawler] 这类 Java 调用方使用），
 * **禁止在主线程调用**。内部自行构建 OkHttp 客户端，不依赖调用方的证书黑科技配置。
 */
object LxnsUploader {

    private const val TAG = "LxnsUploader"

    private const val BASE_URL = "https://maimai.lxns.net/api/v0"

    private val HTML_TYPE = "text/html; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 游戏路径段（落雪口径）。 */
    const val GAME_MAIMAI = "maimai"
    const val GAME_CHUNITHM = "chunithm"

    /**
     * 上传一页 NET 源码。
     *
     * @param game [GAME_MAIMAI] / [GAME_CHUNITHM]
     * @param html 完整页面源码（**必须是完整 HTML**，落雪不支持流式传输）
     * @return 人话状态串，直接写进机台更新日志。失败不抛异常 —— 抓包链路是并发/重试的，
     *   抛异常会打断其它难度的上传；错误信息已经包含在返回串里。
     */
    @JvmStatic
    fun uploadHtmlBlocking(game: String, html: String): String {
        val key = SpUtil.getLxnsApiKey()
        if (key.isBlank()) return "上传失败：尚未绑定落雪 API Key"
        if (html.isBlank()) return "上传失败：页面内容为空"

        val request = Request.Builder()
            .url("$BASE_URL/user/$game/player/html")
            // 个人 API 用 X-User-Token；Authorization 是开发者 API 的头，混用必 401
            .header("X-User-Token", key)
            .post(html.toRequestBody(HTML_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val code = root?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: resp.code
                val message = root?.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

                when {
                    resp.isSuccessful && root?.get("success")?.asBoolean != false -> {
                        val changed = root?.getAsJsonArray("data")?.size() ?: 0
                        "落雪上传成功（变化 $changed 条）"
                    }
                    code == 401 || code == 403 ->
                        "上传失败：落雪 API Key 无效或被撤销（请到「账号详情」重新复制）"
                    // 404 有两种完全不同的含义，靠响应体区分：
                    // - JSON `{"message":"player not found"}`：路由存在，只是账号还没绑定玩家；
                    // - 纯文本 `404 page not found`：路由本身不存在（接口改名/下线）。
                    // 混为一谈会把「接口变更」误导成「去网页同步一次就好」。
                    code == 404 && root == null ->
                        "上传失败：落雪没有这个接口（HTTP 404 ${body.take(60)}），接口可能已变更"
                    code == 404 ->
                        "上传失败：落雪账号还没有玩家数据。请先在落雪网页「同步游戏数据」同步一次，" +
                            "把玩家绑定到账号后，本 App 才能把机台成绩推上去"
                    code == 429 -> "上传失败：落雪限流（每秒最多 5 次），请稍后重试"
                    else -> "上传失败：HTTP ${resp.code} ${message.ifBlank { body.take(120) }}"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lxns upload failed", e)
            "上传失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
