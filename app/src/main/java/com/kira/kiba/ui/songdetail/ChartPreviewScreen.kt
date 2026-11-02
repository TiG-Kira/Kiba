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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
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
 * - chart_id：标准谱 = 曲目 id，DX 谱 = 曲目 id + 10000（宴谱 id 本身 ≥ 100000，原样使用）；
 * - difficulty：0 = BASIC … 4 = Re:MASTER（与本地 level_index 一致；宴谱省略，页面自选）。
 *
 * 失败兜底：WebView 加载失败（断网 / CDN 异常）时给出「用浏览器打开」入口，
 * 用户仍可跳到落雪网页播放。
 */
@Composable
fun ChartPreviewScreen(
    url: String,
    scrollBehavior: ScrollBehavior,
) {
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    // 网页的 Fullscreen API 全屏视图（视频/画布），不接 onShowCustomView 的话全屏按钮无效
    var customView by remember { mutableStateOf<View?>(null) }

    val webView = remember(url) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 播放器由用户点播放键触发，保留手势要求即可；媒体不自动开声
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    loading = false
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
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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

/** WebChromeClient 挂载辅助（避免匿名对象里改捕获变量需要可空包装）。 */
private object WebChromeClientHost {
    fun attach(webView: WebView, onShow: (View) -> Unit, onHide: () -> Unit) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                onShow(view)
            }

            override fun onHideCustomView() {
                onHide()
            }
        }
    }
}
