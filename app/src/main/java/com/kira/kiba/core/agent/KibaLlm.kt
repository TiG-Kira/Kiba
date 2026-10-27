package com.kira.kiba.core.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM 客户端 —— 任意 OpenAI 兼容 chat/completions 端点。
 *
 * 覆盖：OpenAI 官方 / 各类 API 中转 / 本地 ollama（baseUrl 指向本机即可）。
 * 配置见 [SpUtil] 的 LLM 系列字段；未启用或未配置时 Agent 走本地逻辑。
 *
 * 两套工具调用协议同时支持（不少中转 / 本地推理服务只认后者）：
 * - **原生 function calling**：`choices[].tool_calls`；
 * - **文本内联调用**：模型把 `{"name":"best50","arguments":{}}` 直接写进正文，
 *   由 [parseInlineToolCalls] 抠出来执行，并把 JSON 从正文里摘掉。
 *
 * 另外两条硬规矩：
 * - **思考内容（reasoning_content）绝不混进正文**，否则同一段话会在
 *   「深度思考」卡片和正文气泡里各显示一次；
 * - provider 不接受 `tools` 参数时（HTTP 400），自动去掉 tools 重试一次，
 *   转而走文本内联协议。
 */
object KibaLlm {

    private const val TAG = "KibaLlm"

    /**
     * 一条对话消息。
     *
     * @property toolCalls assistant 回合携带的原生工具调用（回传时必须带上，否则 provider 会拒绝下一轮）
     * @property toolCallId role=tool 回合对应的 tool_call_id
     * @property reasoningContent 推理模型在工具回合要求回传的思考内容（缺了下一轮会报错）
     */
    data class Message(
        val role: String,
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String = "",
        val reasoningContent: String = "",
    ) {
        companion object {
            fun system(content: String) = Message("system", content)
            fun user(content: String) = Message("user", content)
            fun assistant(content: String) = Message("assistant", content)
            fun assistantToolCalls(
                content: String,
                calls: List<ToolCall>,
                reasoning: String = "",
            ) = Message("assistant", content, toolCalls = calls, reasoningContent = reasoning)
            fun toolResult(toolCallId: String, content: String) =
                Message("tool", content, toolCallId = toolCallId)
        }
    }

    /** 调用失败分类：UI 据此给出可操作的提示。 */
    sealed class LlmError(message: String) : Exception(message) {
        /** 未启用 / 未配置 Key。 */
        class NotConfigured : LlmError("LLM 未启用或未配置 API Key")

        /** 401/403：Key 无效或无权访问该模型。 */
        class Unauthorized(detail: String) : LlmError("API Key 无效或无权访问（$detail）")

        /** 404：地址或模型名错误。 */
        class NotFound(detail: String) : LlmError("地址或模型不存在（$detail）")

        /** 429：限流。 */
        class RateLimited(detail: String) : LlmError("请求被限流（$detail）")

        /** 其余 HTTP 错误。 */
        class Server(val code: Int, detail: String) : LlmError("服务端错误 HTTP $code（$detail）")

        /** 网络层失败（超时 / DNS / 连接）。 */
        class Network(detail: String) : LlmError("网络不通（$detail）")
    }

    /**
     * 工具的单个入参（JSON Schema 的子集：只用到 type / description）。
     *
     * @property name 参数名（snake_case，LLM 会在 arguments 里用它当 key）
     * @property type JSON Schema 类型：string / number / integer / boolean
     * @property description 给模型看的说明（要写清取值样例，模型才不会乱填）
     * @property required 是否必填
     */
    data class ToolParam(
        val name: String,
        val type: String = "string",
        val description: String = "",
        val required: Boolean = false,
    )

