package com.kira.kiba.core.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow

/**
 * 乐曲播放中枢（曲目详情页「播放乐曲」/ 曲目列表悬浮底栏 / 媒体播放页共用）。
 *
 * ## 为什么是 MediaPlayer 而不是 WebView / media3
 * 音源是落雪 CDN 的整轨 mp3（`assets2.lxns.net/maimai/music/{id%10000}.mp3`，
 * 从落雪谱面预览页 bundle 逆向 + 实测 206 audio/mpeg 验证；取模口径与曲目 id
 * 映射一致——落雪 SD/DX 谱共用曲目 id，DX 官方 id %10000 即回基础 id）。
 * 单一 http 流 + 拖动进度，[MediaPlayer] 的 prepareAsync + seekTo 全够用；
 * media3 要拖一整套依赖，WebView 是给「连谱面一起演」的场景用的，纯听歌杀鸡牛刀。
 *
 * ## 职责边界
 * 本类只管「出声 + 状态」：持有 [MediaPlayer]、维护 [state] 状态流、请求音频焦点。
 * MediaSession / 媒体通知在 [SongPlaybackService]——它订阅本类状态渲染通知，
 * 通知按钮回调本类的 pause/resume/next/prev。UI（播放页 / 悬浮底栏）也只读状态流。
 *
 * ## 队列口径
 * 没有曲库级队列（随机/循环不在本期）。[state.queue] 是「本次会话播放过的曲目」，
 * 从详情页每播一首追加一首；上一首/下一首在此队列内移动，播完自动下一首，
 * 队尾播完即停。够支撑媒体通知和播放页的前后切换，后续要做歌单再扩。
 */
object SongPlaybackController {

    private const val TAG = "SongPlayback"

    /** 一条可播放的乐曲（从曲目详情页的曲库实体构造）。 */
    data class Track(
        val songId: Int,
        val title: String,
        val artist: String,
        /** 封面相对路径（官网镜像路径口径，UI 侧统一经 CoverFrame/CoverSources 拼候选链）。 */
        val imageUrl: String,
        /** 流派（CoverFrame 占位色块与描边用）。 */
        val genre: String,
        /** 封面候选链（媒体通知大图加载用，见 CoverSources.candidates）。 */
        val coverCandidates: List<String>,
        val audioUrl: String,
    )

    data class PlayerState(
        val track: Track? = null,
        val playing: Boolean = false,
        /** prepareAsync 完成、可以出声（加载中 = track 有值 + !prepared）。 */
        val prepared: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
        /** 本次会话播放队列（历史口径，见类注释）。 */
        val queue: List<Track> = emptyList(),
        val queueIndex: Int = -1,
    )

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    // ---- 媒体通知「回到播放页」----
    // 与 ChartPreviewPlayer 同款双通道：MainActivity 收带标记的 Intent →
    // requestReopen() → KibaMainScreen 消费后 pushPage 播放页。
    private val _reopenFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reopenFlow: SharedFlow<Unit> = _reopenFlow

    @Volatile private var pendingReopen = false

    fun requestReopen() {
        pendingReopen = true
        _reopenFlow.tryEmit(Unit)
    }

    /** KibaMainScreen 订阅 reopenFlow 前先消费一次（兜 onCreate 早于订阅的时序）。 */
    fun consumePendingReopen(): Boolean = pendingReopen.also { pendingReopen = false }

    /** 落雪整轨音源（id 取模口径见类注释；宴谱等特殊 id 404 时走 error 分支）。 */
    fun audioUrlFor(songId: Int): String =
        "https://assets2.lxns.net/maimai/music/${songId % 10000}.mp3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mp: MediaPlayer? = null
    private var pollJob: Job? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null

    /** 从曲目详情页发起播放（幂等：同一首在播则只恢复/无操作）。 */
    fun play(context: Context, track: Track) {
        appContext = context.applicationContext
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        // 同一首已在队列中：跳到它重新播（用户意图明确就是「播这首」）
        val st = _state.value
        val existing = st.queue.indexOfFirst { it.songId == track.songId }
        if (existing >= 0 && st.track?.songId == track.songId && st.prepared) {
            if (!st.playing) resume()
            return
        }
        val queue = if (existing >= 0) st.queue else st.queue + track
        val index = if (existing >= 0) existing else queue.lastIndex
        startTrack(queue, index)
    }

    /** 播放/暂停切换（播放页大按钮、通知按钮、悬浮底栏共用）。 */
    fun toggle() {
        if (_state.value.playing) pause() else resume()
    }

