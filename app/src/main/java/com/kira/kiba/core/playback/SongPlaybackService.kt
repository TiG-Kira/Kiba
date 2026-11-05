package com.kira.kiba.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kira.kiba.MainActivity
import com.kira.kiba.R
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 乐曲播放的**媒体通知**服务（与 [ChartPlaybackService] 同构，但遥控的是真播放器）。
 *
 * ## 为什么独立一个服务
 * 谱面播放（WebView 遥控）与乐曲播放（MediaPlayer）是两条完全不同的声源链路，
 * 各自持有 MediaSession 与通知，互不掺和。互斥规则：乐曲开播时按住谱面声源
 * （[SongPlaybackController.startTrack] 里已做），反向不强制——用户先听乐曲再开
 * 谱面预览属于主动换台。
 *
 * ## 与谱面通知的差异
 * - **真进度**：MediaPlayer 有真实 duration/position，metadata 放
 *   METADATA_KEY_DURATION、PlaybackState 带位置与速率 → Android 13+ 媒体卡片
 *   自动渲染可拖动 seekbar（谱面通知因时间线口径对不上刻意不放）；
 * - **按钮**：播放/暂停、上一首/下一首（会话队列内）、停止；
 * - **封面**：曲目封面候选链异步加载（复用 [com.kira.kiba.core.utils.CoverFetcher]）。
 *
 * ## 生命周期
 * [SongPlaybackController.startTrack] 拉起；1s 轮询控制器状态刷 PlaybackState；
 * 控制器队列播完或 stop() → 控制器状态归零 → 本服务自行收尾。
 */
class SongPlaybackService : Service() {

    companion object {
        private const val TAG = "SongPlayback"
        private const val CHANNEL_ID = "song_playback"
        private const val NOTIFICATION_ID = 4102

        /** 通知「关闭」按钮的自定义 action（不冲突媒体卡片预设 action）。 */
        const val ACTION_CLOSE = "com.kira.kiba.playback.ACTION_CLOSE"
        const val ACTION_FAV_TOGGLE = "com.kira.kiba.playback.ACTION_FAV_TOGGLE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SongPlaybackService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SongPlaybackService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSessionCompat? = null
    private var coverBitmap: Bitmap? = null
    /** 已加载封面归属的曲目 id：服务跨曲目常驻时换曲要重拉封面。 */
    private var coverForSongId = 0
    /** 上次通知渲染的签名：状态/曲目/错误没变就不重发通知（位置走 PlaybackState，不用刷通知）。 */
    private var lastNotifySig: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FAV_TOGGLE) {
            // pre-13 通知按钮通道：SP 直写 + 立即刷图标（Android 13+ 走 onCustomAction）
            val id = SongPlaybackController.state.value.track?.songId?.toString()
            if (id != null) {
                SpUtil.setFavorite(id, !SpUtil.isFavorite(id))
                refresh()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLOSE) {
            // 通知「关闭」：彻底关播放（释放播放器 + 状态归零），服务随之撤除通知
            SongPlaybackController.closeSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!bootstrapped) {
            bootstrapped = true
            enterForeground()
            pollLoop()
        }
        return START_NOT_STICKY
    }

