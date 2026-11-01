package com.kira.kiba.core.account

import android.util.Log
import com.google.gson.JsonObject
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.network.LxnsApi
import com.kira.kiba.core.network.LxnsOAuth
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 落雪（LXNS）账号层 —— 支持**两条登录通道**。
 *
 * ## 通道一：OAuth 授权（1.8.7 新增，推荐）
 * 点「用落雪账号授权」→ 浏览器打开授权页 → 用户同意后页面**直接显示授权码**
 * （应用登记的是「无回调地址」，不重定向）→ 复制粘回 App。
 * 拿到的是 15 分钟一换的 access_token + 30 天滚动 refresh_token，权限可被用户随时撤销。
 * 好处：不用去「账号详情」翻个人 API 密钥，写入权限（`write_player`）一次授权就够，
 * 而且**泄露面比长期密钥小得多**。
 *
 * ## 通道二：个人 API 密钥（1.8.3 起，保留为兜底）
 * 用户自己在落雪「账号详情」生成，等价于账号读写凭据。
 * 留着的理由：没打包 OAuth 凭据的安装包、或不想授权的用户仍然能用；
 * OAuth 令牌万一失效，只要 Key 还在就能继续同步。
 *
 * 共存时**优先 OAuth**（见 [LxnsAuth]）。
 *
 * ## 数据挂在「玩家」上
 * 新注册的账号在**落雪网页/同步器同步过一次之前**只有账号没有玩家，
 * 所有成绩接口都返回 `404 player not found` —— 这不是凭据错，也不是 App 错。
 * 所以这里用 [State.playerFound] 单独标出来，UI 必须区别于「登录失败」来提示。
 *
 * ## 好友码
 * 开发者 API（HTML 直传）只认好友码。探活时顺手从 `GET /user/{game}/player`
 * 把两个游戏的好友码存进 SP，用户全程不用手输。
 */
object LxnsAccount {

    private const val TAG = "LxnsAccount"

    /** 注册页（新用户从这里开始）。 */
    const val REGISTER_URL = "https://maimai.lxns.net/register"

    /** 登录页。 */
    const val LOGIN_URL = "https://maimai.lxns.net/login"

    /** 账号详情页 —— 「个人 API 密钥」在这一页（需登录后访问）。 */
    const val API_KEY_URL = "https://maimai.lxns.net/user/profile"

    /**
     * 官方「同步游戏数据」页（代理同步）。
     *
     * 落雪官方的同步方式是**让落雪服务器代抓**：把系统 WLAN 代理指向
     * `proxy.maimai.lxns.net:8080`，网页会实时检测代理是否生效。它和本 App 的
     * 本地 VPN 抓包**只能二选一**（都抢网络接管），所以只能作为备选路径引导用户。
     */
    const val SYNC_URL = "https://maimai.lxns.net/user/sync"

    data class State(
        val bound: Boolean = false,
        val name: String = "",
        val loading: Boolean = false,
        val error: String? = null,
        /**
         * 落雪账号里是否已经绑定了玩家（好友码）。
         *
         * false 时所有成绩接口都是 `404 player not found`，UI 要明确写成
         * 「账号有效，但还没在落雪同步过游戏数据」，否则用户会以为登录失败。
         */
        val playerFound: Boolean = false,
        /** 当前凭据通道是不是 OAuth（决定 UI 显示「授权登录」还是「API 密钥」）。 */
        val oauth: Boolean = false,
    )

