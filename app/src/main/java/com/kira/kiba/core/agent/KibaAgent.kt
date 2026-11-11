package com.kira.kiba.core.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.utils.CacheCleaner
import com.kira.kiba.core.utils.CacheDirs
import com.kira.kiba.core.utils.Constants
import com.kira.kiba.core.data.ChuniRating
import com.kira.kiba.core.data.ChuniStats
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.meme.MemeAnimRenderer
import com.kira.kiba.core.meme.MemeCatalog
import com.kira.kiba.core.meme.MemeRenderer
import com.kira.kiba.core.meme.MemeTemplate
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.stats.ChuniPushRecommender
import com.kira.kiba.core.stats.RecommendFusion
import com.kira.kiba.core.listen.AgentPlaylistStore
import com.kira.kiba.core.listen.PlaylistCover
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.random.Random

/**
 * Kiba Agent 引擎。
 *
 * 设计原则（v0.3.0 起）：
 * - **聊天一律走 LLM**：用户输入不匹配任何本地意图，直接交给 LLM；
 *   LLM 通过 tool calling 调用本地技能，技能数据回灌给模型让它总结。
 * - **本地逻辑只在点功能菜单项时触发**：`runSkill()` 是唯一的本地执行入口，
 *   卡片点击直接产出结果卡片，不经过 LLM。
 * - 所有技能既能离线调用（点卡片），也能被 LLM 调用（作为 tool）。
 *
 * 技能来源：Kiba 原有能力 + KilluaSakata/Kiba（KibaBot）独有能力提炼
 * （舞萌运势 / 定数查歌 / 分数线 / 随机条件曲 / 猜歌等），不含塔罗与群管理。
 */
class KibaAgent(private val context: Context) {

    data class AgentSong(
        val id: Int,
        val title: String,
        val artist: String,
        val genre: String,
        /** 舞萌 SD/DX；中二留空（没有这个区分，UI 见到空串就不渲染类型徽标）。 */
        val type: String,
        val topLevel: String,
        val topDs: Double,
        val imageUrl: String = "",
        /** 最高难度的名字：舞萌 紫/白，中二 MASTER/ULTIMA（由各自的 label 给）。 */
        val difficulty: String = "",
        /**
         * 这首歌属于哪个游戏：`maimai` / `chunithm`。
         *
         * 两套曲库 id 空间**不重叠但会撞数字**，卡片点击必须靠这个字段决定
         * 跳「歌曲详情」还是「中二歌曲详情」，不能靠 id 猜。
         */
        val game: String = GAME_MAIMAI,
    )

    /**
     * 功能菜单项：可点击直接执行（无需 LLM），同时作为 LLM 的 tool。
     *
     * @property params 技能入参；既是 LLM 的 JSON Schema，也是卡片说明。
     * @property paramHint 卡片点击时弹层里的输入提示（只有带参技能用到）。
     */
    data class AgentSkill(
        val id: String,
        val title: String,
        val summary: String,
        val params: List<KibaLlm.ToolParam> = emptyList(),
        val paramHint: String = "",
    )

    /** B50 卡：总分 + 旧曲 35 / 新曲 15 分别的合计。 */
    data class Best50Info(
        val total: Int,
        val b35: Int,
        val b15: Int,
        val oldCount: Int,
        val newCount: Int,
        val entries: List<RecordEntity> = emptyList(),
    )

    data class AgentReply(
        val text: String,
        val songs: List<AgentSong> = emptyList(),
        val records: List<RecordEntity> = emptyList(),
        val skills: List<AgentSkill> = emptyList(),
        val b50: Best50Info? = null,
        /** 梗图类技能产出的图片 Uri（已存相册），用于在对话里直接展示。 */
        val imageUri: String = "",
        /** 本次回复里**真正调用过**的工具（供对话流里做技能调用展示）。 */
        val calls: List<ToolInvocation> = emptyList(),
    )

