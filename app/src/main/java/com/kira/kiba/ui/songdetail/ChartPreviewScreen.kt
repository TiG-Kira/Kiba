package com.kira.kiba.ui.songdetail

import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.core.utils.CoverSources
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.navigation.KibaBackAction
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 落雪谱面预览（App 内 WebView 承载）。
 *
 * 落雪网页版提供官方谱面确认播放器（canvas 渲染 + CDN 音频，开源实现
 * maimai-prober-frontend / maimai-chart-engine）。与其在 App 里从零移植一套
 * simai 渲染器，不如直接加载它的公开预览页：
 * `https://maimai.lxns.net/chart?chart_id={id}&difficulty={n}`。
 *
 * - chart_id：曲目 id 原样（落雪 CDN 的 simai 谱面一曲一份、含全部难度，无 DX 偏移）；
 * - difficulty：0 = BASIC … 4 = Re:MASTER（与本地 level_index 一致；宴谱省略，页面自选）。
 *
 * 失败兜底：WebView 加载失败（断网 / CDN 异常）时给出「用浏览器打开」入口，
 * 用户仍可跳到落雪网页播放。
 *
 * 顶栏：本页自带 miuix 普通 [SmallTopAppBar]（实色、固定不塌缩），不走外层
 * GlassTopAppBar——WebView 不是 LazyColumn，玻璃取景层也采样不到有效背景，
 * 只会渲染出一块纯透明；内容区消费 Scaffold padding，不会顶穿顶栏。
 *
 * 后台播放：返回/离开本页时若还在播，WebView 由 [ChartPreviewPlayer] 保留常播，
 * 控制入口走系统媒体通知（[com.kira.kiba.core.playback.ChartPlaybackService]）。
 */