    private val _state = MutableStateFlow(
        State(
            bound = SpUtil.isLxnsLoggedIn(),
            name = SpUtil.getLxnsName(),
            oauth = SpUtil.hasLxnsOAuth(),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** 是否已登录（OAuth 或 Key 任一）。同步读 SP，供数据源分发时无协程判断。 */
    fun isBound(): Boolean = SpUtil.isLxnsLoggedIn()

    /** OAuth 是否可用（打包了应用凭据才显示授权入口）。 */
    fun isOAuthAvailable(): Boolean = LxnsOAuth.isConfigured()

    /** 取授权链接，直接拿去 [com.kira.kiba.core.utils.BrowserJump] 打开。 */
    fun authorizeUrl(): Result<String> = runCatching { LxnsOAuth.authorizeUrl() }

    /**
     * **OAuth 登录**：授权码 → 令牌 → 探活。
     *
     * 顺序很讲究：
     * 1. 先换令牌（失败说明码过期/被用过了，本地什么都不改）；
     * 2. 再调 UserInfo 证明令牌真的能用（`openid` 权限决定的 `sub` 是稳定身份）；
     * 3. 最后探活玩家（这一步允许 404，只标记 `playerFound=false`）。
     */
    suspend fun loginWithOAuth(rawCode: String): Result<Unit> = runCatching {
        _state.value = _state.value.copy(loading = true, error = null)
        LxnsOAuth.exchangeCode(rawCode)
        val sub = runCatching { LxnsOAuth.userInfo().stringOrEmpty("sub") }.getOrDefault("")
        val probe = probe()
        SpUtil.saveLxnsName(probe.name)
        Log.i(TAG, "lxns oauth bound, sub=$sub playerFound=${probe.playerFound}")
        _state.value = State(
            bound = true,
            name = probe.name,
            playerFound = probe.playerFound,
            oauth = true,
        )
    }.onFailure { e ->
        Log.w(TAG, "lxns oauth login failed", e)
        // 令牌没换成功就不要留下半截状态；但如果之前已经登录过，别把老凭据搞坏
        _state.value = State(
            bound = SpUtil.isLxnsLoggedIn(),
            name = SpUtil.getLxnsName(),
            error = e.message ?: "落雪授权登录失败",
            oauth = SpUtil.hasLxnsOAuth(),
        )
    }

    /**
     * **个人 API 密钥登录**：先落盘再验证（[LxnsApi] 从 SP 取凭据），验证失败回滚。
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
        // Key 与 OAuth 并存时以 OAuth 为准，会导致刚填的 Key 根本不生效 —— 明确清掉 OAuth，
        // 让用户「点哪个通道就走哪个通道」，避免出现「明明换了 Key 还是旧账号」的困惑。
        SpUtil.clearLxnsOAuth()
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

    /** 解绑：清凭据 / 昵称 / 好友码（不动已同步进 Room 的成绩，用户随时可切回水鱼源）。 */
    fun logout() {
        SpUtil.clearLxnsOAuth()
        SpUtil.saveLxnsApiKey("")
        SpUtil.saveLxnsName("")
        SpUtil.clearLxnsFriendCodes()
        _state.value = State()
    }

    /** 启动恢复：已登录时刷新一次昵称 / 好友码；失败只记日志，不影响已同步数据展示。 */
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
                    oauth = SpUtil.hasLxnsOAuth(),
                )
            }
            .onFailure { e ->
                Log.w(TAG, "lxns restore failed", e)
                _state.value = State(
                    bound = true,
                    name = SpUtil.getLxnsName(),
                    error = e.message,
                    oauth = SpUtil.hasLxnsOAuth(),
                )
            }
    }

    private data class Probe(val name: String, val playerFound: Boolean)

    /**
     * 探昵称 / 好友码 / 玩家是否已绑定：两个游戏都探一遍（拿两侧好友码），
     * 只有 401/403 才判凭据无效。两个游戏都 404（`player not found`）时
     * 返回缓存名（或占位），并标记 playerFound=false。
     */
    private suspend fun probe(): Probe {
        var authError: LxnsApi.LxnsException? = null
        var name = ""
        var found = false
        for ((gameType, path) in PROBE_PATHS) {
            try {
                val obj = LxnsApi.getObject(path)
                val fc = obj.longOrZero("friend_code")
                if (fc > 0) SpUtil.saveLxnsFriendCode(gameType, fc)
                val n = obj.stringOrEmpty("name")
                if (n.isNotBlank() && name.isBlank()) name = n
                found = true
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
            name = name.ifBlank { SpUtil.getLxnsName() }.ifBlank { "落雪玩家" },
            playerFound = found,
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
        GameType.MAIMAI to "/user/maimai/player",
        GameType.CHUNITHM to "/user/chunithm/player",
    )

    private fun JsonObject.stringOrEmpty(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }

    private fun JsonObject.longOrZero(name: String): Long {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0L
        return runCatching { el.asLong }.getOrDefault(0L)
    }
}
