package com.kira.kiba.core.listen

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.kira.kiba.core.agent.AgentMemory
import com.kira.kiba.core.agent.KibaLlm
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 智能推荐专属歌单（「听」页，仅 LLM 配置后可用）。
 *
 * ## 机制
 * 把用户口味画像（Best 40 流派分布 + 收藏列表 + Rating 水位）和一份**精选候选**
 * （本地 [SongRecommender] 打过分的曲目，带标题/曲师/流派/B40 定数）交给 LLM，
 * 让它挑出 ≤15 首并给一句推荐理由；结果 JSON 存 SP，每 `intervalHours`
 * （设置页可调，默认 24h）自动重生成。
 *
 * ## 为什么候选走本地预筛
 * 全库 1500+ 曲整包塞 prompt 既烧 token 又拉低命中率；先用本地画像把候选压到
 * ~60 首（分数序），LLM 只做「从相似候选里挑最有味的 15 首」这一层精致活。
 */
object AgentPlaylist {

    private const val TAG = "AgentPlaylist"

    private val gson = Gson()

    /** SP 里的一条 Agent 歌单。 */
    data class Saved(
        /** 曲目 id 列表（已按推荐顺序排好，均在曲库内）。 */
        @SerializedName("ids") val ids: List<Int>,
        /** 每首歌的一句推荐理由（index 对齐 ids；可能少于 ids）。 */
        @SerializedName("reasons") val reasons: List<String> = emptyList(),
        /** 歌单整段推荐语。 */
        @SerializedName("summary") val summary: String = "",
    ) {
        val isEmpty: Boolean get() = ids.isEmpty()
    }

    /** 当前保存的 Agent 歌单（无/解析失败返回 null）。 */
    fun load(): Saved? {
        val json = SpUtil.getAgentPlaylistJson()
        if (json.isBlank()) return null
        return runCatching { gson.fromJson(json, Saved::class.java) }.getOrNull()
    }

    /**
     * 从曲库实体 + 成绩 + 收藏直接生成专属歌单（听页一键入口）。
     * 内部完成画像提取（流派分布 / 收藏名 / Rating）与本地预筛，再调 [refresh]。
     */
    suspend fun refreshWithProfile(
        songs: List<SongWithChartsEntity>,
        records: List<RecordEntity>,
        favoriteIds: List<String>,
    ): Saved {
        // 成绩 ra 取每首歌最高（跨难度去重），构造轻量成绩
        val bestBySong = records.groupBy { it.songId }.mapValues { (_, rs) -> rs.maxOf { it.ra } }
        val genreOf = songs.associate { it.songData.id to it.songData.genre }

        // 流派画像：Best 40（ra 降序前 40 的歌按流派计数）
        val genreCount = LinkedHashMap<String, Int>()
        records.groupBy { it.songId }
            .mapValues { (_, rs) -> rs.maxOf { it.ra } }
            .toList().sortedByDescending { it.second }.take(40)
            .forEach { (songId, _) ->
                genreOf[songId]?.takeIf { it.isNotBlank() }?.let { genreCount.merge(it, 1, Int::plus) }
            }
        favoriteIds.mapNotNull { it.toIntOrNull() }.forEach { sid ->
            genreOf[sid]?.takeIf { it.isNotBlank() }?.let { genreCount.merge(it, 1, Int::plus) }
        }
        val genreProfile = genreCount.entries.sortedByDescending { it.value }
            .take(8).joinToString(" / ") { "${it.key} ${it.value}" }

        // 本地预筛候选（复用 SongRecommender 打分，取前 60 给 LLM 精选）
        val recIds = SongRecommender.recommend(
            songs = songs.map {
                SongRecommender.SongLite(
                    it.songData.id,
                    it.songData.genre,
                    it.songData.version.toIntOrNull(),
                )
            },
            records = bestBySong.map { (sid, ra) ->
                SongRecommender.RecordLite(sid, ra, genreOf[sid].orEmpty())
            },
            favoriteIds = favoriteIds.mapNotNull { it.toIntOrNull() }.toSet(),
            seed = System.nanoTime(),
        )
        val playedIds = bestBySong.keys
        val masterDs = songs.associate { swc ->
            swc.songData.id to swc.charts.filter { it.difficultyType.ordinal == 3 }
                .minOfOrNull { it.ds }
        }
        val candidates = recIds.mapNotNull { id ->
            songs.firstOrNull { it.songData.id == id }?.let { swc ->
                Profile(
                    id = id,
                    title = swc.songData.title,
                    artist = swc.songData.artist,
                    genre = swc.songData.genre,
                    ds = masterDs[id],
                    played = id in playedIds,
                )
            }
        }
        val favTitles = favoriteIds.mapNotNull { it.toIntOrNull() }.mapNotNull { sid ->
            songs.firstOrNull { it.songData.id == sid }?.songData?.title
        }
        return refresh(candidates, genreProfile, favTitles, null)
    }

