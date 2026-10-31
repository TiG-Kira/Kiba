package com.kira.kiba.core.account

import android.util.Log
import com.google.gson.JsonObject
import com.kira.kiba.core.network.LxnsApi
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 落雪（LXNS）账号层 —— **个人 API Key 绑定**。
 *
 * ## 为什么不是账号密码
 * 落雪没有公开的账号密码换 token 接口（网页登录是站内私有接口，随时可能改）。
 * 官方给第三方的合法凭据只有两种：
 * 1. **个人 API Key**（本类采用）：用户在落雪站内生成，`Authorization: <key>` 直传；
 * 2. OAuth2 + PKCE：需要自建 client_id 并通过落雪人工审核，App 无法凭空拥有。
 *
 * ## Key 怎么拿（UI 里的引导文案与这里保持一致，改一处要改两处）
 * 1. 打开 https://maimai.lxns.net/register 注册落雪账号（已有账号直接跳过）；
 * 2. 登录后点右上角头像进「账号详情」（`/user/profile`）；
 * 3. 在「个人 API 密钥」一栏点「重置 API 密钥」旁的复制按钮拿到 Key；
 * 4. 回到本 App 粘贴保存。
 *
 * ## 请求头（1.8.4 纠正）
 * 个人 API 必须用 **`X-User-Token`**；`Authorization` 是开发者 API 用的，
 * 拿个人密钥去 `Authorization` 只会得到 401（1.8.3 的「密钥无法识别」即此因）。
 *
 * Key 等价于「可读写该账号成绩」的凭据，只存本机私有 SP，用户可在落雪站内随时撤销；
 * 撤销后所有请求统一 401，由 [state] 的 [State.error] 提示重新绑定。
 */
object LxnsAccount {

    private const val TAG = "LxnsAccount"

    /** 注册页（新用户从这里开始）。 */
    const val REGISTER_URL = "https://maimai.lxns.net/register"

    /** 登录页。 */
    const val LOGIN_URL = "https://maimai.lxns.net/login"

    /** 账号详情页 —— 「个人 API 密钥」在这一页（需登录后访问）。 */
    const val API_KEY_URL = "https://maimai.lxns.net/user/profile"

    data class State(
        val bound: Boolean = false,
        val name: String = "",
        val loading: Boolean = false,
        val error: String? = null,
        /**
         * 落雪账号里是否已经绑定了玩家（好友码）。
         *
         * 落雪的数据是按「玩家」存的：新注册的账号在**落雪网页/同步器同步过一次之前**
         * 只有账号没有玩家，所有成绩接口都返回 `404 player not found` ——
         * 这不是 Key 错，也不是 App 错，必须先在落雪那边同步一次把玩家绑上。
         * false 时 UI 要明确写出来，否则用户会以为 Key 失效。
         */
        val playerFound: Boolean = false,
    )

    private val _state = MutableStateFlow(
        State(bound = SpUtil.isLxnsLoggedIn(), name = SpUtil.getLxnsName()),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** 是否已绑定 Key。同步读 SP，供数据源分发时无协程判断。 */
    fun isBound(): Boolean = SpUtil.isLxnsLoggedIn()

    /**
     * 绑定 Key：先落盘再验证（[LxnsApi] 从 SP 取 Key），验证失败回滚到绑定前的值。
     *
     * 验证失败分类：
     * - 401/403 → Key 错误或被撤销，直接失败并提示；
     * - 404 → Key 有效但该落雪账号还没上传任何成绩（两个游戏都试过）→ **允许绑定**，
     *   由后续同步给出「落雪暂无成绩」的地道提示，而不是把有效 Key 判成错的。
     */
    suspend fun login(rawKey: String): Result<Unit> = runCatching {
        val key = normalize(rawKey)
        if (key.isBlank()) throw IllegalStateException("请粘贴落雪 API Key")
        if (key.length < 8) throw IllegalStateException("这串 Key 看起来太短了，请确认复制完整")

        _state.value = _state.value.copy(loading = true, error = null)
        val previous = SpUtil.getLxnsApiKey()
        SpUtil.saveLxnsApiKey(key)
        try {
            val probe = probe()
            SpUtil.saveLxnsName(probe.name)
            _state.value = State(bound = true, name = probe.name, playerFound = probe.playerFound)
            Log.i(TAG, "lxns api key bound, playerFound=${probe.playerFound}")
            Unit
        } catch (e: Exception) {
            // 关键：验证没过就别把错的 Key 留在本地，否则每次同步都 401
            SpUtil.saveLxnsApiKey(previous)
            _state.value = State(
                bound = previous.isNotBlank(),
                name = SpUtil.getLxnsName(),
                loading = false,
                error = e.message ?: "落雪 API Key 验证失败",
            )
            throw e
        }
    }.onFailure {
        if (_state.value.loading) {
            _state.value = _state.value.copy(loading = false)
        }
    }

    /** 解绑：清 Key 与昵称（不动已同步进 Room 的成绩，用户随时可切回水鱼源）。 */
    fun logout() {
        SpUtil.saveLxnsApiKey("")
        SpUtil.saveLxnsName("")
        _state.value = State()
    }

    /** 启动恢复：已绑定时刷新一次昵称；失败只记日志，不影响已同步数据展示。 */
    suspend fun restore() {
        if (!SpUtil.isLxnsLoggedIn()) {
            _state.value = State()
            return
        }
        _state.value = _state.value.copy(bound = true, loading = true, error = null)
        runCatching { probe() }
            .onSuccess { probe ->
                if (probe.name.isNotBlank()) SpUtil.saveLxnsName(probe.name)
                _state.value = State(
                    bound = true,
                    name = probe.name.ifBlank { SpUtil.getLxnsName() },
                    playerFound = probe.playerFound,
                )
            }
            .onFailure { e ->
                Log.w(TAG, "lxns restore failed", e)
                _state.value = State(
                    bound = true,
                    name = SpUtil.getLxnsName(),
                    error = e.message,
                )
            }
    }

    private data class Probe(val name: String, val playerFound: Boolean)

    /**
     * 探昵称 + 玩家是否已绑定：先舞萌后中二，只有 401/403 才判 Key 无效。
     * 两个游戏都 404（`player not found`）时返回缓存名（或占位），并标记 playerFound=false。
     */
    private suspend fun probe(): Probe {
        var authError: LxnsApi.LxnsException? = null
        for (path in PROBE_PATHS) {
            try {
                val obj = LxnsApi.getObject(path)
                val name = obj.stringOrEmpty("name").takeIf { it.isNotBlank() }
                    ?: SpUtil.getLxnsName().ifBlank { "落雪玩家" }
                return Probe(name, playerFound = true)
            } catch (e: LxnsApi.LxnsException) {
                if (e.isAuthError) {
                    authError = e
                    break
                }
                // 404（player not found）等继续试下一个游戏
            }
        }
        authError?.let { throw it }
        return Probe(
            name = SpUtil.getLxnsName().ifBlank { "落雪玩家" },
            playerFound = false,
        )
    }

    /** 去掉用户可能一起粘进来的前缀 / 换行 / 引号。 */
    private fun normalize(raw: String): String = raw
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removePrefix("Bearer ")
        .removePrefix("bearer ")
        .trim()

    private val PROBE_PATHS = listOf(
        "/user/maimai/player",
        "/user/chunithm/player",
    )

    private fun JsonObject.stringOrEmpty(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }
}
