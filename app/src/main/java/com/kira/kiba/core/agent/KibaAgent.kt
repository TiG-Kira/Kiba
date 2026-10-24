package com.kira.kiba.core.agent

import android.content.Context
import android.util.Log
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.random.Random

/**
 * Kiba Agent 引擎。
 *
 * 能力（全部本地优先，断网/未授权自动降级）：
 * - Best 50 查询（本地成绩缓存，未登录引导登录）；
 * - Rating 推分推荐（理论 ra 上限 - 当前 ra 的增益排序 + 未打高定数谱面）；
 * - 随机选曲（本地曲库）；
 * - 按别名/ID/标题查歌曲（本地曲库 + alias 表）；
 * - 舞萌游玩建议（内置攻略）；
 * - 兜底：联网搜索（DuckDuckGo），网络异常时明确降级提示。
 */
class KibaAgent(private val context: Context) {

    data class AgentSong(
        val id: Int,
        val title: String,
        val artist: String,
        val genre: String,
        val type: String,
        val topLevel: String,
        val topDs: Double,
    )

    data class AgentReply(
        val text: String,
        val songs: List<AgentSong> = emptyList(),
        val records: List<RecordEntity> = emptyList(),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun handle(input: String): AgentReply {
        val text = input.trim()
        if (text.isEmpty()) return AgentReply("说点什么吧，比如「我的 Best 50」。")
        return when {
            isLogin(text) -> AgentReply(
                "账户相关操作请在「我」页面完成：\n" +
                    "· 未绑定：点击顶部卡片 →「打开授权页面」，在浏览器完成水鱼 OAuth 授权后回填确认码；\n" +
                    "· 已绑定：在「我」页面可查看实时 Rating、Best 50，也可解除绑定。"
            )

            isBest50(text) -> best50()
            isPushRating(text) -> pushRating()
            isRandom(text) -> randomSong()
            isAdvice(text) -> AgentReply(ADVICE_TEXT)
            isHelp(text) -> AgentReply(HELP_TEXT)
            isSongQuery(text) -> querySong(text)
            else -> webFallback(text)
        }
    }

    // ---------- 意图识别 ----------

    private fun isLogin(t: String) =
        t.contains("登录") || t.contains("登陆") || t.contains("账号") || t.contains("账户")

    private fun isBest50(t: String): Boolean {
        val lower = t.lowercase()
        return lower.contains("best") || lower.contains("b50") || t.contains("最好成绩") ||
            t.contains("前五十") || t.contains("成绩单")
    }

    private fun isPushRating(t: String) =
        t.contains("推分") || t.contains("提分") || t.contains("推荐") || t.contains("rating", true) ||
            t.contains("上分") || t.contains("怎么涨")

    private fun isRandom(t: String) =
        t.contains("随机") || t.contains("随便") || t.contains("来一首") || t.contains("选曲") ||
            t.contains("random", true)

    private fun isAdvice(t: String) =
        t.contains("建议") || t.contains("攻略") || t.contains("怎么玩") || t.contains("怎么打") ||
            t.contains("如何提升") || t.contains("新手")

    private fun isHelp(t: String) =
        t == "帮助" || t == "help" || t.contains("你会什么") || t.contains("能做什么")

    private fun isSongQuery(t: String): Boolean {
        if (isRandom(t) || isBest50(t) || isPushRating(t) || isAdvice(t) || isHelp(t) || isLogin(t)) return false
        return t.contains("查歌") || t.contains("查一下歌") || t.contains("搜索") || t.contains("找歌") ||
            t.contains("这首歌") || t.contains("歌曲信息") || t.contains("定数") || t.contains("歌名")
    }

    // ---------- 能力实现 ----------

    private suspend fun best50(): AgentReply {
        val records = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().recordDao().getBest50List()
            }.getOrDefault(emptyList())
        }
        if (records.isEmpty()) {
            return AgentReply(
                "本地还没有成绩数据。\n请先在「我」页面登录查分器账户，登录后我会自动拉取你的 Best 50。"
            )
        }
        val total = records.sumOf { it.ra }
        val sb = StringBuilder()
        sb.append("你的 Best 50（合计 ra = $total）：\n")
        records.take(15).forEachIndexed { i, r ->
            sb.append(
                "${i + 1}. ${r.title} [${difficultyLabel(r.levelIndex)} ${r.level}] " +
                    "%.4f%%".format(r.achievements) + "  ra ${r.ra}\n"
            )
        }
        if (records.size > 15) sb.append("……共 ${records.size} 条\n")
        return AgentReply(sb.toString().trimEnd(), records = records)
    }

    private suspend fun pushRating(): AgentReply {
        val records = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().recordDao().getAllRecordList()
            }.getOrDefault(emptyList())
        }
        if (records.isEmpty()) {
            return AgentReply(
                "推分推荐需要你的成绩数据。\n请先在「我」页面登录查分器账户；登录前我可以先给你通用建议：\n\n" + ADVICE_TEXT
            )
        }
        // 1) 已打谱面：理论 ra 上限（SSS+ 100.5%）与当前 ra 的增益
        val gains = records.map { r ->
            val maxRa = floor(r.ds * 1.005).toInt()
            Triple(r, (maxRa - r.ra).coerceAtLeast(0), maxRa)
        }.sortedByDescending { it.second }

        // 2) 未打的高定数谱面（按 ds 取前 10）
        val playedKeys = records.map { it.songId to it.levelIndex }.toSet()
        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getAllChartsList()
            }.getOrDefault(emptyList())
        }
        val songs = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().songDao().getAllSongsList()
            }.getOrDefault(emptyList())
        }
        val titleById = songs.associate { it.id to it.title }
        val unplayed = charts
            .filter { it.difficultyType != DifficultyType.UTAGE && it.difficultyType != DifficultyType.UTAGE_PLAYER2 }
            .filter { (it.songId to it.difficultyType.ordinal) !in playedKeys }
            .sortedByDescending { it.ds }
            .take(10)

        val sb = StringBuilder()
        sb.append("推分推荐（按增益排序）：\n\n【提升现有成绩】\n")
        gains.filter { it.second > 0 }.take(8).forEach { (r, gain, maxRa) ->
            sb.append(
                "· ${r.title} [${difficultyLabel(r.levelIndex)} ${r.level}] " +
                    "%.4f%% → ra ${r.ra}（上限 $maxRa，可再 +$gain）\n".format(r.achievements)
            )
        }
        if (gains.none { it.second > 0 }) sb.append("· 你的 B50 已全部贴近理论上限，很强。\n")
        sb.append("\n【未打的高定数谱面】\n")
        if (unplayed.isEmpty()) {
            sb.append("· 没有找到未打谱面（曲库可能未更新）。\n")
        } else {
            unplayed.forEach { c ->
                sb.append(
                    "· ${titleById[c.songId] ?: "id ${c.songId}"} " +
                        "[${difficultyLabel(c.difficultyType.ordinal)} ${c.level}] ds ${c.ds}\n"
                )
            }
        }
        return AgentReply(sb.toString().trimEnd())
    }

    private suspend fun randomSong(): AgentReply {
        val song = withContext(Dispatchers.IO) {
            runCatching { AppDataBase.getInstance().songDao().randomSong() }.getOrNull()
        } ?: return AgentReply("曲库还是空的，请先到「设置 → 更新歌曲数据」同步曲库。")
        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getChartsBySongId(song.id)
            }.getOrDefault(emptyList())
        }
        val top = charts.maxByOrNull { it.ds }
        val sb = StringBuilder()
        sb.append("随机选曲：${song.title}\n")
        sb.append("艺术家：${song.artist}　流派：${song.genre}　类型：${song.type}\n")
        if (top != null) {
            sb.append(
                "最高难度：[${difficultyLabel(top.difficultyType.ordinal)} ${top.level}] ds ${top.ds}\n"
            )
        }
        sb.append("祝 greatness！")
        return AgentReply(
            sb.toString(),
            songs = listOf(
                AgentSong(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    genre = song.genre,
                    type = song.type,
                    topLevel = top?.level ?: "-",
                    topDs = top?.ds ?: 0.0,
                )
            ),
        )
    }

    private suspend fun querySong(text: String): AgentReply {
        // 提取查询词：去掉指令词后剩余部分，否则整句当关键词
        val query = text
            .replace("查歌", "").replace("查一下歌", "").replace("搜索", "")
            .replace("找歌", "").replace("这首歌", "").replace("歌曲信息", "")
            .replace("定数", "").replace("歌名", "").replace("的", "")
            .replace("是什么", "").replace("是多少", "")
            .replace("一下", "").replace("帮我", "").replace("我想", "")
            .trim().trim('：', ':', '？', '?', '。', '，', ',', ' ')
        if (query.isBlank()) {
            return AgentReply("想查哪首歌？直接告诉我歌名、别名或 ID，例如「查歌 青」")
        }
        val songs = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().songDao().searchSongsList(query, 20)
            }.getOrDefault(emptyList())
        }
        // ID 精确匹配（用户可能直接发数字）
        val idMatch = query.toIntOrNull()?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().songDao().getAllSongsList().firstOrNull { it.id == id }
                }.getOrNull()
            }
        }
        val matched = listOfNotNull(idMatch) + songs
        if (matched.isEmpty()) {
            return AgentReply(
                "本地曲库没找到「$query」。\n可以先在「设置 → 更新歌曲数据」同步最新曲库，或换个关键词（支持别名模糊匹配）。"
            )
        }
        val sb = StringBuilder()
        matched.distinctBy { it.id }.take(8).forEach { song ->
            val charts = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().chartDao().getChartsBySongId(song.id)
                }.getOrDefault(emptyList())
            }
            sb.append("${song.title}（${song.artist}）\n")
            if (charts.isEmpty()) {
                sb.append("  暂无谱面数据\n")
            } else {
                charts.sortedBy { it.difficultyType.ordinal }.forEach { c ->
                    sb.append(
                        "  [${difficultyLabel(c.difficultyType.ordinal)} ${c.level}] ds ${c.ds}" +
                            "　谱师：${c.charter}\n"
                    )
                }
            }
            sb.append("\n")
        }
        return AgentReply(sb.toString().trimEnd())
    }

    /** 联网兜底：DuckDuckGo lite；失败明确降级。 */
    private suspend fun webFallback(query: String): AgentReply {
        val results = webSearch(query)
        if (results.isEmpty()) {
            return AgentReply(
                "这个问题超出了我的本地知识，而联网搜索暂时不可用（网络异常或无结果）。\n" +
                    "已切换为本地建议：\n\n" + ADVICE_TEXT
            )
        }
        val sb = StringBuilder()
        sb.append("联网搜索结果（DuckDuckGo）：\n")
        results.forEachIndexed { i, (title, url) ->
            sb.append("${i + 1}. $title\n   $url\n")
        }
        sb.append("\n需要我针对某条结果展开说说吗？")
        return AgentReply(sb.toString().trimEnd())
    }

    private suspend fun webSearch(query: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://lite.duckduckgo.com/lite/?q=" +
                    URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Kiba/0.1")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val html = response.body?.string().orEmpty()
                    parseLiteResults(html)
                }
            }.onFailure { Log.w("KibaAgent", "webSearch failed", it) }.getOrDefault(emptyList())
        }

    private fun parseLiteResults(html: String): List<Pair<String, String>> {
        val linkRegex =
            Regex("""<a rel="nofollow" href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        return linkRegex.findAll(html)
            .map { match ->
                val url = match.groupValues[1]
                val title = match.groupValues[2]
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
                    .trim()
                title to url
            }
            .filter { it.first.isNotBlank() }
            .take(5)
            .toList()
    }

    private fun difficultyLabel(index: Int): String = when (index) {
        0 -> "BASIC"
        1 -> "ADVANCED"
        2 -> "EXPERT"
        3 -> "MASTER"
        4 -> "Re:MASTER"
        else -> "UTAGE"
    }

    companion object {
        private val HELP_TEXT = """
            我是 Kiba Agent，我会这些：
            · 「我的 Best 50」——查分器 Best 50 与合计 ra；
            · 「推荐推分曲目」——按增益排序的推分建议 + 未打高定数谱面；
            · 「随机来一首」——本地曲库随机选曲（含定数/谱师）；
            · 「查歌 <歌名/别名/ID>」——歌曲与谱面详情；
            · 「舞萌建议」——通用游玩与推分攻略；
            · 其他问题——我会尝试联网搜索（DuckDuckGo）。

            账户授权：成绩类功能需要在「我」页面登录查分器账户；
            未登录或断网时，我会明确提示并降级为本地能力。
        """.trimIndent()

        private val ADVICE_TEXT = """
            舞萌推分通用建议：
            1. Rating = Best 50 合计（新 35 + 旧 15），优先养“高定数 + 高达成”的谱面；
            2. 单曲 ra ≈ ⌊定数 × min(达成率, 100.5) / 100⌋，100.5%（SSS+）拿满该谱面上限；
            3. 定数优先级高于达成率：14.0 的 99% 往往比 13.5 的 100.5% 更值；
            4. 未打的高定数谱面是稳定的 ra 增量，先铺量再抠达成；
            5. 旧曲（B15）会被新成绩挤出，注意保持新旧组合的总量；
            6. 定数相近时优先 notes 构成熟悉的（break 少的更稳）。
        """.trimIndent()
    }
}
