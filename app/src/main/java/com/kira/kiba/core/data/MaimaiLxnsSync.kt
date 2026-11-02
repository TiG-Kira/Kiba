package com.kira.kiba.core.data

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.best50.Best50ImageBuilder
import com.kira.kiba.core.utils.Constants
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.network.LxnsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 舞萌 DX 成绩同步（落雪 LXNS 个人 API）。
 *
 * ## 取数
 * - `GET /user/maimai/player` → 昵称 / DX Rating；
 * - `GET /user/maimai/player/scores` → 该账号在落雪缓存的**全量成绩**（Score[]）。
 *
 * 落雪是全量返回，不像水鱼 OAuth 的 `player/records` 可能只有 Top 部分；Best 50 仍由
 * 本地按官方口径计算（旧曲 35 + 新曲 15，见 [DivingFishAccount]），与上游 MaimaiData 一致。
 *
 * ## 字段映射注意
 * - 落雪 `Score.dx_rating` 是**浮点**，本地 [RecordEntity.ra] 是 Int —— 取整（官方 B50
 *   实际累加就是整数口径，机台显示的 Ra 也是整数）；缺失或为 0 时（谱面未结算等）
 *   用本地定数 × 达成率补算，避免整表 ra=0 把 B50 Rating 拉穿；
 * - 落雪**不返回** `level_label`（水鱼的 "13+" 那种标签），统一用 `level` 字段填充，
 *   保证列表/详情页不会因为空标签排版塌掉；
 * - `ds`（定数）落雪单条成绩也不给，从本地 `chart` 表按 (songId, levelIndex, type) 取；
 *   曲库没同步时补 0，不阻断成绩入库。
 *
 * ## 曲目 id 映射（核心！两套体系不能直接互用）
 * - 本地曲库 / 水鱼体系：**谱面类型由曲目 id 决定**——SD 谱 id < 10000，
 *   DX 谱（含 SD 曲的 DX 觉醒谱）id = SD id + 10000（10070+），宴谱 ≥ 100000 原样；
 * - 落雪体系：同一曲目的 SD / DX 谱共用一个曲目 id（官方 id % 10000，全部 < 10000，
 *   宴谱除外），用 `type` 字段（standard / dx）区分谱面；
 * - 因此：type=standard → 本地 id 原样；type=dx → 本地 id + 10000；宴谱（≥100000）原样。
 *   （实测校验：落雪 2025 = Hurtling Boys，本地曲库 11920 ✓；落雪 70 = ジングルベル
 *   standard → 本地 70，dx → 本地 10070 ✓）
 * - `type` 落库前统一转本地口径的大写 "SD" / "DX"（曲库 chart 表与 UI 都按大写比较）。
 */
object MaimaiLxnsSync {

    private const val TAG = "MaimaiLxnsSync"

    private const val PATH_PLAYER = "/user/maimai/player"
    private const val PATH_SCORES = "/user/maimai/player/scores"

    data class Result(
        val nickname: String,
        val rating: Int,
        val records: List<RecordEntity>,
    )

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        if (!LxnsAccount.isBound()) {
            throw IllegalStateException("尚未绑定落雪 API Key，请到「设置 → 查分器数据源」绑定")
        }

        val player = runCatching { LxnsApi.getObject(PATH_PLAYER) }
            .onFailure { Log.i(TAG, "lxns maimai player unavailable: ${it.message}") }
            .getOrNull()
        val nickname = player?.str("name").orEmpty()
        val serverRating = player?.int("rating") ?: 0

        // 兼容两种成功形态：顶层数组，或 {scores:[...]} 包裹
        val raw = LxnsApi.get(PATH_SCORES)
        val arr: JsonArray = when (raw) {
            is JsonArray -> raw
            is JsonObject -> raw.get("scores") as? JsonArray ?: JsonArray()
            else -> JsonArray()
        }
        // 空响应**不能**当「没有成绩」直接清库：replaceAllRecord 会抹掉本地已有成绩，
        // 而落雪侧「Key 权限不足 / 账号还没上传」都会表现为空。宁可报错让用户重试。
        if (arr.size() == 0) {
            throw IllegalStateException("落雪没有返回舞萌成绩（账号可能还没上传，或 Key 权限不足）")
        }

        // 曲库补齐定数 / 标题（曲库未同步时降级为 0 / 落雪歌名）
        val songs = runCatching {
            AppDataBase.getInstance().songDao().getAllSongsList().associateBy { it.id }
        }.getOrDefault(emptyMap())
        val charts = runCatching {
            AppDataBase.getInstance().chartDao().getAllChartsList()
                // 键：songId + 难度索引 + 谱面类型（standard / dx 同 id 不同谱）
                .associateBy { chart -> chartKey(chart.songId, chart.difficultyType.ordinal, chart.type) }
        }.getOrDefault(emptyMap())

