package com.kira.kiba.core.stats

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进度明细：某个分类（版本 / 等级）下、指定难度的歌曲列表。
 *
 * 排序规则：未完成在前（按定数从高到低），已完成的垫底（带「已完成」标记）。
 */
object ProgressDetail {

    /** 分类维度。 */
    enum class GroupType { VERSION, LEVEL }

    /** 判定筛选。 */
    enum class JudgeFilter(val label: String) {
        ALL("全部"),
        UNPLAYED("未游玩"),
        PLAYED("已游玩"),
        FC("极"),
        SSS("将"),
        AP("神"),
    }

    /** 一条明细（歌曲 + 该难度谱面 + 本地成绩）。 */
    data class Item(
        val song: SongDataEntity,
        val chart: ChartEntity,
        val record: RecordEntity?,
    ) {
        val title: String get() = song.title
        val artist: String get() = song.artist
        val ds: Double get() = chart.ds
        val level: String get() = chart.level
        val difficulty: DifficultyType get() = chart.difficultyType
        val isDone: Boolean get() = record != null

        /** 是否满足当前筛选下的「已完成」判定。 */
        fun satisfies(filter: JudgeFilter): Boolean {
            val r = record
            return when (filter) {
                JudgeFilter.ALL -> r != null
                JudgeFilter.UNPLAYED -> r == null
                JudgeFilter.PLAYED -> r != null
                JudgeFilter.FC -> r != null && r.isFc
                JudgeFilter.SSS -> r != null && r.isSss
                JudgeFilter.AP -> r != null && r.isAp
            }
        }
    }

    /**
     * 载入明细。
     * @param groupType 分类维度（版本 / 等级）
     * @param groupKey 分类键（版本名 或 等级字符串）
     * @param difficulty 难度筛选（null = 不限）
     * @param filter 判定筛选
     */
    suspend fun load(
        groupType: GroupType,
        groupKey: String,
        difficulty: DifficultyType?,
        filter: JudgeFilter,
    ): List<Item> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val songs = db.songDao().getAllSongsList()
            val charts = db.chartDao().getAllChartsList()
            val records = db.recordDao().getAllRecordList()
                .associateBy { it.songId to it.levelIndex }

            val songById = songs.associateBy { it.id }
            val items = charts.mapNotNull { chart ->
                val song = songById[chart.songId] ?: return@mapNotNull null
                // 分类过滤
                val inGroup = when (groupType) {
                    GroupType.VERSION -> song.from == groupKey
                    GroupType.LEVEL -> chart.level == groupKey
                }
                if (!inGroup) return@mapNotNull null
                if (difficulty != null && chart.difficultyType != difficulty) return@mapNotNull null
                Item(
                    song = song,
                    chart = chart,
                    record = records[chart.songId to chart.difficultyType.ordinal],
                )
            }

            // 判定筛选：不符合条件的直接排除（未游玩筛选只留没成绩的）
            val filtered = items.filter { item ->
                when (filter) {
                    JudgeFilter.ALL -> true
                    else -> item.satisfies(filter)
                }
            }

            // 排序：未完成在前（定数高到低），已完成的垫底
            filtered.sortedWith(
                compareBy(
                    { if (it.satisfies(filter)) 1 else 0 },
                    { -it.ds },
                    { it.title },
                )
            )
        }.getOrDefault(emptyList())
    }
}
