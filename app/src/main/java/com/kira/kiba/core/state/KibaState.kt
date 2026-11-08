package com.kira.kiba.core.state

import android.content.Context
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniSongSync
import com.kira.kiba.core.data.SongDataSync
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用级状态（主题、动态取色、启动引导）。
 * 账户与曲库同步状态分别在 [DivingFishAccount] / [SongDataSync] 上外溢。
 */
object KibaState {

    enum class ThemeMode { System, Light, Dark }

    private val _themeMode = MutableStateFlow(ThemeMode.System)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
    }

    /**
     * 启动引导：恢复登录态 + 检查曲库更新（只检查，有新版本由 UI 弹提示，不主动下载）。
     * 全部异常在各自模块内降级，不阻塞启动。
     */
    fun bootstrap(context: Context) {
        scope.launch {
            // 落雪必须自己也恢复一次：以前只恢复水鱼，落雪的 playerFound / 昵称 / 好友码
            // 全靠内存态，冷启动后恒为「未绑定玩家」，UI 对已绑定账号报假告警。
            // 先恢复落雪再刷新成绩，避免成绩链路拿到半截状态。
            runCatching { LxnsAccount.restore() }
            runCatching { DivingFishAccount.restore() }
        }
        scope.launch {
            runCatching { SongDataSync.checkForUpdate() }
        }
        // 谱面统计（拟合定数 / 相对难度数据源）：5 天节流的后台同步
        scope.launch {
            runCatching { SongDataSync.syncChartStats() }
        }
    }

    /** 用户手动触发：检查曲库更新（只检查；有更新则 [SongDataSync.updateAvailable] 提示）。 */
    fun checkSongUpdate(showProgress: Boolean = false) {
        launchSynced(showProgress, downloaded = false) { SongDataSync.checkForUpdate() }
    }

    /** 用户手动触发：立即更新曲库（下载 + 入库 + 写版本号）。 */
    fun refreshSongData(context: Context, showProgress: Boolean = false) {
        launchSynced(showProgress, downloaded = true) { SongDataSync.forceSync(context) }
    }

    /**
     * 用户手动触发：只刷新中二节奏曲库（下载 music_data → 入库 → 补落雪物量）。
     *
     * 曲目页顶栏的刷新按钮按**当前游戏**分流：中二档位下过去只会去检查舞萌版本，
     * 中二曲库因此永远不会被手动更新，用户看到的就是「点了刷新没反应」。
     */
    fun refreshChuniSongData(context: Context, showProgress: Boolean = true) {
        if (showProgress) {
            SongRefreshSession.clearNotice(RefreshGame.CHUNI)
            SongRefreshSession.begin(1)
        }
        scope.launch {
            try {
                runCatching { ChuniSongSync.forceSync(context) }
            } finally {
                if (showProgress) {
                    SongRefreshSession.endTask(
                        hasFailed = ChuniSongSync.state.value is ChuniSongSync.SyncState.Failed,
                    )
                    reportChuniNotice()
                }
            }
        }
    }

    /**
     * 跑一条曲库同步协程，并按 [showProgress] 登记 / 结束 [SongRefreshSession]。
     *
     * 会话结束一定在 `finally` 里做——即使同步内部抛异常（网络库、Room 等），
     * 计数也不会漏减，曲目页的进度卡不会再次「常驻」。
     * 失败判定看各同步对象最终写下的 SyncState（它们自身会吞掉异常并改写状态）。
     *
     * @param downloaded 本次是否走了「下载 + 入库」（区分「已更新」与「本来就是最新」）。
     */
    private fun launchSynced(
        showProgress: Boolean,
        downloaded: Boolean,
        block: suspend () -> Unit,
    ) {
        if (showProgress) {
            SongRefreshSession.clearNotice(RefreshGame.MAIMAI)
            SongRefreshSession.begin(1)
        }
        scope.launch {
            try {
                runCatching { block() }
            } finally {
                if (showProgress) {
                    SongRefreshSession.endTask(
                        hasFailed = SongDataSync.state.value is SongDataSync.SyncState.Failed,
                    )
                    reportMaimaiNotice(downloaded)
                }
            }
        }
    }

    /**
     * 舞萌侧收尾提示：`Done` 要分三种语义——真的下载入库了、有新版但还没下、
     * 本来就没有新版本。只写「刷新成功」会让「点了没感觉」变成「点了骗我」。
     */
    private fun reportMaimaiNotice(downloaded: Boolean) {
        when (val state = SongDataSync.state.value) {
            is SongDataSync.SyncState.Done -> {
                val pending = SongDataSync.updateAvailable.value
                when {
                    downloaded -> SongRefreshSession.notify(
                        RefreshGame.MAIMAI, true, "舞萌曲库已更新", "数据版本 ${state.version}",
                    )
                    pending != null -> SongRefreshSession.notify(
                        RefreshGame.MAIMAI, true, "发现新版本 $pending", "在更新提示中可立即下载",
                    )
                    else -> SongRefreshSession.notify(
                        RefreshGame.MAIMAI, true, "舞萌曲库已是最新", "数据版本 ${state.version}",
                    )
                }
            }
            is SongDataSync.SyncState.Failed -> SongRefreshSession.notify(
                RefreshGame.MAIMAI, false, "舞萌曲库刷新失败", state.message,
            )
            else -> Unit
        }
    }

    /** 中二侧收尾提示：Done 带的是本次入库的曲目数。 */
    private fun reportChuniNotice() {
        when (val state = ChuniSongSync.state.value) {
            is ChuniSongSync.SyncState.Done -> {
                val version = ChuniSongSync.getLocalVersion()
                SongRefreshSession.notify(
                    RefreshGame.CHUNI, true, "中二节奏曲库已更新",
                    if (version.isBlank()) "共 ${state.count} 首" else "共 ${state.count} 首 · 版本 $version",
                )
            }
            is ChuniSongSync.SyncState.Failed -> SongRefreshSession.notify(
                RefreshGame.CHUNI, false, "中二节奏曲库刷新失败", state.message,
            )
            else -> Unit
        }
    }

    /**
     * 用户手动触发：**同时刷新舞萌与中二节奏两边的曲库**。
     *
     * 两个游戏各自维护 [SongDataSync.state] / [ChuniSongSync.state]，因此这里并行发起
     * 两条协程，UI 侧（曲目页）据此显示两张独立的进度卡。
     *
     * 舞萌侧沿用「有可用更新才下载，否则只检查」——songdata.json 有 13 MB，
     * 每次手点都全量下载太浪费；中二侧曲库只有 1 MB，直接全量刷新。
     *
     * @param forceMaimaiDownload 无视 [SongDataSync.updateAvailable] 直接下载舞萌曲库。
     *   更新弹窗的「立即更新」与「曲库还是空的」入口必须传 true：前者在调用前已用
     *   `dismissUpdatePrompt()` 清空该信号，后者本地根本没有曲库，若依赖它判断就会退化成
     *   「只检查不下载」——弹窗反复重现、songdata.json 永远不下，空曲库无从初始化。
     * @param showProgress 是否点亮曲目页顶部进度卡（设置页刷新不点亮）。
     */
    fun refreshAllSongData(
        context: Context,
        forceMaimaiDownload: Boolean = false,
        showProgress: Boolean = true,
    ) {
        // 两边各算一个任务：会话按「实际发起的任务数」计数，任意一边提前结束都不会误收卡
        if (showProgress) {
            SongRefreshSession.clearNotice(RefreshGame.MAIMAI)
            SongRefreshSession.clearNotice(RefreshGame.CHUNI)
            SongRefreshSession.begin(2)
        }
        scope.launch {
            var downloaded = false
            try {
                runCatching {
                    // 本地从未成功导入过曲库（版本号为空 / "0"）时也必须走下载，兜住漏传参数的调用方
                    val localVersion = SpUtil.getDataVersion()
                    val noLocalData = localVersion.isBlank() || localVersion == "0"
                    if (forceMaimaiDownload || noLocalData || SongDataSync.updateAvailable.value != null) {
                        SongDataSync.forceSync(context)
                        downloaded = true
                    } else {
                        SongDataSync.checkForUpdate()
                    }
                }
            } finally {
                if (showProgress) {
                    SongRefreshSession.endTask(
                        hasFailed = SongDataSync.state.value is SongDataSync.SyncState.Failed,
                    )
                    reportMaimaiNotice(downloaded)
                }
            }
        }
        scope.launch {
            try {
                runCatching { ChuniSongSync.forceSync(context) }
            } finally {
                if (showProgress) {
                    SongRefreshSession.endTask(
                        hasFailed = ChuniSongSync.state.value is ChuniSongSync.SyncState.Failed,
                    )
                    reportChuniNotice()
                }
            }
        }
    }

    /** 用户手动触发：刷新成绩。 */
    fun refreshRecords() {
        scope.launch { runCatching { DivingFishAccount.refreshRecords() } }
    }
}
