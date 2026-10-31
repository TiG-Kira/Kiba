package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonParser
import com.kira.kiba.core.data.LxnsScoreMapper
import com.kira.kiba.core.data.NetHtmlParser
import com.kira.kiba.core.utils.SpUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把机台抓到的 NET 页面解析成成绩，再用**落雪个人 API 的 JSON 接口**上传。
 *
 * ## 为什么不再直传 HTML（1.8.6 改动）
 * 1.8.3～1.8.5 走的是 `POST /api/v0/user/{game}/player/html`，实测被服务端拒成
 * **HTTP 550**。原因是落雪的 HTML 直传端点属于**开发者 API**
 * （`POST /api/v0/{game}/player/{friend_code}/html`），要求
 * `allow_third_party_write_data` 权限与好友码，得走 OAuth 应用审核；
 * 个人 API 密钥根本没有这个能力。
 *
 * 个人 API 文档明确支持的写通道只有一条：
 * ```
 * POST /api/v0/user/{game}/player/scores   { "scores": [Score, ...] }
 * ```
 * 所以改成「本地解析 → JSON 上传」：解析口径与水鱼服务端完全一致
 * （见 [NetHtmlParser] 的注释），曲名到曲目 ID 的映射见 [LxnsScoreMapper]。
 *
 * ## 另一条官方通路（不在本类负责范围）
 * 落雪官网还提供**代理同步**（`/user/sync`，把系统 WLAN 代理指向
 * `proxy.maimai.lxns.net:8080`，由落雪服务器代抓）。那条路与本 App 的本地 VPN
 * 会抢流量，所以两者只能二选一，设置页有入口说明。
 *
 * ## 上传前置条件（很容易被误判成 bug）
 * 落雪的数据挂在「玩家」上，**账号在落雪同步过一次之前没有 player**，
 * 所有写入接口都回 `404 player not found`。这不是 Key 错。
 *
 * ## 线程
 * 阻塞式接口（供 [com.kira.kiba.core.vpn.crawler.WechatCrawler] 这类 Java 调用方使用），
 * **禁止在主线程调用**。
 */
object LxnsUploader {

    private const val TAG = "LxnsUploader"

    private const val BASE_URL = "https://maimai.lxns.net/api/v0"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 游戏路径段（落雪口径）。 */
    const val GAME_MAIMAI = NetHtmlParser.MAIMAI_GAME
    const val GAME_CHUNITHM = NetHtmlParser.CHUNI_GAME

