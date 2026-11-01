package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.data.LxnsScoreMapper
import com.kira.kiba.core.data.NetHtmlParser
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.utils.SpUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把机台抓到的 NET 页面源码送进落雪。**两条通道，官方直传优先**：
 *
 * 1. **开发者 API HTML 直传**（1.8.7 新增，需打包了开发者密钥）
 *    `POST /api/v0/{game}/player/{friend_code}/html`，body 就是整页源码。
 *    解析在落雪服务端做，口径与官方同步器完全一致；中二 WORLD'S END 也只有这条能传。
 *    前置条件：玩家在落雪打开「允许其他查分器写入数据」，否则 403。
 *
 * 2. **本地解析 → 个人/OAuth JSON 上传**（1.8.6 起的兜底）
 *    `POST /api/v0/user/{game}/player/scores`，`{ "scores": [Score...] }`。
 *    不依赖开发者密钥、也不依赖玩家授权第三方，但要求本地曲库能把曲名换成曲目 ID，
 *    而且**中二 WORLD'S END 传不了**（列表页没有可直接引用的谱面 id）。
 *
 * 任一条能成就返回成功；直传失败会带上原因再走兜底，两个都失败才报错 ——
 * 用户看到的永远是一句人话，而不是「直传 403」这种需要懂内情才看得懂的码。
 *
 * ## 为什么个人 API 不能直传 HTML
 * 1.8.3～1.8.5 试过 `POST /api/v0/user/{game}/player/html`，被拒成 **HTTP 550**：
 * 个人 API 根本没有这个端点，写入通道只有上面的 JSON 成绩接口。
 *
 * ## 另一条官方通路（不在本类负责范围）
 * 落雪官网还提供**代理同步**（`/user/sync`，把系统 WLAN 代理指向
 * `proxy.maimai.lxns.net:8080`，由落雪服务器代抓）。那条路与本 App 的本地 VPN
 * 会抢流量，所以两者只能二选一，设置页有入口说明。
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
     * @param diffIndex 该页对应的难度索引，用于日志与 WORLD'S END 判定
     * @param html 完整页面源码
     * @return 人话状态串，直接写进机台更新日志。失败不抛异常 —— 抓包链路是并发/重试的，
     *   抛异常会打断其它难度的上传；错误信息已经包含在返回串里。
     */
    @JvmStatic
    fun uploadScorePageBlocking(game: String, diffIndex: Int, html: String): String {
        if (!LxnsAuth.hasCredential()) return "上传失败：尚未登录落雪账号"
        if (html.isBlank()) return "上传失败：页面内容为空"

        val isChuni = game == GAME_CHUNITHM
        val gameType = if (isChuni) GameType.CHUNITHM else GameType.MAIMAI

        // ① 先嗅探页面类型：把「抓到登录页」这类故障说成人话，而不是「0 条成绩」
        var worldEnd = false
        when (NetHtmlParser.sniff(game, html)) {
            NetHtmlParser.PageKind.LOGIN_OR_ERROR ->
                return "上传失败：抓到的不是成绩页（登录态没拿到或授权未生效），本页已跳过"
            NetHtmlParser.PageKind.CHUNI_WORLD_END -> worldEnd = true
            NetHtmlParser.PageKind.UNKNOWN ->
                return "上传失败：无法识别该页面的结构（页面可能改版），" +
                    "页面里找到的曲名片段：" + NetHtmlParser.debugTitleSample(html)
            else -> Unit
        }

        // ② 官方直传（解析在落雪侧，最准）
        val dev = tryDevHtmlUpload(game, gameType, html)
        if (dev.ok) return dev.text

        // ③ 兜底：本地解析 → JSON 上传
        if (worldEnd) {
            // 只有官方直传支持 WE；它不可用就只能跳过（WE 不参与 Rating，影响很小）
            return "已跳过：WORLD'S END 页面只能用官方直传，当前不可用（${dev.text}）"
        }
        val jsonResult = uploadJsonBlocking(game, html)
        // 兜底也失败：把两条通道的原因都带上，省得用户来回问
        return if (jsonResult.startsWith("上传失败") && dev.text.isNotEmpty()) {
            "$jsonResult；官方直传也未成功（${dev.text}）"
        } else {
            jsonResult
        }
    }

    // ────────────────────── 通道 1：官方 HTML 直传 ──────────────────────

    /**
     * 直传尝试的结果。
     *
     * @param ok true = 落雪收下了这一页（[text] 是给日志的成功描述）
     * @param text ok 时是成功描述，false 时是失败原因（调用方继续走兜底）
     */
    private class DevOutcome(val ok: Boolean, val text: String)

    private fun tryDevHtmlUpload(game: String, gameType: GameType, html: String): DevOutcome {
        if (!LxnsDevApi.isConfigured()) return DevOutcome(false, "本安装包未打包开发者密钥")
        val friendCode = ensureFriendCodeBlocking(game, gameType)
        if (friendCode <= 0) {
            return DevOutcome(false, "拿不到好友码（请先在落雪同步一次，把玩家绑定到账号）")
        }

        val result = LxnsDevApi.uploadHtmlBlocking(game, friendCode, html)
        if (result.ok) {
            Log.i(TAG, "dev html upload ok: $game fc=$friendCode changed=${result.changed}")
        } else {
            Log.i(TAG, "dev html upload failed: $game fc=$friendCode reason=${result.message}")
        }
        return DevOutcome(result.ok, result.message)
    }

    /**
     * 好友码（开发者 API 的唯一寻址方式）。登录时 [com.kira.kiba.core.account.LxnsAccount]
     * 已经顺手存过一份，这里只是**兜底补取**：用户可能是老版本升上来的，SP 里还没有。
     *
     * 失败一律返回 0，不抛异常 —— 拿不到就退回 JSON 通道，别让上分卡在这一步。
     */
    private fun ensureFriendCodeBlocking(game: String, gameType: GameType): Long {
        SpUtil.getLxnsFriendCode(gameType).takeIf { it > 0 }?.let { return it }
        return try {
            val (name, value) = LxnsAuth.headerBlocking()
            val request = Request.Builder()
                .url("$BASE_URL/user/$game/player")
                .header(name, value)
                .header("Accept", "application/json")
                .build()
            val text = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return 0
                resp.body?.string().orEmpty()
            }
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val data = (root?.get("data") as? JsonObject) ?: root ?: return 0
            val fc = data.get("friend_code")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            if (fc > 0) SpUtil.saveLxnsFriendCode(gameType, fc)
            fc
        } catch (e: Exception) {
            Log.i(TAG, "friend code lookup failed: ${e.message}")
            0
        }
    }

    // ─────────────────── 通道 2：本地解析 + JSON 上传 ───────────────────

    private fun uploadJsonBlocking(game: String, html: String): String {
        val isChuni = game == GAME_CHUNITHM

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

        // ④ 上传（鉴权头由 LxnsAuth 决定：OAuth Bearer 或 个人 Key）
        val auth = try {
            LxnsAuth.headerBlocking()
        } catch (e: LxnsApi.LxnsException) {
            return "上传失败：${e.message}"
        }
        val request = Request.Builder()
            .url("$BASE_URL/user/$game/player/scores")
            .header(auth.first, auth.second)
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
                        "上传失败：落雪登录态无效或被撤销（请重新登录落雪账号）"
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
