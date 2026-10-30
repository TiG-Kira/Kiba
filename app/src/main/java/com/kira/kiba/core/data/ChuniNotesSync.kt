package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.model.ChuniDiffType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 中二节奏谱面物量同步（落雪 lxns，补 waterfish music_data 的缺口）。
 *
 * 数据源：`GET https://maimai.lxns.net/api/v0/chunithm/song/list?notes=true`
 * —— 落雪的**公共 API，不需要 token**（实测无鉴权直接 200），响应 1.4 MB 左右。
 *
 * ## 为什么要接它
 * 水鱼 music_data 的 `combo` 字段约 1/3 缺失（ULTIMA 更是 83%），曲库缺数据时
 * 谱面满连击只能显示 0，会误导用户。落雪的 `notes.total` 与之**口径完全一致**
 * （实测 4210 个谱面逐一比对，100% 相等），可以无损补齐；顺带拿到
 * TAP / HOLD / SLIDE / AIR / FLICK 物量细分（水鱼完全不提供），
 * 谱师与定数也能在数据缺失时兜底。
 *
 * ## 曲目 id
 * 落雪与水鱼的曲目 id **同源**（1366/1414 首直接相同）。落雪另有 48 首在 8000+
 * 段的重录条目（如 B.B.K.K.B.K.K. 有 id 3 与 8020 两条），我们按水鱼 id 匹配，
 * 命中的是原始 id 那一条，不受影响。
 *
 * 本同步是**增强项**：失败只记日志、返回 0，绝不阻断曲库同步。
 */
object ChuniNotesSync {

    private const val TAG = "ChuniNotesSync"

    private const val SONG_LIST_URL =
        "https://maimai.lxns.net/api/v0/chunithm/song/list?notes=true"

    /** 常规浏览器 UA：部分站点会拒绝 OkHttp 默认 UA（okhttp/x.y）。 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private data class ChartNotes(
        val total: Int,
        val tap: Int,
        val hold: Int,
        val slide: Int,
        val air: Int,
        val flick: Int,
        val levelValue: Double,
        val designer: String,
        val version: Int,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取落雪曲库 → 合并进本地 chuni_chart。
     *
     * @return 实际被补齐的谱面数（0 = 无变化或失败）。
     */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        runCatching { merge() }
            .onFailure { Log.w(TAG, "lxns notes sync failed", it) }
            .getOrDefault(0)
    }

    private suspend fun merge(): Int {
        val request = Request.Builder()
            .url(SONG_LIST_URL)
            .header("User-Agent", UA)
            .build()
        val json = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val notes = parseSongList(json)
        if (notes.isEmpty()) throw IllegalStateException("落雪曲库为空或解析失败")

        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        val charts = dao.getAllChuniChartsList()
        val updated = mutableListOf<ChuniChartEntity>()
        val worldEnd = ChuniDiffType.WORLD_END.ordinal

        for (chart in charts) {
            val perSong = notes[chart.songId] ?: continue
            val n = perSong[chart.difficultyIndex] ?: continue

            val needCombo = chart.combo <= 0 && n.total > 0
            val needNotes = !chart.hasNotesBreakdown && n.total > 0
            val needDesigner = chart.charter.isBlank() && n.designer.isNotBlank()
            // World's End 官方就没有定数，别拿落雪的 level_value 给它凭空造一个
            val needDs = chart.ds <= 0.0 && n.levelValue > 0.0 && chart.difficultyIndex != worldEnd
            // 谱面版本编码：落雪有值且本地未打标时才写入（用于 Rating 公式区分当前/非当前版本）
            val needVersion = chart.version <= 0 && n.version > 0
            if (!needCombo && !needNotes && !needDesigner && !needDs && !needVersion) continue

            updated.add(
                chart.copy(
                    combo = if (needCombo) n.total else chart.combo,
                    notesTap = if (needNotes) n.tap else chart.notesTap,
                    notesHold = if (needNotes) n.hold else chart.notesHold,
                    notesSlide = if (needNotes) n.slide else chart.notesSlide,
                    notesAir = if (needNotes) n.air else chart.notesAir,
                    notesFlick = if (needNotes) n.flick else chart.notesFlick,
                    charter = if (needDesigner) n.designer else chart.charter,
                    ds = if (needDs) n.levelValue else chart.ds,
                    version = if (needVersion) n.version else chart.version,
                )
            )
        }
        if (updated.isNotEmpty()) dao.updateChuniCharts(updated)
        Log.i(TAG, "lxns notes merged: ${updated.size}/${charts.size} charts updated")
        return updated.size
    }

    /** 解析落雪曲目列表：songId → (难度索引 → 物量)。缺 notes 的谱面直接跳过。 */
    private fun parseSongList(json: String): Map<Int, Map<Int, ChartNotes>> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        val arr = root?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("songs")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return emptyMap()

        val out = HashMap<Int, Map<Int, ChartNotes>>()
        for (i in 0 until arr.size()) {
            val elem = arr.get(i)
            if (!elem.isJsonObject) continue
            val song = elem.asJsonObject
            val id = song.lxInt("id")
            if (id <= 0) continue
            val diffArr = song.get("difficulties")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: continue

            val per = HashMap<Int, ChartNotes>()
            for (j in 0 until diffArr.size()) {
                val dElem = diffArr.get(j)
                if (!dElem.isJsonObject) continue
                val d = dElem.asJsonObject
                val diffIndex = d.lxInt("difficulty")
                val notesObj = d.get("notes")?.takeIf { it.isJsonObject }?.asJsonObject
                val total = notesObj?.lxInt("total") ?: 0
                if (total <= 0) continue
                per[diffIndex] = ChartNotes(
                    total = total,
                    tap = notesObj?.lxInt("tap") ?: 0,
                    hold = notesObj?.lxInt("hold") ?: 0,
                    slide = notesObj?.lxInt("slide") ?: 0,
                    air = notesObj?.lxInt("air") ?: 0,
                    flick = notesObj?.lxInt("flick") ?: 0,
                    levelValue = d.lxDouble("level_value"),
                    designer = d.lxString("note_designer"),
                    version = d.lxInt("version"),
                )
            }
            if (per.isNotEmpty()) out[id] = per
        }
        return out
    }

    // ── JsonObject 安全取值（定义在 object 内，避免与同包其他文件的顶层扩展重名）──

    private fun JsonObject.lxInt(name: String): Int {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0
        return runCatching { el.asInt }.getOrDefault(0)
    }

    private fun JsonObject.lxDouble(name: String): Double {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0.0
        return runCatching { el.asDouble }.getOrDefault(0.0)
    }

    private fun JsonObject.lxString(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }
}
