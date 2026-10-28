package com.kira.kiba.core.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.utils.CacheCleaner
import com.kira.kiba.core.utils.CacheDirs
import com.kira.kiba.core.utils.Constants
import com.kira.kiba.core.best50.MemeImageBuilder
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.stats.RecommendFusion
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 设计原则（v0.3.0 起）：
 * - **聊天一律走 LLM**：用户输入不匹配任何本地意图，直接交给 LLM；
 *   LLM 通过 tool calling 调用本地技能，技能数据回灌给模型让它总结。
 * - **本地逻辑只在点技能卡时触发**：`runSkill()` 是唯一的本地执行入口，
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
        val type: String,
        val topLevel: String,
        val topDs: Double,
        val imageUrl: String = "",
        val difficulty: String = "",
    )

    /**
     * 技能卡：可点击直接执行（无需 LLM），同时作为 LLM 的 tool。
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
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 聊天统一入口：**只走 LLM**。
     * 未配置 LLM 时给出明确引导（技能卡仍可离线使用）。
     */
    suspend fun handle(input: String): AgentReply {
        val text = input.trim()
        if (text.isEmpty()) return AgentReply("说点什么吧。")
        if (!KibaLlm.isConfigured()) {
            return AgentReply(
                "当前未配置 LLM，无法自由提问。\n" +
                    "请点上方技能卡直接使用（Best 50 / 推分 / 随机 / 查歌 / 运势 / 猜歌…），" +
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
                    "请点上方技能卡直接使用（Best 50 / 推分 / 随机 / 查歌 / 运势 / 猜歌…），" +
                    "或到「设置 → Agent 模型配置」开启对话能力。",
                skills = SKILLS,
            )
        }
        return runAgent(text, onChunk)
    }

    /** 直接执行某个技能（技能卡点击专用，不经过 LLM）。 */
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
        "low_high_eq" -> {
            // 文本写法：低情商X高情商Y
            val m = Regex("低情商(.+)高情商(.+)").find(arg)
            if (m != null) {
                lowHighEq(m.groupValues[1].trim(), m.groupValues[2].trim())
            } else {
                val parts = arg.split("|", "，", ",", " ").filter { it.isNotBlank() }
                if (parts.size >= 2) lowHighEq(parts[0], parts[1]) else lowHighEq("打舞萌", "打麻将")
            }
        }
        "gocho" -> {
            val parts = arg.split("|", "，", ",", " ").filter { it.isNotBlank() }
            if (parts.size >= 2) gocho(parts[0], parts[1]) else gocho("", "")
        }
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
            "low_high_eq" -> lowHighEq(
                args["low"] ?: args["left"] ?: args["a"] ?: "",
                args["high"] ?: args["right"] ?: args["b"] ?: "",
            )
            "gocho" -> gocho(
                args["red"] ?: args["a"] ?: args["left"] ?: "",
                args["silver"] ?: args["b"] ?: args["right"] ?: "",
            )
            "today_kink" -> todayKink()
            "eat_what" -> eatWhat()
            "web_search" -> webSearch(
                args["query"] ?: args["q"] ?: args["keyword"] ?: fallbackText
            )
            "random_filtered" -> randomFiltered(
                args["condition"] ?: args["filter"] ?: fallbackText
            )
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
                SpUtil.getLlmSystemPrompt() + "\n\n" + localContext() + "\n\n" + skillHint()
            )
        )
        messages += AgentSession.llmHistory(maxTurns = 8)
        messages += KibaLlm.Message.user(input)

        // LLM 专用工具（如联网搜索）也一并暴露给模型，但不进技能卡
        val tools = (SKILLS + LLM_ONLY_TOOLS).map { KibaLlm.ToolDef(it.id, it.summary, it.params) }
        var collected = AgentReply("")
        // 思考型模型可能把 token 全烧在 reasoning 上，正文一个字都没吐出来。
        // 这种情况下加额重试一次，而不是把思考当答案。
        var budget = SpUtil.getLlmMaxTokens()
        var budgetRetried = false
        // 已经兜底执行过的技能，避免重复调用
        val executed = mutableSetOf<String>()

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
                    collected = collected.merge(reply)
                    results[call] = reply.text
                    executed += call.name
                }

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
                    // 轮次用尽：把已拿到的真实数据直接给用户，不再让模型继续空转
                    return collected.copy(text = collected.text.ifBlank { "已完成技能调用。" })
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
                    collected = collected.merge(reply)
                    results[name] = reply.text
                    executed += name
                }
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
                    return collected.copy(text = collected.text.ifBlank { "已完成技能调用。" })
                }
                continue
            }

            if (narration.isNotEmpty() && answer.isBlank() && collected.text.isNotBlank()) {
                // 模型反复空谈「我要调用 X」但 X 都已执行过、正文始终空白：
                // 手里已有真实数据，直接交付，不再让模型空转到底
                return collected
            }

            if (answer.isBlank() && result.reasoning.isNotBlank() && !budgetRetried) {
                // 典型的「思考烧光 token」：正文被截没了。清掉这段思考，加额重试一次。
                onChunk?.invoke(KibaLlm.StreamChunk(reset = true))
                budgetRetried = true
                budget = (budget * 2).coerceAtMost(MAX_TOKENS_CAP)
                continue
            }

            // 有答案 → 这一轮就是最终答复
            return collected.copy(
                text = answer.ifBlank {
                    collected.text.ifBlank {
                        if (result.reasoning.isNotBlank()) {
                            "模型只输出了思考过程，没有给出结论。可以换个说法再问一次，或点上方技能卡直接使用。"
                        } else {
                            "（模型没有返回内容）"
                        }
                    }
                }
            )
        }
        return collected
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
    )

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
                "除非搜索后确实一无所获，否则不允许直接说不知道，更不能编造。"
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
                val songType = typeById[c.songId] ?: return@mapNotNull null
                val isNew = isNewById[c.songId] ?: return@mapNotNull null
                val floor = (if (isNew) b15Floor else b35Floor) ?: return@mapNotNull null
                val current = recordByKey[c.songId to c.difficultyType.ordinal]
                val target = (if (songType == Constants.CHART_TYPE_DX) RA_TIERS_DX else RA_TIERS_SD)
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

    /** 单曲 Rating 档位表（与上游 diving-fish computeRa 完全一致，floor 口径）：达成率下限 → 乘数。 */
    private val RA_TIERS_DX = listOf(
        100.5 to 22.4, 100.0 to 21.6, 99.5 to 21.1, 99.0 to 20.8,
        98.0 to 20.0, 97.0 to 20.0, 94.0 to 16.8, 90.0 to 15.2,
        80.0 to 13.6, 75.0 to 12.0, 70.0 to 11.2, 60.0 to 9.6, 50.0 to 8.0,
    )
    private val RA_TIERS_SD = listOf(
        100.5 to 14.0, 100.0 to 13.5, 99.5 to 13.2, 99.0 to 13.0,
        98.0 to 12.7, 97.0 to 12.5, 94.0 to 10.5, 90.0 to 9.5,
        80.0 to 8.5, 75.0 to 7.5, 70.0 to 7.0, 60.0 to 6.0, 50.0 to 5.0,
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
     * 低情商 / 高情商梗图（源自 KibaBot「低情商X高情商Y」）：
     * 生成图片并存入相册，同时在对话里展示。
     */
    private fun lowHighEq(left: String, right: String): AgentReply {
        if (left.length > 15 || right.length > 15) {
            return AgentReply("为了图片质量，每边请不要多于 15 个字符嗷。")
        }
        val bmp = MemeImageBuilder.buildLowHighEq(context, left, right)
            ?: return AgentReply("梗图模板缺失，生成失败。")
        return saveMeme(bmp, "高低情商")
    }

    /** gocho 梗图（源自 KibaBot「gocho A B」）。 */
    private fun gocho(red: String, silver: String): AgentReply {
        if (red.isBlank() || silver.isBlank()) {
            return AgentReply("gocho 需要两个参数，例：gocho 打舞萌 打麻将")
        }
        val bmp = MemeImageBuilder.buildGocho(context, red, silver)
            ?: return AgentReply("gocho 生成失败。")
        return saveMeme(bmp, "gocho")
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

    // ---------- 联网搜索（LLM 专用 web_search） ----------

    private val SEARCH_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** 一条搜索结果：标题 / 链接 / 摘要。 */
    private data class WebResult(val title: String, val url: String, val snippet: String)

    /**
     * 联网搜索：Bing 为主（国内直连可用），DuckDuckGo 兜底（有代理时更准）；
     * 再抓前 2 条结果页正文，一并回灌给模型总结。
     */
    private suspend fun webSearch(query: String): AgentReply {
        val q = query.trim()
        if (q.isEmpty()) return AgentReply("web_search 需要一个 query 参数。")
        return withContext(Dispatchers.IO) {
            runCatching {
                val results = searchBing(q).ifEmpty { searchDuckDuckGo(q) }
                if (results.isEmpty()) {
                    return@runCatching AgentReply(
                        "没搜到「$q」的相关资料，可能关键词太偏或网络受限。",
                        skills = SKILLS,
                    )
                }
                val sb = StringBuilder("【web_search 搜索结果】关键词：$q\n")
                results.take(5).forEachIndexed { i, r ->
                    sb.append("${i + 1}. ${r.title}\n   链接：${r.url}\n   摘要：${r.snippet}\n")
                }
                // 正文抓取（最多 2 条，各限 3500 字，超时/反爬就跳过）
                for (r in results.take(2)) {
                    val page = fetchPageText(r.url) ?: continue
                    if (page.isBlank()) continue
                    sb.append("\n【网页正文】${r.title}（${r.url}）\n$page\n")
                }
                AgentReply(sb.toString(), skills = SKILLS)
            }.getOrElse {
                AgentReply(
                    "搜索失败：${it.message}（设备网络可能受限，请基于已有知识谨慎回答，不知道就说不知道）",
                    skills = SKILLS,
                )
            }
        }
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

        /** 猜歌放弃关键词：命中即公布答案、结束对局（答题栏也有放弃按钮）。 */
        private val GIVE_UP_WORDS = setOf("放弃", "不猜了", "投降", "公布答案", "不知道")

        /**
         * 工具调用循环的最大轮数。
         * 一轮 = 模型决策 + 执行技能 + 回灌；3 轮足够覆盖
         * 「先 best50 再 push_rating 再总结」这类多技能组合。
         */
        private const val MAX_ROUNDS = 3

        /** 输出 token 上限（思考被截断时加额重试的封顶值）。 */
        private const val MAX_TOKENS_CAP = 16384

        /**
         * 技能表：既是技能卡，也是 LLM 的 tool 定义。
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
                "low_high_eq", "低情商高情商",
                "生成一张「低情商 / 高情商」对比梗图并存入相册",
                params = listOf(
                    KibaLlm.ToolParam("low", "string", "低情商那半句（≤15 字），例：打舞萌", required = true),
                    KibaLlm.ToolParam("high", "string", "高情商那半句（≤15 字），例：进行音游韵律训练", required = true),
                ),
                paramHint = "输入两句，用空格分开，例如：打舞萌 进行音游韵律训练",
            ),
            AgentSkill(
                "gocho", "gocho 梗图",
                "生成 gocho 风格的红 / 银双行梗图并存入相册",
                params = listOf(
                    KibaLlm.ToolParam("red", "string", "红字那行，例：打舞萌", required = true),
                    KibaLlm.ToolParam("silver", "string", "银字那行，例：打麻将", required = true),
                ),
                paramHint = "输入两行，用空格分开，例如：打舞萌 打麻将",
            ),
        )

        /**
         * LLM 专用工具：不进技能卡（不展示在对话顶部），只作为 tool 定义暴露给模型。
         * 用于让模型对 maimai 游戏机制 / 新版本内容等「本地没有的数据」能联网查证，
         * 查到的结果回灌后由模型总结给用户——不允许 agent 对 maimai 的事完全不知道。
         */
        private val LLM_ONLY_TOOLS = listOf(
            AgentSkill(
                "web_search", "网络搜索",
                "联网搜索并抓取网页正文（maimai 新模式玩法 / 道具获取 / 解谜线索 / 活动情报等游戏机制类问题必查）",
                params = listOf(
                    KibaLlm.ToolParam(
                        "query", "string",
                        "搜索关键词，例：maimai DX kaleidoscope 黄门钥匙 获取方法",
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