    fun pause() {
        val player = mp ?: return
        runCatching { player.pause() }
        abandonFocus()
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        val st = _state.value
        val player = mp ?: return
        if (!st.prepared) return
        runCatching { player.start() }
        requestFocus()
        _state.value = st.copy(playing = true, error = null)
    }

    /** 拖动进度（毫秒，绝对位置）。 */
    fun seekTo(positionMs: Long) {
        val player = mp ?: return
        runCatching { player.seekTo(positionMs.toInt()) }
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    /** 队列内下一首（没有则忽略）。 */
    fun next() = jumpTo(_state.value.queueIndex + 1)

    /** 队列内上一首（没有则忽略；开头再点上一首回到曲首）。 */
    fun prev() {
        val st = _state.value
        if (st.positionMs > 5_000 && st.queueIndex >= 0) {
            startTrack(st.queue, st.queueIndex) // 播放过半先重播本曲（播放器惯例）
        } else {
            jumpTo(st.queueIndex - 1)
        }
    }

    private fun jumpTo(index: Int) {
        val st = _state.value
        if (index in st.queue.indices) startTrack(st.queue, index)
    }

    /** 彻底停止并释放（播放页停止键 / 服务收尾）。通知由服务自行撤除。 */
    fun stop(releasePlayer: Boolean = true) {
        pollJob?.cancel()
        pollJob = null
        abandonFocus()
        if (releasePlayer) {
            runCatching { mp?.release() }
            mp = null
            _state.value = PlayerState()
            appContext?.let { SongPlaybackService.stop(it) }
        } else {
            // 只停声保进度：播放页刷新态用
            runCatching { mp?.stop() }
            _state.value = _state.value.copy(playing = false)
        }
    }

    // ---- 内部 ----

    private fun startTrack(queue: List<Track>, index: Int) {
        val track = queue[index]
        val context = appContext ?: return
        // 双声源互斥：谱面预览还在响就先按住（两路 WebAudio/MP 同时出声没法听）
        com.kira.kiba.ui.songdetail.ChartPreviewPlayer.pause()
        ChartPlaybackService.stop(context)

        pollJob?.cancel()
        runCatching { mp?.release() }
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(track.audioUrl)
            setOnPreparedListener { p ->
                val st = _state.value
                if (st.track?.songId != track.songId) return@setOnPreparedListener
                _state.value = st.copy(prepared = true, durationMs = p.duration.toLong())
                p.start()
                requestFocus()
                _state.value = _state.value.copy(playing = true)
            }
            setOnCompletionListener {
                onTrackEnded()
            }
            setOnErrorListener { _, what, extra ->
                android.util.Log.w(TAG, "MediaPlayer error what=$what extra=$extra url=${track.audioUrl}")
                pollJob?.cancel()
                _state.value = _state.value.copy(
                    playing = false,
                    prepared = false,
                    error = "音源加载失败（该曲目可能暂无落雪音源）",
                )
                true
            }
            prepareAsync()
        }
        _state.value = _state.value.copy(
            track = track,
            playing = false,
            prepared = false,
            positionMs = 0,
            durationMs = 0,
            error = null,
            queue = queue,
            queueIndex = index,
        )
        startPolling()
        SongPlaybackService.start(context)
    }

    /** 曲末统一出口：队列内自动下一首；队尾播完即停（清状态、收通知）。 */
    private fun onTrackEnded() {
        val st = _state.value
        if (st.queueIndex + 1 < st.queue.size) {
            startTrack(st.queue, st.queueIndex + 1)
        } else {
            stop()
        }
    }

    /** 500ms 轮询位置（播放页进度条与通知 seekbar 数据源）。 */
    private fun startPolling() {
        var endTicks = 0
        pollJob = scope.launch {
            while (isActive) {
                val player = mp
                val st = _state.value
                if (player != null && st.prepared && st.playing) {
                    runCatching {
                        val pos = player.currentPosition.toLong()
                        val dur = player.duration.toLong()
                        // 贴尾兑底：http Range 流播到曲末时 onCompletion 偶发不回调
                        // （AVD 实证 pos 溢出 duration 仍报 PLAYING，通知永远显示
                        // 播放中但无声）。连续 ~1.5s 贴尾即按自然播完处理。
                        if (dur > 0 && pos >= dur - 300) {
                            endTicks++
                            if (endTicks >= 3) {
                                android.util.Log.d(TAG, "end-of-track by poll (pos=$pos dur=$dur)")
                                onTrackEnded()
                                return@launch
                            }
                        } else {
                            endTicks = 0
                        }
                        _state.value = st.copy(positionMs = pos, durationMs = dur)
                    }
                }
                delay(500)
            }
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        pause()
                    }
                }
                .build()
        }
        runCatching { am.requestAudioFocus(focusRequest!!) }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    }
}
