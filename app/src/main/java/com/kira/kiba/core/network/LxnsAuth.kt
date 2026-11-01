package com.kira.kiba.core.network

import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 落雪「以谁的身份请求」的唯一判定处。
 *
 * 落雪有两套用户级凭据，指向**同一批** `/api/v0/user/...` 接口，但请求头完全不同：
 *
 * | 凭据 | 请求头 | 生命周期 |
 * |---|---|---|
 * | OAuth 授权 | `Authorization: Bearer <access_token>` | 15 分钟，refresh_token 30 天自动续 |
 * | 个人 API 密钥 | `X-User-Token: <key>` | 长期，用户在站内手动撤销 |
 *
 * **优先 OAuth**：它权限可撤销、令牌短期、泄露面小；个人 Key 保留为兜底
 * （没打包 OAuth 凭据的安装包、或用户不想授权时仍能用）。
 * 两者都混着发会 401，所以这里必须只挑一个。
 *
 * 分发点只应该调本类，不要在调用点各写一遍 `if` —— 以前 [LxnsApi] 与 [LxnsUploader]
 * 各写一份请求头，改鉴权方式时漏改一处就会出现「查询正常、上传 401」这种鬼故事。
 */
object LxnsAuth {

    enum class Mode {
        /** 未登录。 */
        NONE,

        /** OAuth 授权（`Authorization: Bearer`）。 */
        OAUTH,

        /** 个人 API 密钥（`X-User-Token`）。 */
        KEY,
    }

    fun mode(): Mode = when {
        SpUtil.hasLxnsOAuth() -> Mode.OAUTH
        SpUtil.getLxnsApiKey().isNotBlank() -> Mode.KEY
        else -> Mode.NONE
    }

    fun hasCredential(): Boolean = mode() != Mode.NONE

    /** 是否走 OAuth 通道（UI 文案、日志用）。 */
    fun isOAuth(): Boolean = mode() == Mode.OAUTH

    /**
     * 取请求头（挂起版，[LxnsApi] 用）。
     *
     * OAuth 通道会在令牌临近过期时**同步刷新一次**（内部切 IO + 单飞），
     * 所以这个函数本身不要再套在别的主线程代码里当纯内存操作看待。
     */
    suspend fun header(): Pair<String, String> = when (mode()) {
        Mode.OAUTH -> "Authorization" to "Bearer ${LxnsOAuth.accessToken()}"
        Mode.KEY -> "X-User-Token" to SpUtil.getLxnsApiKey()
        Mode.NONE -> throw LxnsApi.LxnsException(401, "尚未登录落雪账号")
    }

    /**
     * 取请求头（阻塞版，[LxnsUploader] / Java 侧爬虫调用）。**禁止在主线程调用**。
     */
    fun headerBlocking(): Pair<String, String> = when (mode()) {
        Mode.OAUTH -> "Authorization" to "Bearer ${LxnsOAuth.accessTokenBlocking()}"
        Mode.KEY -> "X-User-Token" to SpUtil.getLxnsApiKey()
        Mode.NONE -> throw LxnsApi.LxnsException(401, "尚未登录落雪账号")
    }

    /** 人话描述当前凭据，写进机台更新日志。 */
    fun modeLabel(): String = when (mode()) {
        Mode.OAUTH -> "落雪账号授权"
        Mode.KEY -> "个人 API 密钥"
        Mode.NONE -> "未登录"
    }

    /** 清空全部落雪凭据（解绑）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        SpUtil.clearLxnsOAuth()
        SpUtil.saveLxnsApiKey("")
        SpUtil.clearLxnsFriendCodes()
    }
}
