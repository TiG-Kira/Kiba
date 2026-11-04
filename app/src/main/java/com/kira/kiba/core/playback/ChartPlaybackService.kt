package com.kira.kiba.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kira.kiba.MainActivity
import com.kira.kiba.R
import com.kira.kiba.ui.songdetail.ChartPreviewPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 谱面后台播放的**媒体通知**服务。
 *
 * ## 为什么存在
 * 落雪谱面预览页（WebView）在用户返回后仍会继续出声（见 [ChartPreviewPlayer]）。
 * 不给它一个控制入口的话，「后台一直响」就是 bug 而不是功能。这里把保留的 WebView
 * 对接进系统媒体通知：通知栏 / 锁屏 / 媒体控制中心出现标准媒体卡片，提供
 * 播放-暂停、快退（退一小节）、快进（进一小节）、停止——全部映射回网页自己的控制
 * 能力（Space 快捷键派发 + AudioContext suspend/resume，见 ChartPreviewPlayer）。
 *
 * ## 为什么用 framework MediaSession 而不是 media3
 * 这里没有真的播放器——音频在 WebView 的 WebAudio 里，Player 只是个遥控面板。
 * media3 的 MediaSession 必须绑一个 Player 实例，为了不出声还得养一个假 ExoPlayer；
 * framework MediaSession 直接 setCallback 挂回调即可。用 compat 封装是因为
 * MediaStyle 通知的 setMediaSession 只吃 MediaSessionCompat.Token。Android 13+
 * 媒体卡片由系统按 PlaybackState 的 action 渲染按钮，无需自定义通知布局。
 *
 * ## 生命周期
 * - 由 ChartPreviewPlayer 在「预览页离开且还在播」时 startForegroundService 拉起；
 * - 每 1s 探测一次播放态（state/pos/dur），刷新 PlaybackState 与通知进度；
 * - 探测到 `ended`（自然播完）或 WebView 没了 → 收尾（停通知、停服务）；
 * - 用户点停止 / ChartPreviewPlayer.release() → 同上。
 */
class ChartPlaybackService : Service() {

