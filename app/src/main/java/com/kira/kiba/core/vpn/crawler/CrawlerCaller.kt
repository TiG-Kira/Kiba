package com.kira.kiba.core.vpn.crawler

import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.vpn.VpnSettings
import com.kira.kiba.core.vpn.core.LocalVpnService
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

object CrawlerCaller {
    private var listener: WechatCrawlerListener? = null

    /** 微信授权的游戏路径段（官方 proxy 实现：maimai-dx / chunithm）。 */
    const val GAME_MAIMAI = "maimai-dx"
    const val GAME_CHUNITHM = "chunithm"

    /** 当前游戏（曲目页开关）对应的授权路径段。 */
    private fun gameSlug(): String =
        if (SpUtil.getGameType() == GameType.CHUNITHM) GAME_CHUNITHM else GAME_MAIMAI

    /**
     * 取微信授权链接（suspend 版，供 UI 协程调用）。
     *
     * 必须切 IO 线程：内部走 OkHttp 同步请求（含 DNS 解析），若在主线程协程里直接调
     * 会抛 NetworkOnMainThreadException——该异常没有 message，UI 曾显示成「抓包失败：null」。
     *
     * @param game 游戏路径段（[GAME_MAIMAI] / [GAME_CHUNITHM]），默认跟随当前游戏开关。
     */
    suspend fun getWechatAuthUrl(game: String = gameSlug()): String? = withContext(Dispatchers.IO) {
        getWechatAuthUrlBlocking(game)
    }

    /**
     * 取微信授权链接（阻塞版，供非主线程调用方使用：NanoHTTPD 的 serve 在工作线程）。
     * **禁止在主线程调用**。
     *
     * [HttpServer] 是 Java 侧调用方（无参调用），故用 @JvmOverloads 生成无参重载，
     * 无参时自动跟随当前游戏开关，Java 侧无需改造即可支持中二。
     */
    @JvmStatic
    @JvmOverloads
    fun getWechatAuthUrlBlocking(game: String = gameSlug()): String? {
        return try {
            val crawler = WechatCrawler()
            crawler.getWechatAuthUrl(game)
        } catch (error: IOException) {
            writeLog("获取微信登录url时出现错误: ${error.javaClass.simpleName}: ${error.message}")
            onError(error)
            null
        }
    }

    @JvmStatic
    fun writeLog(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            listener?.onMessageReceived(text)
        }
    }

    @JvmStatic
    fun startAuth() {
        CoroutineScope(Dispatchers.Main).launch {
            listener?.onStartAuth()
        }
    }

    @JvmStatic
    fun finishUpdate() {
        CoroutineScope(Dispatchers.Main).launch {
            listener?.onFinishUpdate()
        }
    }

    @JvmStatic
    fun onError(e: Exception) {
        CoroutineScope(Dispatchers.Main).launch {
            listener?.onError(e)
        }
    }

    /**
     * 抓包并上传成绩。
     *
     * **上传目标跟随该游戏在设置页选的数据源**（两游戏各自独立）：
     * - 水鱼：舞萌把账密包裹进 HTML 头部 → `pageparser/page`；中二走账号密码 jwt 通道
     *   （水鱼 OAuth scope 不支持中二），取数是「POST send → GET」两步串行，上传走
     *   `chunithmprober/player/update_records_html`；
     * - 落雪：抓到的 NET 页面**原样** `POST /api/v0/user/{game}/player/html`（X-User-Token），
     *   既不用账密也不用 jwt —— 落雪由服务端解析页面，口径与水鱼 pageparser 同源。
     *
     * [com.kira.kiba.core.vpn.tunnel.HttpCapturerTunnel] 是 Java 侧调用方（单参调用），
     * 故用 @JvmOverloads 生成单参重载，默认跟随当前游戏开关。
     */
    @JvmOverloads
    fun fetchData(authUrl: String, gameType: GameType = SpUtil.getGameType()) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Thread.sleep(3000)
                LocalVpnService.IsRunning = false
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                onError(e)
            }
            val crawler = WechatCrawler()
            val source = SpUtil.getScoreSource(gameType)
            val useLxns = source == ScoreSource.LXNS
            if (useLxns && SpUtil.getLxnsApiKey().isBlank()) {
                writeLog("当前数据源是落雪，但还没有绑定落雪 API Key")
                onError(IllegalStateException("请先到「设置 → 查分器数据源」绑定落雪 API Key"))
                return@launch
            }
            writeLog("上传目标：${source.shortLabel}查分器")
            WechatCrawler.setLxnsMode(useLxns)
            try {
                if (gameType == GameType.CHUNITHM) {
                    // 落雪不需要水鱼 jwt；水鱼中二必须走账号密码通道（OAuth 不支持中二）
                    val jwt = if (useLxns) "" else SpUtil.getDivingFishJwt()
                    if (!useLxns && jwt.isBlank()) {
                        writeLog("中二成绩上传需要水鱼「账号密码」登录：OAuth 授权不支持中二节奏")
                        onError(IllegalStateException("请改用账号密码登录查分器后再更新中二成绩"))
                        return@launch
                    }
                    if (!useLxns) writeLog("使用账号密码通道上传中二成绩…")
                    crawler.fetchAndUploadChuniData(jwt, VpnSettings.getUpdateChuniDifficulty(), authUrl)
                } else {
                    crawler.fetchAndUploadData(
                        SpUtil.getUserName(),
                        SpUtil.getPassword(),
                        VpnSettings.getUpdateDifficulty(),
                        authUrl
                    )
                }
            } catch (e: IOException) {
                onError(e)
            } finally {
                // 会话结束必须复位，否则下一次切回水鱼会继续往落雪推
                WechatCrawler.setLxnsMode(false)
            }
        }
    }

    fun setOnWechatCrawlerListener(listener: WechatCrawlerListener) {
        this.listener = listener
    }

    fun removeOnWechatCrawlerListener() {
        this.listener = null
    }
}