    /** 工具（技能）定义，OpenAI 兼容 function calling 格式。 */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: List<ToolParam> = emptyList(),
    )

    /**
     * 模型请求的工具调用。
     *
     * @property id 原生调用的 id；**文本内联调用为空**（据此区分两套协议）
     */
    data class ToolCall(
        val id: String = "",
        val name: String = "",
        val arguments: String = "",
    )

    /** 一次对话的结果：文本 + 可能存在的工具调用。 */
    data class ChatResult(
        val text: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        /** 深度思考（reasoning_content / reasoning）内容，单独展示用。 */
        val reasoning: String = "",
    )

    /**
     * 流式增量：正文与思考过程分开回传。
     *
     * @property reset 清空已累积的正文（用于丢弃「我打算调用 X」这类没有结论的空谈）
     */
    data class StreamChunk(
        val text: String = "",
        val reasoning: String = "",
        val reset: Boolean = false,
    )

    private data class ChatRequest(
        val model: String,
        val messages: List<MessageDto>,
        val temperature: Float,
        @SerializedName("max_tokens") val maxTokens: Int,
        val tools: List<Map<String, Any>>? = null,
        @SerializedName("tool_choice") val toolChoice: String? = null,
        val stream: Boolean = false,
    )

    /** 出站消息：按 OpenAI 规范序列化（null 字段由 Gson 自动省略）。 */
    private data class MessageDto(
        val role: String,
        val content: String,
        @SerializedName("tool_calls") val toolCalls: List<OutToolCall>? = null,
        @SerializedName("tool_call_id") val toolCallId: String? = null,
        @SerializedName("reasoning_content") val reasoningContent: String? = null,
    )

    private data class OutToolCall(
        val id: String,
        val type: String = "function",
        val function: OutFunction,
    )

    private data class OutFunction(
        val name: String,
        val arguments: String,
    )

    private data class ToolCallDto(
        val id: String? = null,
        val function: FunctionDto? = null,
    )

    private data class FunctionDto(
        val name: String? = null,
        val arguments: String? = null,
    )

    private data class ChatResponse(
        val choices: List<Choice>? = null,
        val error: ApiError? = null,
    )

    private data class Choice(
        val message: ResponseMessage? = null,
        @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    )

    private data class ResponseMessage(val role: String? = null, val content: String? = null)

    private data class ApiError(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 推理模型（如 MiMo）思考期网关可能长时间不吐字节，读超时给足余量，
        // 避免正文还没开始就被 SocketTimeout 中途採断（读超时按「两次字节之间」计）
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** 是否可用（开关打开且有 Key；本地模型无 Key 时至少要有地址）。 */
    fun isConfigured(): Boolean =
        SpUtil.isLlmEnabled() && (SpUtil.getLlmApiKey().isNotBlank() || SpUtil.getLlmBaseUrl().isNotBlank())

    /**
     * 通用对话：messages 原样透传，返回助手文本。
     * 失败抛 [LlmError] 子类（已含人类可读信息）。
     */
    suspend fun chat(
        messages: List<Message>,
        tools: List<ToolDef> = emptyList(),
        maxTokens: Int? = null,
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(LlmError.NotConfigured())
        }
        runCatching {
            execute(messages, tools, maxTokens, stream = false, onChunk = null)
        }.onFailure { e ->
            if (e is LlmError) {
                Log.w(TAG, "chat failed: ${e.message}")
            } else {
                Log.w(TAG, "chat failed", e)
                throw LlmError.Network(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 测试连接：发一条最小对话，返回人类可读结果（成功回模型标识，失败回原因）。
     */
    suspend fun testConnection(): String {
        if (!SpUtil.isLlmEnabled()) return "LLM 未启用（先打开总开关）"
        if (SpUtil.getLlmApiKey().isBlank() && SpUtil.getLlmBaseUrl().isBlank()) {
            return "未配置 API Key 或地址"
        }
        return chat(
            listOf(
                Message.system("你是连接测试探针，只回复 OK 两个字符。"),
                Message.user("ping"),
            ),
        ).fold(
            onSuccess = { "连接成功，模型回复：${it.text.take(20)}" },
            onFailure = { e -> "连接失败：${e.message}" },
        )
    }

    // ---------- 内部：请求 / 解析 ----------

    /**
     * 发一次请求；provider 不接受 `tools` 参数时自动去掉 tools 重试一次。
     *
     * @param maxTokens 覆盖 SpUtil 里的输出上限（思考被截断时用来加额重试）
     * @param onChunk 非空走 SSE 流式
     */
    private suspend fun execute(
        messages: List<Message>,
        tools: List<ToolDef>,
        maxTokens: Int?,
        stream: Boolean,
        onChunk: (suspend (StreamChunk) -> Unit)?,
    ): ChatResult {
        return try {
            executeOnce(messages, tools, maxTokens, stream, onChunk)
        } catch (e: LlmError.Server) {
            // provider 不认 tools 参数（400）：去掉后重试，改走文本内联调用协议。
            // 错误文案五花八门（tool / function / parameter / unsupported），
            // 这里宽松匹配，只要是 400 且请求带了 tools 就降级重试。
            val rejectedTools = e.code == 400 && tools.isNotEmpty()
            if (!rejectedTools) throw e
            Log.w(TAG, "provider 拒绝 tools 参数，去掉后重试（改走文本内联调用协议）：${e.message}")
            // 流式场景下前一次可能已流出部分内容：重置后再重试，避免新旧文本拼接重复
            if (stream && onChunk != null) onChunk(StreamChunk(reset = true))
            executeOnce(messages, emptyList(), maxTokens, stream, onChunk)
        }
    }

    /** 组装请求体。 */
    private fun buildRequest(
        messages: List<Message>,
        tools: List<ToolDef>,
        maxTokens: Int?,
        stream: Boolean,
    ): ChatRequest = ChatRequest(
        model = SpUtil.getLlmModel().ifBlank { SpUtil.LLM_DEFAULT_MODEL },
        messages = messages.map { it.toDto() },
        temperature = SpUtil.getLlmTemperature(),
        maxTokens = maxTokens ?: SpUtil.getLlmMaxTokens(),
        tools = if (tools.isEmpty()) {
            null
        } else {
            tools.map { tool ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to tool.parameters.associate { p ->
                                p.name to mapOf(
                                    "type" to p.type,
                                    "description" to p.description,
                                )
                            },
                            "required" to tool.parameters.filter { it.required }.map { it.name },
                        ),
                    ),
                )
            }
        },
        toolChoice = if (tools.isEmpty()) null else "auto",
        stream = stream,
    )

    /** 出站消息 → DTO（只填该角色需要的字段，其余留 null 由 Gson 省略）。 */
    private fun Message.toDto(): MessageDto = MessageDto(
        role = role,
        content = content,
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
            OutToolCall(
                id = call.id,
                function = OutFunction(
                    name = call.name,
                    arguments = call.arguments.ifBlank { "{}" },
                ),
            )
        },
        toolCallId = toolCallId.takeIf { it.isNotBlank() },
        reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
    )

    /** HTTP 错误 → 带人类可读信息的 [LlmError]。 */
    private fun httpError(code: Int, body: String): LlmError {
        val apiMsg = runCatching {
            gson.fromJson(body, ChatResponse::class.java)?.error?.message
        }.getOrNull().orEmpty()
        return when (code) {
            401, 403 -> LlmError.Unauthorized(apiMsg.ifBlank { "HTTP $code" })
            404 -> LlmError.NotFound(apiMsg.ifBlank { "HTTP $code" })
            429 -> LlmError.RateLimited(apiMsg.ifBlank { "HTTP $code" })
            else -> LlmError.Server(code, apiMsg.ifBlank { body.take(120) })
        }
    }

    /**
     * 解析一次完整响应体（非流式）。
     *
     * @param knownTools 已知技能 id，用于识别「文本内联工具调用」
     */
    private fun parseChatBody(body: String, knownTools: Set<String>, code: Int): ChatResult {
        val nativeCalls = parseToolCalls(body)
        var text = extractContent(body)
        val reasoning = extractReasoning(body)
        // 思考内容不允许混进正文（否则同一句话会在「深度思考」和正文里各出现一次）
        text = stripReasoningEcho(text, reasoning)
        // provider 不支持原生 function calling 时，模型会把调用 JSON 写进正文
        val inline = parseInlineToolCalls(text, knownTools)
        text = inline.cleanedText
        val calls = nativeCalls + inline.calls
        if (calls.isEmpty() && text.isBlank() && reasoning.isBlank()) {
            // 报错带上响应原文片段，便于定位是哪种非标准结构
            val snippet = body.replace(Regex("\\s+"), " ").take(220)
            throw LlmError.Server(code, "响应缺少 content 字段（原文：$snippet）")
        }
        return ChatResult(text = text, toolCalls = calls, reasoning = reasoning)
    }

    /**
     * 从响应体中提取助手文本，兼容多种非标准结构。
     *
     * 覆盖：
     * - OpenAI 标准：choices[0].message.content（字符串）
     * - 数组型 content：[{type:text,text:...}]
     * - 旧版 completions：choices[0].text
     * - Responses 风格：顶层 output_text
     * - 部分中转包装：顶层 content / response / data.content / data.choices
     *
     * 注意：**不再**用 reasoning_content 兜底正文 —— 思考与正文必须分开展示。
     */
    internal fun extractContent(body: String): String {
        val root = runCatching {
            JsonParser.parseString(body).asJsonObject
        }.getOrNull() ?: return ""

        fun readText(el: JsonElement?): String = when {
            el == null || el.isJsonNull -> ""
            el.isJsonPrimitive -> el.asString
            el.isJsonArray -> el.asJsonArray.joinToString("\n") { readText(it) }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                val t = obj.get("text")
                if (t != null && !t.isJsonNull) readText(t)
                else obj.get("content")?.let { readText(it) }.orEmpty()
            }
            else -> ""
        }

        fun fromChoices(choicesEl: JsonElement?): String {
            val arr = choicesEl?.asJsonArray ?: return ""
            for (choice in arr) {
                val obj = choice.asJsonObject
                val msg = obj.get("message")
                if (msg != null && !msg.isJsonNull) {
                    val m = msg.asJsonObject
                    val c = readText(m.get("content"))
                    if (c.isNotBlank()) return c
                    val o = readText(m.get("output_text"))
                    if (o.isNotBlank()) return o
                }
                val t = readText(obj.get("text"))
                if (t.isNotBlank()) return t
                val delta = obj.get("delta")
                if (delta != null && !delta.isJsonNull) {
                    val d = readText(delta.asJsonObject.get("content"))
                    if (d.isNotBlank()) return d
                }
            }
            return ""
        }

        fromChoices(root.get("choices")).takeIf { it.isNotBlank() }?.let { return it }

        readText(root.get("output_text")).takeIf { it.isNotBlank() }?.let { return it }

        listOf("content", "response", "text", "message", "result").forEach { key ->
            readText(root.get(key)).takeIf { it.isNotBlank() }?.let { return it }
        }

        val data = root.get("data")
        if (data != null && !data.isJsonNull && data.isJsonObject) {
            val d = data.asJsonObject
            fromChoices(d.get("choices")).takeIf { it.isNotBlank() }?.let { return it }
            listOf("content", "response", "text", "message", "result").forEach { key ->
                readText(d.get(key)).takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return ""
    }

    /**
     * 流式对话（OpenAI SSE）：逐块回传正文与思考过程，结束返回完整结果。
     *
     * 覆盖的增量字段：
     * - `choices[].delta.content` —— 正文
     * - `choices[].delta.reasoning_content` / `reasoning` —— 深度思考
     * - `choices[].delta.tool_calls[]` —— 工具调用（按 index 累积 name / arguments）
     *
     * 服务端忽略 stream 参数、直接返回普通 JSON 时，按非流式解析同一份响应，
     * 不额外再发一次请求。
     */
    suspend fun chatStream(
        messages: List<Message>,
        tools: List<ToolDef> = emptyList(),
        maxTokens: Int? = null,
        onChunk: suspend (StreamChunk) -> Unit,
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(LlmError.NotConfigured())
        runCatching {
            execute(messages, tools, maxTokens, stream = true, onChunk = onChunk)
        }.onFailure { e ->
            if (e is LlmError) Log.w(TAG, "stream failed: ${e.message}")
            else Log.w(TAG, "stream failed", e)
        }
    }

    /** 单次执行（流式或非流式）。 */
    private suspend fun executeOnce(
        messages: List<Message>,
        tools: List<ToolDef>,
        maxTokens: Int?,
        stream: Boolean,
        onChunk: (suspend (StreamChunk) -> Unit)?,
    ): ChatResult {
        val body = buildRequest(messages, tools, maxTokens, stream)
        val requestBuilder = Request.Builder()
            .url(normalizeChatUrl(SpUtil.getLlmBaseUrl()))
            .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (stream) requestBuilder.header("Accept", "text/event-stream")
        val key = SpUtil.getLlmApiKey()
        if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpError(response.code, response.body?.string().orEmpty())
            }
            if (stream && onChunk != null) {
                readStream(response, tools, onChunk)
            } else {
                val text = response.body?.string().orEmpty()
                parseChatBody(text, tools.map { it.name }.toSet(), response.code)
            }
        }
    }

    /**
     * 读 SSE 流。
     *
     * 容错覆盖（国产模型 / 中转网关的常见花样，任一异常都不允许採断整条流）：
     * - 一个事件的数据分散在多个 `data:` 行（SSE 规范，空行收尾）：缓冲拼接后再解析；
     * - content / reasoning_content 是数组或对象形态（多模态兼容格式）而非纯字符串；
     * - 事件缺少 choices，delta / message / content 直接挂在顶层；
     * - 服务端根本没走 SSE：无 `data:` 前缀的逐行 JSON 事件，或一次性普通 JSON。
     */
    private suspend fun readStream(
        response: okhttp3.Response,
        tools: List<ToolDef>,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ChatResult {
        val source = response.body?.source() ?: throw LlmError.Server(response.code, "空响应体")
        val textSb = StringBuilder()
        val reasonSb = StringBuilder()
        val calls = linkedMapOf<Int, MutableMap<String, String>>()
        val plainLines = mutableListOf<String>()
        var sawData = false
        var pendingData = ""

        suspend fun tryProcess(payload: String): Boolean {
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                ?: return false
            // 事件结构千奇百怪：解析成功但字段形态异常也不能让整条流挂掉
            runCatching { consumeStreamEvent(obj, textSb, reasonSb, calls, onChunk) }
            return true
        }

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) {
                if (line.isBlank()) {
                    // 事件边界：把缓冲里攒出的完整事件处理掉
                    if (pendingData.isNotEmpty()) {
                        tryProcess(pendingData)
                        pendingData = ""
                    }
                } else {
                    plainLines += line
                }
                continue
            }
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]" || payload.isEmpty()) continue
            sawData = true
            val candidate = if (pendingData.isEmpty()) payload else "$pendingData\n$payload"
            if (tryProcess(candidate)) pendingData = "" else pendingData = candidate
        }
        if (pendingData.isNotEmpty()) tryProcess(pendingData)

        if (!sawData) {
            // 服务端没走 SSE。两种可能：
            // ① 逐行 JSON 事件（无 data: 前缀的伪流）——逐行喂给流事件处理；
            // ② 一次性普通 JSON ——整块按非流式解析（不重发请求）。
            var lineEvents = 0
            for (pl in plainLines) {
                val trimmed = pl.trim()
                if (!trimmed.startsWith("{")) continue
                val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
                    ?: continue
                if (obj.has("choices") || obj.has("delta") || obj.has("content")) {
                    runCatching { consumeStreamEvent(obj, textSb, reasonSb, calls, onChunk) }
                    lineEvents++
                }
            }
            if (lineEvents == 0) {
                val raw = plainLines.joinToString("\n")
                if (raw.isBlank()) throw LlmError.Server(response.code, "服务端未返回流式数据")
                val parsed = parseChatBody(raw, tools.map { it.name }.toSet(), response.code)
                if (parsed.text.isNotBlank()) onChunk(StreamChunk(text = parsed.text))
                if (parsed.reasoning.isNotBlank()) onChunk(StreamChunk(reasoning = parsed.reasoning))
                return parsed
            }
        }
        val toolCalls = calls.entries.sortedBy { it.key }.map { (_, v) ->
            ToolCall(
                id = v["id"].orEmpty(),
                name = v["name"].orEmpty(),
                arguments = v["arguments"].orEmpty(),
            )
        }
        var text = textSb.toString()
        val reasoning = reasonSb.toString()
        text = stripReasoningEcho(text, reasoning)
        val inline = parseInlineToolCalls(text, tools.map { it.name }.toSet())
        text = inline.cleanedText
        return ChatResult(text = text, toolCalls = toolCalls + inline.calls, reasoning = reasoning)
    }

    /**
     * 处理单个流式事件：标准 `choices[].delta` 结构，
     * 或 delta / message / content 直接挂顶层的非标准包装。
     */
    private suspend fun consumeStreamEvent(
        obj: JsonObject,
        textSb: StringBuilder,
        reasonSb: StringBuilder,
        calls: LinkedHashMap<Int, MutableMap<String, String>>,
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        val choices = obj.get("choices") as? JsonArray
        if (choices != null) {
            for (c in choices) {
                if (!c.isJsonObject) continue
                val co = c.asJsonObject
                val delta = co.get("delta") as? JsonObject
                    ?: co.get("message") as? JsonObject
                    ?: continue
                consumeDelta(delta, textSb, reasonSb, calls, onChunk)
            }
            return
        }
        // 非标准包装：delta / message 直接挂在顶层
        val topDelta = obj.get("delta") as? JsonObject ?: obj.get("message") as? JsonObject
        if (topDelta != null) {
            consumeDelta(topDelta, textSb, reasonSb, calls, onChunk)
            return
        }
        emitDelta(
            text = readTextElement(obj.get("content")),
            reasoning = readTextElement(obj.get("reasoning_content"))
                .ifBlank { readTextElement(obj.get("reasoning")) },
            textSb = textSb,
            reasonSb = reasonSb,
            onChunk = onChunk,
        )
    }

    /** 处理一条 delta：正文 / 思考 / 工具调用增量。 */
    private suspend fun consumeDelta(
        delta: JsonObject,
        textSb: StringBuilder,
        reasonSb: StringBuilder,
        calls: LinkedHashMap<Int, MutableMap<String, String>>,
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        emitDelta(
            text = readTextElement(delta.get("content")),
            reasoning = readTextElement(delta.get("reasoning_content"))
                .ifBlank { readTextElement(delta.get("reasoning")) },
            textSb = textSb,
            reasonSb = reasonSb,
            onChunk = onChunk,
        )
        val tcArr = delta.get("tool_calls") as? JsonArray ?: return
        for (tc in tcArr) {
            if (!tc.isJsonObject) continue
            val t = tc.asJsonObject
            val idx = t.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val slot = calls.getOrPut(idx) { mutableMapOf() }
            t.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { slot["id"] = it }
            val fn = t.get("function") as? JsonObject ?: continue
            fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let { name ->
                slot["name"] = mergeStreamText(slot["name"], name)
            }
            fn.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                slot["arguments"] = slot["arguments"].orEmpty() + it
            }
        }
    }

    /** 把一对增量回传并累积（含「中转把同一段话同时塞进 content 与 reasoning」的去重）。 */
    private suspend fun emitDelta(
        text: String,
        reasoning: String,
        textSb: StringBuilder,
        reasonSb: StringBuilder,
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        val isEcho = reasoning.isNotEmpty() && text == reasoning
        val dText = if (isEcho) "" else text
        val dReason = if (isEcho) text else reasoning
        if (dText.isNotEmpty()) {
            textSb.append(dText)
            onChunk(StreamChunk(text = dText))
        }
        if (dReason.isNotEmpty()) {
            reasonSb.append(dReason)
            onChunk(StreamChunk(reasoning = dReason))
        }
    }

    /**
     * 容错读取 JSON 值的文本：字符串直取；数组拼接各元素；
     * 对象取 text / content 字段。非标准形态一律不抛异常。
     */
    private fun readTextElement(el: JsonElement?): String = when {
        el == null || el.isJsonNull -> ""
        el.isJsonPrimitive -> el.asString
        el.isJsonArray -> el.asJsonArray.joinToString("") { readTextElement(it) }
        el.isJsonObject -> {
            val obj = el.asJsonObject
            readTextElement(obj.get("text")).ifBlank { readTextElement(obj.get("content")) }
        }
        else -> ""
    }

    /**
     * 流式片段合并：部分 provider 每个 chunk 都重发完整 name，
     * 直接拼接会得到 "best50best50"，这里做去重。
     */
    private fun mergeStreamText(old: String?, incoming: String): String {
        val prev = old.orEmpty()
        if (prev.isEmpty()) return incoming
        if (prev == incoming) return prev
        if (incoming.startsWith(prev)) return incoming
        if (prev.startsWith(incoming)) return prev
        return prev + incoming
    }

    /** 从响应体提取深度思考（reasoning_content / reasoning），用于非流式兜底。 */
    internal fun extractReasoning(body: String): String {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        val arr = root.getAsJsonArray("choices") ?: return ""
        for (choice in arr) {
            val msg = choice.asJsonObject.getAsJsonObject("message") ?: continue
            listOf("reasoning_content", "reasoning").forEach { key ->
                msg.get(key)?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotBlank()) return it }
            }
        }
        return ""
    }

    /** 从响应体中提取 tool_calls（兼容非标准包装）。 */
    internal fun parseToolCalls(body: String): List<ToolCall> {
        val root = runCatching {
            JsonParser.parseString(body).asJsonObject
        }.getOrNull() ?: return emptyList()

        fun fromChoices(choicesEl: JsonElement?): List<ToolCall> {
            val arr = choicesEl?.asJsonArray ?: return emptyList()
            val out = mutableListOf<ToolCall>()
            for (choice in arr) {
                val obj = choice.asJsonObject
                val calls = obj.get("tool_calls")?.asJsonArray ?: continue
                for (c in calls) {
                    val co = c.asJsonObject
                    val fn = co.get("function")?.asJsonObject
                    out += ToolCall(
                        id = co.get("id")?.asString.orEmpty(),
                        name = fn?.get("name")?.asString.orEmpty(),
                        arguments = fn?.get("arguments")?.let { args ->
                            if (args.isJsonPrimitive) args.asString else args.toString()
                        }.orEmpty(),
                    )
                }
            }
            return out
        }

        val direct = fromChoices(root.get("choices"))
        if (direct.isNotEmpty()) return direct
        val data = root.get("data")
        if (data != null && !data.isJsonNull && data.isJsonObject) {
            return fromChoices(data.asJsonObject.get("choices"))
        }
        return emptyList()
    }

    /**
     * 文本内联工具调用的解析结果。
     *
     * @property calls 解析出的调用（id 为空 → 走文本内联协议）
     * @property cleanedText 去掉调用 JSON 之后的干净正文
     */
    internal data class InlineToolCalls(
        val calls: List<ToolCall>,
        val cleanedText: String,
    )

    /**
     * 解析「写在正文里的工具调用」。
     *
     * 很多中转 / 本地推理服务不支持原生 function calling，模型会照着系统提示词
     * 把调用写成 JSON 塞进正文，例如：
     * ```
     * {"name":"best50","arguments":{}}
     * ```
     * 这里把它抠出来执行，并把 JSON 从正文里摘掉（不然用户会看到裸 JSON）。
     *
     * 只认 [knownTools] 里的技能 id，避免把正常回答里的 JSON 误判成调用。
     */
    internal fun parseInlineToolCalls(text: String, knownTools: Set<String>): InlineToolCalls {
        val parsed = LlmParse.parseInlineToolCalls(text, knownTools)
        return InlineToolCalls(
            calls = parsed.calls.map { ToolCall(id = it.id, name = it.name, arguments = it.arguments) },
            cleanedText = parsed.cleanedText,
        )
    }

    /**
     * 把「思考内容」从正文里剔掉。
     *
     * 部分推理模型会把同一段话同时塞进 reasoning_content 和 content，
     * 不去重的话用户会在「深度思考」卡片和正文气泡里各看一遍同样的句子。
     */
    internal fun stripReasoningEcho(text: String, reasoning: String): String =
        LlmParse.stripReasoningEcho(text, reasoning)

    /**
     * 把模型给出的 arguments 字符串解析成扁平 Map（值统一转成字符串，便于本地解析）。
     *
     * 容错：
     * - 空串 / 非法 JSON → 空 Map（调用方回退到用原始用户输入兜底）
     * - arguments 本身不是对象（例如模型塞了裸字符串）→ 放到 `raw` 键
     * - 数字 / 布尔按字面量转字符串，避免 Gson 把 12.0 变成 "12.0" 之外的奇怪形式
     */
    internal fun parseArguments(argumentsJson: String): Map<String, String> =
        LlmParse.parseArguments(argumentsJson)

    /** baseUrl → chat/completions 完整地址（容忍用户填了 /chat/completions 或结尾斜杠）。 */
    internal fun normalizeChatUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) url = SpUtil.LLM_DEFAULT_BASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (!url.endsWith("/chat/completions")) url = "$url/chat/completions"
        return url
    }
}
