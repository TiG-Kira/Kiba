package com.kira.kiba.core.agent

import android.util.Log
import com.google.gson.Gson
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

    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Float,
        @SerializedName("max_tokens") val maxTokens: Int,
    )

    private data class ChatResponse(
        val choices: List<Choice>? = null,
        val error: ApiError? = null,
    )

    private data class Choice(val message: ResponseMessage? = null)

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
    suspend fun chat(messages: List<Message>): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(LlmError.NotConfigured())
        }
        runCatching {
            val body = ChatRequest(
                model = SpUtil.getLlmModel().ifBlank { SpUtil.LLM_DEFAULT_MODEL },
                messages = messages,
                temperature = SpUtil.getLlmTemperature(),
                maxTokens = SpUtil.getLlmMaxTokens(),
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
                val parsed = gson.fromJson(text, ChatResponse::class.java)
                val content = parsed?.choices?.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) {
                    throw LlmError.Server(response.code, "响应缺少 content 字段")
                }
                content
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
            onSuccess = { "连接成功，模型回复：${it.take(20)}" },
            onFailure = { e -> "连接失败：${e.message}" },
        )
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