    private var bootstrapped = false

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        session?.release()
        session = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "乐曲播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kiba 乐曲播放时的控制通知"
                setShowBadge(false)
                setSound(null, null)
            },
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(PlaybackStateCompat.STATE_PAUSED),
                type,
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "startForeground failed", e)
        }
        loadCoverAsync()
    }

    /** 点媒体卡片回到乐曲播放页（contentIntent 与 sessionActivity 共用）。 */
    private fun reopenPendingIntent(): PendingIntent {
        val reopenIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_REOPEN_SONG_PLAYER, true)
        }
        return PendingIntent.getActivity(
            this,
            1, // 与谱面 reopen 的 requestCode 区分开，避免互相覆盖
            reopenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun getOrCreateSession(): MediaSessionCompat {
        session?.let { return it }
        val ms = MediaSessionCompat(this, "KibaSongPlayback")
        ms.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = SongPlaybackController.resume()
            override fun onPause() = SongPlaybackController.pause()
            override fun onSkipToNext() = SongPlaybackController.next()
            override fun onSkipToPrevious() = SongPlaybackController.prev()
            override fun onStop() {
                SongPlaybackController.stop()
                stopSelf()
            }

            override fun onSeekTo(pos: Long) = SongPlaybackController.seekTo(pos)

            override fun onCustomAction(action: String, extras: Bundle?) {
                // Android 13+ 媒体卡片只渲染 PlaybackState 动作，「关闭」按钮走这里
                if (action == ACTION_CLOSE) {
                    SongPlaybackController.closeSession()
                    stopSelf()
                }
                // 「收藏」：SP 直写（播放器仅舞萌，详情页顶栏同命名空间），
                // 立即刷新让卡片图标立刻切换
                if (action == ACTION_FAV_TOGGLE) {
                    val id = SongPlaybackController.state.value.track?.songId?.toString() ?: return
                    SpUtil.setFavorite(id, !SpUtil.isFavorite(id))
                    refresh()
                }
            }
        })
        ms.setSessionActivity(reopenPendingIntent())
        ms.isActive = true
        session = ms
        return ms
    }

    private fun pollLoop() {
        scope.launch {
            while (isActive) {
                refresh()
                delay(1_000)
            }
        }
    }

    private fun refresh() {
        val st = SongPlaybackController.state.value
        if (st.track == null) {
            // 控制器已归零（用户彻底停止 / 通知关闭）：通知没有存在意义
            stopSelf()
            return
        }
        // 换曲：重拉封面（清掉旧图避免闪现上一首的封面）
        if (st.track.songId != coverForSongId) {
            coverForSongId = st.track.songId
            coverBitmap = null
            loadCoverAsync()
        }
        val ms = getOrCreateSession()
        val state = when {
            st.error != null -> PlaybackStateCompat.STATE_ERROR
            st.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        ms.setPlaybackState(buildState(state, st))
        ms.setMetadata(buildMetadata(st))
        // ended（队列播完停止态）也要进签名：暂停态通知上多出「已播完」文案，
        // 否则用户看不到状态变化
        val sig = "${st.track?.songId}|$state|${st.error != null}|${st.ended}"
        if (sig != lastNotifySig) {
            lastNotifySig = sig
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildState(state: Int, st: SongPlaybackController.PlayerState): PlaybackStateCompat {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        // 收藏态决定按钮图标（空心/实心）：pollLoop 每秒重设 PlaybackState，
        // 点击后 onCustomAction 里再立即 refresh() 一次，切换无感延迟
        val fav = st.track != null && SpUtil.isFavorite(st.track.songId.toString())
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            // 自定义动作：Android 13+ 系统媒体卡片忽略 Notification.addAction，
            // 只有这里注册才会出现按钮（点击回调 onCustomAction）
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_FAV_TOGGLE,
                    if (fav) "取消收藏" else "收藏",
                    if (fav) R.drawable.ic_notify_star_filled else R.drawable.ic_notify_star,
                ).build(),
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_CLOSE,
                    "关闭",
                    R.drawable.ic_notify_close,
                ).build(),
            )
            .setState(state, st.positionMs, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
            .build()
    }

    private fun buildMetadata(st: SongPlaybackController.PlayerState): MediaMetadataCompat {
        val track = st.track
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "Kiba")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "乐曲播放")
            // 真时长：Android 13+ 媒体卡片按 duration + 位置渲染 seekbar
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, st.durationMs)
            .apply {
                coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }
            .build()
    }

    private fun buildNotification(state: Int): Notification {
        val st = SongPlaybackController.state.value
        val contentIntent = reopenPendingIntent()
        // pre-13 回退：旧版通知仍用 addAction（Android 13+ 由系统媒体卡片渲染
        // PlaybackState 自定义动作，这里的 action 会被忽略但不报错）
        val fav = st.track != null && SpUtil.isFavorite(st.track.songId.toString())
        val favIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, SongPlaybackService::class.java).apply { action = ACTION_FAV_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val favAction = NotificationCompat.Action.Builder(
            if (fav) R.drawable.ic_notify_star_filled else R.drawable.ic_notify_star,
            if (fav) "取消收藏" else "收藏",
            favIntent,
        ).build()
        // 通知「关闭」按钮：展开态可见，点击 = 关播放 + 撤通知
        val closeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SongPlaybackService::class.java).apply { action = ACTION_CLOSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeAction = NotificationCompat.Action.Builder(
            R.drawable.ic_kiba_notification,
            "关闭",
            closeIntent,
        ).build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kiba_notification)
            .setContentTitle(st.track?.title ?: "Kiba")
            .setContentText(
                when {
                    st.error != null -> st.error ?: "播放出错"
                    st.ended -> "已播完 · 点播放重播"
                    else -> st.track?.artist ?: "乐曲播放"
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(state == PlaybackStateCompat.STATE_PLAYING)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(coverBitmap)
            .addAction(favAction)
            .addAction(closeAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(getOrCreateSession().sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    /** 后台加载曲目封面（复用 CoverFetcher + Coil 磁盘缓存），成功后刷一次通知。 */
    private fun loadCoverAsync() {
        val candidates = SongPlaybackController.state.value.track?.coverCandidates ?: return
        if (candidates.isEmpty()) return
        val ms = session
        scope.launch(Dispatchers.IO) {
            val bmp = com.kira.kiba.core.utils.CoverFetcher.fetch(applicationContext, candidates)
            if (bmp == null) return@launch
            launch(Dispatchers.Main) {
                coverBitmap = bmp
                if (session === ms) lastNotifySig = null // 强制下次 refresh 重发通知
            }
        }
    }
}
