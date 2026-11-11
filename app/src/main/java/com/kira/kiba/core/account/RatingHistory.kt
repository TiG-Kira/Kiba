package com.kira.kiba.core.account

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Rating 更新历史（本地记录，画趋势曲线用），**按「游戏 × 查分器」分桶**。
 *
 * ## 为什么要分桶
 * 水鱼和落雪是两套 Rating 口径（同一份成绩两边算出来的总分可能差几分），
 * 混在一条曲线上会出现莫名其妙的台阶；中二节奏更是另一套公式（÷50、Best30+B20）。
 * 所以曲线必须**跟着当前数据源走**：水鱼源看水鱼的历史，落雪源看落雪的历史，
 * 中二节奏看中二自己的历史。
 *
 * 桶键 = `"game:source"`，例如 `maimai:waterfish` / `chunithm:lxns`。
 *
 * ## 采集口径
 * 所有成绩通道（水鱼 / 落雪 / 机台 VPN / 二维码导入）最终都会在同步成功后算出
 * Rating，并在那里调 [record]。**Rating 没变就不记**：启动恢复、下拉刷新、
 * 上传后没跨过门槛都不该在曲线上留一串平点。
 *
 * ## 「下降 = 新版本初始分数」
 * 官方换版本时新曲 Best 15 清空重算，Rating 大概率回落，这不是用户退步。
 * 下降点打 `dropped` 标记，曲线在该点画分割符 + 「新版本初始分数」提示。
 */
object RatingHistory {

    data class Entry(
        /** 记录时刻（epoch ms）。 */
        val ts: Long = 0L,
        /**
         * 该次更新后的 Rating。
         *
         * 用 Double 是因为两套口径精度不同：舞萌是整数（16000），
         * 中二节奏是两位小数（16.23）。展示层按游戏格式化，不做隐式取整。
         */
        val rating: Double = 0.0,
        /** 相比上一条降低了（按新版本初始分数处理，曲线画分割符）。 */
        val dropped: Boolean = false,
    )

    /**
     * 单桶条目上限。曲线点太密已经没法点；200 条（按「变化才记」的口径是数个月的量）
     * 到顶丢最老的。
     */
    const val MAX_ENTRIES = 200

    private val gson = Gson()

    /** 全部桶的当前快照（UI 只读）。 */
    private val _all = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val all: StateFlow<Map<String, List<Entry>>> = _all.asStateFlow()

    /** 每个桶一条独立的 StateFlow（UI 订阅单条曲线时不会因别桶变化而重组）。 */
    private val perKey = ConcurrentHashMap<String, MutableStateFlow<List<Entry>>>()

    private var loaded = false

    /** 桶键：`游戏:数据源`。 */
    fun keyOf(game: GameType, source: ScoreSource): String =
        "${game.name.lowercase(Locale.ROOT)}:${source.key}"

    /** 订阅某一桶的历史（首次访问会自动加载并做旧数据迁移）。 */
    fun entries(game: GameType, source: ScoreSource): StateFlow<List<Entry>> {
        ensureLoaded()
        val key = keyOf(game, source)
        return perKey.getOrPut(key) { MutableStateFlow(_all.value[key].orEmpty()) }
    }

    /** 同步取某一桶的历史（非 Compose 场景用）。 */
    fun snapshot(game: GameType, source: ScoreSource): List<Entry> {
        ensureLoaded()
        return _all.value[keyOf(game, source)].orEmpty()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val v2 = SpUtil.getRatingHistoryBySourceJson()
        if (v2.isNotBlank()) {
            runCatching {
                val type = object : TypeToken<Map<String, List<Entry>>>() {}.type
                val map = gson.fromJson<Map<String, List<Entry>>>(v2, type).orEmpty()
                publish(map.mapValues { (_, list) -> list.filter { it.rating > 0 } })
                return
            }
        }
        // ── 旧版迁移：v2.5 之前只有一条「舞萌曲线」，不区分数据源 ──
        // 无法回溯它到底是水鱼还是落雪记的，按「升级瞬间舞萌所选源」归属：
        // 那正是这条曲线最近一直在更新的通道，对两类用户都能接上最近的走势。
        val legacy = SpUtil.getMaimaiRatingHistoryJson()
        if (legacy.isBlank()) return
        runCatching {
            val type = object : TypeToken<List<Entry>>() {}.type
            val list = gson.fromJson<List<Entry>>(legacy, type).orEmpty().filter { it.rating > 0 }
            if (list.isEmpty()) return
            val source = SpUtil.getScoreSource(GameType.MAIMAI)
            publish(mapOf(keyOf(GameType.MAIMAI, source) to list))
            persist()
        }
    }

    /**
     * 记一次更新后的 Rating。与最后一条相同值时不记；降低时打 [Entry.dropped]。
     *
     * @param rating 该源该游戏的 Rating（舞萌整数 / 中二两位小数）
     * @return true = 实际落了一条新记录
     */
    fun record(
        game: GameType,
        source: ScoreSource,
        rating: Double,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (rating <= 0.0) return false
        ensureLoaded()
        val key = keyOf(game, source)
        val list = _all.value[key].orEmpty()
        val last = list.lastOrNull()
        // 浮点比较留一点容差：中二 Rating 两位小数，算出来的值可能带尾差
        if (last != null && kotlin.math.abs(last.rating - rating) < 0.005) return false
        val entry = Entry(ts = now, rating = rating, dropped = last != null && rating < last.rating)
        val next = (list + entry).takeLast(MAX_ENTRIES)
        _all.value = _all.value + (key to next)
        perKey[key]?.value = next
        persist()
        return true
    }

    private fun publish(map: Map<String, List<Entry>>) {
        _all.value = map
        map.forEach { (key, list) ->
            perKey.getOrPut(key) { MutableStateFlow(emptyList()) }.value = list
        }
    }

    private fun persist() {
        runCatching { SpUtil.setRatingHistoryBySourceJson(gson.toJson(_all.value)) }
    }
}

/**
 * Rating 数值格式化：舞萌取整（16000），中二保留两位小数（16.23）。
 * 曲线标注 / 点击提示 / 卡片副标题共用同一条口径。
 */
fun formatRatingValue(game: GameType, rating: Double): String = when (game) {
    GameType.CHUNITHM -> String.format(Locale.ROOT, "%.2f", rating)
    else -> rating.toInt().toString()
}
