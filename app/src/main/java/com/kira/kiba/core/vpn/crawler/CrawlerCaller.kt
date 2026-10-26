package com.kira.kiba.core.vpn.crawler

import com.kira.kiba.core.vpn.core.LocalVpnService
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.core.vpn.VpnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

object CrawlerCaller {
    private var listener: WechatCrawlerListener? = null

    /**
     * 取微信授权链接（suspend 版，供 UI 协程调用）。
     *
     * 必须切 IO 线程：内部走 OkHttp 同步请求（含 DNS 解析），若在主线程协程里直接调
     * 会抛 NetworkOnMainThreadException——该异常没有 message，UI 曾显示成「抓包失败：null」。
     */
    suspend fun getWechatAuthUrl(): String? = withContext(Dispatchers.IO) {
        getWechatAuthUrlBlocking()
    }

    /**
     * 取微信授权链接（阻塞版，供非主线程调用方使用：NanoHTTPD 的 serve 在工作线程）。
     * **禁止在主线程调用**。
     */
    @JvmStatic
    fun getWechatAuthUrlBlocking(): String? {
        return try {
            val crawler = WechatCrawler()
            crawler.getWechatAuthUrl()
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

    fun fetchData(authUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Thread.sleep(3000)
                LocalVpnService.IsRunning = false
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                onError(e)
            }
            try {
                val crawler = WechatCrawler()
                crawler.fetchAndUploadData(
                    SpUtil.getUserName(),
                    SpUtil.getPassword(),
                    getDifficulties(),
                    authUrl
                )
            } catch (e: IOException) {
                onError(e)
            }
        }
    }

    fun setOnWechatCrawlerListener(listener: WechatCrawlerListener) {
        this.listener = listener
    }

    fun removeOnWechatCrawlerListener() {
        this.listener = null
    }

    private fun getDifficulties(): Set<Int> {
        return VpnSettings.getUpdateDifficulty()
    }
}