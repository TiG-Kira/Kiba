package com.kira.kiba.core.vpn.crawler

import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.vpn.VpnSettings
import com.kira.kiba.core.vpn.core.LocalVpnService
import com.kira.kiba.core.vpn.tunnel.HttpCapturerTunnel
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
     * **调用时机**：由 [com.kira.kiba.core.vpn.tunnel.HttpCapturerTunnel] 在 VPN 抓到
     * 微信 OAuth **回调**（`callback/{game}?code=...`）时调用 —— 那个 URL 带着 code，
     * 拿它去换登录态才能抓到有数据的页面。绝不能在用户还没在微信里打开链接时抢跑调用
     * （曾因此在授权链接阶段就关掉 VPN，抓到的页面全是登录页/空串，落雪侧直接报
     * 「页面内容为空」）。
     *
     * **上传目标跟随该游戏在设置页选的数据源**（两游戏各自独立）：
     * - 水鱼：舞萌把账密包裹进 HTML 头部 → `pageparser/page`；中二走账号密码 jwt 通道
     *   （水鱼 OAuth scope 不支持中二），取数是「POST send → GET」两步串行，上传走
     *   `chunithmprober/player/update_records_html`；
     * - 落雪：**官方 HTML 直传优先**（开发者 API，`POST /api/v0/{game}/player/{fc}/html`，
     *   解析在落雪侧、中二 WORLD'S END 也能传），直传不可用时回落「本地解析 NET 页面
     *   （[com.kira.kiba.core.data.NetHtmlParser]）→ `POST /api/v0/user/{game}/player/scores`」。
     *   两条通道都不需要账密 / jwt。详见 [com.kira.kiba.core.network.LxnsUploader]。
     */
    @JvmOverloads
    fun fetchData(authUrl: String, gameType: GameType = SpUtil.getGameType()) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 回调请求本身还要经 VPN 回给微信（微信那边要看到「登录信息已获取」页面），
                // 留够时间再拆 VPN；拆晚一点不影响我们自己的后续直连请求。
                Thread.sleep(3000)
                LocalVpnService.requestStop()
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                onError(e)
            }
            writeLog("已结束 VPN 抓包，开始用机台会话登录…")
            val crawler = WechatCrawler()
            val source = SpUtil.getScoreSource(gameType)
            val useLxns = source == ScoreSource.LXNS
            if (useLxns && !com.kira.kiba.core.network.LxnsAuth.hasCredential()) {
                writeLog("当前数据源是落雪，但还没有登录落雪账号")
                onError(IllegalStateException("请先到「设置 → 查分器数据源」登录落雪账号"))
                return@launch
            }
            writeLog("上传目标：${source.shortLabel}查分器")
            WechatCrawler.setLxnsMode(useLxns)
            if (useLxns) {
                // 落雪通道要靠曲名换曲目 ID，先把曲名索引刷掉：
                // 用户可能是刚更新完曲库就来做机台上分，缓存里的旧曲名会导致新曲匹配不上
                com.kira.kiba.core.data.LxnsScoreMapper.invalidate()
            }
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
                HttpCapturerTunnel.resetCaptureState()
                // 抓完就收掉前台服务和通知，别让「机台抓包进行中」一直挂着
                LocalVpnService.shutdown()
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
