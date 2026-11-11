package com.kira.kiba.core.account

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 舞萌 DX Rating 更新历史（本地记录，画趋势曲线用）。
 *
 * 采集口径：所有成绩通道（水鱼 / 落雪 / 机台 VPN / 二维码导入）最终都会走
 * [DivingFishAccount.refreshRecords] 并在成功后算出官方口径的 B50 Rating——
 * 在那两个成功落点调 [record]。**Rating 没变就不记**：启动恢复、下拉刷新、
 * 上传后没跨过 B50 门槛都不该在曲线上留一串平点。
 *
 * 「下降 = 新版本初始分数」：官方换版本时新曲 Best 15 清空重算，Rating 大概率回落，
 * 这不是用户退步。下降点打 `dropped` 标记，曲线在该点画分割符 + 「新版本初始分数」提示。
 * 严格说下降也可能有其他原因，但 B50 口径下回落几乎只有版本更替一种解释，
 * 且该解释对用户最有行动价值（曲线照常分段展示，历史不被抹掉）。
 */
object RatingHistory {

    data class Entry(
        /** 记录时刻（epoch ms）。 */
        val ts: Long = 0L,
        /** 该次更新后的 DX Rating（官方 B50 口径）。 */
        val rating: Int = 0,
        /** 相比上一条降低了（按新版本初始分数处理，曲线画分割符）。 */
        val dropped: Boolean = false,
    )

    /**
     * 条目上限。B50 页要画完整曲线，但 Canvas 上几百个点已经密到没法点；
     * 200 条（按「变化才记」的口径是数个月的量）到顶丢最老的。
     */
    const val MAX_ENTRIES = 200

    private val gson = Gson()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = SpUtil.getMaimaiRatingHistoryJson()
        if (json.isBlank()) return
        runCatching {
            val type = object : TypeToken<List<Entry>>() {}.type
            _entries.value = gson.fromJson<List<Entry>>(json, type)
                ?.filter { it.rating > 0 }
                ?: emptyList()
        }
    }

    /**
     * 记一次更新后的 Rating。与最后一条相同值时不记；降低时打 [Entry.dropped]。
     *
     * @return true = 实际落了一条新记录
     */
    fun record(rating: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (rating <= 0) return false
        ensureLoaded()
        val list = _entries.value
        val last = list.lastOrNull()
        if (last != null && last.rating == rating) return false
        val entry = Entry(ts = now, rating = rating, dropped = last != null && rating < last.rating)
        _entries.value = (list + entry).takeLast(MAX_ENTRIES)
        persist()
        return true
    }

    private fun persist() {
        runCatching { SpUtil.setMaimaiRatingHistoryJson(gson.toJson(_entries.value)) }
    }
}
