package com.kira.kiba.core.state

import android.content.Context
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.data.SongDataSync
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
    fun checkSongUpdate() {
        scope.launch { runCatching { SongDataSync.checkForUpdate() } }
    }

    /** 用户手动触发：立即更新曲库（下载 + 入库 + 写版本号）。 */
    fun refreshSongData(context: Context) {
        scope.launch { runCatching { SongDataSync.forceSync(context) } }
    }

    /** 用户手动触发：刷新成绩。 */
    fun refreshRecords() {
        scope.launch { runCatching { DivingFishAccount.refreshRecords() } }
    }
}
