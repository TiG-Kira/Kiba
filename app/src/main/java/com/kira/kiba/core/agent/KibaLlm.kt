package com.kira.kiba.core.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
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
        .readTimeout(120, TimeUnit.SECONDS)
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
            val rejectedTools = e.code == 400 && tools.isNotEmpty() && e.message.orEmpty().contains("tool", true)
            if (!rejectedTools) throw e
            Log.w(TAG, "provider 不接受 tools 参数，去掉后重试（改走文本内联调用协议）")
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

    /** 读 SSE 流。 */
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
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) {
                if (line.isNotBlank()) plainLines += line
                continue
            }
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            sawData = true
            val obj = runCatching {
                JsonParser.parseString(payload).asJsonObject
            }.getOrNull() ?: continue
            val choices = obj.getAsJsonArray("choices") ?: continue
            for (c in choices) {
                val co = c.asJsonObject
                val delta = co.getAsJsonObject("delta") ?: co.getAsJsonObject("message") ?: continue
                val rawContent = delta.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val rawReason = (
                    delta.get("reasoning_content")?.takeIf { !it.isJsonNull }
                        ?: delta.get("reasoning")
                    )?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                // 有些中转把同一段话同时塞进 content 和 reasoning_content：
                // 这种重复只算思考，正文不再重复回传。
                val isEcho = rawReason.isNotEmpty() && rawContent == rawReason
                val dText = if (isEcho) "" else rawContent
                val dReason = if (isEcho) rawContent else rawReason
                if (dText.isNotEmpty()) {
                    textSb.append(dText)
                    onChunk(StreamChunk(text = dText))
                }
                if (dReason.isNotEmpty()) {
                    reasonSb.append(dReason)
                    onChunk(StreamChunk(reasoning = dReason))
                }
                delta.getAsJsonArray("tool_calls")?.forEach { tc ->
                    val t = tc.asJsonObject
                    val idx = t.get("index")?.asInt ?: 0
                    val slot = calls.getOrPut(idx) { mutableMapOf() }
                    t.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { slot["id"] = it }
                    t.getAsJsonObject("function")?.let { fn ->
                        fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { name ->
                            slot["name"] = mergeStreamText(slot["name"], name)
                        }
                        fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let {
                            slot["arguments"] = slot["arguments"].orEmpty() + it
                        }
                    }
                }
            }
        }
        if (!sawData) {
            // 服务端没走 SSE：把收到的普通 JSON 当成一次性响应解析（不重发请求）
            val raw = plainLines.joinToString("\n")
            if (raw.isBlank()) throw LlmError.Server(response.code, "服务端未返回流式数据")
            val parsed = parseChatBody(raw, tools.map { it.name }.toSet(), response.code)
            if (parsed.text.isNotBlank()) onChunk(StreamChunk(text = parsed.text))
            if (parsed.reasoning.isNotBlank()) onChunk(StreamChunk(reasoning = parsed.reasoning))
            return parsed
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