    /**
     * 解析并上传一页 NET 成绩源码。
     *
     * @param game [GAME_MAIMAI] / [GAME_CHUNITHM]
     * @param diffIndex 该页对应的难度索引，仅用于日志（真实难度以页面解析结果为准）
     * @param html 完整页面源码
     * @return 人话状态串，直接写进机台更新日志。失败不抛异常 —— 抓包链路是并发/重试的，
     *   抛异常会打断其它难度的上传；错误信息已经包含在返回串里。
     */
    @JvmStatic
    fun uploadScorePageBlocking(game: String, diffIndex: Int, html: String): String {
        val key = SpUtil.getLxnsApiKey()
        if (key.isBlank()) return "上传失败：尚未绑定落雪 API Key"
        if (html.isBlank()) return "上传失败：页面内容为空"

        val isChuni = game == GAME_CHUNITHM

        // 中二 WORLD'S END 页（record/worldsEndList）是独立表格，没有可直接引用的谱面 id；
        // 落雪把 WE 当成独立 difficulty，乱猜会把成绩写到错的谱面上。WE 不参与 Rating，跳过影响很小。
        if (isChuni && diffIndex == 5) {
            return "已跳过：WORLD'S END 页面暂不支持 JSON 上传（不参与 Rating 计算，影响很小）"
        }

        // ① 先嗅探页面类型：把「抓到登录页」这类故障说成人话，而不是「0 条成绩」
        when (NetHtmlParser.sniff(game, html)) {
            NetHtmlParser.PageKind.LOGIN_OR_ERROR ->
                return "上传失败：抓到的不是成绩页（登录态没拿到或授权未生效），本页已跳过"
            NetHtmlParser.PageKind.CHUNI_WORLD_END ->
                return "已跳过：WORLD'S END 页面暂不支持 JSON 上传（不参与 Rating 计算，影响很小）"
            NetHtmlParser.PageKind.UNKNOWN ->
                return "上传失败：无法识别该页面的结构（页面可能改版），" +
                    "页面里找到的曲名片段：" + NetHtmlParser.debugTitleSample(html)
            else -> Unit
        }

        // ② 解析
        val scores = if (isChuni) NetHtmlParser.parseChuni(html) else NetHtmlParser.parseMaimai(html)
        if (scores.isEmpty()) {
            return "上传失败：页面里没解析出任何成绩（结构可能改版），" +
                "曲名片段：" + NetHtmlParser.debugTitleSample(html)
        }

        // ③ 曲名 → 曲目 ID → 载荷
        val index = LxnsScoreMapper.index()
        if (index.isEmpty) {
            return "上传失败：本地曲库为空，无法把曲名换成曲目 ID。" +
                "请先到「设置 → 曲库更新」同步一次曲库再重试"
        }
        val built = if (isChuni) {
            LxnsScoreMapper.buildChuniPayload(scores, index)
        } else {
            LxnsScoreMapper.buildMaimaiPayload(scores, index)
        }
        if (!built.isNotEmpty) {
            val sample = built.skippedTitles.take(3).joinToString(" / ")
            return "上传失败：${scores.size} 条成绩都没能匹配到曲目 ID（曲库可能过期）" +
                (if (sample.isBlank()) "" else "，例如：$sample")
        }

        // ④ 上传
        val request = Request.Builder()
            .url("$BASE_URL/user/$game/player/scores")
            // 个人 API 用 X-User-Token；Authorization 是开发者 API 的头，混用必 401
            .header("X-User-Token", key)
            .post(built.payload.toString().toRequestBody(JSON_TYPE))
            .build()

        val summary = buildString {
            append("解析 ${scores.size} 条 → 上传 ${built.mapped} 条")
            if (built.skipped > 0) append("，${built.skipped} 条曲名未匹配")
            if (built.unsupported > 0) append("，${built.unsupported} 条该页面类型不支持")
        }

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val code = root?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: resp.code
                val message = root?.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

                when {
                    resp.isSuccessful && root?.get("success")?.asBoolean != false -> {
                        val changed = root?.getAsJsonArray("data")?.size() ?: 0
                        "$summary；落雪已接收，变化 $changed 条"
                    }
                    code == 401 || code == 403 ->
                        "上传失败：落雪 API Key 无效或被撤销（请到「账号详情」重新复制）"
                    // 404 有两种完全不同的含义，靠响应体区分：
                    // - JSON `{"message":"player not found"}`：路由存在，只是账号还没绑定玩家；
                    // - 纯文本 `404 page not found`：路由本身不存在（接口改名/下线）。
                    code == 404 && root == null ->
                        "上传失败：落雪没有这个接口（HTTP 404 ${body.take(60)}），接口可能已变更"
                    code == 404 ->
                        "上传失败：落雪账号还没有玩家数据。请先在落雪网页「同步游戏数据」同步一次，" +
                            "把玩家绑定到账号后，本 App 才能把机台成绩推上去"
                    code == 429 -> "上传失败：落雪限流（每秒最多 5 次），请稍后重试"
                    // 550 是 1.8.5 及之前 HTML 直传被拒时返回的状态，JSON 通道不应再出现；
                    // 若真出现说明落雪网关在拦这一批请求，把 body 原样带出来便于定位。
                    resp.code == 550 ->
                        "上传失败：落雪网关拒绝（HTTP 550），响应：${body.take(160)}"
                    else -> "上传失败：HTTP ${resp.code} ${message.ifBlank { body.take(160) }}（$summary）"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lxns upload failed", e)
            "上传失败：${e.javaClass.simpleName}: ${e.message}（$summary）"
        }
    }
}