    /** 距上次生成是否已过期（intervalHours 来自设置页）。 */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val generatedAt = SpUtil.getAgentPlaylistGeneratedAt()
        if (generatedAt <= 0) return true
        val intervalMs = SpUtil.getAgentPlaylistIntervalHours() * 3_600_000L
        return nowMs - generatedAt >= intervalMs
    }

    /**
     * 调 LLM 生成专属歌单并落盘。
     *
     * @param candidates 本地预筛候选（[SongRecommender] 已按分数排好序，
     *   建议传入打分排序后的前 60 首，UI 层负责从曲库实体转换）
     * @param genreProfile 流派画像摘要（如 "流行 12 / 东方 8 / niconico 5"）
     * @param favoriteTitles 收藏曲目名列表（截断到 30 个防止 prompt 过长）
     * @param rating 用户 Rating（冷启动/无成绩可为 null）
     * @return 保存结果；LLM 未配置/网络失败抛 [KibaLlm.LlmError]
     */
    suspend fun refresh(
        candidates: List<Profile>,
        genreProfile: String,
        favoriteTitles: List<String>,
        rating: Int?,
    ): Saved = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) throw IllegalStateException("曲库为空，无法生成推荐")
        check(KibaLlm.isConfigured()) { "LLM 未配置" }

        val max = SongRecommender.LIMIT
        val candidateLines = candidates.take(60).mapIndexed { i, c ->
            val parts = mutableListOf("${i + 1}. ${c.title} / ${c.artist} / ${c.genre}")
            c.ds?.let { parts.add("定数$it") }
            if (c.played) parts.add("已打过")
            parts.joinToString(" ")
        }
        val system = buildString {
            append("你是音乐游戏 maimai DX 的私人 DJ。根据玩家画像从候选里挑出最值得听的 ")
            append(max)
            append(" 首乐曲，做成一张专属歌单。\n")
            append("规则：\n")
            append("1. 只能从候选里选，数量 5~").append(max).append(" 首；\n")
            append("2. 兼顾口味延续与新鲜感：多数贴合画像，少量跳出舒适区；\n")
            append("3. 每首给不超过 20 字的中文推荐理由；\n")
            append("4. 严格输出 JSON：{\"summary\":\"歌单一句话\",\"reasons\":[\"理由1\",\"理由2\",...],\"picks\":[1,2,...]}，")
            append("picks 是候选序号（从 1 开始），reasons 与 picks 一一对应；不要输出 JSON 以外内容。")
            // 长期记忆里用户自己声明的口味 / 雷点，比统计出来的流派分布更权威
            AgentMemory.promptBlock().takeIf { it.isNotBlank() }?.let {
                append("\n若上面的长期记忆提到这个人的口味偏好或明确排斥，选曲时优先贴合它。\n")
                append(it)
            }
        }
        val user = buildString {
            append("玩家画像：")
            rating?.let { append("Rating $it；") }
            append("流派分布：").append(genreProfile.ifBlank { "暂无成绩" }).append("\n")
            append("收藏曲目：").append(favoriteTitles.take(30).joinToString("、").ifBlank { "暂无" }).append("\n")
            append("候选（序号. 标题 / 曲师 / 流派 / [定数] / [已打过]）：\n")
            append(candidateLines.joinToString("\n"))
        }

        val result = KibaLlm.chat(
            listOf(KibaLlm.Message.system(system), KibaLlm.Message.user(user)),
            maxTokens = 1200,
        ).getOrThrow()

        val saved = parse(result.text, candidates)
        SpUtil.setAgentPlaylistJson(gson.toJson(saved), System.currentTimeMillis())
        saved
    }

    /** 解析 LLM 输出：宽容处理围栏代码块 / 非法序号；picks 与 reasons 对齐截断。 */
    private fun parse(content: String, candidates: List<Profile>): Saved {
        val cleaned = content
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val root = JsonParser.parseString(cleaned).asJsonObject
        val summary = root.get("summary")?.asString.orEmpty()
        val reasons = root.getAsJsonArray("reasons")?.map { it.asString } ?: emptyList()
        val picks = root.getAsJsonArray("picks")?.map { it.asInt } ?: emptyList()

        // 序号 → 曲目 id：候选序号从 1 起；越界丢弃，去重保序
        val ids = picks.mapNotNull { n ->
            candidates.getOrNull(n - 1)?.id
        }.distinct()
        return Saved(ids = ids, reasons = reasons, summary = summary)
    }

    /** 候选条目（UI 层从曲库实体 + 画像转换）。 */
    data class Profile(
        val id: Int,
        val title: String,
        val artist: String,
        val genre: String,
        /** MASTER 定数（让 LLM 知道难度水位），可空。 */
        val ds: Double?,
        /** 是否已有成绩（帮 LLM 区分「回味」与「尝新」）。 */
        val played: Boolean,
    )
}
