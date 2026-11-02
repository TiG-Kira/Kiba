package com.kira.kiba.ui.songdetail

import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * 落雪谱面「后台播放」中枢。
 *
 * ## 为什么需要它
 * 谱面预览页是 WebView 承载的落雪播放器（WebAudio 播 CDN 音频）。用户点播放后返回
 * 上层页面时，WebView 脱离组合但**不会自动停声**——音频在后台一直播，App 只能靠
 * 杀进程退出。既然音频能后台播，索性把它变成功能：保留 WebView 继续出声，并交给
 * **系统媒体通知**（[com.kira.kiba.core.playback.ChartPlaybackService]）控制——
 * 通知栏 / 媒体控制中心提供 播放-暂停 / 快退 / 快进 / 停止，等应用退后台也有标准入口。
 *
 * ## 播放控制原理（免注入对方业务代码）
 * 反编译落雪公开 bundle（src_pages_*_chart chunk）确认：
 * - 快捷键监听挂在 `window`（`window.addEventListener('keydown')`），无 isTrusted 检查，
 *   合成 KeyboardEvent 即可触发：`Space` = 播放/暂停、`←/→` = 步进位置、
 *   `Shift+←/→` = 跳整小节、`R` = 重播小节；
 * - store 的 `pause()` 只置 `isPlaying=false`，**不动 BufferSource**（是否 stop 由
 *   音频引擎决定，两个版本行为不同）——这解释了历史上「派发成功但探测仍 playing」：
 *   不是没派发上，是老探测把「已暂停但音源未结束」误判成 playing。
 * 双保险：Space 派发之后**再无条件**对 AudioContext 调 `suspend()/resume()`——
 * suspend 会冻结整个上下文（音频时钟停走），与页面自身状态正交，永远有效。
 * 回到预览页时无条件 `resume()`，避免用户手动点播放时上下文还挂在挂起态导致无声。
 *
 * ## 播放态探测
 * 音频走 `AudioContext + BufferSource`（无 `<audio>` 元素），页面没有暴露全局 store。
 * 三条探测通道汇总进一个 JSON（[STATE_JS]）：
 * 1. **音源通道**：页面加载早期 hook `AudioBufferSourceNode.prototype.buffer` setter，
 *    时长 >10s 的源（音乐整轨，音效都 <2s）挂到 `__kibaMusicSource`；live（未 ended）
 *    时结合 `context.state`（suspended=暂停）与播放按钮 aria-label 判定；
 * 2. **起点通道**：hook `start()` 记录 `{ctx时间, offset}`，位置 = 当前 ctx 时间 - 起点
 *    + offset，播到哪一秒通知进度条就有真数据；暂停时冻结在 `__kibaLastPos`；
 * 3. **按钮兜底**：mantine Tooltip 把 aria-label（"播放"/"暂停"）放在播放按钮上，
 *    音源通道拿不到时（引擎 pause 时 stop 了音源）靠它判 playing/paused；
 *    位置已到曲末附近且按钮显示「播放」判 `ended`（自然播完，服务据此收尾）。
 */
object ChartPreviewPlayer {

    private const val TAG = "LxnsChartPreview"

    internal var webView: WebView? = null
        private set

    internal var currentUrl: String? = null
        private set

    /** 媒体通知展示的曲名 / 难度（从谱面详情页带入）。 */
    var title: String = ""
        private set
    var subtitle: String = ""
        private set

    /** 曲目 id 与封面 URL（点通知重开预览页时还原 KibaSubPage.ChartPreview 用）。 */
    var songId: Int = 0
        private set
    var songImageUrl: String = ""
        private set

    /** 谱面封面候选链（[com.kira.kiba.core.utils.CoverSources.candidates]），
     *  供媒体通知封面加载；空 = 未知曲，通知退化为应用图标。 */
    var coverCandidates: List<String> = emptyList()
        private set

    /** 预览页当前是否在组合中（服务收尾判定用：页面还挂着就不能销毁 WebView）。 */
    internal var screenVisible: Boolean = false
        private set

