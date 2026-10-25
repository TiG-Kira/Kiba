package com.kira.kiba.core.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.model.AppUpdateModel
import com.kira.kiba.core.model.SongData
import com.kira.kiba.core.network.MaimaiDataRequests
import com.kira.kiba.core.repository.ChartStatsRepository
import com.kira.kiba.core.repository.SongDataRepository
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.JsonConvertToDb
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 曲库数据同步（复刻 MaimaiData 上游更新逻辑）：
 *
 * 1. 拉取 update.json 拿 dataVersion4 / dataUrl4（dx2026 数据集）；
 * 2. 与本地数据版本比对（[isNewerVersion]），有新版本才提示 / 下载；
 * 3. 下载 songdata.json → 解析入库（[SongWithChartRepository.updateDatabase]）→ 写回数据版本；
 * 4. Room LiveData 自动推送，曲目页列表无需手动刷新即更新。
 *
 * 与上游（MainActivity.checkUpdate + startDataDownload）的对应关系：
 * - [checkForUpdate] ≈ onResume 里的 checkUpdate：只检查、弹提示，不主动下载；
 * - [forceSync] ≈ startDataDownload：用户确认后下载 + 入库 + 落版本号。
 *
 * 全程状态外溢到 [state] / [updateAvailable]，UI（曲目页 / 设置页）据此展示。
 */
object SongDataSync {

    private const val TAG = "SongDataSync"

    /** 上游 update.json（与 MaimaiDataService 的 urlName 头一致）。 */
    private const val UPDATE_JSON_URL =
        "https://bucket-1256206908.cos.ap-shanghai.myqcloud.com/update.json"

    sealed class SyncState {
        data object Idle : SyncState()
        data object Checking : SyncState()
        data class Downloading(val received: Long, val total: Long) : SyncState()
        data class Done(val version: String) : SyncState()
        data class Failed(val message: String) : SyncState()
    }

    /** 曲库更新检查结果（[checkForUpdate] 的返回值形态也用于内部流转）。 */
    data class DataUpdateInfo(
        val hasUpdate: Boolean,
        val remoteVersion: String?,
        val localVersion: String,
    )

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** 检测到可用更新时的远端版本号；UI 据此弹更新提示。null = 无更新 / 已处理。 */
    private val _updateAvailable = MutableStateFlow<String?>(null)
    val updateAvailable: StateFlow<String?> = _updateAvailable.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 版本比较：上游版本形如日期串 "20261001"。
     * 两侧都能按数值解析时按数值比（避免 "1.9.0" < "1.10.0" 的字典序错误），否则退化为字典序。
     */
    fun isNewerVersion(remote: String?, local: String): Boolean {
        if (remote.isNullOrBlank()) return false
        if (local.isBlank()) return true
        val r = remote.trim()
        val l = local.trim()
        val rn = r.toLongOrNull()
        val ln = l.toLongOrNull()
        if (rn != null && ln != null) return rn > ln
        return r > l
    }

    /**
     * 检查曲库更新（只检查，不下载）。启动引导 / 用户手动检查都走这里。
     * 有新版本时写入 [updateAvailable]（UI 弹提示）；失败写入 [state]。
     */
    suspend fun checkForUpdate() {
        if (_state.value is SyncState.Checking || _state.value is SyncState.Downloading) return
        _state.value = SyncState.Checking
        val update = withContext(Dispatchers.IO) {
            runCatching { fetchUpdateInfo() }.getOrNull()
        }
        if (update == null) {
            _state.value = SyncState.Failed("无法获取更新信息（网络异常），请稍后重试")
            return
        }
        val remote = update.dataVersion4 ?: update.dataVersion3 ?: update.dataVersion2
        if (remote.isNullOrBlank()) {
            _state.value = SyncState.Failed("更新信息缺少数据版本")
            return
        }
        val local = SpUtil.getDataVersion()
        if (isNewerVersion(remote, local)) {
            _updateAvailable.value = remote
        } else {
            _updateAvailable.value = null
        }
        _state.value = SyncState.Done(local)
    }

    /** 关闭更新提示（用户点“稍后”）。 */
    fun dismissUpdatePrompt() {
        _updateAvailable.value = null
    }