    companion object {
        private const val TAG = "ChartPlayback"
        private const val CHANNEL_ID = "chart_playback"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "com.kira.kiba.playback.STOP"

        /** 服务是否在跑（预览页轮询用它避免重复 startForegroundService）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 在播拉起服务（幂等：已在跑则只刷新一次状态）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChartPlaybackService::class.java),
            )
        }

        /** 任意入口收掉服务（幂等：没在跑就什么都不发生）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ChartPlaybackService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSessionCompat? = null
    private var lastPosMs = 0L
    /** onStartCommand 幂等闸：重复 start（页面内轮询 + 离页拉起双重触发）不重跑引导。 */
    private var bootstrapped = false

    // ---- 播放恢复复核（应用外点「继续播放」无声的自愈）----
    // onPlay 后先宽限 2 个轮询（音频拉起有延迟，别急着重发），之后每轮检查
    // isMusicActive：仍在播 → 清零；仍无声 → 重发幂等的 ctx.resume()，最多 3 次。
    private var playVerifyGrace = 0
    private var playVerifyRetries = 0
    /** 谱面封面（后台异步加载，成功后刷一次元数据；拿不到就退化为应用图标）。 */
    @Volatile private var coverBitmap: android.graphics.Bitmap? = null

    /** 系统级音频活动检测（页面无关的「真的在响吗」金标准）。 */
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!bootstrapped) {
            bootstrapped = true
            enterForeground()
            loadCoverAsync()
            pollLoop()
        } else {
            // 已在跑（页面内轮询与离页收尾可能双重触发）：元数据/封面由
            // ChartPreviewPlayer 单例提供、服务每次刷新都读最新值，刷一次即可。
            refresh()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        session?.release()
        session = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 必须在 startForegroundService 后 5s 内前台化，type=mediaPlayback。 */
    private fun enterForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "谱面后台播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "落雪谱面预览在后台播放时的控制通知"
                setShowBadge(false)
                setSound(null, null)
            },
        )
        val notification = buildNotification(PlaybackStateCompat.STATE_PAUSED, lastPosMs)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // 前台化失败只丢通知，不影响 WebView 里已经在响的音频
            android.util.Log.w(TAG, "startForeground failed", e)
        }
    }

    /** 点媒体卡片回到谱面预览页的统一入口（通知 contentIntent 与 sessionActivity 共用）。 */
    private fun reopenPendingIntent(): PendingIntent {
        // SINGLE_TOP|CLEAR_TOP 让已存在的 MainActivity 收 onNewIntent 而不是叠新实例；
        // extras 标记由 MainActivity 转发给 ChartPreviewPlayer.requestReopen()，
        // KibaMainScreen 消费后 pushPage 回 ChartPreview（复用保留 WebView，进度不丢）。
        val reopenIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_REOPEN_CHART_PREVIEW, true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            reopenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun getOrCreateSession(): MediaSessionCompat {
        session?.let { return it }
        val ms = MediaSessionCompat(this, "KibaChartPlayback")
        ms.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                // 复核窗口：onPlay 后 2s 宽限 + 最多 3 次重发（见 refresh 的复核逻辑）。
                // 真机（HyperOS 4 / ColorOS 17）实证：应用外点媒体通知的播放键，
                // WebView/渲染器可能还在冻结恢复中，单次派发会石沉大海。
                playVerifyGrace = 2
                playVerifyRetries = 3
                ChartPreviewPlayer.resume()
            }
            override fun onPause() = ChartPreviewPlayer.pause()
            override fun onRewind() = ChartPreviewPlayer.seekBack()
            override fun onFastForward() = ChartPreviewPlayer.seekForward()

            override fun onStop() {
                // 无论前后台都**只停声、保留 WebView**（谱面停在暂停态）：
                // - 预览页挂着时销毁会让页面变死页；
                // - 后台销毁会让用户回页面看谱时进度全丢（Android 17
                //   HyperOS 4 / ColorOS 17 的媒体控制卡片点击可能触发 onStop，
                //   若在此 release，症状正是「点卡片 → 回主页 + 播放消失」）。
                // WebView 由下次 register/release 或页面正常关闭兜底清理，
                // 不会永久泄漏；重新播放（页面内或通知）后轮询会重拉本服务。
                ChartPreviewPlayer.pause()
                stopSelf()
            }
        })
        // Android 13+ 的媒体控制卡片（通知栏顶部 / 锁屏 / QS）点正文走的是
        // sessionActivity，**不是**通知的 contentIntent；不设置时系统 fallback
        // 到 launcher intent（无 reopen 标记）→ 只回主页。HyperOS 4 / ColorOS 17
        // 实测即此症状，必须显式指向同一 reopen 入口。
        ms.setSessionActivity(reopenPendingIntent())
        ms.isActive = true
        session = ms
        return ms
    }

    /** 1s 轮询 WebView 播放态，驱动 PlaybackState / 通知刷新与收尾判定。 */
    private fun pollLoop() {
        scope.launch {
            while (isActive) {
                refresh()
                delay(1_000)
            }
        }
    }

    private fun refresh() {
        val ms = session ?: return
        if (ChartPreviewPlayer.webView == null) {
            // 保留的 WebView 没了（App 重建 / release 异常）：通知没有存在意义
            stopSelf()
            return
        }
        // 播放恢复复核：刚请求过播放但整机仍无声 → 重发幂等的 ctx.resume()。
        // 只解冻、不派发 Space —— Space 是切换键，重发有把播放切成暂停的风险。
        if (playVerifyGrace > 0) {
            playVerifyGrace--
        } else if (playVerifyRetries > 0) {
            if (audioManager.isMusicActive) {
                playVerifyGrace = 0
                playVerifyRetries = 0
            } else {
                playVerifyRetries--
                android.util.Log.d(
                    TAG,
                    "play verify: still silent, re-issue ctx resume (left=$playVerifyRetries)",
                )
                ChartPreviewPlayer.resumeCtxOnly()
            }
        }
        ChartPreviewPlayer.probeState { st ->
            if (session != ms) return@probeState // 服务正在销毁
            when (st.state) {
                "playing" -> {
                    // **isMusicActive 复核**：页面 JS 探测在「用户于页面内暂停」时
                    // 会误报 playing——实测（HyperOS 4 / ColorOS 17 的落雪页面）：
                    // 页面暂停既不 suspend AudioContext 也不可靠置 ended，且播放
                    // 按钮没有 aria-label，三条探测通道全部失效，pos 还会假增长。
                    // 系统级音频活动（isMusicActive）是页面无关的金标准：JS 说在播
                    // 但整机没有任何音频输出 → 页面必然已静，判暂停（pos 冻结）。
                    // 已知局限：若有别的 App 恰好在放歌，isMusicActive 恒 true，
                    // 复核失效退回 JS 判定——可接受的边缘场景。
                    if (audioManager.isMusicActive) {
                        lastPosMs = st.posMs
                        applyState(ms, PlaybackStateCompat.STATE_PLAYING, st.posMs)
                    } else if (st.durMs > 0 && st.posMs >= st.durMs - 1500) {
                        // 整机无声 + JS pos 已假增长并被 clamp 到曲末 = 自然播完
                        // （pos = ctx.currentTime - 起点，页面播完会重置音频源但
                        // ctx 可能还在走，探出的 pos 触底不再动）。
                        android.util.Log.d(TAG, "silent + pos at track end -> ended")
                        if (ChartPreviewPlayer.screenVisible) stopSelf()
                        else ChartPreviewPlayer.release()
                    } else {
                        android.util.Log.d(
                            TAG,
                            "JS says playing but isMusicActive=false -> treat as paused (pos=${st.posMs})",
                        )
                        applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
                    }
                }
                "paused" -> {
                    if (st.posMs > 0) lastPosMs = st.posMs
                    applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
                }
                "ended" -> {
                    // 自然播完：WebView 留着不会再响。页面还挂着就只撤通知
                    // （销毁 WebView 会让预览页变死页），用户重播后页面轮询再拉起；
                    // 后台场景彻底收尾（release 会顺带 stop 服务）。
                    if (ChartPreviewPlayer.screenVisible) stopSelf()
                    else ChartPreviewPlayer.release()
                }
                else -> applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
            }
        }
    }

    /**
     * 后台加载谱面封面（复用 [com.kira.kiba.core.utils.CoverFetcher] 与全局 Coil
     * 磁盘缓存——详情页刚展示过的封面多半直接命中，秒回）。成功后刷一次元数据。
     */
    private fun loadCoverAsync() {
        if (ChartPreviewPlayer.coverCandidates.isEmpty()) return
        val ms = session ?: return
        scope.launch(Dispatchers.IO) {
            val bmp = com.kira.kiba.core.utils.CoverFetcher.fetch(
                applicationContext,
                ChartPreviewPlayer.coverCandidates,
            )
            if (bmp == null) return@launch
            launch(Dispatchers.Main) {
                if (session == ms && ChartPreviewPlayer.webView != null) {
                    coverBitmap = bmp
                    applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
                }
            }
        }
    }

    private fun applyState(ms: MediaSessionCompat, state: Int, posMs: Long) {
        ms.setPlaybackState(buildState(state, posMs))
        ms.setMetadata(buildMetadata())
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state, posMs))
    }

    private fun buildMetadata(): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, ChartPreviewPlayer.title.ifBlank { "谱面播放中" })
            .putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                ChartPreviewPlayer.subtitle.ifBlank { "落雪谱面预览" },
            )
            .apply {
                // 封面：媒体卡片左侧小图 + 锁屏大图。刻意**不放 METADATA_KEY_DURATION**：
                // 落雪时间线按小节拍数走，与音频整轨秒数对不上，展示出来是错的。
                coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }
            .build()

    private fun buildState(state: Int, posMs: Long): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(
                state,
                posMs,
                if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f,
            )
            .build()

    /**
     * MediaStyle 通知。Android 13+ 媒体卡片上的按钮由系统按 PlaybackState 的
     * action 渲染（播放/暂停、快退、快进、停止），无需在通知里塞自定义 action。
     */
    private fun buildNotification(state: Int, posMs: Long): Notification {
        // 点通知卡片正文回到谱面预览页（与 sessionActivity 同一入口，见
        // reopenPendingIntent 注释）。
        val contentIntent = reopenPendingIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kiba_notification)
            .setContentTitle(ChartPreviewPlayer.title.ifBlank { "谱面播放中" })
            .setContentText(ChartPreviewPlayer.subtitle.ifBlank { "落雪谱面预览" })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(coverBitmap)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(getOrCreateSession().sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            // 不带进度条：时长口径对不上（见 buildMetadata），展示反而误导。
            .build()
    }
}
