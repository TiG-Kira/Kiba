package com.kira.kiba.core.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.repository.ChuniSongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 中二节奏曲库同步（对标 [SongDataSync]，但走水鱼 chunithmprober/music_data）。
 *
 * 与舞萌不同：中二节奏没有 update.json 版本号，music_data 是一次性全量 JSON，
 * 故这里不做版本比较，直接「拉取 → 解析 → 入库」，版本字段复用 dataVersion 思路存最后同步时间。
 */
object ChuniSongSync {

    private const val TAG = "ChuniSongSync"

    /** 中二节奏全量歌曲数据（水鱼 chunithmprober/music_data）。 */
    private const val MUSIC_DATA_URL =
        "https://www.diving-fish.com/api/chunithmprober/music_data"

    sealed class SyncState {
        data object Idle : SyncState()
        data object Syncing : SyncState()
        data class Done(val count: Int) : SyncState()
        data class Failed(val message: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 最后成功同步的版本标识（用当前日期串，便于展示「曲库版本」）。 */
    private const val KEY_CHUNI_DATA_VERSION = "chuni_data_version"

    fun getLocalVersion(): String = SpUtil.getChuniDataVersion()

    fun isEmpty(): Boolean = SpUtil.getChuniDataVersion().isBlank()

    /** 立即同步：下载 music_data → 解析 → 入库。重复调用由 [SyncState] 守门。 */
    suspend fun forceSync(context: Context) {
        if (_state.value is SyncState.Syncing) return
        _state.value = SyncState.Syncing
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(MUSIC_DATA_URL).build()
                val json = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
                val (songs, charts) = parseMusicData(json)
                if (songs.isEmpty()) throw IllegalStateException("曲库数据为空或解析失败")
                val ok = ChuniSongWithChartRepository.getInstance(
                    AppDataBase.getInstance().chuniSongWithChartDao()
                ).updateDatabase(songs, charts)
                if (!ok) throw IllegalStateException("曲库入库失败")
                val version = java.time.LocalDate.now().toString()
                SpUtil.setChuniDataVersion(version)
                songs.size
            }.getOrElse { e ->
                Log.w(TAG, "forceSync failed", e)
                _state.value = SyncState.Failed(e.message ?: "同步失败")
                return@withContext -1
            }
        }
        if (result >= 0) _state.value = SyncState.Done(result)
    }

    /**
     * 解析 chunithmprober/music_data JSON 数组。
     * 每个元素：id / title / ds[5] / level[5] / cids[5] / charts[5]{combo,charter} / basic_info{title,artist,genre,bpm,from}。
     */
    private fun parseMusicData(json: String): Pair<List<ChuniSongEntity>, List<ChuniChartEntity>> {
        val root = JsonParser.parseString(json)
        val array = if (root is JsonArray) root else JsonArray()
        val songs = mutableListOf<ChuniSongEntity>()
        val charts = mutableListOf<ChuniChartEntity>()
        for (elem in array) {
            if (elem !is JsonObject) continue
            val id = elem.get("id")?.takeIf { !it.isJsonNull }?.asInt ?: continue
            val basic = elem.getAsJsonObject("basic_info")
            val title = basic?.get("title")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val artist = basic?.get("artist")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val genre = basic?.get("genre")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val bpm = basic?.get("bpm")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val from = basic?.get("from")?.takeIf { !it.isJsonNull }?.asString ?: ""
            songs.add(
                ChuniSongEntity(
                    id = id, title = title, titleKana = "",
                    artist = artist, imageUrl = "", genre = genre, bpm = bpm, from = from,
                )
            )
            val dsArr = elem.getAsJsonArray("ds")
            val levelArr = elem.getAsJsonArray("level")
            val chartsArr = elem.getAsJsonArray("charts")
            val count = minOf(dsArr?.size() ?: 0, levelArr?.size() ?: 0, chartsArr?.size() ?: 0, 5)
            for (i in 0 until count) {
                val ds = dsArr!![i].takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                val level = levelArr!![i].takeIf { !it.isJsonNull }?.asString ?: ""
                val chartObj = chartsArr!![i].takeIf { it is JsonObject } as? JsonObject
                val combo = chartObj?.get("combo")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val charter = chartObj?.get("charter")?.takeIf { !it.isJsonNull }?.asString ?: ""
                charts.add(
                    ChuniChartEntity(
                        songId = id,
                        difficultyIndex = i,
                        difficulty = ChuniDiffType.fromIndex(i).label,
                        level = level,
                        levelLabel = null,
                        ds = ds,
                        charter = charter,
                        combo = combo,
                    )
                )
            }
        }
        return songs to charts
    }
}