    // ---- 点媒体通知「回到预览页」----
    // 路由链：通知 contentIntent → MainActivity（onCreate/onNewIntent）→
    // requestReopen() → KibaMainScreen 消费（pushPage 回 ChartPreview）。
    // 活跃信号走 SharedFlow；onCreate 时序上先于 Compose 订阅者挂上，再用
    // pendingReopen 补一发（订阅前先消费一次），两条路都通。

    private val _reopenFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reopenFlow: SharedFlow<Unit> = _reopenFlow

    @Volatile private var pendingReopen = false

    /** MainActivity 收到带 reopen 标记的 Intent 时调用。 */
    fun requestReopen() {
        pendingReopen = true
        _reopenFlow.tryEmit(Unit)
    }

    /** KibaMainScreen 订阅 reopenFlow 前先消费一次（兜 onCreate 早于订阅的时序）。 */
    fun consumePendingReopen(): Boolean = pendingReopen.also { pendingReopen = false }

    /** 一次播放态探测结果。state ∈ playing / paused / ended / idle。 */
    data class Snapshot(val state: String, val posMs: Long, val durMs: Long)

    /** 预览页进来时取同 url 的保留实例（复用播放进度），没有则返回 null 新建。 */
    fun current(url: String): WebView? = webView?.takeIf { currentUrl == url }

    /** 预览页注册实例；若手里还留着别的谱面（后台播放中）先销毁它。 */
    fun register(
        url: String,
        view: WebView,
        songTitle: String,
        songSubtitle: String,
        coverCandidates: List<String> = emptyList(),
        newSongId: Int = 0,
        newSongImageUrl: String = "",
    ) {
        screenVisible = true
        if (webView === view) {
            // 同一实例回到前台：解锁上下文（后台期间可能被我们 suspend 过），
            // 否则用户手动点网页播放键时 AudioContext 还挂着，页面有进度没声音。
            android.util.Log.d(TAG, "register: same instance, resume ctx")
            view.evaluateJavascript(SUSPEND_JS.replace("%op%", "resume()"), null)
            return
        }
        android.util.Log.d(TAG, "register: new instance for $url (old=${currentUrl ?: "none"})")
        release()
        webView = view
        currentUrl = url
        title = songTitle
        subtitle = songSubtitle
        this.coverCandidates = coverCandidates
        songId = newSongId
        songImageUrl = newSongImageUrl
    }

    /**
     * 预览页离开（返回键 / 系统返回 / 切 Tab 均会触发 onDispose）时调用：
     * 探测播放态——在播则保留 WebView 并拉起媒体通知服务，否则直接销毁。
     * 探测是异步回调，期间 WebView 由本对象持有，不会被 GC。
     */
    fun onScreenDispose() {
        screenVisible = false
        val view = webView ?: return
        probeState { st ->
            // 回调期间用户可能又开了别的谱面预览（webView 已被换掉），别越权处理新实例
            if (webView !== view) return@probeState
            if (st.state == "playing") {
                android.util.Log.d(TAG, "keep alive in background (playing) -> media notification")
                com.kira.kiba.core.playback.ChartPlaybackService.start(view.context.applicationContext)
            } else {
                release()
            }
        }
    }

