package com.kira.kiba.core.account

import android.util.Log
import com.google.gson.JsonObject
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 账户页顶栏右上角头像的数据层。
 *
 * ## 为什么是「四格」而不是「两张」
 *
 * 舞萌和中二是**两套玩家**。同一份落雪凭据下，`/user/maimai/player` 与
 * `/user/chunithm/player` 各自的 `icon` 是两张不同的图；舞萌侧还有机台抓包这条独立来路。
 * 只按源存一张，切到中二就会把舞萌的脸挂在中二账户上 —— 所以按
 * **游戏 × 数据源** 分成四格，互不覆盖，切来切去都是各自那张：
 *
 * | | 水鱼源 | 落雪源 |
 * |---|---|---|
 * | 舞萌 | [saveFromMachine]（机台抓包，[extractFromProfileHtml]） | [saveFromLxns]（`player.icon.data`） |
 * | 中二 | 无来源（水鱼中二接口不回头像）→ 默认头像 | [saveFromLxns]（`player.icon.data`） |
 *
 * 四格都可能为空（没抓过包 / 落雪没回 icon / 中二水鱼源）。**空不是错误**，
 * UI 侧用 `MeAvatar` 的默认头像兜底，不要在这里硬造一张假图。
 *
 * 隐私：只存 URL / data URI，不带任何账号凭据；登出时由 [clearFor] 按源一起清掉
 * （水鱼 / 落雪各是一次全局登出，所以一次清掉两个游戏在该源下的格子）。
 */
object AccountAvatar {

    private const val TAG = "AccountAvatar"

    /** 四格头像快照。UI 只关心 [forAccount] 挑出来的那一条。 */
    data class Info(
        val maimaiMachine: String = "",
        val maimaiLxns: String = "",
        val chuniMachine: String = "",
        val chuniLxns: String = "",
    ) {
        fun forAccount(game: GameType, source: ScoreSource): String = when (game) {
            GameType.MAIMAI ->
                if (source == ScoreSource.LXNS) maimaiLxns else maimaiMachine
            GameType.CHUNITHM ->
                if (source == ScoreSource.LXNS) chuniLxns else chuniMachine
        }

        internal fun with(game: GameType, source: ScoreSource, url: String): Info = when {
            game == GameType.MAIMAI && source == ScoreSource.LXNS -> copy(maimaiLxns = url)
            game == GameType.MAIMAI -> copy(maimaiMachine = url)
            source == ScoreSource.LXNS -> copy(chuniLxns = url)
            else -> copy(chuniMachine = url)
        }
    }

    private val _state = MutableStateFlow(loadAll())
    val state: StateFlow<Info> = _state.asStateFlow()

    fun current(game: GameType, source: ScoreSource): String = _state.value.forAccount(game, source)

    /**
     * 写入某一格。
     *
     * [url] 为空 = 「这一格没有头像」，直接清掉旧值（换账号 / 解析失败都会走到这里）。
     * 也提供按来源的语义化入口 [saveFromMachine] / [saveFromLxns]，调用点别自己拼 [ScoreSource]。
     */
    fun save(game: GameType, source: ScoreSource, url: String) {
        val clean = url.trim()
        if (clean == _state.value.forAccount(game, source)) return
        SpUtil.saveAccountAvatar(game, source, clean)
        _state.value = _state.value.with(game, source, clean)
        Log.i(TAG, "avatar ${game.name}/${source.key} ${if (clean.isBlank()) "cleared" else "updated"}")
    }

    /** 机台抓包拿到的头像（只对舞萌有意义）。 */
    fun saveFromMachine(game: GameType, url: String) =
        save(game, ScoreSource.WATER_FISH, url)

    /** 落雪 `player.icon` 头像（两个游戏各自的 player 各调一次）。 */
    fun saveFromLxns(game: GameType, url: String) =
        save(game, ScoreSource.LXNS, url)