    /**
     * 一次真实的技能调用记录（对话流里以「技能调用」卡片展示）。
     *
     * 和 [AgentSkill] 的区别：AgentSkill 是**技能目录**（点卡片用），
     * ToolInvocation 是**这一次实际发生了什么**——模型点了哪个技能、带了什么参数、
     * 成没成功、拿到什么。WorkBuddy 那种「调用了 X」的展示就是这层信息。
     */
    data class ToolInvocation(
        /** 技能 id（对应 [AgentSkill.id]）。 */
        val id: String,
        /** 展示名（技能标题）。 */
        val title: String,
        /** 参数摘要，一行（无参数为空串）。 */
        val args: String = "",
        /** 是否成功。 */
        val ok: Boolean = true,
        /** 结果摘要，一行（过长会截断）。 */
        val summary: String = "",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 聊天统一入口：**只走 LLM**。
     * 未配置 LLM 时给出明确引导（功能菜单项仍可离线使用）。
     */
    suspend fun handle(input: String): AgentReply {
        val text = input.trim()
        if (text.isEmpty()) return AgentReply("说点什么吧。")
        if (!KibaLlm.isConfigured()) {
            return AgentReply(
                "当前未配置 LLM，无法自由提问。\n" +
                    "请点上方「功能」按钮直接使用（Best 50 / 推分 / 随机 / 查歌 / 运势 / 猜歌…），" +
                    "或到「设置 → Agent 模型配置」开启对话能力。",
                skills = SKILLS,
            )
        }
        return runAgent(text, null)
    }

    /**
     * 流式聊天入口：**只走 LLM**，正文 / 思考过程通过 [onChunk] 增量回传。
     * 流程同 [runAgent]：模型可调用技能，最多 [MAX_ROUNDS] 轮；只是每轮都流式。
     */
    suspend fun handleStream(
        input: String,
        onChunk: suspend (KibaLlm.StreamChunk) -> Unit,
    ): AgentReply {
        val text = input.trim()
        if (text.isEmpty()) return AgentReply("说点什么吧。")
        if (!KibaLlm.isConfigured()) {
            return AgentReply(
                "当前未配置 LLM，无法自由提问。\n" +
                    "请点上方「功能」按钮直接使用（Best 50 / 推分 / 随机 / 查歌 / 运势 / 猜歌…），" +
                    "或到「设置 → Agent 模型配置」开启对话能力。",
                skills = SKILLS,
            )
        }
        return runAgent(text, onChunk)
    }

    /** 直接执行某个技能（功能菜单项点击专用，不经过 LLM）。 */
    suspend fun runSkill(id: String, arg: String = ""): AgentReply = when (id) {
        "best50" -> best50()
        "push_rating" -> pushRating()
        "random_song" -> randomSong(arg)
        "song_query" -> querySong(arg)
        "fortune" -> fortune()
        "ds_query" -> dsQuery(arg)
        "score_line" -> scoreLine(arg)
        "random_filtered" -> randomFiltered(arg)
        "guess_song" -> guessSong()
        "chuni_song" -> chuniSongQuery(arg)
        "chuni_ds" -> chuniDsQuery(arg)
        "chuni_random" -> chuniRandom()
        "chuni_best" -> chuniBest()
        "chuni_push" -> chuniPush()
        "random_number" -> {
            val nums = Regex("\\d+").findAll(arg).map { it.value.toIntOrNull() }.filterNotNull().toList()
            if (nums.size >= 2) randomNumber(nums[0], nums[1]) else randomNumber(1, 100)
        }
        "roll_dice" -> {
            val nums = Regex("\\d+").findAll(arg).map { it.value.toIntOrNull() }.filterNotNull().toList()
            val sides = if (arg.contains("百面") || nums.any { it == 100 }) 100 else 6
            rollDice(nums.firstOrNull { it <= 10 } ?: 1, sides)
        }
        "today_kink" -> todayKink()
        "eat_what" -> eatWhat()
        "meme" -> {
            // 功能菜单走专用模板选择弹层；这里兼容「key|文字1|文字2」的直输写法
            val parts = arg.split("|").map { it.trim() }
            runMeme(parts.firstOrNull().orEmpty(), parts.drop(1).filter { it.isNotEmpty() })
        }
        "playlist_cover" -> playlistCover(emptyMap())
        else -> AgentReply("未知技能：$id", skills = SKILLS)
    }

    /**
     * LLM 工具调用入口：把模型给出的 arguments（JSON 字符串）解析成技能能吃的参数。
     *
     * 兼容两类模型输出：
     * - 规范：{"query":"群青"} / {"min":12,"max":13} / {"target":99.5,"difficulty":"紫"}
     * - 不规范：arguments 为空、字段名与约定不一致 —— 一律回退到用户原始输入 [fallbackInput]
     *   （本地解析器本来就吃自由文本，所以兜底是安全的）。
     */
    suspend fun runToolCall(name: String, argumentsJson: String, fallbackInput: String): AgentReply {
        val args = KibaLlm.parseArguments(argumentsJson)
        // 模型没按字段名给值时，把值拼成自由文本交给本地解析器
        val fallbackText = args.values.joinToString(" ").ifBlank { fallbackInput }
        return when (name) {
            "song_query" -> querySong(
                args["query"] ?: args["keyword"] ?: args["title"] ?: fallbackText
            )
            "ds_query" -> {
                val lo = args["min"]?.toDoubleOrNull()
                    ?: args["ds_min"]?.toDoubleOrNull()
                    ?: args["ds"]?.toDoubleOrNull()
                val hi = args["max"]?.toDoubleOrNull()
                    ?: args["ds_max"]?.toDoubleOrNull()
                if (lo != null || hi != null) {
                    val l = lo ?: hi!!
                    val h = hi ?: lo!!
                    dsQueryRange(l.coerceAtMost(h), l.coerceAtLeast(h))
                } else {
                    dsQuery(fallbackText)
                }
            }
            "score_line" -> {
                val songKey = args["song"] ?: args["song_id"] ?: args["id"] ?: ""
                val diffWord = args["difficulty"] ?: args["level"] ?: ""
                val line = args["line"]?.toDoubleOrNull()
                    ?: args["target"]?.toDoubleOrNull()
                    ?: args["rate"]?.toDoubleOrNull()
                if (songKey.isNotBlank() && diffWord.isNotBlank() && line != null) {
                    scoreLineChart(songKey, diffWord, line)
                } else {
                    scoreLine(fallbackText)
                }
            }
            "random_number" -> {
                val lo = args["min"]?.toDoubleOrNull()?.toInt()
                val hi = args["max"]?.toDoubleOrNull()?.toInt()
                if (lo != null && hi != null) randomNumber(lo, hi) else randomNumber(1, 100)
            }
            "roll_dice" -> {
                val count = args["count"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 10) ?: 1
                val sides = args["sides"]?.toDoubleOrNull()?.toInt() ?: 6
                rollDice(count, sides)
            }
            "meme" -> {
                val key = args["template"] ?: args["key"] ?: args["name"] ?: fallbackInput
                val raw = args["texts"] ?: args["text"] ?: ""
                val extras = args.entries
                    .filter { it.key.matches(Regex("text\\d+")) }
                    .sortedBy { it.key }
                    .map { it.value }
                runMeme(key, (parseMemeTexts(raw) + extras).distinct())
            }
            "today_kink" -> todayKink()
            "eat_what" -> eatWhat()
            "playlist_cover" -> playlistCover(args)
            "web_search" -> webSearch(
                args["query"] ?: args["q"] ?: args["keyword"] ?: fallbackText,
                args["url"] ?: args["href"] ?: "",
            )
            "moegirl_kb" -> moegirlKb(
                args["query"] ?: args["q"] ?: args["keyword"] ?: fallbackText
            )
            "browser" -> browserAction(args)
            "maimai_chart_simai" -> maimaiChartSimai(args)
            "simai_syntax" -> simaiSyntax(
                args["topic"] ?: args["query"] ?: args["q"] ?: fallbackText
            )
            "memory" -> memoryAction(args, fallbackText)
            "random_filtered" -> randomFiltered(
                args["condition"] ?: args["filter"] ?: fallbackText
            )
            "chuni_song" -> chuniSongQuery(
                args["query"] ?: args["keyword"] ?: args["title"] ?: fallbackText
            )
            "chuni_ds" -> {
                val lo = args["min"]?.toDoubleOrNull()
                    ?: args["ds_min"]?.toDoubleOrNull()
                    ?: args["ds"]?.toDoubleOrNull()
                val hi = args["max"]?.toDoubleOrNull()
                    ?: args["ds_max"]?.toDoubleOrNull()
                if (lo != null || hi != null) {
                    val l = lo ?: hi!!
                    val h = hi ?: lo!!
                    chuniDsQuery("$l $h")
                } else {
                    chuniDsQuery(fallbackText)
                }
            }
            else -> runSkill(name, fallbackText)
        }
    }

    /**
     * LLM 工具循环：模型可调用本地技能，最多 [MAX_ROUNDS] 轮；
     * 工具结果回灌后由模型总结成自然语言回答。
     *
     * @param onChunk 非空 → 流式（正文 / 思考过程增量回传）；空 → 一次性返回
     */
    private suspend fun runAgent(
        input: String,
        onChunk: (suspend (KibaLlm.StreamChunk) -> Unit)?,
    ): AgentReply {
        val messages = mutableListOf(
            KibaLlm.Message.system(
                buildList {
                    add(SpUtil.getLlmSystemPrompt())
                    add(localContext())
                    // 长期记忆拼在本地上下文之后、技能说明之前：
                    // 它是「关于这个人的稳定事实」，要在模型读技能清单前就进入视野。
                    AgentMemory.promptBlock().takeIf { it.isNotBlank() }?.let { add(it) }
                    add(skillHint())
                }.joinToString("\n\n")
            )
        )
        messages += AgentSession.llmHistory(maxTurns = 8)
        messages += KibaLlm.Message.user(input)

        // LLM 专用工具（如联网搜索）也一并暴露给模型，但不进功能菜单项
        val tools = (SKILLS + LLM_ONLY_TOOLS).map { KibaLlm.ToolDef(it.id, it.summary, it.params) }
        var collected = AgentReply("")
        // 思考型模型可能把 token 全烧在 reasoning 上，正文一个字都没吐出来。
        // 这种情况下加额重试一次，而不是把思考当答案。
        var budget = SpUtil.getLlmMaxTokens()
        var budgetRetried = false
        // 已兜底执行过的技能，避免重复调用
        val executed = mutableSetOf<String>()
        // 本次回复里真正执行过的工具（对话流「技能调用」展示用）
        val invocations = mutableListOf<ToolInvocation>()
        // 已经回传给 UI 的条数：只推增量，UI 才能按段追加而不是重复铺同一张卡
        var emittedCalls = 0

        /** 把新执行完的工具增量推给 UI（独立成段，跟在上面那段进展文字后面）。 */
        suspend fun emitCalls() {
            val sink = onChunk ?: return
            if (invocations.size <= emittedCalls) return
            sink(KibaLlm.StreamChunk(calls = invocations.drop(emittedCalls)))
            emittedCalls = invocations.size
        }
        // finish_reason == "length" 的续写次数（续写到的正文用 accumulated 累积，
        // 流式路径下 onChunk 已在追加，这里只保证非流式路径拼得出来）
        var accumulated = ""
        var continuations = 0

        /**
         * 轮次用尽时的收尾：**不再把原始数据甩给用户**，也不再让模型继续调工具，
         * 而是把已拿到的真实数据塞回去，让它用一句自然语言把结论说完整。
         * 这是「维持会话直到 LLM 主动结束」的兜底——只有模型自己收不了尾时才用。
         */
        suspend fun finalAnswer(): AgentReply {
            val nudge = messages + KibaLlm.Message.user(
                "工具调用轮次已到上限。请立刻基于上面已经拿到的真实数据，" +
                    "用自然语言完整回答用户最初的问题，不要再调用任何工具，也不要解释流程。"
            )
            val r = if (onChunk != null) {
                KibaLlm.chatStream(nudge, emptyList(), maxTokens = budget, onChunk = onChunk).getOrNull()
                    ?: KibaLlm.chat(nudge, emptyList(), maxTokens = budget).getOrNull()
            } else {
                KibaLlm.chat(nudge, emptyList(), maxTokens = budget).getOrNull()
            }
            val tail = r?.text?.trim().orEmpty()
            return collected.copy(
                text = (accumulated + tail).ifBlank {
                    collected.text.ifBlank { "技能已执行完，数据在上面几张卡里。" }
                },
                calls = collected.calls + invocations,
            )
        }

        for (round in 0 until MAX_ROUNDS) {
            val result = if (onChunk != null) {
                KibaLlm.chatStream(messages, tools, maxTokens = budget, onChunk = onChunk).getOrElse {
                    // 流式不可用（不支持 SSE / 网络错误 / 中途被採断）：退回一次性请求。
                    // 流式可能已流出半截内容：先 reset 清掉，再整块回传，避免新旧文本拼接重复。
                    val single = KibaLlm.chat(messages, tools, maxTokens = budget).getOrElse { e ->
                        return AgentReply("LLM 调用失败：${e.message}", skills = SKILLS)
                    }
                    onChunk(KibaLlm.StreamChunk(reset = true))
                    onChunk(KibaLlm.StreamChunk(text = single.text, reasoning = single.reasoning))
                    single
                }
            } else {
                KibaLlm.chat(messages, tools, maxTokens = budget).getOrElse { e ->
                    return AgentReply("LLM 调用失败：${e.message}", skills = SKILLS)
                }
            }

            if (result.toolCalls.isNotEmpty()) {
                // 正常路径：模型真的调了工具
                val nativeCalls = result.toolCalls.filter { it.id.isNotBlank() }
                val inlineCalls = result.toolCalls.filter { it.id.isBlank() }
                val results = LinkedHashMap<KibaLlm.ToolCall, String>()
                for (call in result.toolCalls) {
                    val reply = runToolCall(call.name, call.arguments, input)
                    // 技能结果里的 skills 是「技能目录」（引导用户去功能菜单用的），
                    // 不能并入 LLM 回复 —— 否则一次工具调用就会在气泡下面铺出整排
                    // 不可点击的技能说明卡（用户看到的就是「弹一串卡片」）。
                    collected = collected.merge(reply.copy(skills = emptyList()))
                    results[call] = reply.text
                    executed += call.name
                    invocations += invocationOf(call.name, call.arguments, reply)
                }
                // 实时回传已调用技能（增量），让「技能调用」卡按段浮现
                emitCalls()

                if (nativeCalls.isNotEmpty()) {
                    // 原生 function calling：assistant(tool_calls) + tool(tool_call_id) 成对回灌
                    messages.add(
                        KibaLlm.Message.assistantToolCalls(
                            content = result.text,
                            calls = nativeCalls,
                            reasoning = result.reasoning,
                        )
                    )
                    nativeCalls.forEach { call ->
                        messages.add(
                            KibaLlm.Message.toolResult(
                                toolCallId = call.id,
                                content = results[call].orEmpty(),
                            )
                        )
                    }
                }
                if (inlineCalls.isNotEmpty()) {
                    // 文本内联协议：没有 tool_call_id，只能按普通对话回灌
                    messages.add(
                        KibaLlm.Message.assistant(
                            inlineCalls.joinToString("；") { "[调用技能 ${it.name}]" }
                        )
                    )
                    inlineCalls.forEach { call ->
                        messages.add(
                            KibaLlm.Message.user(
                                "技能 ${call.name} 返回如下（这是本地真实数据，请据此用自然语言回答用户）：\n" +
                                    results[call].orEmpty()
                            )
                        )
                    }
                }

                if (round == MAX_ROUNDS - 1) {
                    // 轮次用尽：让模型基于已拿到的数据把话说完整，而不是甩原始数据
                    return finalAnswer()
                }
                continue
            }

            // 没有工具调用 —— 看是「有答案」还是「只在空谈」
            val answer = result.text.trim()
            val narration = detectNarratedSkills(answer.ifBlank { result.reasoning })
            val unpicked = narration.filter { it !in executed }

            if (narration.isNotEmpty() && unpicked.isNotEmpty()) {
                // 模型嘴上说「我打算调用 best50 / push_rating」却没真调：
                // 直接按它点名的技能兜底执行，把真实数据拿回来再让它总结。
                // 已流出的空谈正文先清掉，否则用户会看到一段没有结论的话。
                onChunk?.invoke(KibaLlm.StreamChunk(reset = true))
                val picked = unpicked
                val results = LinkedHashMap<String, String>()
                for (name in picked) {
                    val reply = runToolCall(name, "", input)
                    // 技能结果里的 skills 是「技能目录」（引导用户去功能菜单用的），
                    // 不能并入 LLM 回复 —— 否则一次工具调用就会在气泡下面铺出整排
                    // 不可点击的技能说明卡（用户看到的就是「弹一串卡片」）。
                    collected = collected.merge(reply.copy(skills = emptyList()))
                    results[name] = reply.text
                    executed += name
                    invocations += invocationOf(name, "", reply)
                }
                // 实时回传已调用技能（增量），让「技能调用」卡按段浮现
                emitCalls()
                messages.add(KibaLlm.Message.assistant("[已执行技能 ${picked.joinToString("、")}]"))
                picked.forEach { name ->
                    messages.add(
                        KibaLlm.Message.user(
                            "技能 $name 返回如下（这是本地真实数据，请据此用自然语言回答用户）：\n" +
                                results[name].orEmpty()
                        )
                    )
                }
                if (round == MAX_ROUNDS - 1) {
                    return finalAnswer()
                }
                continue
            }

            if (narration.isNotEmpty() && answer.isBlank() && collected.text.isNotBlank()) {
                // 模型反复空谈「我要调用 X」但 X 都已执行过、正文始终空白：
                // 手里已有真实数据，直接交付，不再让模型空转到底
                return collected.copy(calls = collected.calls + invocations)
            }

            if (answer.isBlank() && result.reasoning.isNotBlank() && !budgetRetried) {
                // 典型的「思考烧光 token」：正文被截没了。清掉这段思考，加额重试一次。
                onChunk?.invoke(KibaLlm.StreamChunk(reset = true))
                budgetRetried = true
                budget = (budget * 2).coerceAtMost(MAX_TOKENS_CAP)
                continue
            }

            // 输出被 max_tokens 截断（finish_reason == "length"）：之前会当成正常收尾，
            // 用户看到的就是「说到一半自己停了」。这里接上断点续写，直到模型自然结束。
            if (result.truncated && answer.isNotBlank() && continuations < MAX_CONTINUATIONS) {
                continuations++
                accumulated += answer
                messages.add(KibaLlm.Message.assistant(result.text))
                messages.add(
                    KibaLlm.Message.user(
                        "上一条回复被输出长度上限截断了。请**紧接断点继续写完**：" +
                            "不要重复已经输出的内容，不要重新开头，不要加任何前言或客套。"
                    )
                )
                // 续写往往还需要输出空间：顺手把预算抬到下一个档
                budget = (budget * 2).coerceAtMost(MAX_TOKENS_CAP)
                continue
            }

            // 有答案 → 这一轮就是最终答复
            return collected.copy(
                text = (accumulated + answer).ifBlank {
                    collected.text.ifBlank {
                        if (result.reasoning.isNotBlank()) {
                            "模型只输出了思考过程，没有给出结论。可以换个说法再问一次，或点上方「功能」按钮直接使用。"
                        } else {
                            "（模型没有返回内容）"
                        }
                    }
                },
                calls = collected.calls + invocations,
            )
        }
        // 循环自然走完（理论上到不了）：按收尾处理，保证永远有自然语言答复
        return finalAnswer()
    }

    /**
     * 检测「模型只是在描述打算调用哪些技能」。
     *
     * 有些模型 / 中转不支持 function calling，模型会写
     * 「我打算调用 best50 和 push_rating 来获取数据」然后就没有下文了 ——
     * 用户看到的是一句空话。识别出这些技能名就能兜底执行。
     *
     * 只在**没有真工具调用**时使用，且必须命中明确的「将要调用」语义，
     * 避免把「根据查询结果 best50 总分是…」这种正常回答误判成空谈。
     */
    private fun detectNarratedSkills(text: String): List<String> =
        LlmParse.detectNarratedSkills(text, SKILLS.map { it.id })

    /** 合并两次回复的产物（技能数据 / 卡片）。 */
    private fun AgentReply.merge(other: AgentReply): AgentReply = AgentReply(
        text = text + other.text,
        songs = songs + other.songs,
        records = records + other.records,
        // 多次技能调用会把 skills=SKILLS 反复带回来，按 id 去重避免卡片重复
        skills = (skills + other.skills).distinctBy { it.id },
        b50 = b50 ?: other.b50,
        imageUri = imageUri.ifBlank { other.imageUri },
        calls = calls + other.calls,
    )

    /** 把一次工具调用记成可展示的 [ToolInvocation]（参数与结果各压成一行）。 */
    private fun invocationOf(name: String, argsJson: String, reply: AgentReply): ToolInvocation {
        val meta = SKILLS.firstOrNull { it.id == name } ?: LLM_ONLY_TOOLS.firstOrNull { it.id == name }
        val args = runCatching {
            val map = KibaLlm.parseArguments(argsJson)
            map.entries
                .filter { it.value.isNotBlank() }
                .joinToString("、") { (k, v) -> "$k=$v" }
        }.getOrDefault("")
        return ToolInvocation(
            id = name,
            title = meta?.title ?: name,
            args = args.take(60),
            ok = !reply.text.startsWith("出错了") && !reply.text.startsWith("未知技能"),
            summary = reply.text.lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .take(80),
        )
    }

    /** 给 LLM 的技能说明（含每个技能的入参与调用示例）。 */
    private fun skillHint(): String = buildString {
        append("【可用技能】（需要真实数据时调用工具，不要凭空编造）\n")
        (SKILLS + LLM_ONLY_TOOLS).forEach { skill ->
            append("- ${skill.id}：${skill.summary}")
            if (skill.params.isEmpty()) {
                append("（无参数）\n")
            } else {
                append("\n")
                skill.params.forEach { p ->
                    append("    · ${p.name}（${p.type}${if (p.required) "，必填" else "，可选"}）：${p.description}\n")
                }
            }
        }
        append(
            "【调用格式】需要数据时输出 function call：工具名用上面的 id，参数放在 arguments（JSON 对象）里。\n" +
                "例：查歌 → {\"name\":\"song_query\",\"arguments\":{\"query\":\"群青\"}}；" +
                "定数区间 → {\"name\":\"ds_query\",\"arguments\":{\"min\":12,\"max\":13}}；" +
                "分数线 → {\"name\":\"score_line\",\"arguments\":{\"target\":99.5,\"difficulty\":\"紫\"}}。\n" +
                "一次可以调用多个技能（如 best50 和 push_rating 一起调）。\n" +
                "如果当前接口不支持 function calling，就把同样的 JSON 单独输出一行" +
                "（例：{\"name\":\"best50\",\"arguments\":{}}），Kiba 会识别并执行。\n" +
                "用户没给足参数时，要么用自然语言追问，要么用最宽松的默认值调用，不要瞎填数字。\n" +
                "【重要】调用完工具后必须基于返回的真实数据给出最终中文答复，" +
                "不要只描述「我打算调用什么」，也不要复述工具调用的过程。\n" +
                "【游戏知识】关于 maimai 的游戏机制问题（新版本 / 新模式玩法、功能道具获取、" +
                "解谜任务、活动情报等），必须先调用 web_search 查资料再总结回答；" +
                "除非搜索后确实一无所获，否则不允许直接说不知道，更不能编造。\n" +
                "【谱面问题】用户问某首歌某难度的谱面（配置 / 物量 / 滑键 / 长按 / 变速 / 段落 / " +
                "「这段怎么写」）时，按 song_query 查曲目 ID → maimai_chart_simai 取标准 Simai " +
                "谱面 → 看不懂记号就调 simai_syntax → 基于真实谱面文本回答。" +
                "没拿到谱面文本时不许凭印象描述配置，也不许编造确定的物量数字。\n" +
                "【浏览器】web_search 已用后台浏览器读渲染后正文；若页面还要点按钮 / 展开 / 翻页 / " +
                "读某个元素 / 执行 JS，改用 browser（先 open，再对同一页面 text / click / fill / eval）。\n" +
                "【表情包 / 主动表达】meme 生成的表情包会直接贴在对话里，不需要在正文里描述图片。" +
                "当你想玩梗、吐槽、表达哭笑不得 / 无语 / 震惊 / 得意等情绪时，**可以主动调用 meme**，" +
                "不必等用户说「生成表情包」；挑贴合语境的模板，文字写中文短句，" +
                "之后照常给出自然语言答复（图文并茂，别只甩一张图就结束）。" +
                "需要用户照片的模板无法自动调用，请改选纯文字模板；不确定有哪些模板就把 template 传 list。\n" +
                "【长期记忆】用户说「记住…」「以后都…」「别忘了…」时，调用 memory(save) 真的写进长期记忆" +
                "（用户可在「设置 → Agent → 长期记忆」查看 / 修改 / 删除），不要只回一句「好的我记住了」；" +
                "用户要你忘掉某件事时用 memory(forget)。\n" +
                MemeCatalog.usageHint(context)
        )
    }

    /** 本地数据上下文：注入 LLM 的 maimai 事实（登录态 / Rating / 曲库版本），防止编造。 */
    private suspend fun localContext(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("【本地数据上下文】\n")
        val account = DivingFishAccount.state.value
        if (account.bound) {
            sb.append("· 查分器已登录：${account.nickname.ifBlank { "用户" }}，DX Rating ${account.rating}，")
            sb.append("本地成绩 ${account.recordCount} 条\n")
        } else {
            sb.append("· 查分器未登录（Best 50 / Rating 类问题应引导用户去「我」页面登录）\n")
        }
        runCatching {
            val songs = AppDataBase.getInstance().songDao().getAllSongsList()
            sb.append("· 本地曲库版本 ${SpUtil.getDataVersion().ifBlank { "未知" }}，共 ${songs.size} 首歌\n")
        }
        // 中二是平行表：不补这一段，模型会拿舞萌 Rating 当中二 Rating 用
        runCatching {
            val stats = ChuniStats.compute()
            if (stats.isEmpty) {
                sb.append("· 中二节奏：本地没有成绩数据（中二相关问题应引导去「我」→ 中二节奏同步）\n")
            } else {
                sb.append(
                    "· 中二节奏 Rating ${"%.2f".format(stats.rating)}" +
                        "（非当前 TOP30 ${stats.nonCurrent.size} 首 / 当前 TOP20 ${stats.current.size} 首，" +
                        "本地成绩 ${stats.recordCount} 条）\n"
                )
            }
        }
        sb.append("回答涉及玩家本人数据时，以上下文为准，不知道就说不知道。")
        sb.toString()
    }

    // ---------- 能力实现 ----------

    private suspend fun best50(): AgentReply {
        val b50 = DivingFishAccount.best50()
        if (b50.old.isEmpty() && b50.new.isEmpty()) {
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().recordDao().getBest50List()
                }.getOrDefault(emptyList())
            }
            if (local.isEmpty()) {
                return AgentReply(
                    "本地还没有成绩数据。\n请先在「我」页面登录查分器账户，登录后我会自动拉取你的 Best 50。"
                )
            }
            val total = local.sumOf { it.ra }
            return AgentReply(
                "Best 50（本地缓存，合计 ra = $total）：\n" +
                    local.take(10).joinToString("\n") { r ->
                        "· ${r.title} [${difficultyLabel(r.levelIndex)} ${r.level}] " +
                            "%.4f%%".format(r.achievements) + "  ra ${r.ra}"
                    },
                records = local,
            )
        }
        return AgentReply(
            "你的 Best 50：总分 ${b50.rating}\n" +
                "· 旧曲 ${b50.old.size} 首 = ${b50.oldRa}\n" +
                "· 新曲 ${b50.new.size} 首 = ${b50.newRa}",
            records = b50.all,
            b50 = Best50Info(
                total = b50.rating,
                b35 = b50.oldRa,
                b15 = b50.newRa,
                oldCount = b50.old.size,
                newCount = b50.new.size,
                entries = b50.all,
            ),
        )
    }

    private suspend fun pushRating(): AgentReply {
        val records = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().recordDao().getAllRecordList()
            }.getOrDefault(emptyList())
        }
        if (records.isEmpty()) {
            return AgentReply(
                "推分推荐需要你的成绩数据。\n请先在「我」页面登录查分器账户，" +
                    "登录后我会按你的 Best 50 缺口给出针对性建议。"
            )
        }
        // 曲库先取：宴会场判定（流派/六位数 id）与 SD/DX 类型判定需要
        val songs = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().songDao().getAllSongsList()
            }.getOrDefault(emptyList())
        }
        val titleById = songs.associate { it.id to it.title }
        val typeById = songs.associate { it.id to it.type }
        // 宴会场谱面不获 Rating（官方设定）：流派 = 宴会場、六位数 id 段、或等级带「?」（如 14?）
        val utageIds = utageSongIds(songs)

        // 成绩里混入的宴会场谱面（等级带「?」）不参与 Rating 计算，一并排除
        val played = records.filter { !it.level.contains("?") && it.songId !in utageIds }

        // ── Best 50 地板：优先查分器数据，退回本地记录。分池标准是「登场版本」：
        //    当前版本曲（isNew）→ B15，旧版本曲（含旧版 DX 谱）→ B35；
        //    谱面类型 SD/DX 只决定档位乘数表，不决定分池。──
        val isNewById = songs.associate { it.id to it.isNew }
        val b50 = DivingFishAccount.best50()
        val b35 = b50.old.ifEmpty {
            played.filter { isNewById[it.songId] == false }.sortedByDescending { it.ra }.take(35)
        }
        val b15 = b50.new.ifEmpty {
            played.filter { isNewById[it.songId] == true }.sortedByDescending { it.ra }.take(15)
        }
        val b35Floor = b35.lastOrNull()?.ra
        val b15Floor = b15.lastOrNull()?.ra
        if (b35Floor == null && b15Floor == null) {
            return AgentReply("还没有 Best 50 成绩数据，先去打几首再来看推分建议吧。")
        }

        // 当前成绩索引：songId + 难度 → 记录
        val recordByKey = played.associateBy { it.songId to it.levelIndex }

        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getAllChartsList()
            }.getOrDefault(emptyList())
        }

        // ── 候选：按官方 Rating 档位表全面计算（floor 口径，DX/SD 两套乘数，与上游一致）。
        //    对每个谱面找「最低的、ra 能超过对应地板的档位」作为目标：
        //    当前版本曲（isNew）对标 B15 地板，旧版本曲对标 B35 地板；
        //    档位乘数表仍按谱面类型 SD/DX 选（与分池无关）；
        //    且要求本曲还没打到该档位（打到了就没有提升空间）。
        val candidates = charts
            .filter { !isUtageChart(it, utageIds) }
            .mapNotNull { c ->
                // 谱面类型只用于过滤「曲库没登记类型的谱面」；档位乘数 SD/DX 共用一套
                if (typeById[c.songId] == null) return@mapNotNull null
                val isNew = isNewById[c.songId] ?: return@mapNotNull null
                val floor = (if (isNew) b15Floor else b35Floor) ?: return@mapNotNull null
                val current = recordByKey[c.songId to c.difficultyType.ordinal]
                val target = RA_TIERS
                    .firstOrNull { (boundary, mult) ->
                        tierRa(c.ds, boundary, mult) > floor &&
                            (current == null || current.achievements < boundary - 1e-9)
                    }
                    ?: return@mapNotNull null
                val potential = tierRa(c.ds, target.first, target.second)
                Cand(
                    songId = c.songId,
                    levelIndex = c.difficultyType.ordinal,
                    level = c.level,
                    ds = c.ds,
                    isNew = isNew,
                    tierAch = target.first,
                    potential = potential,
                    floor = floor,
                    gap = target.first - (current?.achievements ?: 0.0),
                    current = current,
                )
            }
            // 达成率差距小的（最近一档有分的）优先，同差距下潜在 ra 高的优先
            .sortedWith(compareBy({ it.gap }, { -it.potential }))
            .take(10)

        // ── EasyMai 融合：水鱼大数据两路参考（拟合定数热门 / 定数差值性价比）。
        //    Kiba 档位候选仍是主回答，这里只补充「全服玩家视角」的参考曲目；
        //    chart_stats 未同步或为空时静默降级为纯 Kiba 档位推荐。──
        val fusionRating = b50.rating.takeIf { it > 0 }
            ?: (b35.sumOf { it.ra } + b15.sumOf { it.ra })
        val fusion = withContext(Dispatchers.IO) {
            runCatching {
                val stats = AppDataBase.getInstance().chartStatsDao().getAllChartStatsList()
                if (stats.isEmpty()) return@runCatching null
                RecommendFusion.recommend(
                    stats = stats.map {
                        RecommendFusion.StatChart(
                            songId = it.songId,
                            levelIndex = it.levelIndex,
                            cnt = it.cnt ?: 0.0,
                            avg = it.avg ?: 0.0,
                            fitDiff = it.fitDiff,
                        )
                    },
                    dsByChart = charts
                        .filter { !isUtageChart(it, utageIds) }
                        .associate { it.songId to it.difficultyType.ordinal to it.ds },
                    rating = fusionRating,
                )
            }.getOrNull()
        }

        val sb = StringBuilder()
        // ── Markdown 排版（AgentScreen 走 GFM 渲染器）──
        // 歌名加粗作每条锚点；难度定数用行内代码做「chip」；条目间 `---` 分隔线明确分批。
        sb.append("## 推分推荐\n\n")
        sb.append("B35 地板 ra ${b35Floor ?: "—"} · B15 地板 ra ${b15Floor ?: "—"}\n\n")
        if (candidates.isEmpty()) {
            sb.append("暂无合适目标：高于地板的谱面你都贴着档位上限了，去冲更高定数的新谱面吧。")
        } else {
            candidates.forEachIndexed { index, c ->
                // 条目之间插分隔线（最后一条之后不留尾线）
                if (index > 0) sb.append("\n---\n\n")
                val cur = c.current
                val rank = rankName(c.tierAch)
                val listName = if (c.isNew) "B15" else "B35"
                // 歌名先转义 Markdown 元字符再加粗，防止歌名里的 * _ ` [ ] ~ 破坏排版
                val title = mdEscape(titleById[c.songId] ?: "id ${c.songId}")
                sb.append(
                    "**$title** · `${difficultyLabel(c.levelIndex)} ${c.level}` · 定数 ${c.ds}\n"
                )
                when {
                    cur == null -> {
                        // 未游玩：打上目标档位即进入 Best 50，顶替地板
                        sb.append(
                            "未游玩：达成 %.1f%%（%s）得 ra ${c.potential} → 顶替 $listName 地板（**+${c.potential - c.floor}**）\n"
                                .format(c.tierAch, rank)
                        )
                    }
                    cur.ra > c.floor -> {
                        // 已在列表内：本曲涨多少，总分就加多少
                        sb.append(
                            "当前 %.4f%%（ra ${cur.ra}）：还差 %.2f%% 达成 %.1f%%（%s）得 ra ${c.potential} → 总分还可 **+${c.potential - cur.ra}**\n"
                                .format(cur.achievements, c.gap, c.tierAch, rank)
                        )
                    }
                    else -> {
                        // 成绩低于地板（不在列表内）：打上去是顶替地板进榜，
                        // 实际 Rating 增益 = potential - floor，不能按 potential - cur.ra 算
                        //（那会把地板与现有成绩的差距也错算成增益，虚高好几 ra）。
                        sb.append(
                            "当前 %.4f%%（ra ${cur.ra}，低于 $listName 地板 ${c.floor}）：还差 %.2f%% 达成 %.1f%%（%s）得 ra ${c.potential} → 顶替地板 **+${c.potential - c.floor}**\n"
                                .format(cur.achievements, c.gap, c.tierAch, rank)
                        )
                    }
                }
            }
        }

        // ── EasyMai 融合输出：水鱼大数据参考（有数据且任一路非空才展示）──
        if (fusion != null && (fusion.fitCandidates.isNotEmpty() || fusion.diffCandidates.isNotEmpty())) {
            sb.append("\n\n---\n\n")
            sb.append("## 水鱼大数据参考\n\n")
            sb.append(
                "平均定数 ${fusion.averageDs} · 推荐定数范围 **${fusion.rangeMin} ~ ${fusion.rangeMax}**（EasyMai 算法）\n\n"
            )
            if (fusion.fitCandidates.isNotEmpty()) {
                sb.append("### 热门高均分\n\n")
                sb.append(
                    fusion.fitCandidates.take(5).joinToString("\n") { c ->
                        val title = mdEscape(titleById[c.songId] ?: "id ${c.songId}")
                        "- **$title** · `${difficultyLabel(c.levelIndex)} ${c.ds}` · " +
                            "全服均分 ${"%.2f%%".format(c.avg)} · 样本 ${c.cnt.toInt()}"
                    }
                )
                sb.append("\n\n")
            }
            if (fusion.diffCandidates.isNotEmpty()) {
                sb.append("### 定数虚高性价比\n\n")
                sb.append(
                    fusion.diffCandidates.take(5).joinToString("\n") { c ->
                        val title = mdEscape(titleById[c.songId] ?: "id ${c.songId}")
                        "- **$title** · `${difficultyLabel(c.levelIndex)}` " +
                            "官标 ${c.ds} / 拟合 ${"%.2f".format(c.fitDiff ?: 0.0)}" +
                            "（虚高 +${"%.2f".format(c.dsDifference ?: 0.0)}）"
                    }
                )
            }
        }
        return AgentReply(sb.toString().trimEnd())
    }

    /** 推分候选：谱面 + 目标档位 + 潜在 ra + 与地板/当前成绩的关系。 */
    private data class Cand(
        val songId: Int,
        val levelIndex: Int,
        val level: String,
        val ds: Double,
        /** 当前版本曲（B15 池）；旧版本曲（B35 池）为 false。 */
        val isNew: Boolean,
        /** 目标档位的达成率下限（如 100.5）。 */
        val tierAch: Double,
        /** 该档位下的单曲 ra。 */
        val potential: Int,
        /** 对应列表的地板 ra。 */
        val floor: Int,
        /** 距目标档位的达成率差距（百分点）。 */
        val gap: Double,
        /** 当前成绩（未游玩为 null）。 */
        val current: RecordEntity?,
    )

    /**
     * 单曲 Rating 档位表：达成率下限 → 乘数（机台口径，floor 计算）。
     *
     * 与上游 MaimaiData `ConvertUtils.achievementToRating` 的档位对齐。
     * **SD / DX 谱共用同一张表** —— 机台对标准谱与 DX 谱用同一套系数，
     * 之前这里给 SD 单列了一套「14.0 / 13.5 / …」的低档表（来源不明），
     * 会让 Agent 把标准谱的潜在 ra 严重低估（推分建议偏保守）。
     */
    private val RA_TIERS = listOf(
        100.5 to 22.4, 100.0 to 21.6, 99.5 to 21.1, 99.0 to 20.8,
        98.0 to 20.3, 97.0 to 20.0, 94.0 to 16.8, 90.0 to 15.2,
        80.0 to 13.6, 75.0 to 12.0, 70.0 to 11.2, 60.0 to 9.6, 50.0 to 8.0,
    )

    /** 档位达成率下限 → 达成率（floor 口径）：floor(定数 × 达成率/100 × 乘数)。 */
    private fun tierRa(ds: Double, tierAch: Double, mult: Double): Int =
        floor(ds * (tierAch.coerceAtMost(100.5) / 100.0) * mult).toInt()

    /** 档位达成率下限 → 评级名（官方档位）。 */
    private fun rankName(tierAch: Double): String = when (tierAch) {
        100.5 -> "SSS+"
        100.0 -> "SSS"
        99.5 -> "SS+"
        99.0 -> "SS"
        98.0 -> "S+"
        97.0 -> "S"
        94.0 -> "AAA"
        90.0 -> "AA"
        80.0 -> "A"
        75.0 -> "BBB"
        70.0 -> "BB"
        60.0 -> "B"
        50.0 -> "C"
        else -> "%.1f%%".format(tierAch)
    }

    private suspend fun randomSong(arg: String = ""): AgentReply {
        // 随机也排除宴会场（流派 = 宴会場 / 六位数 id / 全谱面等级带「?」）：这类谱面不获 Rating
        val (target, charts) = pickNonUtageSong()
            ?: return AgentReply("曲库还是空的，请先到「设置 → 曲库更新」同步曲库。")
        val top = charts.maxByOrNull { it.ds }
        val sb = StringBuilder()
        sb.append("随机选曲：${target.title}\n")
        sb.append("曲师：${target.artist}　流派：${target.genre}　类型：${target.type}\n")
        if (top != null) {
            sb.append(
                "最高难度：[${difficultyLabel(top.difficultyType.ordinal)} ${top.level}] 定数 ${top.ds}\n"
            )
        }
        sb.append("祝 greatness！")
        return AgentReply(
            sb.toString(),
            songs = listOf(target.toAgentSong(top)),
        )
    }

    private suspend fun querySong(text: String): AgentReply {
        val query = extractQuery(text)
        if (query.isBlank()) {
            return AgentReply("想查哪首歌？直接告诉我歌名、别名或 ID，例如「查歌 青」")
        }
        val songs = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().songDao().searchSongsList(query, 20)
            }.getOrDefault(emptyList())
        }
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
                "本地曲库没找到「$query」。\n可以先在「设置 → 曲库更新」同步最新曲库，或换个关键词（支持别名模糊匹配）。"
            )
        }
        val sb = StringBuilder()
        val cards = mutableListOf<AgentSong>()
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
                        "  [${difficultyLabel(c.difficultyType.ordinal)} ${c.level}] 定数 ${c.ds}" +
                            "　谱师：${c.charter}\n"
                    )
                }
            }
            sb.append("\n")
            cards += song.toAgentSong(charts.maxByOrNull { it.ds })
        }
        return AgentReply(sb.toString().trimEnd(), songs = cards)
    }

    /**
     * 今日舞萌运势（源自 KibaBot 的「今日舞萌」）。
     *
     * 按日期做确定性伪随机（同一天结果稳定），内容由运势等级 + 宜忌 + 曲目推荐组成。
     */
    private suspend fun fortune(): AgentReply {
        val today = java.time.LocalDate.now()
        val seed = today.toEpochDay()
        val rnd = Random(seed)

        val level = when (rnd.nextInt(100)) {
            in 0..9 -> "大凶"
            in 10..24 -> "凶"
            in 25..44 -> "末吉"
            in 45..69 -> "小吉"
            in 70..89 -> "吉"
            else -> "大吉"
        }
        val stars = when (level) {
            "大吉" -> 5
            "吉" -> 4
            "小吉" -> 3
            "末吉" -> 2
            "凶" -> 1
            else -> 0
        }
        val bar = "★".repeat(stars) + "☆".repeat(5 - stars)

        val good = listOf(
            "挑战新难度", "刷高定数谱面", "推分", "整理 Best 50", "学习新曲",
            "连打热身", "打神曲", "研究判定表", "换个玩法", "拉好友一起打",
        )
        val bad = listOf(
            "硬刚未练过的谱面", "连打手指疼的曲子", "通宵练习", "只打高难", "乱按一通",
            "疲劳作战", "空腹打歌", "冲动补曲",
        )
        val goodPick = good[rnd.nextInt(good.size)]
        val badPick = bad[rnd.nextInt(bad.size)]

        // 今日推荐曲目同样排除宴会场（流派/六位数 id/全谱面等级带「?」）
        val picked = pickNonUtageSong()
        val song = picked?.first
        val charts = picked?.second.orEmpty()
        val top = charts.maxByOrNull { it.ds }

        val sb = StringBuilder()
        sb.append("今日舞萌运势（${today}）\n")
        sb.append("运势：$level　$bar\n")
        sb.append("宜：$goodPick\n")
        sb.append("忌：$badPick\n")
        if (song != null) {
            sb.append("今日推荐曲目：${song.title}（${song.artist}）")
            if (top != null) {
                sb.append("　[${difficultyLabel(top.difficultyType.ordinal)} ${top.level}] 定数 ${top.ds}")
            }
            sb.append("\n")
        }
        sb.append("祝今天出货顺利、greatness！")
        return AgentReply(
            sb.toString(),
            songs = song?.let { listOf(it.toAgentSong(top)) } ?: emptyList(),
        )
    }

    /**
     * 定数查歌（源自 KibaBot「定数查歌」）：按定数区间列出歌曲。
     * 参数形如「12.5」「12 13」「13以上」。
     */
    private suspend fun dsQuery(arg: String): AgentReply {
        val range = parseDsRange(arg)
            ?: return AgentReply("请给出定数范围，例如「定数查歌 12.5」或「定数查歌 12 13」。")
        return dsQueryRange(range.first, range.second)
    }

    /** 定数查歌本体：按 [lo]–[hi] 闭区间检索。 */
    private suspend fun dsQueryRange(lo: Double, hi: Double): AgentReply {
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
        val artistById = songs.associate { it.id to it.artist }
        val utageIds = utageSongIds(songs)
        val matched = charts
            .filter { !isUtageChart(it, utageIds) }
            .filter { it.ds >= lo && it.ds <= hi }
            .sortedByDescending { it.ds }
            .take(30)
        if (matched.isEmpty()) {
            return AgentReply("曲库里没有定数在 ${fmtRange(lo, hi)} 的谱面。")
        }
        val sb = StringBuilder("定数 ${fmtRange(lo, hi)} 的谱面（共 ${matched.size} 首，显示前 ${minOf(matched.size, 30)} 首）：\n")
        matched.forEach { c ->
            sb.append(
                "· ${titleById[c.songId] ?: "id ${c.songId}"}（${artistById[c.songId] ?: "-"}）" +
                    " [${difficultyLabel(c.difficultyType.ordinal)} ${c.level}] 定数 ${c.ds}\n"
            )
        }
        return AgentReply(sb.toString().trimEnd())
    }

    /**
     * 分数线（对齐 KibaBot「分数线 <难度+歌曲id> <分数线>」）：
     * 给定某首歌的某个难度与目标达成率，输出达成该线允许的各种判定容错
     * （TAP GREAT 等价数），与 Bot 输出同一套公式与表格。
     *
     * Bot 公式：
     * - total_score = 500×tap + 1500×slide + 1000×hold + 500×touch + 2500×break
     * - break_bonus = 0.01 / break；break_50_reduce = total_score × break_bonus / 4
     * - reduce = 101 − 目标达成率
     * - 单个判定的 ACHV. 损失 = 10000 / total_score × 权重（TAP 1 / HOLD 2 / SLIDE 3；
     *   GREAT=×1、GOOD=×2.5、MISS=×5）
     */
    private suspend fun scoreLineChart(songKey: String, diffWord: String, line: Double): AgentReply {
        val reduce = 101.0 - line
        if (reduce <= 0 || reduce >= 101) {
            return AgentReply("分数线要在 0 ~ 101 之间（例：分数线 紫799 100）。")
        }
        val diff = parseDifficultyKeyword(diffWord)
            ?: return AgentReply("请指定难度（绿 / 黄 / 红 / 紫 / 白），例：「分数线 紫799 100」。")

        // 歌曲：优先按 ID，其次按歌名模糊匹配
        val song = withContext(Dispatchers.IO) {
            val dao = AppDataBase.getInstance().songDao()
            songKey.trim().toIntOrNull()?.let { id ->
                runCatching { dao.getAllSongsList().firstOrNull { it.id == id } }.getOrNull()
            } ?: runCatching { dao.searchSongsList(songKey.trim(), 5) }.getOrDefault(emptyList()).firstOrNull()
        } ?: return AgentReply("没找到「$songKey」这首曲子，换一个 ID 或歌名试试。")

        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getChartsBySongId(song.id)
            }.getOrDefault(emptyList())
        }
        val chart = charts.firstOrNull { it.difficultyType == diff }
            ?: return AgentReply("「${song.title}」没有 ${difficultyLabel(diff.ordinal)} 难度。")

        val tap = chart.notesTap
        val hold = chart.notesHold
        val slide = chart.notesSlide
        val touch = chart.notesTouch
        val brk = chart.notesBreak
        if (brk <= 0) return AgentReply("该谱面缺少 BREAK 数据，算不了分数线。")
        val totalScore = 500.0 * tap + 1500.0 * slide + 1000.0 * hold + 500.0 * touch + 2500.0 * brk
        val break50Reduce = totalScore * (0.01 / brk) / 4.0
        val unit = 10000.0 / totalScore
        val base = totalScore * reduce / 10000.0

        val sb = StringBuilder()
        sb.append("▾ ACHV. Line | 分数线\n")
        sb.append("♪ ${song.id}（${song.type}）\n")
        sb.append("${song.title} | ${difficultyLabel(diff.ordinal)}\n")
        sb.append("设置的达成率为${"%.4f".format(line).trimEnd('0').trimEnd('.')}%，其 Note 损失参照如下:\n")
        sb.append("----------------------\n")
        sb.append("此表格遵循的格式为:\n类型 | ACHV.损失/个 | 最多损失数\n----------------------\n")
        sb.append("Great 评价:\n")
        sb.append("Tap & Touch | ${"%.4f".format(unit)}% | ${"%.2f".format(base)}\n")
        sb.append("Hold | ${"%.4f".format(unit * 2)}% | ${"%.2f".format(base / 2)}\n")
        sb.append("Slide | ${"%.4f".format(unit * 3)}% | ${"%.2f".format(base / 3)}\n")
        sb.append("Good 评价:\n")
        sb.append("Tap & Touch | ${"%.4f".format(unit * 2.5)}% | ${"%.2f".format(base / 2.5)}\n")
        sb.append("Hold | ${"%.4f".format(unit * 5)}% | ${"%.2f".format(base / 5)}\n")
        sb.append("Slide | ${"%.4f".format(unit * 7.5)}% | ${"%.2f".format(base / 7.5)}\n")
        sb.append("Miss 评价:\n")
        sb.append("Tap & Touch | ${"%.4f".format(unit * 5)}% | ${"%.2f".format(base / 5)}\n")
        sb.append("Hold | ${"%.4f".format(unit * 10)}% | ${"%.2f".format(base / 10)}\n")
        sb.append("Slide | ${"%.4f".format(unit * 15)}% | ${"%.2f".format(base / 15)}\n\n")
        sb.append("Break 各评价损失:\n")
        sb.append("注意: Break 的 Great 与 Perfect 评价都有细分等级，此表格的 Break Great 不做细分。\n")
        sb.append(
            "本谱面每个 Break Perfect 2600 的达成率是 ${
                "%.4f".format(unit * 25 + break50Reduce / totalScore * 100 * 4)
            }%，谱面共 $brk 个 Break，其占总体达成率的 ${
                "%.4f".format((unit * 25 + break50Reduce / totalScore * 100 * 4) * brk)
            }%。\n"
        )
        sb.append("----------------------\n类型 | ACHV.损失/个 | Tap Great 等价数\n----------------------\n")
        sb.append("Perfect 2550 | ${"%.4f".format(break50Reduce / totalScore * 100)}% | ${"%.3f".format(break50Reduce / 100)}\n")
        sb.append(
            "Perfect 2500 | ${"%.4f".format(break50Reduce / totalScore * 100 * 2)}% | ${
                "%.3f".format(break50Reduce / 100 * 2)
            }\n"
        )
        sb.append(
            "Great | ≈${"%.4f".format(unit * 5 + break50Reduce / totalScore * 100 * 4)}% | ≈${
                "%.3f".format(5 + break50Reduce / 100 * 4)
            }\n"
        )
        sb.append(
            "Good | ${"%.4f".format(unit * 12.5 + break50Reduce / totalScore * 100 * 4)}% | ${
                "%.3f".format(12.5 + break50Reduce / 100 * 4)
            }\n"
        )
        sb.append(
            "Miss | ${"%.4f".format(unit * 25 + break50Reduce / totalScore * 100 * 4)}% | ${
                "%.3f".format(25 + break50Reduce / 100 * 4)
            }"
        )
        return AgentReply(sb.toString())
    }

    /** 分数线文本入口：「分数线 紫799 100」/「分数线 799 紫 100」。 */
    private suspend fun scoreLine(arg: String): AgentReply {
        val diffWord = parseDifficultyWord(arg)
        val line = Regex("\\d+(?:\\.\\d+)?").findAll(arg)
            .map { it.value.toDoubleOrNull() }.filterNotNull().toList()
        if (line.isEmpty()) {
            return AgentReply(
                "分数线用法：分数线 <难度+歌曲id> <分数线>\n" +
                    "例：分数线 紫799 100 —— 返回达到该达成率允许的 TAP GREAT 容错。\n" +
                    "难度关键词：绿 / 黄 / 红 / 紫 / 白。"
            )
        }
        if (line.size < 2 || diffWord.isBlank()) {
            return AgentReply("请给全「难度 + 歌曲 ID + 目标达成率」，例：分数线 紫799 100")
        }
        val songKey = arg.replace(diffWord, "")
            .let { Regex("[0-9]+").find(it)?.value } ?: line.first().toInt().toString()
        return scoreLineChart(songKey, diffWord, line.last())
    }

    /** 随机数（源自 KibaBot「XrY」）：在 [min, max] 闭区间随机取整数。 */
    private fun randomNumber(min: Int, max: Int): AgentReply {
        if (min > max) return AgentReply("随机数范围写反了：$min 比 $max 还大。")
        val v = Random.nextInt(min, max + 1)
        return AgentReply("▾ 随机数\n您的随机数是 $v。（$min ~ $max）")
    }

    /** 投骰子（源自 KibaBot「投骰子[数量]」「投百面骰子[数量]」），最多 10 颗。 */
    private fun rollDice(count: Int, sides: Int): AgentReply {
        if (count < 1) return AgentReply("骰子数量至少 1 个。")
        if (count > 10) return AgentReply("骰子数量不能大于 10 个，你是要刷屏嘛？")
        if (sides < 2) return AgentReply("骰子至少要有 2 面。")
        val sb = StringBuilder("▾ 骰子（${sides} 面 × $count）\n结果如下:")
        repeat(count) { i ->
            sb.append("\n第 ${i + 1} 个骰子 投掷结果是: ${Random.nextInt(1, sides + 1)}点")
        }
        return AgentReply(sb.toString())
    }

    /**
     * 表情包生成器：功能菜单（可带用户选的图片）与 LLM tool 共用。
     *
     * 模板来自 MemeCrafters/meme-generator，见 [MemeCatalog]。
     * `template` 传 `list` / `?` 时返回可生成模板清单，方便模型和自行探索。
     * 旧的「低情商高情商」「gocho」已经并进这里（high_EQ / 5000choyen），
     * 上游版本的渐变描边与排版比早期手搓实现更贴近原图。
     */
    suspend fun runMeme(
        templateKey: String,
        texts: List<String> = emptyList(),
        imageUris: List<Uri> = emptyList(),
    ): AgentReply = withContext(Dispatchers.IO) {
        val key = templateKey.trim()
        if (key.isBlank() || key.equals("list", ignoreCase = true) || key == "?" || key == "帮助") {
            return@withContext AgentReply(MemeCatalog.listText(MemeCatalog.renderable(context)))
        }
        val template = MemeCatalog.find(context, key)
            ?: MemeCatalog.search(context, key, onlyRenderable = false).firstOrNull()
            ?: return@withContext AgentReply(
                "没找到「$key」这个模板。把 template 传 list 可以查看全部可生成模板。"
            )
        if (!template.renderable) {
            return@withContext AgentReply(
                "「${template.name}」${template.unavailableReason}；" +
                    "换个可生成的模板吧（template 传 list 看清单）。"
            )
        }
        if (template.requiredImages > imageUris.size) {
            return@withContext AgentReply(
                "「${template.name}」需要 ${template.requiredImages} 张图片，请到" +
                    "「功能 → 娱乐功能 → 表情包生成器」里手动选图生成；" +
                    "想直接出图可以换纯文字模板，如 high_EQ / 5000choyen / good_news。"
            )
        }
        val filled = texts.ifEmpty { template.defaultTexts }
        val script = template.anim
        if (script != null) {
            // 动图要逐帧合成 + GIF 编码，几百毫秒到几秒，交给进度弹窗交代清楚
            _memeProgress.value = MemeProgress(template.name, "共 ${script.frames.size} 帧，正在合成…")
            val bitmaps = ArrayList<android.graphics.Bitmap>()
            try {
                imageUris.take(template.requiredImages).forEach { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()?.let { bitmaps.add(it) }
                }
                val result = MemeAnimRenderer.render(context, template, filled, bitmaps)
                    ?: return@withContext AgentReply(
                        "「${template.name}」动图合成失败：帧资源或图片读取出错。"
                    )
                return@withContext saveMemeGif(result.gif, template.name)
            } finally {
                _memeProgress.value = null
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
        val bmp = MemeRenderer.render(context, template, filled, imageUris)
            ?: return@withContext AgentReply("「${template.name}」生成失败：模板资源或图片读取出错。")
        saveMeme(bmp, template.name)
    }

    /** 模型给的文字可能是 JSON 数组、竖线分隔或单句。 */
    private fun parseMemeTexts(raw: String): List<String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[")) {
            val arr = runCatching { JSONArray(t) }.getOrNull()
            if (arr != null) {
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return t.split("|", "\n").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(t) }
    }

    /** 动图暂存为 .gif（对话里用 AnimatedImageDrawable 播放）并回传 content URI。 */
    private fun saveMemeGif(bytes: ByteArray, tag: String): AgentReply {
        CacheCleaner.markGenerationActive()
        val dir = CacheDirs.generated(context)
        val name = "kiba_${tag}_${System.currentTimeMillis()}.gif"
        val file = java.io.File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return AgentReply(
            "已生成「$tag」动图（${bytes.size / 1024} KB，点开可看；长期不用会被自动清理）。",
            imageUri = uri.toString(),
        )
    }

    /** 梗图暂存到缓存（cacheDir/generated）并回传 content URI 供对话内展示。 */
    private fun saveMeme(bmp: android.graphics.Bitmap, tag: String): AgentReply {
        CacheCleaner.markGenerationActive()
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val dir = CacheDirs.generated(context)
        val name = "kiba_${tag}_${System.currentTimeMillis()}.png"
        val file = java.io.File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return AgentReply(
            "已生成「$tag」图片（点开可查看；长期不用会被自动清理）。",
            imageUri = uri.toString(),
        )
    }


    // ══════════════════════ 中二节奏（CHUNITHM）技能 ══════════════════════
    //
    // 中二是**平行表**（chuni_song / chuni_chart / chuni_record），查询逻辑不能套舞萌：
    // 没有 SD/DX 之分、没有宴会場、成绩是 score（0–1009000）而不是达成率、
    // 单曲 Rating 口径也不同（官方 /50 公式，见 ChuniRating）。
    //
    // 但**输出格式**（Markdown 正文 + AgentSong 卡片）与舞萌保持一致，
    // UI 侧只按 AgentSong.game 决定点开哪个详情页，不用写两套渲染。

    /** 中二难度标签（0..4，WE 不参与 Rating 也基本不在曲库里单独出现）。 */
    private fun chuniLabel(index: Int): String = ChuniDiffType.fromIndex(index).label

    /** 中二查歌：歌名 / 假名 / 曲师 / 流派 / 曲目 id 模糊匹配。 */
    private suspend fun chuniSongQuery(text: String): AgentReply {
        val query = text.trim()
        if (query.isBlank()) {
            return AgentReply("想查中二哪首歌？给我歌名、假名或曲目 ID，例如「查歌 宿星」")
        }
        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        val songs = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniSongsList() }.getOrDefault(emptyList())
        }
        if (songs.isEmpty()) {
            return AgentReply("本地中二曲库还是空的，请先到「设置 → 曲库更新」同步中二曲库。")
        }

        val lower = query.lowercase()
        val idMatch = query.toIntOrNull()?.let { id -> songs.firstOrNull { it.id == id } }
        val matched = buildList {
            idMatch?.let { add(it) }
            songs.filter { song ->
                song.id != idMatch?.id && (
                    song.title.lowercase().contains(lower) ||
                        song.titleKana.lowercase().contains(lower) ||
                        song.artist.lowercase().contains(lower) ||
                        song.genre.lowercase().contains(lower)
                    )
            }
        }.take(8)

        if (matched.isEmpty()) {
            return AgentReply(
                "本地中二曲库没找到「$query」。\n" +
                    "可以换个关键词（支持假名与曲师名），或到「设置 → 曲库更新」同步最新中二曲库。"
            )
        }

        val sb = StringBuilder()
        val cards = mutableListOf<AgentSong>()
        for (song in matched) {
            val charts = withContext(Dispatchers.IO) {
                runCatching { dao.getChuniChartsBySongId(song.id) }.getOrDefault(emptyList())
            }
            sb.append("**${mdEscape(song.title)}**（${song.artist}）· ${song.genre}\n")
            if (charts.isEmpty()) {
                sb.append("  暂无谱面数据\n")
            } else {
                charts.sortedBy { it.difficultyIndex }.forEach { c ->
                    sb.append(
                        "  · ${chuniLabel(c.difficultyIndex)} ${c.level}　定数 ${c.ds}" +
                            "　谱师：${c.charter.ifBlank { "-" }}"
                    )
                    if (c.combo > 0) sb.append("　物量 ${c.combo}")
                    sb.append("\n")
                }
            }
            sb.append("\n")
            val top = charts.maxByOrNull { it.ds }
            cards += song.toChuniAgentSong(top)
        }
        return AgentReply(sb.toString().trimEnd(), songs = cards)
    }

    /** 中二定数查歌（按定数区间）。 */
    private suspend fun chuniDsQuery(arg: String): AgentReply {
        val range = parseDsRange(arg)
            ?: return AgentReply("请给出定数范围，例如「中二定数 14.5」或「中二定数 14 14.5」。")
        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        val charts = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniChartsList() }.getOrDefault(emptyList())
        }
        val songs = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniSongsList() }.getOrDefault(emptyList())
        }
        val titleById = songs.associate { it.id to it.title }
        val artistById = songs.associate { it.id to it.artist }
        val matched = charts
            .filter { it.difficultyIndex != ChuniDiffType.WORLD_END.ordinal }
            .filter { it.ds >= range.first && it.ds <= range.second }
            .sortedByDescending { it.ds }
            .take(30)
        if (matched.isEmpty()) {
            return AgentReply("中二曲库里没有定数在 ${fmtRange(range.first, range.second)} 的谱面。")
        }
        val sb = StringBuilder(
            "中二定数 ${fmtRange(range.first, range.second)} 的谱面（共 ${matched.size} 首，显示前 ${minOf(matched.size, 30)} 首）：\n"
        )
        for (c in matched) {
            sb.append(
                "· ${titleById[c.songId] ?: "id ${c.songId}"}（${artistById[c.songId] ?: "-"}）" +
                    " [${chuniLabel(c.difficultyIndex)} ${c.level}] 定数 ${c.ds}\n"
            )
        }
        return AgentReply(sb.toString().trimEnd())
    }

    /** 中二随机一首（排除 WORLD'S END：不参与 Rating，也没法当作练习目标）。 */
    private suspend fun chuniRandom(): AgentReply {
        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        val songs = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniSongsList() }.getOrDefault(emptyList())
        }
        if (songs.isEmpty()) {
            return AgentReply("本地中二曲库还是空的，请先到「设置 → 曲库更新」同步中二曲库。")
        }
        val song = songs.random()
        val charts = withContext(Dispatchers.IO) {
            runCatching { dao.getChuniChartsBySongId(song.id) }.getOrDefault(emptyList())
        }
        val top = charts.maxByOrNull { it.ds }
        val sb = StringBuilder()
        sb.append("随机选曲：${song.title}\n")
        sb.append("曲师：${song.artist}　流派：${song.genre}　BPM：${song.bpm}\n")
        if (top != null) {
            sb.append("最高难度：[${chuniLabel(top.difficultyIndex)} ${top.level}] 定数 ${top.ds}\n")
        }
        sb.append("祝 AJC！")
        return AgentReply(sb.toString(), songs = listOf(song.toChuniAgentSong(top)))
    }

    /** 我的中二 Rating（官方 /50 口径，从 Room 现算，不读缓存值）。 */
    private suspend fun chuniBest(): AgentReply {
        val stats = withContext(Dispatchers.IO) { ChuniStats.compute() }
        if (stats.isEmpty) {
            return AgentReply(
                "本地还没有中二成绩。\n" +
                    "请先到「我」页面（切到中二节奏）登录查分器并同步一次成绩。"
            )
        }
        val sb = StringBuilder()
        sb.append("## 中二 Rating ${"%.2f".format(stats.rating)}\n\n")
        sb.append(
            "· 非当前版本 TOP30：${stats.nonCurrent.size} 首，均值 ${"%.2f".format(stats.nonCurrentAverage)}\n"
        )
        sb.append(
            "· 当前版本 TOP20：${stats.current.size} 首，均值 ${"%.2f".format(stats.currentAverage)}\n"
        )
        if (!stats.hasVersionInfo) {
            sb.append("\n曲库没有版本标记，当前 / 非当前无法分池，Rating 暂按 TOP30 均值口径。\n")
        }
        val top = (stats.nonCurrent + stats.current).sortedByDescending { it.ra }.take(10)
        if (top.isNotEmpty()) {
            sb.append("\n**Rating 贡献最高的 10 首**\n")
            for ((index, r) in top.withIndex()) {
                sb.append(
                    "${index + 1}. ${mdEscape(r.title)} [${chuniLabel(r.levelIndex)} ${r.level}] " +
                        "得分 ${r.score}　ra ${"%.2f".format(r.ra)}\n"
                )
            }
        }
        return AgentReply(sb.toString().trimEnd())
    }

    /**
     * 中二推分推荐。
     *
     * 与舞萌推分**口径不同**：中二 SSS+ 要 1009000（满分才 1010000，余量只有 1000 分，
     * 漏一个 note 就掉线），比 SSS 只多 0.15 ra —— 死磕 SSS+ 是典型的高投入低回报。所以这里
     * 先按**综合定数**（Best50 定数平均）卡出 −0.5 ~ +1.0 的定数区间，只在区间内
     * 「按定数从高到低排、同定数取能压过地板的最低档位」，SSS+ 结构性沉底但保留，
     * 详见 [ChuniPushRecommender]。
     */
    private suspend fun chuniPush(): AgentReply {
        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        val stats = withContext(Dispatchers.IO) { ChuniStats.compute() }
        if (stats.isEmpty) {
            return AgentReply("推分推荐需要你的中二成绩。请先在「我」→ 中二节奏里同步一次成绩。")
        }
        val nonCurrentFloor = stats.nonCurrent.lastOrNull()?.ra
        val currentFloor = stats.current.lastOrNull()?.ra
        if (nonCurrentFloor == null && currentFloor == null) {
            return AgentReply("还没有可用于推分的 Best 成绩，先去打几首再看建议吧。")
        }

        val charts = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniChartsList() }.getOrDefault(emptyList())
        }
        val songs = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniSongsList() }.getOrDefault(emptyList())
        }
        val versions = withContext(Dispatchers.IO) {
            runCatching { dao.getChartVersions() }.getOrDefault(emptyList())
        }
        val records = withContext(Dispatchers.IO) {
            runCatching { dao.getAllChuniRecordList() }.getOrDefault(emptyList())
        }
        if (charts.isEmpty() || songs.isEmpty()) {
            return AgentReply("本地中二曲库为空，无法给出推分目标。请先到「设置 → 曲库更新」同步中二曲库。")
        }

        val titleById = songs.associate { it.id to it.title }
        val versionMap = versions.associate {
            ChuniRating.chartKey(it.songId, it.difficultyIndex) to it.version
        }
        val recordByKey = records
            .filter { it.levelIndex != ChuniDiffType.WORLD_END.ordinal }
            .associateBy { ChuniRating.chartKey(it.songId, it.levelIndex) }

        // 当前版本枠判定：优先曲库 version，其次落雪官方 new_bests 分池（hasOfficialSplit）。
        // 两者都拿不到时才整体归非当前枠（此时当前枠地板也没有，不会误推）。
        val chartInputs = charts
            .filter { it.difficultyIndex != ChuniDiffType.WORLD_END.ordinal }
            .map { chart ->
                val version = versionMap[ChuniRating.chartKey(chart.songId, chart.difficultyIndex)] ?: 0
                val byVersion = stats.currentVersion > 0 && version == stats.currentVersion
                val byOfficial = stats.hasOfficialSplit &&
                    recordByKey[ChuniRating.chartKey(chart.songId, chart.difficultyIndex)]
                        ?.isCurrentVersion == true
                ChuniPushRecommender.Chart(
                    songId = chart.songId,
                    levelIndex = chart.difficultyIndex,
                    level = chart.level,
                    ds = chart.ds,
                    isCurrent = byVersion || byOfficial,
                )
            }

        // 综合定数 = Best50（非当前 30 + 当前 20）的定数平均；曲线区间的锚点。
        val poolDs = (stats.nonCurrent + stats.current).map { it.ds }.filter { it > 0.0 }
        val avgDs = if (poolDs.isEmpty()) 0.0 else poolDs.average()
        val libMaxDs = chartInputs.maxOfOrNull { it.ds } ?: 0.0
        val band = ChuniPushRecommender.bandOf(avgDs, libMaxDs)

        val candidates = ChuniPushRecommender.recommend(
            charts = chartInputs,
            scores = records
                .filter { it.levelIndex != ChuniDiffType.WORLD_END.ordinal }
                .map {
                    ChuniPushRecommender.Score(
                        songId = it.songId,
                        levelIndex = it.levelIndex,
                        score = it.score,
                        ra = it.ra,
                    )
                },
            nonCurrent = ChuniPushRecommender.Pool(
                floor = nonCurrentFloor,
                size = stats.nonCurrent.size,
                capacity = ChuniRating.NON_CURRENT_SIZE,
            ),
            current = ChuniPushRecommender.Pool(
                floor = currentFloor,
                size = stats.current.size,
                capacity = ChuniRating.CURRENT_SIZE,
            ),
            band = band,
            limit = 10,
        )

        val sb = StringBuilder()
        sb.append("## 中二推分推荐\n\n")
        sb.append(
            "非当前 TOP30 地板 ra ${nonCurrentFloor?.let { "%.2f".format(it) } ?: "—"}" +
                " · 当前 TOP20 地板 ra ${currentFloor?.let { "%.2f".format(it) } ?: "—"}\n\n"
        )
        sb.append(
            "综合定数 ${"%.2f".format(band.center)}（Best50 定数平均）→ " +
                "推荐区间定数 **${"%.1f".format(band.low)} ~ ${"%.1f".format(band.high)}**" +
                "（曲库最高 ${"%.1f".format(libMaxDs)}）：区间外的谱不推 —— " +
                "低于区间只能靠 SSS+ 硬顶，高于区间打不动也拿不到 ra。\n\n"
        )
        sb.append(
            "SSS+ 要 ${ChuniPushRecommender.SSS_PLUS_SCORE}（满分 ${ChuniPushRecommender.MAX_SCORE}），" +
                "余量只有 ${ChuniPushRecommender.SSS_PLUS_MARGIN} 分 —— 一个 note 就值上千分，" +
                "漏一个基本就掉线，却只比 SSS 多 0.15 ra；所以压在最后，" +
                "rating 17+ 推无可推时才会是唯一选择。下面按定数从高到低排，" +
                "同定数挑最容易压过地板的档位。\n\n"
        )
        if (candidates.isEmpty()) {
            sb.append(
                "综合定数区间 ${"%.1f".format(band.low)} ~ ${"%.1f".format(band.high)} 内暂时没有" +
                    "能压过地板的目标：区间里的谱要么已经打到位，要么分数还差得远。" +
                    "先把这段的谱刷稳，或者等新曲进曲库再看。"
            )
        } else {
            candidates.forEachIndexed { index, c ->
                if (index > 0) sb.append("\n---\n\n")
                val title = mdEscape(titleById[c.songId] ?: "id ${c.songId}")
                val pool = if (c.isCurrent) "当前 TOP20" else "非当前 TOP30"
                val tier = if (c.tierName == "SSS+") {
                    "`${c.tierScore}`（SSS+，余量仅 ${ChuniPushRecommender.SSS_PLUS_MARGIN} 分）"
                } else {
                    "`${c.tierScore}`（${c.tierName}）"
                }
                sb.append("**$title** · `${chuniLabel(c.levelIndex)} ${c.level}` · 定数 ${c.ds}\n")
                when {
                    !c.played -> {
                        sb.append(
                            "未游玩：打到 $tier 得 ra ${"%.2f".format(c.potential)} " +
                                "→ 顶替 $pool 地板 **+${"%.2f".format(c.gain)}**\n"
                        )
                    }
                    c.inPool -> {
                        sb.append(
                            "当前 ${c.currentScore}（ra ${"%.2f".format(c.currentRa ?: 0.0)}，已在榜内）：" +
                                "还差 ${c.gap} 分到 $tier 得 ra ${"%.2f".format(c.potential)} " +
                                "→ 总分还可 **+${"%.2f".format(c.gain)}**\n"
                        )
                    }
                    else -> {
                        sb.append(
                            "当前 ${c.currentScore}（ra ${"%.2f".format(c.currentRa ?: 0.0)}，" +
                                "低于地板 ${"%.2f".format(c.floor)}）：还差 ${c.gap} 分到 $tier " +
                                "得 ra ${"%.2f".format(c.potential)} " +
                                "→ 顶替地板 **+${"%.2f".format(c.gain)}**\n"
                        )
                    }
                }
            }
        }
        sb.append(
            "\n\n档位由易到难：AAA < S < S+ < SS < SS+ < SSS < SSS+；" +
                "排在前面的定数更高、打起来不一定更累，SSS+ 会沉在末尾。"
        )
        sb.append(
            "\n数据来自本地曲库定数 + 官方 ra 公式，实际增益以落雪 / 机台结算为准。" +
                "综合定数 = Best50 的定数平均，同步新成绩后会跟着变。"
        )
        return AgentReply(sb.toString().trimEnd())
    }

    /** 中二曲目 → Agent 卡片。 */
    private fun ChuniSongEntity.toChuniAgentSong(top: ChuniChartEntity?): AgentSong = AgentSong(
        id = id,
        title = title,
        artist = artist,
        genre = genre,
        // 中二没有 SD/DX，类型徽标留空（UI 侧见到空串就不渲染 ChartTypeChip）
        type = "",
        topLevel = top?.level ?: "",
        topDs = top?.ds ?: 0.0,
        imageUrl = imageUrl,
        difficulty = top?.let { chuniLabel(it.difficultyIndex) } ?: "",
        game = GAME_CHUNITHM,
    )

    /** 今日性癖（源自 KibaBot「今日性癖 / jrxp」）。 */
    private fun todayKink(): AgentReply {
        val nick = DivingFishAccount.state.value.nickname.ifBlank { "你" }
        // 人品值按昵称做确定性哈希（Bot 用 QQ 号），性癖每次随机
        val rp = (nick.hashCode() and 0x7fffffff) % 100
        val xp = KINK_LIST[Random.nextInt(KINK_LIST.size)]
        return AgentReply(
            "▾ 今日性癖\n${nick}今天的性癖是${xp}，人品值是${rp}%.\n不满意的话再随一个吧！"
        )
    }

    /**
     * 今天吃什么（Bot 传统技能）：纯随机抽一个，
     * 25% 概率随到舞萌恶趣味选项（吃绝赞 / 吃星星 / 吃黄金迪拉熊）。
     */
    private fun eatWhat(): AgentReply {
        val maimai = Random.nextInt(100) < 25
        return if (maimai) {
            val (item, note) = EAT_MAIMAI_LIST[Random.nextInt(EAT_MAIMAI_LIST.size)]
            AgentReply("▾ 今天吃什么\n今天吃「${item}」！\n$note")
        } else {
            val food = EAT_LIST[Random.nextInt(EAT_LIST.size)]
            AgentReply("▾ 今天吃什么\n今天吃「${food}」，干饭去吧！\n不满意的话再随一个吧。")
        }
    }

    /**
     * 歌单封面设计技能：把 Agent 填的文案套进 [PlaylistCover] 模板并落盘，
     * 结果写回 [AgentPlaylistStore]（列表缩略图 / 详情页大封面 / 顶栏大图都用这张）。
     *
     * 文案兜底链：技能参数 → 歌单现有名字 / 推荐语 → 曲目数。**任何一步都不许失败到没封面**，
     * 最差情况下用默认配色 + 默认标题渲染一张，也比「歌单没封面」强。
     */
    private suspend fun playlistCover(args: Map<String, String>): AgentReply =
        withContext(Dispatchers.IO) {
            val all = AgentPlaylistStore.load()
            if (all.isEmpty()) {
                return@withContext AgentReply(
                    "还没有 Agent 歌单，先去「畅听 → Agent 专属歌单」生成一张，再来设计封面。",
                )
            }
            val key = args["playlist"].orEmpty().trim()
            val target = all.firstOrNull { it.id == key }
                ?: all.firstOrNull { key.isNotBlank() && it.name.contains(key) }
                ?: all.first()

            val title = args["title"].orEmpty().trim().ifBlank { target.name }.take(12)
            val subtitle = args["subtitle"].orEmpty().trim().ifBlank { target.summary }
            val mood = args["mood"].orEmpty().trim()
            val tags = args["tags"].orEmpty()
                .split('、', ',', '，', '/', '|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
            val spec = PlaylistCover.Spec(
                title = title,
                subtitle = subtitle,
                mood = mood,
                tags = tags,
                songCount = target.ids.size,
            )
            val file = PlaylistCover.render(context, spec, target.id)
                ?: return@withContext AgentReply("封面渲染失败了（写文件出错），稍后再试一次。")
            AgentPlaylistStore.update(
                target.copy(name = spec.title, coverPath = file.absolutePath),
            )
            AgentReply(
                buildString {
                    append("已用「").append(spec.paletteName).append("」模板为「").append(spec.title)
                    append("」生成封面：\n")
                    append("· 主标题：").append(spec.title).append("\n")
                    if (subtitle.isNotBlank()) append("· 副标题：").append(subtitle).append("\n")
                    append("· 配色：").append(spec.paletteName)
                    if (tags.isNotEmpty()) append("（标签 ").append(tags.joinToString("、")).append("）")
                    append("\n到底部「畅听 → Agent 专属歌单」里就能看到这张新封面。")
                },
                skills = emptyList(),
            )
        }

    // ---------- 联网搜索（LLM 专用 web_search） ----------

    private val SEARCH_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** 一条搜索结果：标题 / 链接 / 摘要。 */
    private data class WebResult(val title: String, val url: String, val snippet: String)

    /**
     * 联网搜索：Bing 为主（国内直连可用），DuckDuckGo 兜底（有代理时更准）；
     * 再抓前 2 条结果页正文，一并回灌给模型总结。
     */
    /**
     * 萌娘百科内置知识库检索（舞萌 / 舞萌DX / 中二节奏三篇）。
     *
     * 定位：**机制类问题的第一优先来源**。内容随 APK 固化，离线可用、零延迟、
     * 不会像模型自由发挥那样编造定数/公式。检索不到时明确返回「没命中」，
     * 让模型改用 web_search，而不是硬拿一段不相关正文凑答案。
     */
    private suspend fun moegirlKb(query: String): AgentReply = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext AgentReply("moegirl_kb 需要一个 query 参数。")
        val hit = runCatching { MoegirlKb.search(context, q) }.getOrDefault("")
        if (hit.isBlank()) {
            return@withContext AgentReply(
                "本地知识库（舞萌DX / maimai 系列 / CHUNITHM / Simai 语法）里没有检索到「$q」相关小节。\n" +
                    "可以换更核心的名词再查一次，或改用 web_search 联网确认。",
            )
        }
        AgentReply(hit)
    }

    /**
     * 联网搜索（Bing / DDG）+ 抓正文。
     *
     * 「自主浏览器」的接入点：正文不再只靠 OkHttp 抠静态 HTML，而是交给 [AgentBrowser]
     * 真正跑完页面 JS 再取 innerText，CSR 页面（React / Vue）也能读到用户看到的内容；
     * 浏览器不可用时才回退旧的 [fetchPageText]。另外支持模型直接给 `url` 跳过搜索，
     * 于是 web_search 从「搜一下」升级成「上网看一眼」，复杂交互再交给 browser 工具。
     */
    private suspend fun webSearch(query: String, directUrl: String = ""): AgentReply {
        val q = query.trim()
        val url = directUrl.trim()
        if (q.isEmpty() && url.isEmpty()) return AgentReply("web_search 需要 query 或 url 参数。")
        return withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("http")) {
                    val page = readWebPage(url)
                    return@runCatching if (page.isNullOrBlank()) {
                        AgentReply("没能读到 $url 的正文（页面可能需要 JS 渲染，或站点拒绝访问）。")
                    } else {
                        AgentReply("【web_search 打开网页】$url\n【渲染后正文】\n$page")
                    }
                }
                val results = searchBing(q).ifEmpty { searchDuckDuckGo(q) }
                if (results.isEmpty()) {
                    return@runCatching AgentReply("没搜到「$q」的相关资料，可能关键词太偏或网络受限。")
                }
                val sb = StringBuilder("【web_search 搜索结果】关键词：$q\n")
                results.take(5).forEachIndexed { i, r ->
                    sb.append("${i + 1}. ${r.title}\n   链接：${r.url}\n   摘要：${r.snippet}\n")
                }
                // 正文抓取（最多 2 条，各限 3500 字，超时/反爬就跳过）
                for (r in results.take(2)) {
                    val page = readWebPage(r.url) ?: continue
                    if (page.isBlank()) continue
                    sb.append("\n【网页正文】${r.title}（${r.url}）\n$page\n")
                }
                AgentReply(sb.toString())
            }.getOrElse {
                AgentReply(
                    "搜索失败：${it.message}（设备网络可能受限，请基于已有知识谨慎回答，不知道就说不知道）",
                )
            }
        }
    }

    /** 用后台浏览器读渲染后的正文；失败退回静态 HTML 抓取。 */
    private suspend fun readWebPage(url: String): String? {
        val outcome = runCatching {
            AgentBrowser.act(context, "open", url = url, waitMs = 1_200, maxChars = 3_500)
        }.getOrNull()
        if (outcome != null && outcome.ok && outcome.text.isNotBlank()) return outcome.text
        return withContext(Dispatchers.IO) { fetchPageText(url) }
    }

    /**
     * `browser` 工具：把 LLM 的 action / url / js / selector 直接转给 [AgentBrowser]。
     * 结果（含失败原因）原样回灌，让模型自己决定下一步点哪、读哪。
     */
    private suspend fun browserAction(args: Map<String, String>): AgentReply {
        val action = args["action"]?.trim().orEmpty()
        if (action.isBlank()) {
            return AgentReply("browser 需要 action 参数：open / text / eval / click / fill / scroll / close。")
        }
        val outcome = AgentBrowser.act(
            context = context,
            action = action,
            url = args["url"] ?: args["href"] ?: "",
            js = args["js"] ?: args["script"] ?: "",
            selector = args["selector"] ?: args["sel"] ?: "",
            value = args["value"] ?: args["text"] ?: "",
            waitMs = (args["wait_ms"]?.toLongOrNull() ?: 1_500L).coerceIn(0L, 10_000L),
            maxChars = (args["max_chars"]?.toIntOrNull() ?: 8_000).coerceIn(200, 200_000),
        )
        return AgentReply(outcome.render())
    }

    /**
     * `maimai_chart_simai`（舞萌分类，LLM 专用）：读某曲目某难度的**标准 Simai 谱面**。
     *
     * 流程：后台浏览器打开落雪预览页 → 在页面上下文 fetch 它自己用的整曲 Simai
     * （`assets2.lxns.net/maimai/chart/{id}.txt`，即「Simai 语句」栏的数据源）→
     * 按 `&inote_{difficulty+2}=` 切出目标难度 → 保留元数据拼回单难度标准文本。
     * 页面把 BPM/拍数渲染成 `BPM 174 | 1/16` 标记行是显示层，这里的数据本来就是标准写法，
     * [SimaiChart.normalizeDisplayed] 再兜一层，防止拿到的是显示文本。
     */
    private suspend fun maimaiChartSimai(args: Map<String, String>): AgentReply {
        val id = (args["song_id"] ?: args["id"] ?: args["chart_id"])?.trim()?.toIntOrNull()
            ?: Regex("""\d{1,6}""").find(args.values.joinToString(" "))?.value?.toIntOrNull()
        if (id == null || id <= 0) {
            return AgentReply(
                "maimai_chart_simai 需要 song_id（曲目 ID）。" +
                    "请先用 song_query 按歌名查出 ID，再带上 difficulty 调用。",
            )
        }
        val diff = args["difficulty"]?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        val maxChars = (args["max_chars"]?.toIntOrNull() ?: 20_000).coerceIn(2_000, 120_000)
        val preview = SimaiChart.previewUrl(id, diff)
        val cdn = SimaiChart.cdnUrl(id)

        val opened = AgentBrowser.act(context, "open", url = preview, waitMs = 2_500, maxChars = 1_000)
        if (!opened.ok) {
            return AgentReply("打开落雪谱面预览页失败：${opened.text}\n页面：$preview")
        }

        // 页面自己就是从 assets2 取这份 txt，同源策略 / CORS 一定通；由 WebView 发起
        // 比 App 直连更贴近「读取页面内 Simai 语句栏」的语义。
        AgentBrowser.act(context, "eval", js = SimaiChart.fetchJs(cdn), maxChars = 200)
        var ready = false
        var fetchError = ""
        repeat(24) {
            if (ready || fetchError.isNotEmpty()) return@repeat
            val state = AgentBrowser.act(
                context, "eval", js = SimaiChart.STATE_JS, maxChars = 200,
            ).text.trim()
            when {
                state.startsWith("done:") &&
                    (state.removePrefix("done:").toIntOrNull() ?: 0) > 0 -> ready = true
                state.startsWith("error:") -> fetchError = state.removePrefix("error:")
                else -> delay(300)
            }
        }
        var raw = if (ready) {
            AgentBrowser.act(context, "eval", js = SimaiChart.RAW_JS, maxChars = 200_000).text
        } else {
            ""
        }
        if (raw.isBlank()) {
            // 兜底：WebView 内 fetch 被拦时，App 侧直连同一个 CDN 纯文本
            raw = withContext(Dispatchers.IO) {
                runCatching { httpGet(cdn).orEmpty() }.getOrDefault("")
            }
        }
        if (raw.isBlank()) {
            return AgentReply(
                "没拿到曲目 $id 的谱面数据（页面已打开但 Simai 文本为空）。" +
                    (if (fetchError.isNotBlank()) "\n$fetchError" else "") +
                    "\n页面：$preview\n数据源：$cdn",
            )
        }
        val inote = SimaiChart.resolveInote(raw, diff)
        val chart = SimaiChart.extract(raw, inote)
        if (chart == null) {
            return AgentReply(
                "落雪谱面文件里没有难度 &inote_$inote。" +
                    "可用：${SimaiChart.availableInotes(raw).joinToString("、")}" +
                    "（URL difficulty = inote − 2）。\n数据源：$cdn",
            )
        }
        val standard = SimaiChart.normalizeDisplayed(chart)
        val body = if (standard.length > maxChars) {
            standard.take(maxChars) + "\n…（谱面过长已截断，共 ${standard.length} 字符）"
        } else {
            standard
        }
        return AgentReply(buildString {
            append("【舞萌 DX 标准 Simai 谱面】曲目 ID $id · ${SimaiChart.difficultyName(diff)}" +
                "（落雪 &inote_$inote）\n")
            append("来源：落雪谱面预览页「Simai 语句」栏 $preview\n")
            append("BPM / 拍数已按标准 Simai 写法 `(bpm){n}` 归一，其余记号不变。\n\n")
            append(SimaiChart.summarize(standard)).append('\n')
            append("【谱面正文】\n").append(body)
        })
    }

    /**
     * `simai_syntax`（LLM 专用）：查 Simai 语法，用于解读上一步拿到的谱面，
     * 或回答用户「simai 里 SLIDE 怎么写」这类写法讲解。
     */
    private suspend fun simaiSyntax(topic: String): AgentReply = withContext(Dispatchers.IO) {
        val q = topic.trim()
        if (q.isEmpty()) return@withContext AgentReply("simai_syntax 需要 topic 参数。")
        val hit = runCatching {
            MoegirlKb.search(context, q, limit = 8, docFilter = "Simai")
        }.getOrDefault("")
        if (hit.isBlank()) {
            return@withContext AgentReply(
                "Simai 语法知识库里没有检索到「$q」相关小节。" +
                    "换个核心词再查（SLIDE / HOLD / BPM / 拍数 / EACH / BREAK / TOUCH / EX / 连结）。",
            )
        }
        AgentReply(hit)
    }

    /** DuckDuckGo HTML 端点搜索。 */
    private fun searchDuckDuckGo(query: String): List<WebResult> {
        val html = httpGet("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
            ?: return emptyList()
        if (html.contains("anomaly") || html.contains("challenge")) return emptyList()
        val linkRe = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippets = snipRe.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
        return linkRe.findAll(html).mapIndexedNotNull { i, m ->
            val rawUrl = m.groupValues[1]
            val url = unwrapDdgUrl(rawUrl) ?: return@mapIndexedNotNull null
            val title = htmlToText(m.groupValues[2])
            if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
            WebResult(title, url, snippets.getOrNull(i).orEmpty())
        }.toList()
    }

    /** Bing 搜索（DDG 不可用时的兜底）。 */
    private fun searchBing(query: String): List<WebResult> {
        val html = httpGet("https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-hans")
            ?: return emptyList()
        val blockRe = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        val linkRe = Regex("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>", RegexOption.DOT_MATCHES_ALL)
        return blockRe.findAll(html).mapNotNull { block ->
            val m = linkRe.find(block.groupValues[1]) ?: return@mapNotNull null
            val title = htmlToText(m.groupValues[2])
            val url = m.groupValues[1]
            if (title.isBlank() || !url.startsWith("http")) return@mapNotNull null
            val snip = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                .find(block.groupValues[1])?.groupValues?.get(1).orEmpty()
            WebResult(title, url, htmlToText(snip))
        }.toList()
    }

    /** DDG 结果里的跳转链接还原成真实地址。 */
    private fun unwrapDdgUrl(raw: String): String? {
        if (!raw.contains("uddg=")) {
            return if (raw.startsWith("http")) raw else (if (raw.startsWith("//")) "https:$raw" else null)
        }
        val idx = raw.indexOf("uddg=") + 5
        val end = raw.indexOf('&', idx).let { if (it == -1) raw.length else it }
        return runCatching { java.net.URLDecoder.decode(raw.substring(idx, end), "UTF-8") }.getOrNull()
    }

    /** 抓网页并提取纯文本（限长，失败返回 null）。 */
    private fun fetchPageText(url: String): String? {
        val html = httpGet(url) ?: return null
        val text = htmlToText(html)
        return if (text.length > 3500) text.take(3500) + "…（正文过长已截断）" else text
    }

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SEARCH_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string().orEmpty()
        }
    }.getOrNull()

    /** 粗暴的 HTML → 纯文本：去 script/style/标签 + 常见实体解码。 */
    private fun htmlToText(html: String): String {
        val noScript = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
        return noScript
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0xFFFF }?.toChar()?.toString().orEmpty()
            }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 随机条件曲（源自 KibaBot「随个[dx/标准][绿黄红紫白]<难度>」）：
     * 按版本（SD/DX）+ 难度关键词随机取 N 首。
     */
    private suspend fun randomFiltered(arg: String): AgentReply {
        val diff = parseDifficultyKeyword(arg)
        val wantDx = arg.contains("dx", true)
        val wantSd = arg.contains("标准", true) || arg.contains("sd", true)
        val count = Regex("\\d+").findAll(arg).firstOrNull()?.value?.toIntOrNull()?.coerceIn(1, 10) ?: 1

        val songs = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().songDao().getAllSongsList()
            }.getOrDefault(emptyList())
        }
        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getAllChartsList()
            }.getOrDefault(emptyList())
        }
        val bySong = charts.groupBy { it.songId }
        val utageIds = utageSongIds(songs)
        val pool = songs.filter { s ->
            // 宴会场（流派 = 宴会場，或六位数 id，或谱面等级带「?」）不获 Rating，排除
            if (s.genre == GENRE_UTAGE || s.id in 100000..999999) return@filter false
            val cs = bySong[s.id].orEmpty()
            if (cs.isNotEmpty() && cs.all { isUtageChart(it, utageIds) }) return@filter false
            val typeOk = when {
                wantDx && wantSd -> true
                wantDx -> s.type.contains("DX", true)
                wantSd -> !s.type.contains("DX", true)
                else -> true
            }
            val diffOk = diff == null || bySong[s.id]?.any { it.difficultyType == diff } == true
            typeOk && diffOk
        }
        if (pool.isEmpty()) {
            return AgentReply("没有符合条件的歌曲（试试放宽难度或版本条件）。")
        }
        val picked = pool.shuffled().take(count)
        val sb = StringBuilder("随机条件曲（${count} 首）：\n")
        val cards = mutableListOf<AgentSong>()
        picked.forEach { s ->
            val top = bySong[s.id]?.maxByOrNull { it.ds }
            sb.append("· ${s.title}（${s.artist}）")
            if (top != null) {
                sb.append(" [${difficultyLabel(top.difficultyType.ordinal)} ${top.level}] 定数 ${top.ds}")
            }
            sb.append("\n")
            cards += s.toAgentSong(top)
        }
        return AgentReply(sb.toString().trimEnd(), songs = cards)
    }

    /**
     * 猜歌（源自 KibaBot「猜歌」）：随机一首歌给线索，让用户在输入框猜。
     *
     * **不直接给答案**：线索只含遮罩歌名 / 曲师 / 流派 / 难度定数 / BPM / 版本，
     * 也不挂歌曲卡（卡面会泄底）。答案由 [submitGuess] 判定：用户输入
     * 歌名 / 别名 / 歌曲 ID 任一指向答案歌曲即算对。
     */
    private suspend fun guessSong(): AgentReply {
        val song = withContext(Dispatchers.IO) {
            runCatching { AppDataBase.getInstance().songDao().randomSong() }.getOrNull()
        } ?: return AgentReply("曲库还是空的，无法开始猜歌。")
        val charts = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().chartDao().getChartsBySongId(song.id)
            }.getOrDefault(emptyList())
        }
        val top = charts.maxByOrNull { it.ds }
        _pendingGuess.value = GuessSession(songId = song.id)
        val sb = StringBuilder()
        sb.append("猜歌开始！在下方输入框提交你的答案（歌名 / 别名 / 歌曲 ID 均可），不想猜了就点「放弃」。\n")
        sb.append("线索 1：曲名 ${maskTitle(song.title)}\n")
        sb.append("线索 2：曲师 ${maskArtist(song.artist)}\n")
        sb.append("线索 3：流派 ${song.genre}　类型 ${song.type}\n")
        if (top != null) {
            sb.append("线索 4：最高难度 ${difficultyLabel(top.difficultyType.ordinal)} ${top.level}，定数 ${top.ds}\n")
        }
        if (song.bpm > 0) {
            sb.append("线索 5：BPM ${song.bpm}\n")
        }
        val version = song.from.ifBlank { song.version }
        if (version.isNotBlank() && version != "-") {
            sb.append("线索 6：来自 ${version}\n")
        }
        sb.append("猜错会逐步揭示歌名，慢慢来～")
        return AgentReply(sb.toString())
    }

    /**
     * 提交猜歌答案。输入命中答案歌曲的**歌名 / 别名 / 歌曲 ID 任一**即算猜中：
     * - 纯数字 → 按歌曲 ID 精确比对；
     * - 否则归一化（去空白 / 小写）后与歌名、别名（alias 表）、标题假名互相包含。
     *
     * 放弃（输入「放弃 / 不猜了 / 投降」，或点答题栏的放弃按钮）：公布答案并挂上歌曲卡。
     * 猜对：同样公布答案 + 歌曲卡，清除对局。
     * 猜错：累计次数，按次数逐步揭示歌名，保持对局继续。
     */
    suspend fun submitGuess(input: String): AgentReply {
        val session = _pendingGuess.value
            ?: return AgentReply("当前没有进行中的猜歌对局，先点上方「猜歌」卡片开一局吧。")
        val text = input.trim()
        if (text.isEmpty()) return AgentReply("输入点什么再提交哦（歌名 / 别名 / 歌曲 ID）。")
        val song = withContext(Dispatchers.IO) {
            runCatching { AppDataBase.getInstance().songDao().getSongById(session.songId) }.getOrNull()
        }
        if (song == null) {
            // 答案歌曲不见了（曲库被更新清掉）：对局作废
            _pendingGuess.value = null
            return AgentReply("答案歌曲已不在曲库里（曲库可能刚更新过），这一局作废，再开一局吧。")
        }
        // 公布答案用的公共尾部（猜对 / 放弃共用）：答案 + 曲师流派 + 歌曲卡
        suspend fun revealAnswer(prefix: String, tail: String): AgentReply {
            val charts = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().chartDao().getChartsBySongId(song.id)
                }.getOrDefault(emptyList())
            }
            val top = charts.maxByOrNull { it.ds }
            val sb = StringBuilder()
            sb.append("$prefix 答案是 **${mdEscape(song.title)}**。\n")
            sb.append("曲师 ${song.artist.ifBlank { "—" }} · ${song.genre} · ${song.type}")
            if (top != null) {
                sb.append(" · 最高 ${difficultyLabel(top.difficultyType.ordinal)} ${top.level}（定数 ${top.ds}）")
            }
            sb.append("\n$tail")
            return AgentReply(sb.toString(), songs = listOf(song.toAgentSong(top)))
        }
        // 放弃：关键词命中即公布答案，结束对局
        if (text in GIVE_UP_WORDS) {
            _pendingGuess.value = null
            return revealAnswer(
                "已放弃～",
                "共猜错 ${session.wrongCount} 次。想再来一局就点上方「猜歌」卡片～",
            )
        }
        if (isGuessCorrect(song, text)) {
            _pendingGuess.value = null
            return revealAnswer(
                "🎉 猜对了！",
                "共猜错 ${session.wrongCount} 次。想再来一局就点上方「猜歌」卡片～",
            )
        }
        val wrong = session.wrongCount + 1
        _pendingGuess.value = session.copy(wrongCount = wrong)
        val sb = StringBuilder()
        sb.append("不对哦～ 再试试！（已猜错 $wrong 次）\n")
        sb.append("线索更新：曲名 ${revealTitle(song.title, wrong)}\n")
        sb.append("还是没头绪的话，换个方向：流派 / 难度定数 / BPM 都对得上吗？")
        return AgentReply(sb.toString())
    }

    /** 猜歌判定：输入命中答案歌曲的歌名 / 别名 / 标题假名 / ID 任一即算对。 */
    private suspend fun isGuessCorrect(song: SongDataEntity, input: String): Boolean {
        // 纯数字按歌曲 ID 精确匹配
        input.toIntOrNull()?.let { return it == song.id }
        val needle = normalizeGuess(input)
        if (needle.length < 2) return false
        if (normalizeGuess(song.title).contains(needle)) return true
        val kana = song.titleKana.trim()
        if (kana.isNotBlank() && kana != "-" && normalizeGuess(kana).contains(needle)) return true
        val aliases = withContext(Dispatchers.IO) {
            runCatching {
                AppDataBase.getInstance().aliasDao().getAliasesBySongIdSync(song.id)
            }.getOrDefault(emptyList())
        }
        return aliases.any { normalizeGuess(it.alias).contains(needle) }
    }

    /** 归一化猜歌输入 / 比对目标：去空白（含全角）、统一小写。 */
    private fun normalizeGuess(text: String): String =
        text.lowercase().replace(Regex("[\\s\\u3000]+"), "")

    /**
     * 逐步揭示歌名：首轮（[wrong] = 0）交给 [maskTitle]（首尾保留）；
     * 每猜错一次多揭示一个字，直到只剩最后一个字。
     */
    private fun revealTitle(title: String, wrong: Int): String {
        if (title.length <= 2) return title
        val shown = (wrong + 1).coerceIn(1, title.length - 1)
        return title.substring(0, shown) + "＊".repeat(title.length - shown)
    }

    // ---------- 长期记忆（LLM 专用 memory 工具） ----------

    /**
     * `memory` 工具：让模型自己维护跨会话记忆。
     *
     * 参数全部宽松解析（模型经常把「action」写成中文、或者干脆只给 content）：
     * - 没给 action 但有 content → 当作 save
     * - 没给 action 也没有 content → 当作 list
     * - forget 允许用 id，也允许用关键词（模型更容易拿到用户原话，拿不到 8 位 id）
     *
     * 返回给模型的文本要**明确说出结果**（记了什么 / 删了几条 / 现在有哪些），
     * 否则它会在下一轮再调一次，把同一句话记两遍。
     */
    private fun memoryAction(args: Map<String, String>, fallbackInput: String): AgentReply {
        val content = (args["content"] ?: args["text"] ?: args["memory"] ?: args["value"]).orEmpty()
        val action = (args["action"] ?: args["op"] ?: args["operation"]).orEmpty()
            .trim()
            .lowercase()
            .ifBlank { if (content.isBlank()) "list" else "save" }

        if (!AgentMemory.isEnabled()) {
            return AgentReply(
                "长期记忆已在「设置 → Agent → 长期记忆」里关闭，未写入。",
                skills = emptyList(),
            )
        }

        fun result(text: String) = AgentReply(text, skills = emptyList())

        return when (action) {
            "save", "add", "remember", "记住", "保存" -> {
                val text = content.ifBlank {
                    // 模型常把要记的话直接写进 args 之外的自由文本（或只给 keyword 字段）
                    args["keyword"] ?: args["query"] ?: fallbackInput
                }
                val item = AgentMemory.add(text, AgentMemory.Source.AGENT)
                if (item == null) {
                    result("没记下来：内容为空，或者这句话已经记过了（现有 ${AgentMemory.items.value.size} 条）。")
                } else {
                    result(
                        "已记住第 ${AgentMemory.items.value.size} 条：${item.text}\n" +
                            "（用户可以在「设置 → Agent → 长期记忆」里修改或删除。）"
                    )
                }
            }

            "list", "read", "show", "查看", "列出" -> result(AgentMemory.describeForTool())

            "update", "edit", "修改" -> {
                val id = args["id"] ?: args["memory_id"] ?: args["target"] ?: ""
                val ok = if (id.isNotBlank()) {
                    AgentMemory.update(id, content)
                } else {
                    // 没有 id：按关键词找唯一一条再改（模型给不出 id 时的退路）
                    val key = args["keyword"] ?: args["query"] ?: ""
                    val hits = AgentMemory.items.value.filter { it.text.contains(key, ignoreCase = true) }
                    if (hits.size == 1) AgentMemory.update(hits.first().id, content) else false
                }
                if (ok) result("已更新：${AgentMemory.normalize(content)}") else {
                    result("没改成：找不到对应条目（先用 action=list 拿到清单和 id），或新内容是空的。")
                }
            }

            "forget", "delete", "remove", "忘掉", "删除" -> {
                val id = args["id"] ?: args["memory_id"] ?: args["target"] ?: ""
                val keyword = args["keyword"] ?: args["query"] ?: content
                val removed = when {
                    id.isNotBlank() && AgentMemory.remove(id) -> 1
                    keyword.isNotBlank() -> AgentMemory.forgetKeyword(keyword)
                    else -> 0
                }
                if (removed > 0) {
                    result("已忘掉 $removed 条，还剩 ${AgentMemory.items.value.size} 条。")
                } else {
                    result("没找到要忘掉的内容（先用 action=list 看看有哪些）。")
                }
            }

            "clear", "清空" -> {
                val n = AgentMemory.items.value.size
                AgentMemory.clear()
                result("已清空长期记忆（原 $n 条）。")
            }

            else -> result(
                "未知的 memory 操作：$action（可用 save / list / update / forget / clear）。"
            )
        }
    }

    // ---------- 工具函数 ----------

    /** 歌曲实体 → Agent 歌曲卡数据。 */

    private fun SongDataEntity.toAgentSong(top: ChartEntity?): AgentSong = AgentSong(
        id = id,
        title = title,
        artist = artist,
        genre = genre,
        type = type,
        topLevel = top?.level ?: "-",
        topDs = top?.ds ?: 0.0,
        imageUrl = imageUrl,
        difficulty = top?.let { difficultyLabel(it.difficultyType.ordinal) } ?: "",
    )

    /** 从自由文本里提取歌曲查询词。 */
    private fun extractQuery(text: String): String = text
        .replace("查歌", "").replace("查一下歌", "").replace("搜索", "")
        .replace("找歌", "").replace("这首歌", "").replace("歌曲信息", "")
        .replace("定数", "").replace("歌名", "").replace("的", "")
        .replace("是什么", "").replace("是多少", "")
        .replace("一下", "").replace("帮我", "").replace("我想", "")
        .trim().trim('：', ':', '？', '?', '。', '，', ',', ' ')

    /** 解析定数区间：单值 / 上下限 / 「以上」。 */
    private fun parseDsRange(arg: String): Pair<Double, Double>? {
        val nums = Regex("\\d+(?:\\.\\d+)?").findAll(arg).map { it.value.toDouble() }.toList()
        return when {
            nums.size >= 2 -> Pair(nums[0], nums[1])
            nums.size == 1 -> {
                val v = nums[0]
                if (arg.contains("以上")) Pair(v, 99.0) else Pair(v, v)
            }
            else -> null
        }
    }

    private fun fmtRange(lo: Double, hi: Double): String =
        if (lo == hi) "${"%.2f".format(lo)}" else "${"%.2f".format(lo)} - ${"%.2f".format(hi)}"

    /**
     * 转义 Markdown 元字符：歌名等动态文本进 GFM 排版前过一遍，
     * 防止歌名里的 `*` `_` `` ` `` `[` `]` `~` 被解析成强调/代码/链接而破坏版式。
     * 反斜杠转义由 MarkdownContent 的行内解析统一还原。
     */
    private fun mdEscape(text: String): String = buildString(text.length + 4) {
        text.forEach { ch ->
            when (ch) {
                '\\', '*', '_', '`', '[', ']', '~' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }

    /**
     * 宴会场谱面判定（官方设定这类谱面不获 Rating，推分/随机/定数类技能一律排除）：
     * - 歌曲流派 = 宴会場；
     * - 谱面难度为 UTAGE / UTAGE_PLAYER2；
     * - **等级里带「?」**（如 13?）—— 这是宴会场谱面的标志，等级字段本身没有别的区分方式。
     */
    private fun isUtageChart(chart: ChartEntity, utageSongIds: Set<Int>): Boolean =
        chart.difficultyType == DifficultyType.UTAGE ||
            chart.difficultyType == DifficultyType.UTAGE_PLAYER2 ||
            chart.songId in utageSongIds ||
            chart.level.contains("?")

    /**
     * 宴会場歌曲 id 集合：genre = 宴会場，或 **id 为六位数**（100000-999999，
     * 宴会场曲目 id 段，部分数据源 genre 字段缺失时靠 id 兜底）。
     */
    private fun utageSongIds(songs: List<SongDataEntity>): Set<Int> =
        songs.filter { it.genre == GENRE_UTAGE || it.id in 100000..999999 }.map { it.id }.toSet()

    /**
     * 随机取一首非宴会场歌曲（含其谱面列表）：流派 = 宴会場、六位数 id（100000-999999，
     * 宴会曲目 id 段）、或全部谱面等级带「?」都视为宴会场。重试 12 次仍命中则放弃。
     */
    private suspend fun pickNonUtageSong(): Pair<SongDataEntity, List<ChartEntity>>? {
        var result: Pair<SongDataEntity, List<ChartEntity>>? = null
        withContext(Dispatchers.IO) {
            repeat(12) {
                val picked = runCatching {
                    AppDataBase.getInstance().songDao().randomSong()
                }.getOrNull() ?: return@repeat
                val cs = runCatching {
                    AppDataBase.getInstance().chartDao().getChartsBySongId(picked.id)
                }.getOrDefault(emptyList())
                val utage = picked.genre == GENRE_UTAGE ||
                    picked.id in 100000..999999 ||
                    cs.isNotEmpty() && cs.all { it.level.contains("?") }
                if (!utage) {
                    result = picked to cs
                    return@withContext
                }
            }
        }
        return result
    }

    /** 取出文本里的中文难度词本身（绿/黄/红/紫/白），用于「紫799」这类粘连写法的切分。 */
    private fun parseDifficultyWord(arg: String): String =
        listOf("绿", "黄", "红", "紫", "白").firstOrNull { arg.contains(it) }.orEmpty()

    /** 难度关键词（绿/黄/红/紫/白 + 英文名）→ DifficultyType。
     *  注意：白谱 = Re:MASTER（宗师），宴会场谱面等级带「?」，不要混淆。 */
    private fun parseDifficultyKeyword(arg: String): DifficultyType? {
        val lower = arg.lowercase()
        return when {
            lower.contains("basic") || arg.contains("绿") -> DifficultyType.BASIC
            lower.contains("advanced") || arg.contains("黄") -> DifficultyType.ADVANCED
            lower.contains("expert") || arg.contains("红") -> DifficultyType.EXPERT
            lower.contains("remaster") || lower.contains("re:master") ||
                lower.contains("white") || arg.contains("白") -> DifficultyType.REMASTER
            lower.contains("master") || arg.contains("紫") -> DifficultyType.MASTER
            else -> null
        }
    }

    /** 歌名遮罩（保留首尾字）。 */
    private fun maskTitle(title: String): String = when {
        title.length <= 1 -> title
        title.length <= 3 -> title.first() + "＊".repeat(title.length - 1)
        else -> title.first() + "＊".repeat(title.length - 2) + title.last()
    }

    /** 曲师名遮罩（保留首字）。 */
    private fun maskArtist(artist: String): String = when {
        artist.isBlank() || artist == "-" -> "—"
        artist.length <= 1 -> artist
        else -> artist.first() + "＊".repeat(artist.length - 1)
    }

    private fun difficultyLabel(index: Int): String = when (index) {
        0 -> "BASIC"
        1 -> "ADVANCED"
        2 -> "EXPERT"
        3 -> "MASTER"
        4 -> "Re:MASTER"
        5, 6 -> "UTAGE"
        else -> "?"
    }

    companion object {
        private const val TAG = "KibaAgent"

        /** 宴会場流派：官方设定该类歌曲不获 Rating。 */
        private const val GENRE_UTAGE = "宴会場"

        /**
         * 进行中的猜歌对局：答案歌曲 id + 已猜错次数（驱动逐步揭示）。
         *
         * 进程级状态：AgentScreen 的 KibaAgent 实例随导航销毁重组，
         * 对局必须活在 companion 里才能「出了 Agent 页再回来接着猜」。
         * null = 当前没有对局（输入框按普通聊天 / 提示处理）。
         */
        data class GuessSession(val songId: Int, val wrongCount: Int = 0)

        private val _pendingGuess = MutableStateFlow<GuessSession?>(null)

        /** 当前进行中的猜歌对局（UI 据此把输入框切成答题卡，无需 LLM）。 */
        val pendingGuess: StateFlow<GuessSession?> = _pendingGuess.asStateFlow()

        /**
         * 表情包动图合成进度（null = 空闲）。
         *
         * 动图要解码多帧 + 逐层合成 + GIF 编码，耗时从几百毫秒到几秒。UI 监听它
         * 弹一个不可取消的等待层；静态模板不走这条路径，所以平时永远是 null。
         */
        data class MemeProgress(val title: String, val detail: String)

        private val _memeProgress = MutableStateFlow<MemeProgress?>(null)
        val memeProgress: StateFlow<MemeProgress?> = _memeProgress.asStateFlow()

        /** 猜歌放弃关键词：命中即公布答案、结束对局（答题栏也有放弃按钮）。 */
        private val GIVE_UP_WORDS = setOf("放弃", "不猜了", "投降", "公布答案", "不知道")

        /**
         * 工具调用循环的最大轮数。
         * 一轮 = 模型决策 + 执行技能 + 回灌；3 轮足够覆盖
         * 「先 best50 再 push_rating 再总结」这类多技能组合。
         */
        /**
         * 工具链最大轮次。
         *
         * 原来是 3：模型多查一步（例如先 best50 再 push_rating 再查曲目）就到顶，
         * 于是「莫名其妙自己停了」。现在放到 8，正常对话根本碰不到；
         * 真撞到上限也不再甩原始数据，而是走 finalAnswer() 让模型收尾。
         */
        private const val MAX_ROUNDS = 8

        /**
         * 输出被 max_tokens 截断时最多续写几次。
         * 3 次 × 每次预算翻倍，足够写完一篇长回答；再长就是模型自己没完了。
         */
        private const val MAX_CONTINUATIONS = 3

        /** 游戏标记（[AgentSong.game]）：决定卡片点开哪个详情页。 */
        const val GAME_MAIMAI = "maimai"
        const val GAME_CHUNITHM = "chunithm"

        /**
         * 中二推分档位表已迁到 [ChuniPushRecommender.TIERS]：那套「从最省力一档往上挑」
         * 的选择与排序逻辑需要单测覆盖，留在 companion 里没法测。
         */

        /** 输出 token 上限（思考被截断时加额重试的封顶值）。 */
        private const val MAX_TOKENS_CAP = 16384

        /**
         * 技能表：既是功能菜单项，也是 LLM 的 tool 定义。
         *
         * 来源：Kiba 原有能力 + KibaBot（KilluaSakata/Kiba）独有能力提炼。
         * 已剔除：塔罗 / 运势外的占卜、群管理（戳一戳、禁言）、雀魂、Arcaea，
         * 以及与 Kiba 重复的查歌 / 随机一首歌 / B50 查询。
         */
        val SKILLS = listOf(
            AgentSkill(
                "best50", "我的 Best 50",
                "查询查分器 Best 50（官方口径：旧曲 35 + 新曲 15，ra 之和即 DX Rating）",
            ),
            AgentSkill(
                "push_rating", "推荐推分曲目",
                "基于 Best 50 的 B35/B15 地板 Rating，推荐能顶替地板的谱面（含未打与未达上限的已打谱面）",
            ),
            AgentSkill(
                "random_song", "随机来一首",
                "从本地曲库随机选一首歌（含曲师、流派、最高难度与定数）",
            ),
            AgentSkill(
                "song_query", "查歌曲信息",
                "按歌名 / 别名 / 歌曲 ID 查询歌曲信息与各难度的定数、谱师",
                params = listOf(
                    KibaLlm.ToolParam(
                        "query", "string",
                        "歌曲名、别名或歌曲 ID；支持模糊匹配，例：「群青」「グリーンライツ」「1234」",
                        required = true,
                    ),
                ),
                paramHint = "输入歌名 / 别名 / 歌曲 ID，例如：群青",
            ),
            AgentSkill(
                "fortune", "今日舞萌运势",
                "今天的舞萌运势：运势星级、宜忌与今日推荐曲目",
            ),
            AgentSkill(
                "ds_query", "定数查歌",
                "按定数区间查询谱面（宴会場不获 Rating，已排除）",
                params = listOf(
                    KibaLlm.ToolParam(
                        "min", "number",
                        "定数下限，例：12.5；只查一个定数时 min 与 max 填相同值",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "max", "number",
                        "定数上限，例：13.0；省略时与 min 相同（精确匹配）",
                    ),
                ),
                paramHint = "输入定数区间，例如：12 13（只查一个定数就填 12.5）",
            ),
            AgentSkill(
                "score_line", "分数线",
                "指定某首歌的某个难度与目标达成率，给出达到该线允许的判定容错（TAP GREAT 等价数）",
                params = listOf(
                    KibaLlm.ToolParam(
                        "song", "string",
                        "歌曲 ID 或歌名，例：799 或「群青」",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "difficulty", "string",
                        "难度关键词：绿 / 黄 / 红 / 紫 / 白",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "line", "number",
                        "目标达成率百分数，例：100（理论值 101）",
                        required = true,
                    ),
                ),
                paramHint = "输入 歌曲ID 难度 分数线，例如：799 紫 100",
            ),
            AgentSkill(
                "random_filtered", "随机条件曲",
                "按版本（SD/DX）与难度随机取若干首符合条件的曲子",
                params = listOf(
                    KibaLlm.ToolParam(
                        "condition", "string",
                        "条件串，例：「dx 紫 3」（DX + MASTER + 3 首）、「标准 expert」、「sd 红 1」",
                        required = true,
                    ),
                ),
                paramHint = "输入条件，例如：dx 紫 3",
            ),
            AgentSkill(
                "guess_song", "猜歌",
                "随机挑一首歌给出线索，在输入框提交答案（歌名/别名/ID），猜错逐步揭示",
            ),
            AgentSkill(
                "chuni_song", "中二查歌",
                "按歌名 / 假名 / 曲师 / 曲目 ID 查中二节奏歌曲，给出各难度的定数、谱师与物量",
                params = listOf(
                    KibaLlm.ToolParam(
                        "query", "string",
                        "中二歌曲名、假名、曲师或曲目 ID；支持模糊匹配，例：「宿星」「shama」",
                        required = true,
                    ),
                ),
                paramHint = "输入中二歌名 / 假名 / 曲目 ID，例如：宿星",
            ),
            AgentSkill(
                "chuni_ds", "中二定数查歌",
                "按定数区间查中二节奏谱面（只列 EXPERT 及以上没必要，全难度都可查）",
                params = listOf(
                    KibaLlm.ToolParam(
                        "min", "number",
                        "定数下限，例：14.5；只查一个定数时 min 与 max 填相同值",
                        required = true,
                    ),
                    KibaLlm.ToolParam("max", "number", "定数上限，例：15.0；省略时与 min 相同"),
                ),
                paramHint = "输入定数区间，例如：14.5 15",
            ),
            AgentSkill(
                "chuni_random", "中二随机一首",
                "从中二节奏曲库随机选一首（含曲师、流派、BPM 与最高难度定数）",
            ),
            AgentSkill(
                "chuni_best", "我的中二 Rating",
                "按官方口径算中二 Rating（非当前 TOP30 + 当前 TOP20，÷50），并列出贡献最高的 10 首",
            ),
            AgentSkill(
                "chuni_push", "中二推分推荐",
                "按官方单曲 ra 公式推「高定数 + 低档位就能压过地板」的谱面，" +
                    "只在你的综合定数（Best50 定数平均）−0.5 ~ +1.0 区间内挑，按定数从高到低排；" +
                    "SSS+ 大幅降权（要 1009000、距满分只剩 1000 分，只比 SSS 多 0.15 ra），" +
                    "只在 rating 17+ 推无可推时出现",
            ),
            AgentSkill(
                "random_number", "随机数",
                "在给定闭区间内随机取一个整数（Bot 的 XrY 写法）",
                params = listOf(
                    KibaLlm.ToolParam("min", "integer", "下界（含），例：1", required = true),
                    KibaLlm.ToolParam("max", "integer", "上界（含），必须 ≥ min，例：100", required = true),
                ),
                paramHint = "输入区间，例如：1 100",
            ),
            AgentSkill(
                "roll_dice", "投骰子",
                "投若干个骰子（默认 6 面，最多 10 颗；也支持百面骰）",
                params = listOf(
                    KibaLlm.ToolParam("count", "integer", "骰子数量，1–10", required = true),
                    KibaLlm.ToolParam("sides", "integer", "面数，默认 6；百面骰填 100"),
                ),
                paramHint = "输入数量与面数，例如：3（默认6面）或 3 100",
            ),
            AgentSkill(
                "today_kink", "今日性癖",
                "随一个今日性癖 + 人品值（Bot 的 jrxp / 今日性癖）",
            ),
            AgentSkill(
                "eat_what", "今天吃什么",
                "随机决定今天吃什么（有一定概率随到舞萌恶趣味选项：吃绝赞 / 吃星星 / 吃黄金迪拉熊）",
            ),
            AgentSkill(
                "meme", "表情包生成器",
                // 卡片文案只讲「是什么」；「什么时候该主动调用」是给模型看的规则，
                // 写在 skillHint() 的【表情包 / 主动表达】段里，不必占用用户视线。
                "用 meme-generator 的模板生成表情包（静图 / 动图），直接贴在对话里",
                params = listOf(
                    KibaLlm.ToolParam(
                        "template", "string",
                        "模板 key 或中文名/别名，例：high_EQ（低情商高情商）、5000choyen（gocho）、" +
                            "good_news（喜报）、luxun_say（鲁迅说过）；传 list 可拿到全部可生成模板清单",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "texts", "string",
                        "要填进模板的文字，多句用 | 分隔或给 JSON 数组；例：打舞萌|打麻将。" +
                            "省略时用模板自带示例文字",
                    ),
                ),
                paramHint = "模板与文字，用 | 分隔，例如：high_EQ|打舞萌|进行音游韵律训练",
            ),
        )

        /**
         * LLM 专用工具：不进功能菜单项（不展示在对话顶部），只作为 tool 定义暴露给模型。
         * 用于让模型对 maimai 游戏机制 / 新版本内容等「本地没有的数据」能联网查证，
         * 查到的结果回灌后由模型总结给用户——不允许 agent 对 maimai 的事完全不知道。
         */
        private val LLM_ONLY_TOOLS = listOf(
            AgentSkill(
                "memory", "长期记忆",
                "跨会话记住关于用户的**稳定信息**：称呼、主玩游戏与主玩难度、常去的机厅城市、" +
                    "口味偏好（流派 / 曲师 / 谱面风格）、明确要求（「以后都用中文」「别推荐 XX」）、" +
                    "以及他自己说「记住…」的任何事。用户说「记住 / 别忘了 / 以后都…」时**必须调用 save**，" +
                    "不要只在嘴上答应；问「你还记得…吗」或需要个性化时先 list 查一遍（每轮对话开头" +
                    "也会自动注入现有记忆，重复注入的内容不必再查）。" +
                    "不要记一次性的临时信息（这次查的是哪首歌、临时数字），也不要记密码 / API Key 等敏感内容。",
                params = listOf(
                    KibaLlm.ToolParam(
                        "action", "string",
                        "save 记住 / list 列出现有记忆 / update 修改 / forget 忘掉 / clear 清空",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "content", "string",
                        "save / update 必填：一句话写清要记的事实（例：用户叫 Kira，主玩舞萌 DX，中二是副游）",
                    ),
                    KibaLlm.ToolParam(
                        "id", "string",
                        "update / forget 时指哪一条（用 list 返回的 8 位 id）",
                    ),
                    KibaLlm.ToolParam(
                        "keyword", "string",
                        "forget 时按关键词匹配删除（拿不到 id 就用它）",
                    ),
                ),
            ),
            AgentSkill(
                "playlist_cover", "歌单封面设计",
                "为 Agent 专属歌单生成封面。固定模板 kiba-nocturne：对角渐变底 + 左上柔光 + 右下同心弧，" +
                    "左对齐排版（小标签 / 主标题 / 副标题 / 页脚），配色由 mood 决定。" +
                    "**每次生成歌单都必须用它出封面**（生成流程已自动调一次，模型不必重复生成歌单）；" +
                    "用户说「换个封面 / 改个名字 / 要更燃一点」时调用它重做。" +
                    "填字段：title ≤ 12 字（主标题）、subtitle ≤ 24 字（一行副标题）、" +
                    "mood 从「夜行 / 清晨 / 热血 / 静谧 / 赛博 / 复古 / 海洋 / 甜梦」里选、tags 2~3 个短标签。" +
                    "不传 playlist 时默认改最近一张歌单。",
                params = listOf(
                    KibaLlm.ToolParam(
                        "playlist", "string",
                        "歌单 id 或名字关键词；省略则取最近生成的一张",
                    ),
                    KibaLlm.ToolParam("title", "string", "封面主标题，≤ 12 字，例：深夜循环"),
                    KibaLlm.ToolParam("subtitle", "string", "封面副标题，≤ 24 字，例：给凌晨还在打歌的人"),
                    KibaLlm.ToolParam(
                        "mood", "string",
                        "情绪关键词（决定配色）：夜行 / 清晨 / 热血 / 静谧 / 赛博 / 复古 / 海洋 / 甜梦",
                    ),
                    KibaLlm.ToolParam("tags", "string", "2~3 个短标签，用「、」分隔，例：电子、女声"),
                ),
            ),
            AgentSkill(
                "moegirl_kb", "萌娘百科知识库",
                "查舞萌 / 舞萌DX / 中二节奏的**机制与设定**：定数、Rating 公式与分代、判定档位与帧数、" +
                    "音符种类、达成率 / BREAK 加成、评级区间、段位认定、宴会场与特殊谱面、集章卡 / 区域、" +
                    "国服登录与功能差异、CHUNITHM 技能数值 / 角色界限突破 / 地图格 / CLASS 认定、" +
                    "版本沿革、术语黑话。回答机制类问题**先用它**，比联网快且不会编造；" +
                    "本地没检索到再用 web_search。资料含玩家站与镜像口径，数值类结论要注明「可能随版本变化」。",
                params = listOf(
                    KibaLlm.ToolParam(
                        "query", "string",
                        "要查的名词或短句，例：单曲 Rating 公式 / 判定档位 / 段位认定 / 宴会场 / 金框体 / BREAK 加成 / 界限突破",
                        required = true,
                    ),
                ),
            ),
            AgentSkill(
                "web_search", "网络搜索",
                "联网搜索并抓取网页正文（maimai 新模式玩法 / 道具获取 / 解谜线索 / 活动情报等游戏机制类问题必查）；" +
                    "正文由后台浏览器渲染后读取，SPA 页面也拿得到。给 url 可跳过搜索直接打开某网页",
                params = listOf(
                    KibaLlm.ToolParam(
                        "query", "string",
                        "搜索关键词，例：maimai DX kaleidoscope 黄门钥匙 获取方法；给 url 时可省略",
                    ),
                    KibaLlm.ToolParam(
                        "url", "string",
                        "可选：直接打开并读取这个网址的渲染后正文，例：https://maimai.lxns.net/chart?chart_id=1920",
                    ),
                ),
            ),
            AgentSkill(
                "browser", "后台浏览器",
                "AI 可操控的后台 WebView：open 打开网页、text 读渲染后正文、eval 执行 JS、" +
                    "click 点元素、fill 填表单、scroll 滚动、close 关闭。" +
                    "适合需要 JS 渲染 / 展开 / 翻页 / 点按钮的站点；操作有状态，先 open 再操作同一页面。" +
                    "返回的是当前页面真实内容，不要凭空猜网页里有什么",
                params = listOf(
                    KibaLlm.ToolParam(
                        "action", "string",
                        "open / text / eval / click / fill / scroll / close",
                        required = true,
                    ),
                    KibaLlm.ToolParam("url", "string", "open 时必填，完整 http(s) 地址"),
                    KibaLlm.ToolParam(
                        "js", "string",
                        "eval 时必填：在页面里执行的 JS，最后表达式的值会返回（可读 DOM / 取全局变量 / 调页面 API）",
                    ),
                    KibaLlm.ToolParam("selector", "string", "text/click/fill 时的 CSS 选择器"),
                    KibaLlm.ToolParam(
                        "value", "string",
                        "fill 时要填的值；scroll 时填滚动像素（正数向下，默认 800）",
                    ),
                    KibaLlm.ToolParam(
                        "wait_ms", "number",
                        "open 后在 onPageFinished 之外额外等 SPA 渲染的毫秒数，默认 1500，慢站可给 4000",
                    ),
                    KibaLlm.ToolParam(
                        "max_chars", "number",
                        "返回文本上限，默认 8000；要拿完整长文本可给 200000",
                    ),
                ),
            ),
            AgentSkill(
                "maimai_chart_simai", "读取舞萌谱面（Simai）",
                "【舞萌 DX 分类】按曲目 ID + 难度从落雪谱面预览页「Simai 语句」栏读取谱面，" +
                    "返回该难度的**标准 Simai 文本**（BPM / 拍数已还原成 (bpm){n} 写法）。" +
                    "用户问某首歌某难度的谱面配置、物量、星星 / 滑键 / 长按、变速、段落、" +
                    "或要你分析谱面时，**必须先调用它拿到真实谱面**，绝不能凭印象编造。" +
                    "不知道曲目 ID 就先用 song_query 查；解读记号再调 simai_syntax",
                params = listOf(
                    KibaLlm.ToolParam(
                        "song_id", "integer",
                        "曲目 ID（本地 / 水鱼 id；DX 谱面与标准谱面共用 id，宴谱 ≥100000）",
                        required = true,
                    ),
                    KibaLlm.ToolParam(
                        "difficulty", "integer",
                        "0=BASIC / 1=ADVANCED / 2=EXPERT / 3=MASTER / 4=Re:MASTER；宴谱可省略",
                    ),
                    KibaLlm.ToolParam(
                        "max_chars", "number",
                        "谱面正文返回上限，默认 20000；谱面很长时可给到 120000",
                    ),
                ),
            ),
            AgentSkill(
                "simai_syntax", "Simai 谱面语法",
                "查 Simai（舞萌 DX 谱面记述格式）的标准写法与记号含义：" +
                    "BPM 与 (bpm){n} 拍数、TAP / HOLD / SLIDE 形状与连结、TOUCH、EACH、BREAK、EX、" +
                    "first / E / 元数据。**用途一**：解读 maimai_chart_simai 拿到的谱面文本；" +
                    "**用途二**：用户问「simai 里 XX 怎么写」时讲解写法。" +
                    "来源为 simai 官方语法页整理，回答写法问题以它为准",
                params = listOf(
                    KibaLlm.ToolParam(
                        "topic", "string",
                        "要查的语法点，例：SLIDE 连结 / BPM 与拍数 / HOLD 时长 / BREAK / EACH / TOUCH / EX 音符",
                        required = true,
                    ),
                ),
            ),
        )

        /** 今日性癖候选（源自 KibaBot plugins/maimaidx.py 的 xp_list，33 项）。 */
        private val KINK_LIST = listOf(
            "滴蜡熊", "幸隐", "14+", "白潘", "紫潘", "PANDORA BOXXX", "排队区", "旧框", "干饭",
            "超常maimai", "收歌", "福瑞", "削除", "HAPPY", "谱面-100号", "lbw", "茄子卡狗",
            "打五把CSGO", "一姬", "打麻将", "光吉猛修", "怒锤", "暴漫", "鼓动", "鼓动(红)",
            "大司马", "电棍", "海子姐", "东雪莲", "GIAO", "去沈阳大街", "一眼丁真", "陈睿",
        )

        /** 今天吃什么：普通菜单（大众家常 / 外卖常客）。 */
        private val EAT_LIST = listOf(
            "红烧肉", "宫保鸡丁", "鱼香肉丝", "麻婆豆腐", "回锅肉", "可乐鸡翅",
            "兰州拉面", "牛肉面", "刀削面", "炸酱面", "重庆小面",
            "黄焖鸡米饭", "沙县小吃", "麻辣烫", "冒菜", "螺蛳粉", "过桥米线",
            "猪脚饭", "盖浇饭", "蛋炒饭", "煲仔饭", "寿喜锅", "小火锅",
            "烤肉拌饭", "韩式炸鸡", "披萨", "汉堡", "寿司", "饺子", "生煎",
            "肠粉", "烧鹅饭", "羊肉泡馍", "大盘鸡拌面", "锅包肉", "糖醋里脊",
        )

        /**
         * 今天吃什么：舞萌恶趣味选项（Bot 传统艺能，低概率随到）。
         * - 吃绝赞：机况不好把你的 BREAK 判定吃掉了（失分）
         * - 吃星星：机况不好把你的 SLIDE 判定吃掉了（失分）
         * - 吃黄金迪拉熊：Valsqotch 封面金熊的戏称
         */
        private val EAT_MAIMAI_LIST = listOf(
            "绝赞" to "机况不好，BREAK 判定被吃了，建议找机修讨个说法。",
            "星星" to "机况不好，SLIDE 判定被吃了，这锅不背在你身上。",
            "黄金迪拉熊" to "Valsqotch 封面那只金熊，能吃得上它说明你是天选之人。",
        )

    }
}
