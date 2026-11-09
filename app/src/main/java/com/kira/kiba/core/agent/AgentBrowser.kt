// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Agent 的「自主浏览器」：一个不出现在界面上的后台 [WebView]，模型可以打开网页、
 * 读取渲染后的 DOM、执行任意 JS、点击 / 填表 / 滚动。
 *
 * ## 为什么不是继续用 OkHttp
 * [KibaAgent.webSearch] 原来的 `fetchPageText()` 只能拿到服务器返回的原始 HTML，
 * 遇到 React / Vue 这类 CSR 页面（落雪谱面预览就是）正文是空的。现代网页几乎都是
 * 先吐骨架再由 JS 填充，纯 HTTP 客户端等于瞎了。这里让 WebView 真正跑完 JS，
 * 再取 `document.body.innerText`，搜索结果页 / 谱面页都能读到「用户看到的内容」。
 *
 * ## 为什么必须挂到视图树
 * 完全不 attach 的 WebView 在某些机型上 `window.innerWidth` 为 0，虚拟列表不挂载、
 * IntersectionObserver 不触发，SPA 会只剩空壳。所以把 1×1 的 WebView 塞进当前
 * Activity 的 decorView 并平移到屏幕外：不占布局、不可见、不拦点击，但WebView
 * 认为自己「在屏幕上」，布局与渲染全部正常。Activity 销毁时自动摘掉，避免泄漏。
 * 拿不到 Activity（App 在后台跑 Agent）时退化为不 attach，只是少数 SPA 取不到全文。
 *
 * ## 安全边界
 * - **不注册 addJavascriptInterface**：JS 侧拿不到任何 Java 对象，模型写的 JS 只能在
 *   网页沙箱里跑，碰不到 App 数据；
 * - 禁用自动弹窗 / 图片加载 / 自动播放，后台浏览不打扰用户也不吃流量；
 * - 所有操作经 [Mutex] 串行，模型连续发 open→click→text 不会互相踩；
 * - 每次操作有超时，页面卡死不会把 Agent 会话挂住。
 *
 * 生命周期：单例常驻，长时间不用或 App 退出时 [act] 的 `close` 动作会销毁。
 */
