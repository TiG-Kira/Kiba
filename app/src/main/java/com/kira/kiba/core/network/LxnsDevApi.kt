package com.kira.kiba.core.network

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.data.NetHtmlParser
import com.kira.kiba.core.security.LxnsVault
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
 * | NET 页面 HTML 直传 | `POST /{game}/player/{fc}/html` | `allow_third_party_write_data` |
 * | 按好友码查玩家（「查朋友」） | `GET /{game}/player/{fc}` | `allow_third_party_fetch_player` |
 * | 按好友码查 Best / 全量成绩（「查朋友」） | `GET /{game}/player/{fc}/bests` `/scores` | `allow_third_party_fetch_scores` |
 *
 * 注意「查朋友」的两道门槛都在**对方**那边：对方要在落雪设置里打开
 * 「允许他人查询我的玩家信息 / 成绩」，否则我们这边一律 403（不是我们密钥的问题）。
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
 * 开发者密钥是**全账号**级别的，打进 APK 后理论上仍可被逆向取出（客户端无真保密）。
 * 因此做了三层：① 只从构建密钥注入，公共仓库里是空串；② 构建期密封成加扰分片载荷，
 * 运行时由 [com.kira.kiba.core.security.LxnsVault] 还原（`strings` 捞不到）；
 * ③ 解不出 / 为空时整个通道自动关闭，回落本地解析，不给用户看密钥相关报错。
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
    fun isConfigured(): Boolean = LxnsVault.hasDevKey()

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
            .header("Authorization", LxnsVault.devKey())
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

    // ══════════════════════ 按好友码查询他人（「查朋友」） ══════════════════════
    //
    // 与「查自己」的区别：这里走开发者 API（Authorization = 开发者密钥），不需要也不使用
    // 用户自己的 OAuth / 个人 Key —— 换个人登录照样能查同一个好友码。
    // 反过来说，能不能查到完全取决于对方在落雪里有没有打开第三方查询开关。

    /** 玩家信息（两游戏共用的精简壳）。 */
    data class FriendPlayer(
        val name: String,
        /** 舞萌：整数 DX Rating；中二：两位小数 Rating。已格式化好，UI 直接用。 */
        val ratingText: String,
        val friendCode: Long,
        /** 舞萌：段位 / 阶级；中二：等级 + Rating 领域色 + CLASS。 */
        val badge: String,
        val trophy: String,
        /** 落雪侧最后一次同步时间（UTC）。 */
        val uploadTime: String,
    )

    /** 单条成绩。 */
    data class FriendScore(
        val songId: Int,
        val title: String,
        val levelIndex: Int,
        val level: String,
        /** 舞萌达成率（100.5000%）/ 中二分数（1007500）。 */
        val valueText: String,
        /** 单曲 Rating 贡献（舞萌 dx_rating / 中二 rating）。 */
        val ra: Double,
        val fc: String,
    )

    /** 一组成绩（舞萌 B35 / B15；中二 B30 / Selection 10 / New 20）。 */
    data class FriendGroup(
        val label: String,
        val scores: List<FriendScore>,
        /** 该组 ra 之和。舞萌直接就是该组 Rating；中二官方 Rating 是均值口径，只作参考。 */
        val total: Double,
    )

    /** 查朋友的完整结果。 */
    data class FriendSnapshot(
        val game: String,
        val player: FriendPlayer,
        val groups: List<FriendGroup>,
        /** 全量成绩条数（`/scores` 简化成绩数量）。 */
        val scoreCount: Int,
        /** 难度名 → 该难度已游玩谱面数（按难度顺序）。 */
        val levelCounts: List<Pair<String, Int>>,
    )

    /** 难度名（与本地曲库的 level_index 口径一致）。 */
    private val MAIMAI_LEVELS = listOf("BASIC", "ADVANCED", "EXPERT", "MASTER", "Re:MASTER")
    private val CHUNI_LEVELS = listOf("BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA")

    /**
     * 查一个好友码的全部公开数据（阻塞，**禁止主线程**）。
     *
     * @param withScores 是否额外拉全量成绩（只用于统计条数与难度分布，多一次请求；
     *                   落雪限流每秒 5 次，连续查多人时可以关掉）
     * @throws LxnsApi.LxnsException 带人话 message（403 = 对方没开第三方查询）
     */
    fun fetchFriendBlocking(
        game: String,
        friendCode: Long,
        withScores: Boolean = true,
    ): FriendSnapshot {
        if (!isConfigured()) {
            throw LxnsApi.LxnsException(0, "本安装包没有打包开发者密钥，无法按好友码查询")
        }
        if (friendCode <= 0L) {
            throw LxnsApi.LxnsException(0, "好友码要 15 位数字，检查一下再试")
        }

        val playerJson = devGet("/$game/player/$friendCode")
        val bestJson = devGet("/$game/player/$friendCode/bests")
        val scoresJson = if (withScores) devGet("/$game/player/$friendCode/scores") else null

        val chuni = game == NetHtmlParser.CHUNI_GAME
        val levels = if (chuni) CHUNI_LEVELS else MAIMAI_LEVELS

        return FriendSnapshot(
            game = game,
            player = parsePlayer(playerJson, friendCode, chuni),
            groups = parseGroups(bestJson, chuni),
            scoreCount = scoresJson?.let { countScores(it) } ?: 0,
            levelCounts = scoresJson?.let { json ->
                val list = (json as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                levels.mapIndexed { index, name ->
                    name to list.count { it.num("level_index").toInt() == index }
                }
            } ?: emptyList(),
        )
    }

    private fun countScores(json: com.google.gson.JsonElement): Int =
        (json as? JsonArray)?.size() ?: 0

    private fun parsePlayer(json: JsonObject, friendCode: Long, chuni: Boolean): FriendPlayer {
        val name = json.str("name")
        val trophy = json.obj("trophy")?.str("name").orEmpty()
        val upload = json.str("upload_time")
        return if (chuni) {
            val level = json.num("level").toInt()
            val possession = json.str("rating_possession")
            val emblem = json.obj("class_emblem")
            val base = emblem?.num("base")?.toInt() ?: 0
            val medal = emblem?.num("medal")?.toInt() ?: 0
            val badge = buildList {
                if (level > 0) add("等级 $level")
                if (possession.isNotBlank()) add("领域色 ${possessionCn(possession)}")
                if (base > 0 || medal > 0) add("CLASS ${base + 1}-${medal}")
            }.joinToString(" · ")
            FriendPlayer(name, "%.2f".format(json.num("rating")), friendCode, badge, trophy, upload)
        } else {
            val course = json.num("course_rank").toInt()
            val clazz = json.num("class_rank").toInt()
            val star = json.num("star").toInt()
            val badge = buildList {
                if (course > 0) add("段位 $course")
                if (clazz > 0) add("阶级 $clazz")
                if (star > 0) add("搭档觉醒 $star")
            }.joinToString(" · ")
            FriendPlayer(
                name,
                json.num("rating").toInt().toString(),
                friendCode,
                badge,
                trophy,
                upload,
            )
        }
    }

    /** Rating 领域色的中文名（落雪给的是英文枚举）。 */
    private fun possessionCn(raw: String): String = when (raw.lowercase()) {
        "normal" -> "普通"
        "copper" -> "铜"
        "silver" -> "银"
        "gold" -> "金"
        "platinum" -> "铂金"
        "rainbow" -> "虹"
        else -> raw
    }

    /** 把 bests 响应拆成若干组（两游戏字段名不同，结构都是「若干数组」）。 */
    private fun parseGroups(json: JsonObject, chuni: Boolean): List<FriendGroup> {
        val raw = if (chuni) {
            listOf(
                "Best 30（旧版本）" to json.arr("bests"),
                "Selection 10" to json.arr("selections"),
                "New 20（当前版本）" to json.arr("new_bests"),
            )
        } else {
            listOf(
                "Best 35（旧版本）" to json.arr("standard"),
                "Best 15（现版本）" to json.arr("dx"),
                "Selection 10（旧）" to json.arr("standard_selections"),
                "Selection 10（新）" to json.arr("dx_selections"),
            )
        }
        return raw.mapNotNull { (label, arr) ->
            if (arr.isEmpty()) {
                null
            } else {
                val scores = arr.map { parseScore(it, chuni) }.sortedByDescending { it.ra }
                FriendGroup(label, scores, scores.sumOf { it.ra })
            }
        }
    }

    private fun parseScore(json: JsonObject, chuni: Boolean): FriendScore {
        val fc = if (chuni) {
            val combo = json.str("full_combo")
            val chain = json.str("full_chain")
            when {
                combo.isNotBlank() -> comboCn(combo)
                chain.isNotBlank() -> chainCn(chain)
                else -> ""
            }
        } else {
            val fsRaw = json.str("fs")
            if (fsRaw.isNotBlank()) fsCn(fsRaw) else fcCn(json.str("fc"))
        }
        return FriendScore(
            songId = json.num("id").toInt(),
            title = json.str("song_name"),
            levelIndex = json.num("level_index").toInt(),
            level = json.str("level"),
            valueText = if (chuni) {
                json.num("score").toInt().toString()
            } else {
                "%.4f%%".format(json.num("achievements"))
            },
            ra = json.num(if (chuni) "rating" else "dx_rating"),
            fc = fc,
        )
    }

    private fun fcCn(raw: String): String = when (raw.lowercase()) {
        "app" -> "AP+"
        "ap" -> "AP"
        "fcp" -> "FC+"
        "fc" -> "FC"
        else -> raw.uppercase()
    }

    private fun fsCn(raw: String): String = when (raw.lowercase()) {
        "fsd" -> "FDX"
        "fsdp" -> "FDX+"
        "fsp" -> "FS+"
        "fs" -> "FS"
        else -> raw.uppercase()
    }

    private fun comboCn(raw: String): String = when (raw.lowercase()) {
        "alljusticecritical" -> "AJC"
        "alljustice" -> "AJ"
        "fullcombo" -> "FC"
        else -> raw
    }

    private fun chainCn(raw: String): String = when (raw.lowercase()) {
        "fullchain" -> "铂 FULL CHAIN"
        "fullchain2" -> "金 FULL CHAIN"
        else -> raw
    }

    /** GET 开发者 API，返回业务 JSON（自动剥 `{success,code,data}` 外壳）。 */
    private fun devGet(path: String): JsonObject {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", LxnsVault.devKey())
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val message = root?.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (!resp.isSuccessful || root == null) {
                    throw LxnsApi.LxnsException(resp.code, devErrorText(resp.code, message, body))
                }
                (root.get("data") as? JsonObject) ?: root
            }
        } catch (e: LxnsApi.LxnsException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "dev get failed: $path", e)
            throw LxnsApi.LxnsException(0, "网络异常：${e.javaClass.simpleName}")
        }
    }

    /** 开发者 API 错误码 → 人话（与直传通道同一口径，别各写一份）。 */
    private fun devErrorText(code: Int, message: String, body: String): String = when (code) {
        401 -> "开发者密钥无效（本安装包的构建密钥有问题）"
        403 -> "对方没有允许第三方查询：落雪设置里「允许他人查看我的玩家信息 / 成绩」需要打开"
        404 -> "落雪没有这个好友码，或者对方还没在落雪绑定玩家"
        429 -> "落雪限流（每秒 5 次），缓一下再查"
        else -> "HTTP $code ${message.ifBlank { body.take(120) }}"
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.num(key: String): Double =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

}
