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
 */
object KibaLlm {

    private const val TAG = "KibaLlm"

    data class Message(val role: String, val content: String) {
        companion object {
            fun system(content: String) = Message("system", content)
            fun user(content: String) = Message("user", content)
            fun assistant(content: String) = Message("assistant", content)
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

    /** 模型请求的工具调用。 */
    data class ToolCall(
        val id: String = "",
        val name: String = "",
        val arguments: String = "",
    )

    /** 一次对话的结果：文本 + 可能存在的工具调用。 */
    data class ChatResult(
        val text: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
    )

    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Float,
        @SerializedName("max_tokens") val maxTokens: Int,
        val tools: List<Map<String, Any>>? = null,
        @SerializedName("tool_choice") val toolChoice: String? = null,
    )

    private data class ToolCallDto(
        val id: String? = null,
        @SerializedName("function") val function: FunctionDto? = null,
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
        .readTimeout(90, TimeUnit.SECONDS)
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
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(LlmError.NotConfigured())
        }
        runCatching {
            val body = ChatRequest(
                model = SpUtil.getLlmModel().ifBlank { SpUtil.LLM_DEFAULT_MODEL },
                messages = messages,
                temperature = SpUtil.getLlmTemperature(),
                maxTokens = SpUtil.getLlmMaxTokens(),
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
            )
            val requestBuilder = Request.Builder()
                .url(normalizeChatUrl(SpUtil.getLlmBaseUrl()))
                .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            val key = SpUtil.getLlmApiKey()
            if (key.isNotBlank()) requestBuilder.header("Authorization", "Bearer $key")
            client.newCall(requestBuilder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = runCatching {
                        gson.fromJson(text, ChatResponse::class.java)?.error?.message
                    }.getOrNull().orEmpty()
                    throw when (response.code) {
                        401, 403 -> LlmError.Unauthorized(apiMsg.ifBlank { "HTTP ${response.code}" })
                        404 -> LlmError.NotFound(apiMsg.ifBlank { "HTTP ${response.code}" })
                        429 -> LlmError.RateLimited(apiMsg.ifBlank { "HTTP ${response.code}" })
                        else -> LlmError.Server(response.code, apiMsg.ifBlank { text.take(120) })
                    }
                }
                val calls = parseToolCalls(text)
                val content = extractContent(text)
                if (calls.isEmpty() && content.isBlank()) {
                    // 报错带上响应原文片段，便于定位是哪种非标准结构
                    val snippet = text.replace(Regex("\\s+"), " ").take(220)
                    throw LlmError.Server(
                        response.code,
                        "响应缺少 content 字段（原文：$snippet）",
                    )
                }
                ChatResult(text = content, toolCalls = calls)
            }
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

    /**
     * 从响应体中提取助手文本，兼容多种非标准结构。
     *
     * 覆盖：
     * - OpenAI 标准：choices[0].message.content（字符串）
     * - 数组型 content：[{type:text,text:...}]
     * - 推理模型：choices[0].message.reasoning_content（content 为空时兜底）
     * - 旧版 completions：choices[0].text
     * - Responses 风格：顶层 output_text
     * - 部分中转包装：顶层 content / response / data.content / data.choices
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
                    val r = readText(m.get("reasoning_content"))
                    if (r.isNotBlank()) return r
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
     * 把模型给出的 arguments 字符串解析成扁平 Map（值统一转成字符串，便于本地解析）。
     *
     * 容错：
     * - 空串 / 非法 JSON → 空 Map（调用方回退到用原始用户输入兜底）
     * - arguments 本身不是对象（例如模型塞了裸字符串）→ 放到 `raw` 键
     * - 数字 / 布尔按字面量转字符串，避免 Gson 把 12.0 变成 "12.0" 之外的奇怪形式
     */
    internal fun parseArguments(argumentsJson: String): Map<String, String> {
        val raw = argumentsJson.trim()
        if (raw.isEmpty() || raw == "{}") return emptyMap()
        val el = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return mapOf("raw" to raw)
        if (!el.isJsonObject) return mapOf("raw" to el.asString)
        val out = linkedMapOf<String, String>()
        el.asJsonObject.entrySet().forEach { (k, v) ->
            if (v == null || v.isJsonNull) return@forEach
            out[k] = if (v.isJsonPrimitive) v.asString else v.toString()
        }
        return out
    }

    /** baseUrl → chat/completions 完整地址（容忍用户填了 /chat/completions 或结尾斜杠）。 */
    internal fun normalizeChatUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) url = SpUtil.LLM_DEFAULT_BASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (!url.endsWith("/chat/completions")) url = "$url/chat/completions"
        return url
    }
}