        val records = ArrayList<RecordEntity>(arr.size())
        for (i in 0 until arr.size()) {
            val elem = arr.get(i)
            if (!elem.isJsonObject) continue
            mapScore(elem.asJsonObject, charts, songs)?.let { records.add(it) }
        }

        // 全 0 保护（对齐中二侧 ChuniLxnsSync）：ra 整表为 0 说明数据形态异常，
        // 不能入库——replaceAllRecord 会先清掉本地已有成绩，B50 直接开天窗。
        if (records.isNotEmpty() && records.all { it.ra <= 0 }) {
            throw IllegalStateException("落雪返回的成绩全部缺少 DX Rating，疑似数据异常，已放弃入库")
        }

        Log.i(TAG, "lxns maimai fetched: ${records.size} records, serverRating=$serverRating")
        Result(nickname = nickname, rating = serverRating, records = records)
    }

    /**
     * 单条落雪成绩 → 本地 [RecordEntity]。
     *
     * - 曲目 id 映射：DX 谱 +10000、宴谱原样、其余原样（见类注释「曲目 id 映射」）；
     * - type 统一转本地大写口径（SD / DX）；
     * - dx_rating 缺失 / 未结算（=0）时用本地定数补算，避免 ra 整表为 0；
     * - 难度索引不在 0..4（异常数据）返回 null 丢弃。
     * 纯函数无 Android 依赖，便于单元测试覆盖映射口径。
     */
    internal fun mapScore(
        o: JsonObject,
        charts: Map<Long, ChartEntity>,
        songs: Map<Int, SongDataEntity>,
    ): RecordEntity? {
        // 落雪 type 统一转本地大写口径（SD / DX），后续 chartKey / UI 全按大写比较
        val type = o.str("type").ifBlank { "standard" }
            .let { if (it.equals("dx", true)) Constants.CHART_TYPE_DX else Constants.CHART_TYPE_SD }
        // 落雪曲目 id → 本地曲库 id：DX 谱 +10000；宴谱（≥100000）两套体系同 id，原样
        val rawId = o.int("id")
        val songId = when {
            rawId >= 100000 -> rawId
            type == Constants.CHART_TYPE_DX -> rawId + 10000
            else -> rawId
        }
        val levelIndex = o.int("level_index")
        if (levelIndex !in 0..4) return null
        val level = o.str("level")
        val chart = charts[chartKey(songId, levelIndex, type)]
        val achievements = o.dbl("achievements")
        // dx_rating 缺失 / 未结算（=0）时用本地定数补算，避免 ra 整表为 0
        val dxRating = o.dbl("dx_rating").toInt()
        val ra = if (dxRating > 0) dxRating
        else if (achievements > 0.0 && (chart?.ds ?: 0.0) > 0.0) {
            Best50ImageBuilder.computeRa(chart!!.ds, achievements, spp = true)
        } else 0
        return RecordEntity(
            id = 0,
            achievements = achievements,
            ds = chart?.ds ?: 0.0,
            dxScore = o.int("dx_score"),
            fc = o.str("fc"),
            fs = o.str("fs"),
            level = level,
            levelIndex = levelIndex,
            // 落雪不返回 level_label，用 level 顶替，避免列表出现空白标签
            levelLabel = level,
            ra = ra,
            rate = o.str("rate"),
            songId = songId,
            title = songs[songId]?.title ?: o.str("song_name"),
            type = type,
        )
    }

    /** 谱面复合键：songId + 难度 + 类型（用 Long 打包，避免 kotlin.Pair 被 R8 裁掉的老坑）。 */
    internal fun chartKey(songId: Int, levelIndex: Int, type: String): Long {
        val typeBit = if (type == Constants.CHART_TYPE_DX) 1L else 0L
        return (songId.toLong() shl 16) or (levelIndex.toLong() shl 8) or typeBit
    }

    private fun JsonObject.int(name: String): Int {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0
        return runCatching { el.asInt }.getOrDefault(0)
    }

    private fun JsonObject.dbl(name: String): Double {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return 0.0
        return runCatching { el.asDouble }.getOrDefault(0.0)
    }

    private fun JsonObject.str(name: String): String {
        val el = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive } ?: return ""
        return runCatching { el.asString }.getOrDefault("")
    }
}