@Composable
fun ChartPreviewScreen(
    url: String,
    title: String,
    subtitle: String,
    songId: Int,
    songImageUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 后台播放器是否留有同 url 的 WebView（mini 播放中返回同谱面）：复用则不在加载态
    val isReused = ChartPreviewPlayer.current(url) != null
    var loading by remember(url) { mutableStateOf(!isReused) }
    var failed by remember(url) { mutableStateOf(false) }
    // 网页的 Fullscreen API 全屏视图（视频/画布），不接 onShowCustomView 的话全屏按钮无效
    var customView by remember { mutableStateOf<View?>(null) }

    val webView = remember(url) {
        ChartPreviewPlayer.current(url) ?: WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 播放器由用户点播放键触发，保留手势要求即可；媒体不自动开声
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, u, favicon)
                    // 赶在页面 bundle 执行前给 AudioContext 打补丁，捕获音乐源供后台探测
                    view.evaluateJavascript(ChartPreviewPlayer.PATCH_JS, null)
                }

                override fun onPageFinished(view: WebView, u: String) {
                    loading = false
                    injectKibaStyle(view)
                    // 标题区隐藏靠注入脚本里的常驻 MutationObserver（标题一挂载即藏）。
                    // 8 秒补注入只是兜底：万一 observer 因极端时序没挂上还能重挂。
                    // 这里只排一次；injectKibaStyle 内部不再自排，避免无限链式调度。
                    view.postDelayed({ injectKibaStyle(view) }, 8_000L)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // 主文档失败才算真的失败；CDN 音频/图片等子资源失败不影响谱面预览
                    if (request.isForMainFrame) {
                        loading = false
                        failed = true
                    }
                }
            }
            WebChromeClientHost.attach(this, onShow = { customView = it }, onHide = { customView = null })
            loadUrl(url)
        }
    }

    DisposableEffect(url) {
        // 注册到后台播放器中枢：同 url 复用保留实例（播放进度不丢），
        // 手里留着别的谱面则销毁旧实例。离开本页时探测播放态——
        // 在播则保留 WebView + 弹 mini 播放器；未播直接销毁，不漏后台音频。
        ChartPreviewPlayer.register(
            url = url,
            view = webView,
            songTitle = title,
            songSubtitle = subtitle,
            // 媒体通知封面候选链（与 UI 封面同一套源，复用 Coil 磁盘缓存）
            coverCandidates = CoverSources.candidates(songId, songImageUrl),
        )
        onDispose { ChartPreviewPlayer.onScreenDispose() }
    }

    val cv = customView
    if (cv != null) {
        // 网页进入全屏：把自定义视图挂到全屏 Box 上
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    (cv.parent as? ViewGroup)?.removeView(cv)
                    android.widget.FrameLayout(ctx).apply { addView(cv) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SmallTopAppBar(
                title = "谱面预览",
                navigationIcon = { KibaBackAction(onClick = onBack) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!failed) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pageContentPadding(withNavBar = false)),
                ) {
                    EmptyStateCard(
                        text = "谱面预览加载失败\n请检查网络后重试，或用浏览器打开落雪播放页",
                        action = null,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "重试",
                            onClick = {
                                failed = false
                                loading = true
                                webView.loadUrl(url)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(
                            onClick = { BrowserJump.openUrl(context, url) },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "用浏览器打开")
                        }
                    }
                }
            }
            if (loading && !failed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在加载谱面预览…",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 注入 Kiba 侧脚本：隐藏落雪页自带的站点头部（汉堡菜单 / Logo / 游戏切换 Tab /
 * 主题切换）和「谱面预览 测试版」标题区，避免用户在 App 内跳去落雪其他页面。
 *
 * 站点头部：落雪前端用 CSS 变量做占位（--header-height / --header-bar-height），
 * 变量归零 + display:none 容器即可无留白；类名是 vite CSS module 编译产物
 * `_header_<hash>_<n>`（只保留 local 名），用 `_header_` 属性子串匹配。
 *
 * 标题区：JSX 是 `Group > (返回按钮 + Title(h3)"谱面预览" + Badge"测试版")`，
 * mantine v7 组件带全局类 `mantine-Group-root`，故按 h3 文本定位后
 * hide 最近的一个 Group。SPA 渲染晚于 onPageFinished，一次性查找会在标题
 * 尚未挂载时空手而归（页面上挂约半分钟才消失）——因此挂一个**常驻
 * MutationObserver**：标题节点一插入 DOM 立刻隐藏，与渲染时机彻底解耦；
 * React 后续重渲染重建节点也由 observer 兜住。style 挂在 html
 * （documentElement）上——react-helmet 会重写 head，挂那里会被它清掉。
 */
private fun injectKibaStyle(view: WebView) {
    val js = """
        (function() {
            var s = document.getElementById('kiba-inject-style');
            if (!s) {
                s = document.createElement('style');
                s.id = 'kiba-inject-style';
                document.documentElement.appendChild(s);
            }
            s.textContent = ':root { --header-height: 0px !important; --header-bar-height: 0px !important; }'
                + 'div[class*="_header_"] { display: none !important; }';
            function hideTitle() {
                var n = 0;
                var list = document.querySelectorAll('h3');
                for (var i = 0; i < list.length; i++) {
                    var h = list[i];
                    if ((h.textContent || '').trim() !== '谱面预览') continue;
                    var c = h.closest('.mantine-Container-root');
                    if (c && c.firstElementChild && c.firstElementChild.style.display !== 'none') {
                        c.firstElementChild.style.display = 'none';
                        n++;
                    }
                }
                return n;
            }
            var hid = hideTitle();
            if (!window.__kibaTitleObserver) {
                window.__kibaTitleObserver = new MutationObserver(function() { hideTitle(); });
                window.__kibaTitleObserver.observe(document.documentElement, { childList: true, subtree: true });
            }
            return 'alive:' + !!s.parentNode + ',hid:' + hid;
        })()
    """.trimIndent().replace("\n", " ")
    view.evaluateJavascript(js) { result ->
        android.util.Log.d("LxnsChartPreview", "inject -> $result")
    }
}

/** WebChromeClient 挂载辅助（避免匿名对象里改捕获变量需要可空包装）。 */
private object WebChromeClientHost {
    private const val TAG = "LxnsChartPreview"

    fun attach(webView: WebView, onShow: (View) -> Unit, onHide: () -> Unit) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                onShow(view)
            }

            override fun onHideCustomView() {
                onHide()
            }

            // 落雪页面 JS 报错转发到 logcat，便于排查 WebView 内的播放故障
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.w(
                    TAG,
                    "[js:${message.messageLevel()}] ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})",
                )
                return true
            }
        }
    }
}
