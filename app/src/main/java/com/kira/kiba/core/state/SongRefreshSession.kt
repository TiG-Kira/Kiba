package com.kira.kiba.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 曲库刷新会话：**计数式**的进度卡生命周期。
 *
 * ## 为什么要计数，而不是「两边都结束就隐藏」
 * 曲目页会同时展示两张进度卡（舞萌 / 中二），早期的隐藏条件是
 * 「[com.kira.kiba.core.data.SongDataSync.state] 与
 * [com.kira.kiba.core.data.ChuniSongSync.state] 都进入终态」。
 * 但并非每个刷新入口都会同时启动两条同步：顶栏刷新在「没有可用更新」时只走
 * `checkForUpdate()`，完全不碰中二——中二侧于是永远停在 `Idle`，
 * 隐藏条件永不成立，两张卡（一张空卡 + 一张「等待同步…」）就一直挂在列表顶部，
 * 直到杀进程才消失（1.8.1 实测复现）。
 *
 * 现在改为：**谁发起、发起几个任务，就登记几个待完成计数**，任务全部结束后
 * 会话自然关闭，与「另一边是否参与」无关。任一任务失败时，卡片多保留
 * [FAILURE_HOLD_MS] 让用户看清原因（原来这段延迟写在 UI 的 LaunchedEffect 里）。
 *
 * UI 侧只需观察 [visible]，并按各游戏自己的 `SyncState` 决定渲染哪张卡——
 * `Done` / `Idle` 一律不渲染，空卡片与「等待同步…」占位随之消失。
 */
object SongRefreshSession {

    /** 失败时进度卡多保留的时长（毫秒）。 */
    private const val FAILURE_HOLD_MS = 3_000L

    private val _visible = MutableStateFlow(false)

    /** 当前是否有刷新会话在进行（曲目页据此展示置顶进度卡）。 */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 本次会话尚未结束的任务数。 */
    private var pending = 0

    /** 本次会话是否有任务失败（决定结束时是否停留展示）。 */
    private var failed = false

    private var holdJob: Job? = null

    /**
     * 登记一次刷新会话（累加计数，可并发）。
     *
     * @param tasks 本次实际发起的同步任务数（舞萌 + 中二各算一个，只刷新一边就传 1）。
     */
    @Synchronized
    fun begin(tasks: Int = 1) {
        holdJob?.cancel()
        holdJob = null
        // 上一轮已收尾时才重置失败标记，避免并发登记把「已失败」冲掉
        if (pending == 0) failed = false
        pending += tasks.coerceAtLeast(1)
        _visible.value = true
    }

    /**
     * 一个任务结束（成功 / 失败 / 取消都要调，放在 `finally` 里）。
     *
     * @param hasFailed 该任务是否以失败告终。
     */
    @Synchronized
    fun endTask(hasFailed: Boolean = false) {
        if (hasFailed) failed = true
        if (pending > 0) pending--
        if (pending > 0) return
        finish()
    }

    /** 所有任务结束：成功立即收卡，失败先停留展示错误再收。 */
    private fun finish() {
        if (!failed) {
            _visible.value = false
            return
        }
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(FAILURE_HOLD_MS)
            synchronized(this@SongRefreshSession) {
                if (pending == 0) {
                    _visible.value = false
                    failed = false
                }
            }
        }
    }
}