    /**
     * 立即更新：下载 + 入库 + 写回版本号。
     * 下载中重复调用直接返回（[state] 守门）。
     */
    suspend fun forceSync(context: Context) {
        if (_state.value is SyncState.Downloading || _state.value is SyncState.Checking) return
        _state.value = SyncState.Checking
        val update = withContext(Dispatchers.IO) {
            runCatching { fetchUpdateInfo() }.getOrNull()
        }
        if (update == null) {
            _state.value = SyncState.Failed("无法获取更新信息（网络异常），请稍后重试")
            return
        }
        val version = update.dataVersion4 ?: update.dataVersion3 ?: update.dataVersion2
        val url = update.dataUrl4 ?: update.dataUrl3 ?: update.dataUrl2
        if (version.isNullOrBlank() || url.isNullOrBlank()) {
            _state.value = SyncState.Failed("更新信息缺少数据地址")
            return
        }
        val ok = download(context, url)
        if (!ok) return // 失败状态已写入
        val imported = importToDatabase(context)
        if (imported) {
            SpUtil.setDataVersion(version)
            _updateAvailable.value = null
            _state.value = SyncState.Done(version)
            // 曲库更新后顺带刷新谱面统计（拟合定数 / 相对难度数据源），失败不影响曲库结果
            runCatching { syncChartStats(force = true) }
        } else {
            _state.value = SyncState.Failed("曲库导入失败，请重试")
        }
    }

    // ---------- 谱面统计（chart_stats）同步 ----------

    /** chart_stats 同步最小间隔：5 天。 */
    private const val CHART_STATS_INTERVAL = 5L * 24 * 60 * 60 * 1000

    /** chart_stats 是否需要同步（从未同步或已超 5 天）。 */
    fun shouldSyncChartStats(): Boolean =
        System.currentTimeMillis() - SpUtil.getLastUpdateChartStats() > CHART_STATS_INTERVAL

    /**
     * 全量拉取水鱼 chart_stats 并入库（相对难度 / 拟合定数的数据源）。
     * 约 1.5MB、6600 行，[shouldSyncChartStats] 5 天节流；失败静默——
     * 详情页将显示「暂时缺少数据」，待同步成功后 LiveData 自动刷新。
     *
     * @param force 跳过 5 天节流（曲库刚更新时用）
     */
    suspend fun syncChartStats(force: Boolean = false) {
        if (!force && !shouldSyncChartStats()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val response = MaimaiDataRequests.getChartStatus().blockingFirst()
                val list = JsonConvertToDb.convertChatStats(response)
                if (list.isEmpty()) throw IllegalStateException("chart_stats 响应为空")
                ChartStatsRepository.getInstance(AppDataBase.getInstance().chartStatsDao())
                    .replaceAllChartStats(list)
                SpUtil.saveLastUpdateChartStats(System.currentTimeMillis())
                Log.i(TAG, "chart_stats synced: ${list.size} rows")
            }.onFailure { Log.w(TAG, "syncChartStats failed", it) }
        }
    }

    private suspend fun fetchUpdateInfo(): AppUpdateModel? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(UPDATE_JSON_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                gson.fromJson(response.body?.string(), AppUpdateModel::class.java)
            }
        }.onFailure { Log.w(TAG, "fetchUpdateInfo failed", it) }.getOrNull()
    }

    /** 下载 songdata.json 到 filesDir，带进度。 */
    private suspend fun download(context: Context, url: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("空响应")
                    val total = body.contentLength()
                    val target = File(context.filesDir, "songdata.json")
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                received += read
                                _state.value = SyncState.Downloading(received, total)
                            }
                            output.flush()
                        }
                    }
                }
                true
            }.onFailure { e ->
                Log.w(TAG, "download failed", e)
                _state.value = SyncState.Failed("曲库下载失败：${e.message}")
            }.getOrDefault(false)
        }

    /** 解析 songdata.json 入库（Room 事务整体替换，LiveData 自动推送新列表）。 */
    private suspend fun importToDatabase(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val songs: List<SongData> = SongDataRepository().getData(context)
            if (songs.isEmpty()) throw IllegalStateException("曲库文件为空或解析失败")
            SongWithChartRepository.getInstance(
                AppDataBase.getInstance().songWithChartDao()
            ).updateDatabase(songs)
        }.onFailure { Log.w(TAG, "import failed", it) }.getOrDefault(false)
    }
}