    /** 销毁 WebView、停掉一切音频，并收掉媒体通知服务。 */
    fun release() {
        android.util.Log.d(TAG, "release: url=$currentUrl, hasView=${webView != null}")
        val appContext = webView?.context?.applicationContext
        webView?.apply {
            evaluateJavascript("(function(){try{var s=window.__kibaMusicSource;if(s&&!s.ended){try{s.stop()}catch(e){}}}catch(e){}})()", null)
            stopLoading()
            onPause()
            // **绝不能调 pauseTimers()**：它是进程级 API（暂停所有 WebView 的渲染
            // 与 JS 调度），销毁旧实例时会把紧接着新建的 WebView 一起冻住 ——
            // 正是「返回后打开另一首歌灰屏」的根因。音源已在上面显式 stop。
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        currentUrl = null
        title = ""
        subtitle = ""
        songId = 0
        songImageUrl = ""
        coverCandidates = emptyList()
        screenVisible = false
        appContext?.let { com.kira.kiba.core.playback.ChartPlaybackService.stop(it) }
    }

    // ---- 播放控制：Space 派发 + AudioContext suspend/resume 双保险 ----

    private fun dispatchKey(key: String, shift: Boolean) {
        val js = "(function(){window.dispatchEvent(new KeyboardEvent('keydown',{key:'$key',shiftKey:$shift}));return 'ok'})()"
        webView?.evaluateJavascript(js) { r ->
            android.util.Log.d(TAG, "dispatchKey '$key' shift=$shift -> $r")
        }
    }

    /** `suspend()` / `resume()` 整个 AudioContext（页面音源全走它，一冻全冻）。 */
    private fun ctx(op: String) {
        webView?.evaluateJavascript(SUSPEND_JS.replace("%op%", op), null)
    }

    /** 播放 / 暂停：先派发网页 Space（页面状态跟着走），再 suspend 兜底。 */
    fun pause() {
        dispatchKey(" ", shift = false)
        ctx("suspend()")
    }

    /** 恢复播放：先派发 Space，再无条件 resume（对 running 态的 ctx 调 resume 无害）。 */
    fun resume() {
        dispatchKey(" ", shift = false)
        ctx("resume()")
    }

    /** 快退一小节（等价网页 Shift+←）。 */
    fun seekBack() = dispatchKey("ArrowLeft", shift = true)

    /** 快进一小节（等价网页 Shift+→）。 */
    fun seekForward() = dispatchKey("ArrowRight", shift = true)

    /** 探测播放态（state/pos/dur），回调在主线程。无 WebView 时返回 idle。 */
    fun probeState(onResult: (Snapshot) -> Unit) {
        val view = webView
        if (view == null) {
            onResult(Snapshot("idle", 0, 0))
            return
        }
        view.evaluateJavascript(STATE_JS) { result ->
            val snap = parseSnapshot(result)
            android.util.Log.d(TAG, "probe -> $result")
            onResult(snap)
        }
    }

    private fun parseSnapshot(result: String?): Snapshot {
        if (result.isNullOrBlank()) return Snapshot("idle", 0, 0)
        return runCatching {
            // evaluateJavascript 包一层 JSON 字符串字面量（带引号 + 转义），剥掉再解析
            val raw = result.trim().removeSurrounding("\"").replace("\\\"", "\"")
            val o = JSONObject(raw)
            Snapshot(
                state = o.optString("state", "idle"),
                posMs = o.optLong("pos", 0L),
                durMs = o.optLong("dur", 0L),
            )
        }.getOrElse { Snapshot("idle", 0, 0) }
    }

    /** AudioContext suspend/resume 通道（%op% 占位替换）。 */
    private val SUSPEND_JS = """
        (function(){try{var s=window.__kibaMusicSource;var c=s&&s.context;
        if(!c)return 'noc';c.%op%;return c.state}catch(e){return 'err'}})()
    """.trimIndent().replace("\n", " ")

    /**
     * 播放态探测脚本，返回 JSON {state, pos, dur}（pos/dur 单位 ms）。
     * 顺带幂等重挂补丁（兜住 onPageStarted 补丁因时序没挂上的情况）。
     *
     * 判定层级：
     * 1. **ctx suspended** → paused（我们的通知控制走 ctx.suspend，状态必然同步）；
     * 2. **音源 ended === true**（音乐轨真放完）→ pos 贴曲末判 ended，否则 paused；
     * 3. 音源 live → playing（pos = ctx 时钟 - start 锚点，音乐轨真实位置）；
     * 4. 兜底 paused。
     *
     * **页面内暂停不在此判定**（AVD 实测：落雪暂停按钮只停谱面演出、音频整轨
     * 继续播，pos 仍真实增长——JS 层「在播」符合听觉事实）：「页面内暂停后通知
     * 是否转暂停」由服务端用系统级 AudioManager.isMusicActive 复核——真机上若
     * 页面暂停连带音乐停（引擎版本行为不同），isMusicActive=false 会把 JS 的
     * playing 误报纠正为 paused；演出停但音乐还响时通知保持播放中同样符合听觉。
     * 其他不可用信号（实测记录，勿再踩）：
     * - 播放按钮无 aria-label（26 个 button 全查过）；
     * - 音源暂停后 ended 读出 undefined（页面疑似 stop 旧源+建新源不 start）；
     * - 谱面位置文本（m:d.d [n/N]）是 canvas 绘制，DOM 文本通道拿不到；
     * - JS 体内不能写 // 注释——脚本会被单行化，第二行注释会吞掉后续代码。
     */
    internal val STATE_JS = """
        (function(){
            try { kibaHookAudio(); } catch(e) {}
            var out = {state:'idle', pos:0, dur:0};
            var s = window.__kibaMusicSource;
            try { if (s && s.buffer) out.dur = Math.round(s.buffer.duration * 1000); } catch(e) {}
            var ctxSuspended = !!(s && s.context && s.context.state === 'suspended');
            var st = window.__kibaMusicStart;
            if (ctxSuspended) {
                out.pos = window.__kibaLastPos || 0;
                out.state = 'paused';
            } else if (s && s.ended === true) {
                out.pos = window.__kibaLastPos || 0;
                if (out.dur > 0 && out.pos >= out.dur - 1500) out.state = 'ended';
                else out.state = 'paused';
            } else if (s && !s.ended) {
                try {
                    var p = (s.context.currentTime - st.t + st.off) * 1000;
                    out.pos = Math.max(0, Math.round(p));
                    if (out.dur > 0 && out.pos > out.dur) out.pos = out.dur;
                    window.__kibaLastPos = out.pos;
                } catch(e) { out.pos = window.__kibaLastPos || 0; }
                out.state = 'playing';
            } else {
                out.pos = window.__kibaLastPos || 0;
                if (out.dur > 0 && out.pos >= out.dur - 1500) out.state = 'ended';
                else out.state = 'paused';
            }
            return JSON.stringify(out);
        })()
    """.trimIndent().replace("\n", " ")

    /**
     * 页面加载早期注入的 AudioContext 补丁（STATE_JS 里的 kibaHookAudio 同源）。
     * 必须赶在页面 bundle 执行前挂上，否则第一批音乐源捕获不到。
     *
     * 两个 hook：
     * 1. **buffer setter**：不能在 createBufferSource 返回时检查 buffer——页面是先
     *    create 再赋 `src.buffer = audioBuffer`，创建瞬间 buffer 还是 null。正确姿势
     *    是 hook setter，赋值时判断时长：音乐整轨 >10s，音效全部 <2s；
     * 2. **start()**：记录 `{ctx.currentTime, offset}` 作为播放起点，位置估算全靠它；
     *    seek / 暂停重播都会开新音源，每次 start 都刷新起点。
     */
    internal val PATCH_JS = """
        (function(){
            if (window.__kibaAudioPatched) return;
            window.__kibaAudioPatched = true;
            window.kibaHookAudio = function(){
                if (window.__kibaBufferHooked) return;
                var proto = window.AudioBufferSourceNode && window.AudioBufferSourceNode.prototype;
                if (!proto) return;
                var desc = Object.getOwnPropertyDescriptor(proto, 'buffer');
                if (!desc || !desc.set) return;
                Object.defineProperty(proto, 'buffer', {
                    configurable: true,
                    get: desc.get,
                    set: function(v){
                        try { if (v && v.duration > 10) window.__kibaMusicSource = this; } catch(e){}
                        desc.set.call(this, v);
                    }
                });
                var origStart = proto.start;
                if (origStart) {
                    proto.start = function(){
                        try {
                            if (this === window.__kibaMusicSource ||
                                (this.buffer && this.buffer.duration > 10)) {
                                window.__kibaMusicStart = {
                                    t: this.context.currentTime,
                                    off: arguments.length > 1 ? (+arguments[1] || 0) : 0
                                };
                                window.__kibaLastPos = 0;
                            }
                        } catch(e){}
                        return origStart.apply(this, arguments);
                    };
                }
                window.__kibaBufferHooked = true;
            };
            window.kibaHookAudio();
        })()
    """.trimIndent().replace("\n", " ")
}