@SuppressLint("SetJavaScriptEnabled")
object AgentBrowser {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Kiba Agent Browser) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val PAGE_TIMEOUT_MS = 20_000L
    private const val JS_TIMEOUT_MS = 15_000L
    private const val DEFAULT_SETTLE_MS = 1_500L
    private const val DEFAULT_MAX_CHARS = 8_000

    /** 一次浏览器动作的返回，直接作为 tool result 回灌给模型。 */
    data class Outcome(
        val ok: Boolean,
        val text: String,
        val url: String = "",
        val title: String = "",
    ) {
        /** 拼成给模型看的一行文本。 */
        fun render(): String = buildString {
            if (!ok) append("浏览器操作失败：")
            if (title.isNotBlank()) append("【页面】$title\n")
            if (url.isNotBlank()) append("【地址】$url\n")
            append(text)
        }
    }

    private val mutex = Mutex()

    @Volatile private var webView: WebView? = null

    @Volatile private var mutableContext: MutableContextWrapper? = null

    @Volatile private var hostActivity: WeakReference<Activity>? = null

    @Volatile private var lifecycleRegistered = false

    @Volatile private var pageFinished = false

    @Volatile private var pageError = ""

    @Volatile private var currentUrl = ""

    /**
     * 统一动作入口。参数名与 LLM 的 `browser` 工具 schema 一一对应，
     * [KibaAgent] 不需要知道内部有哪些 WebView 细节。
     */
    suspend fun act(
        context: Context,
        action: String,
        url: String = "",
        js: String = "",
        selector: String = "",
        value: String = "",
        waitMs: Long = DEFAULT_SETTLE_MS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): Outcome = mutex.withLock {
        runCatching {
            when (action.trim().lowercase()) {
                "open" -> open(context, url, waitMs, maxChars)
                "text" -> readText(selector, maxChars)
                "eval" -> {
                    if (js.isBlank()) return@runCatching Outcome(false, "eval 需要 js 参数")
                    val (ok, out) = callJs(js, maxChars)
                    Outcome(ok, out, currentUrl)
                }
                "click" -> domAction("click", selector, "")
                "fill" -> domAction("fill", selector, value)
                "scroll" -> {
                    val dy = value.toIntOrNull() ?: 800
                    val (ok, out) = callJs(
                        "window.scrollBy(0,$dy); 'scrolled to '+window.scrollY",
                        maxChars,
                    )
                    Outcome(ok, out, currentUrl)
                }
                "close" -> {
                    release()
                    Outcome(true, "后台浏览器已关闭，下次 open 会重新创建。")
                }
                else -> Outcome(
                    false,
                    "未知动作「$action」。可用：open / text / eval / click / fill / scroll / close。",
                )
            }
        }.getOrElse { Outcome(false, "浏览器异常：${it.message ?: "未知错误"}") }
    }

    /** 打开 URL，等主文档加载完 + 再等一小段给 SPA 挂载，返回渲染后的正文。 */
    private suspend fun open(
        context: Context,
        url: String,
        waitMs: Long,
        maxChars: Int,
    ): Outcome {
        val target = url.trim()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            return Outcome(false, "只支持 http/https 地址，收到：$target")
        }
        val wv = ensure(context)
        withContext(Dispatchers.Main) {
            pageFinished = false
            pageError = ""
            currentUrl = target
            wv.stopLoading()
            wv.loadUrl(target)
        }
        val deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MS
        while (!pageFinished && System.currentTimeMillis() < deadline) delay(150)
        if (!pageFinished) return Outcome(false, "页面加载超时（20s）：$target", target)
        if (pageError.isNotBlank()) {
            return Outcome(false, "页面加载失败：$pageError", target)
        }
        // onPageFinished 只代表主文档和静态资源好了；React 还得 fetch 数据再 mount。
        // 给一段固定等待，也允许模型用 wait_ms 拉长（慢站 / 需要等某个元素）。
        delay(waitMs.coerceIn(0L, 10_000L))
        val (titleOk, title) = callJs("document.title || ''", 200)
        val (bodyOk, body) = callJs(
            "(document.body ? document.body.innerText : '').slice(0,$maxChars)",
            maxChars,
        )
        return Outcome(
            ok = titleOk && bodyOk,
            text = if (bodyOk) body else "页面正文读取失败：$body",
            url = currentUrl,
            title = if (titleOk) title else "",
        )
    }

    /** 读当前页面的文本；给了 selector 就只读那个元素。 */
    private suspend fun readText(selector: String, maxChars: Int): Outcome {
        val expr = if (selector.isBlank()) {
            "(document.body ? document.body.innerText : '')"
        } else {
            "(() => { var el = document.querySelector(${JSONObject.quote(selector)});" +
                " if (!el) throw new Error('未找到元素');" +
                " return el.value !== undefined && el.value !== null && el.value !== ''" +
                " ? el.value : (el.innerText || el.textContent || ''); })()"
        }
        val (ok, text) = callJs("$expr.slice(0,$maxChars)", maxChars)
        return Outcome(ok, if (ok) text else "读取失败：$text", currentUrl)
    }

    /** 点击元素 / 给输入框填值（同时派发 input + change，覆盖受控组件）。 */
    private suspend fun domAction(kind: String, selector: String, value: String): Outcome {
        if (selector.isBlank()) return Outcome(false, "$kind 需要 selector 参数")
        val sel = JSONObject.quote(selector)
        val body = if (kind == "click") {
            "(() => { var el = document.querySelector($sel);" +
                " if (!el) throw new Error('未找到元素：$selector');" +
                " el.click(); return 'clicked'; })()"
        } else {
            "(() => { var el = document.querySelector($sel);" +
                " if (!el) throw new Error('未找到元素：$selector');" +
                " el.focus(); el.value = ${JSONObject.quote(value)};" +
                " el.dispatchEvent(new Event('input', {bubbles:true}));" +
                " el.dispatchEvent(new Event('change', {bubbles:true}));" +
                " return 'filled'; })()"
        }
        val (ok, out) = callJs(body, 200)
        return Outcome(ok, out, currentUrl)
    }

    /**
     * 在页面里执行一段 JS，返回它的值。
     *
     * 用 `eval` 包一层而不是要求模型只写表达式：模型给的经常是
     * 「先 querySelector 再 return」这种片段。返回值统一 JSON.stringify，
     * [WebView.evaluateJavascript] 会再把整个字符串 JSON 编码一层，这里解两层。
     */
    private suspend fun callJs(body: String, maxChars: Int): Pair<Boolean, String> {
        val script =
            "(function(){try{" +
                "var r = eval(${JSONObject.quote(body)});" +
                "return JSON.stringify({ok:true,v:r===undefined?null:r});" +
                "}catch(e){return JSON.stringify({ok:false,e:String(e)});}})()"
        val raw = evaluate(script) ?: return false to "后台浏览器尚未打开页面，请先 open"
        val json = unquote(raw) ?: return false to "JS 返回值无法解析"
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return false to "JS 返回值不是合法 JSON"
        if (!obj.optBoolean("ok")) {
            return false to obj.optString("e", "JS 执行出错").take(maxChars)
        }
        val v = obj.opt("v")
        val text = when {
            v == null || v === JSONObject.NULL -> ""
            v is String -> v
            else -> v.toString()
        }
        return true to text.take(maxChars)
    }

    /** [WebView.evaluateJavascript] 必须在主线程调用；回调经可取消协程桥回 suspend。 */
    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        withTimeoutOrNull(JS_TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { cont ->
                wv.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "null")
                }
            }
        }
    }

    /** evaluateJavascript 返回的是被引号包住的 JSON 字符串，先脱一层。 */
    private fun unquote(raw: String): String? = runCatching {
        val t = raw.trim()
        if (t.startsWith("\"") && t.endsWith("\"")) JSONArray("[$t]").getString(0) else t
    }.getOrNull()

    /** 懒创建单例 WebView（必须在主线程）。 */
    private suspend fun ensure(context: Context): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val app = context.applicationContext
        registerLifecycle(app)
        // 第一次创建时 onActivityResumed 已经过去了，回调不会补发；从调用方 Context
        // （Compose 的 LocalContext，通常就是 Activity 的包装）里主动解一层，
        // 否则 WebView 会拿 applicationContext 构造，遇到 <select> 之类要主题的控件会崩。
        val initialActivity = hostActivity?.get() ?: findActivity(context)
        if (initialActivity != null) hostActivity = WeakReference(initialActivity)
        val mc = MutableContextWrapper(initialActivity ?: app)
        val wv = WebView(mc).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = UA
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    currentUrl = url
                    pageFinished = false
                    pageError = ""
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    pageFinished = true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // 主文档失败才算失败；图片/CDN 子资源失败不影响正文
                    if (request.isForMainFrame) {
                        pageError = runCatching { error.description.toString() }.getOrDefault("网络错误")
                        pageFinished = true
                    }
                }
            }
            // 吞掉页面 console / 全屏请求：后台浏览器不该弹 UI，也不刷屏 logcat
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean = true
            }
        }
        mutableContext = mc
        webView = wv
        hostActivity?.get()?.let { attach(wv, it) }
        wv
    }

    /** 从 ContextWrapper 链里找 Activity（Compose 的 LocalContext 通常包着它）。 */
    private fun findActivity(context: Context): Activity? {
        var c: Context = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** 把 1×1 WebView 挂到当前 Activity 的 decorView 并移出屏幕。 */
    private fun attach(wv: WebView, activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (wv.parent === decor) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            decor.addView(wv, ViewGroup.LayoutParams(1, 1))
            wv.translationX = -20_000f
            wv.translationY = -20_000f
        }
    }

    /** 跟踪当前可见 Activity：WebView 的主题（`<select>` 弹窗等）需要真实 Activity context。 */
    private fun registerLifecycle(appContext: Context) {
        if (lifecycleRegistered) return
        val app = appContext as? Application ?: return
        lifecycleRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                hostActivity = WeakReference(activity)
                mutableContext?.baseContext = activity
                webView?.let { attach(it, activity) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (hostActivity?.get() !== activity) return
                webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                mutableContext?.baseContext = app
                hostActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** 释放后台浏览器（下次 open 会重建）。必须在主线程 destroy。 */
    private suspend fun release() = withContext(Dispatchers.Main) {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
        webView = null
        mutableContext = null
        currentUrl = ""
        pageFinished = false
        pageError = ""
    }
}
