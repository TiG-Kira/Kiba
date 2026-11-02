package com.kira.kiba.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var lastDurationMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        pollLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

    private fun getOrCreateSession(): MediaSessionCompat {
        session?.let { return it }
        val ms = MediaSessionCompat(this, "KibaChartPlayback")
        ms.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = ChartPreviewPlayer.resume()
            override fun onPause() = ChartPreviewPlayer.pause()
            override fun onRewind() = ChartPreviewPlayer.seekBack()
            override fun onFastForward() = ChartPreviewPlayer.seekForward()
            override fun onStop() = ChartPreviewPlayer.release()
        })
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
        ChartPreviewPlayer.probeState { st ->
            if (session != ms) return@probeState // 服务正在销毁
            when (st.state) {
                "playing" -> {
                    lastPosMs = st.posMs
                    lastDurationMs = st.durMs
                    applyState(ms, PlaybackStateCompat.STATE_PLAYING, st.posMs)
                }
                "paused" -> {
                    if (st.posMs > 0) lastPosMs = st.posMs
                    if (st.durMs > 0) lastDurationMs = st.durMs
                    applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
                }
                "ended" -> {
                    // 自然播完：WebView 留着不会再响，直接收尾（release 会顺带 stop 服务）
                    ChartPreviewPlayer.release()
                }
                else -> applyState(ms, PlaybackStateCompat.STATE_PAUSED, lastPosMs)
            }
        }
    }

    private fun applyState(ms: MediaSessionCompat, state: Int, posMs: Long) {
        ms.setPlaybackState(buildState(state, posMs))
        ms.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, ChartPreviewPlayer.title.ifBlank { "谱面播放中" })
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    ChartPreviewPlayer.subtitle.ifBlank { "落雪谱面预览" },
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastDurationMs)
                .build(),
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state, posMs))
    }

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
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.kiba_alpha)
            .setContentTitle(ChartPreviewPlayer.title.ifBlank { "谱面播放中" })
            .setContentText(ChartPreviewPlayer.subtitle.ifBlank { "落雪谱面预览" })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(getOrCreateSession().sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            // MediaStyle 进度条：duration>0 且 setProgress(enabled) 时显示
            .apply {
                if (lastDurationMs > 0) {
                    setProgress(
                        lastDurationMs.toInt(),
                        posMs.toInt().coerceAtMost(lastDurationMs.toInt()),
                        state == PlaybackStateCompat.STATE_PLAYING,
                    )
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }
}