    /**
     * 退出登录时清掉某一数据源下的**两个游戏**。
     *
     * 水鱼 / 落雪都是整账号登出（凭据只有一份），两个游戏在该源下的头像必须一起清，
     * 否则退出后顶栏还挂着前一个账号的图。传 null 表示四格全清。
     */
    fun clearFor(source: ScoreSource?) {
        when (source) {
            ScoreSource.WATER_FISH -> {
                saveFromMachine(GameType.MAIMAI, "")
                saveFromMachine(GameType.CHUNITHM, "")
            }
            ScoreSource.LXNS -> {
                saveFromLxns(GameType.MAIMAI, "")
                saveFromLxns(GameType.CHUNITHM, "")
            }
            null -> {
                GameType.entries.forEach { g ->
                    ScoreSource.entries.forEach { s -> SpUtil.saveAccountAvatar(g, s, "") }
                }
                _state.value = Info()
            }
        }
    }

    private fun loadAll(): Info = Info(
        maimaiMachine = SpUtil.getAccountAvatar(GameType.MAIMAI, ScoreSource.WATER_FISH),
        maimaiLxns = SpUtil.getAccountAvatar(GameType.MAIMAI, ScoreSource.LXNS),
        chuniMachine = SpUtil.getAccountAvatar(GameType.CHUNITHM, ScoreSource.WATER_FISH),
        chuniLxns = SpUtil.getAccountAvatar(GameType.CHUNITHM, ScoreSource.LXNS),
    )

    // ── 取图 ───────────────────────────────────────────────────────────

    /**
     * 从落雪 `player` 对象里取头像，转成 `data:` URI。
     *
     * 落雪文档的 `icon` 是 `{id, data}`，`data` 为图片的 base64 编码（**不带** `data:` 前缀）。
     * 已经带前缀的（有些版本会带）原样返回，避免拼成 `data:image/png;base64,data:image/png;...`。
     * 拿不到 `data` 就返回 null —— 只有 id 的话还得再猜一个 CDN 地址，猜错反而是一张裂图。
     */
    fun fromLxnsPlayer(player: JsonObject?): String? {
        val icon = player?.get("icon")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = icon.get("data")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("data:", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "data:image/png;base64,$raw"
        }
    }

    /**
     * 从机台「玩家资料页」HTML 里捞出玩家头像 URL。
     *
     * 这一手是 **best-effort**：官方页面结构随时可能变，抓不到就返回 null，
     * 顶栏退默认头像，绝不影响成绩上传主流程。所以这里的匹配刻意写得宽：
     * 先试 `img/p/` 与 `img/icon/` 这两类玩家头像目录，再退一步找带 `icon` 字样的
     * `<img>`，相对路径统一补 `https://maimai.wahlap.com`。
     */
    fun extractFromProfileHtml(html: String?): String? {
        if (html.isNullOrBlank() || html.length < 200) return null
        val patterns = listOf(
            // 玩家头像（舞萌官网资料页里玩家图标都在 img/p/ 下，个别版本是 img/icon/）
            Regex("""<img[^>]+src=["']([^"']*?/img/p/[^"']+\.(?:png|jpg|jpeg))["']""", RegexOption.IGNORE_CASE),
            Regex("""<img[^>]+src=["']([^"']*?/img/icon/[^"']+\.(?:png|jpg|jpeg))["']""", RegexOption.IGNORE_CASE),
            // 兜底：任何带 icon 字样的图片（可能误抓，但只在上面两条全落空时才用）
            Regex("""<img[^>]+src=["']([^"']*icon[^"']+\.(?:png|jpg|jpeg))["']""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val found = pattern.find(html)?.groupValues?.getOrNull(1)?.let(::absoluteUrl)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    private fun absoluteUrl(url: String): String? {
        val clean = url.trim()
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("https://", true) -> clean
            clean.startsWith("http://", true) -> clean.replaceFirst("http://", "https://")
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "https://maimai.wahlap.com$clean"
            else -> "https://maimai.wahlap.com/$clean"
        }
    }
}
