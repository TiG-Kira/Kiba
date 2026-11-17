package com.kira.kiba.core.listen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent 专属歌单的双通道生成调度器（「听」页 + Agent 对话共用）。
 *
 * 两条通道**互不互锁**（一条在生成时另一条照样能发起），各自有 busy 状态：
 * - **智能生成**（[startSmart]）：按口味画像选曲。听页一键入口 + 过期自动续生成。
 * - **按需求生成**（[startRequirement]）：带用户要求文本。听页「按你的要求生成歌单」
 *   与 Agent 对话内的 [generate] 工具**共用这条通道**（落的是同一份 SP/历史），
 *   通道忙时另一侧不得再次发起 —— 对话发起时 [requirementViaChat] = true，
 *   听页按钮据此显示「正在通过对话生成歌单中」。
 *
 * 每次成功落盘后 [revision] 自增：监听它的界面（听页历史列表）重载即可，无需回调穿层。
 */
object AgentPlaylistJobs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 智能生成通道忙。 */
    var smartBusy by mutableStateOf(false)
        private set

    /** 按需求生成通道忙（听页输入框入口与对话工具共用）。 */
    var requirementBusy by mutableStateOf(false)
        private set

    /** 当前按需求生成是否由 Agent 对话发起（听页文案用）。 */
    var requirementViaChat by mutableStateOf(false)
        private set

    /** 生成成功落盘次数（含失败不增）：观察者据此重载历史列表。 */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * 发起一次智能生成（已在跑则忽略）。完成后 [onResult] 在主线程回调。
     * 数据源缺省时从 Room / SP 现取（Agent 对话侧没有现成列表就传 null）。
     */
    fun startSmart(
        context: Context,
        songs: List<SongWithChartsEntity>? = null,
        records: List<RecordEntity>? = null,
        favoriteIds: List<String>? = null,
        onResult: (Result<AgentPlaylist.Saved>) -> Unit = {},
    ) {
        if (smartBusy) return
        smartBusy = true
        scope.launch {
            val result = runGeneration(SMART) { data ->
                AgentPlaylist.refreshWithProfile(
                    context, data.songs, data.records, data.favoriteIds,
                )
            }
            onResult(result)
        }
    }

    /**
     * 发起一次按需求生成（通道忙时回调失败结果，不排队）。完成后 [onResult] 在主线程回调。
     *
     * @param viaChat true = 由 Agent 对话发起（听页按钮显示「正在通过对话生成歌单中」）
     */
    fun startRequirement(
        context: Context,
        requirement: String,
        viaChat: Boolean = false,
        songs: List<SongWithChartsEntity>? = null,
        records: List<RecordEntity>? = null,
        favoriteIds: List<String>? = null,
        onResult: (Result<AgentPlaylist.Saved>) -> Unit = {},
    ) {
        val req = requirement.trim()
        if (requirementBusy) {
            onResult(Result.failure(IllegalStateException("已有一张歌单正在生成中")))
            return
        }
        requirementBusy = true
        requirementViaChat = viaChat
        scope.launch {
            val result = runGeneration(REQUIREMENT) { data ->
                AgentPlaylist.refreshWithRequirements(context, req, data.songs, data.records, data.favoriteIds)
            }
            onResult(result)
        }
    }

    /** 通道代号：只清自己这条的 busy，不动另一条。 */
    private const val SMART = 0
    private const val REQUIREMENT = 1

    /** 通道内实际执行：补齐缺省数据源 → 生成 → 落 revision / 清本通道状态。 */
    private suspend fun runGeneration(
        channel: Int,
        block: suspend (PlaylistData) -> AgentPlaylist.Saved,
    ): Result<AgentPlaylist.Saved> {
        val result = runCatching {
            block(loadData())
        }
        if (result.isSuccess) revision++
        if (channel == SMART) smartBusy = false else {
            requirementBusy = false
            requirementViaChat = false
        }
        return result
    }

    private suspend fun loadData(): PlaylistData = withContext(Dispatchers.IO) {
        val db = AppDataBase.getInstance()
        val songs = db.songDao().getAllSongsList().filter { it.genre != "宴会場" }
        val chartsBySong = db.chartDao().getAllChartsList().groupBy { it.songId }
        val swcs = songs.map { SongWithChartsEntity(it, chartsBySong[it.id].orEmpty()) }
        PlaylistData(
            songs = swcs,
            records = db.recordDao().getAllRecordList(),
            favoriteIds = SpUtil.getFavIds(),
        )
    }

    private class PlaylistData(
        val songs: List<SongWithChartsEntity>,
        val records: List<RecordEntity>,
        val favoriteIds: List<String>,
    )
}
