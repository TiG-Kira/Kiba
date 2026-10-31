package com.kira.kiba.core.data

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.dao.ChartVersionRow
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.model.ChuniDiffType
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 中二节奏**本地口径**统计：Rating + 非当前 TOP30 / 当前 TOP20 分池。
 *
 * ## 为什么不再依赖同步时算好的缓存值
 * 1.7.x 起 Rating 与两个枠的均值只在 [ChuniRecordSync.refresh] 里算一次并写进 SpUtil。
 * 但版本标记（`chuni_chart.version`）是**曲库同步**才有的：只要成绩同步早于曲库打标
 * （首次安装的常见顺序）、或曲库重导入把版本清零，那一刻算出来的「当前版本 TOP20 均值」
 * 就是 0.00，并且会一直躺在 SharedPreferences 里，直到用户再手动同步一次成绩——
 * Best 页于是长期显示「当前 TOP20 均值 0.00」。
 *
 * 现在统一改成：**展示时从 Room 现算**（[compute]），曲库同步完也重算一次回写
 * （[computeAndPersist]），页面与徽章永远同一套数。
 *
 * 计算本身是纯函数（[build]），不依赖 Android，可直接单测。
 */
object ChuniStats {

    /**
     * 统计结果。
     *
     * @param rating 官方 /50 口径 Rating（两位小数）；无版本信息时退化为 best30 均值。
     * @param nonCurrent 非当前版本 TOP30（ra 降序）。
     * @param current 当前版本 TOP20（ra 降序）。
     * @param currentVersion 曲库里最大的有效版本编码；<= 0 = 曲库没有版本标记。
     * @param recordCount 参与统计的成绩条数（已排除 Recent 枠与 World's End）。
     */
    data class Result(
        val rating: Double,
        val nonCurrent: List<ChuniRecordEntity>,
        val current: List<ChuniRecordEntity>,
        val currentVersion: Int,
        val recordCount: Int,
    ) {
        /** 曲库是否带版本标记（否则「当前 / 非当前」无从区分，不能拿 0.00 当均值展示）。 */
        val hasVersionInfo: Boolean get() = currentVersion > 0

        val nonCurrentAverage: Double get() = ChuniRating.averageOf(nonCurrent.map { it.ra })

        val currentAverage: Double get() = ChuniRating.averageOf(current.map { it.ra })

        val isEmpty: Boolean get() = nonCurrent.isEmpty() && current.isEmpty()
    }

    /** 从 Room 读成绩 + 谱面版本并计算（IO 线程）。 */
    suspend fun compute(): Result = withContext(Dispatchers.IO) {
        val dao = AppDataBase.getInstance().chuniSongWithChartDao()
        build(
            records = dao.getAllChuniRecordList(),
            versions = dao.getChartVersions(),
        )
    }

    /** 计算并回写 SpUtil（我页面的 Rating 徽章等仍读缓存），供曲库同步结束后调用。 */
    suspend fun computeAndPersist(): Result = compute().also { persist(it) }

    /**
     * 纯计算：成绩 + 谱面版本 → Rating / 两个枠。
     *
     * 参与统计的成绩：**排除 Recent 枠**（水鱼 r10，2026 后恒空；混进来会把最近一次手滑
     * 的低分算进历史最佳）与 **World's End 谱面**（官方不参与 Rating）。
     * 若整张表都被标记为 Recent（早期数据异常），退化为不过滤 Recent，避免 Best 页直接空掉。
     */
    fun build(records: List<ChuniRecordEntity>, versions: List<ChartVersionRow>): Result {
        val worldEnd = ChuniDiffType.WORLD_END.ordinal
        val playable = records.filter { it.levelIndex != worldEnd }
        val best = playable.filter { !it.isRecent }.ifEmpty { playable }
        val versionMap = versions.associate { ChuniRating.chartKey(it.songId, it.difficultyIndex) to it.version }
        val currentVersion = versions.maxOfOrNull { it.version } ?: 0

        val split = ChuniRating.split(
            items = best,
            currentVersion = currentVersion,
            raOf = { it.ra },
            versionOf = { versionMap[ChuniRating.chartKey(it.songId, it.levelIndex)] ?: 0 },
            isWorldEndOf = { it.levelIndex == worldEnd },
        )

        val rating = if (currentVersion <= 0) {
            // 无版本信息：官方 30 + 20 无从分池，退回 best30 均值兜底（不把 Rating 砍没）
            ChuniRating.bestAverage(best.map { it.ra })
        } else {
            ChuniRating.ratingOf(
                split.nonCurrent.map { it.ra },
                split.current.map { it.ra },
            )
        }

        return Result(
            rating = rating,
            nonCurrent = split.nonCurrent,
            current = split.current,
            currentVersion = currentVersion,
            recordCount = best.size,
        )
    }

    /** 把统计结果写回 SpUtil（我页面徽章 / 详情页等处读缓存）。 */
    fun persist(result: Result) {
        SpUtil.setChuniRating(result.rating)
        SpUtil.setChuniNonCurrentAvg(result.nonCurrentAverage)
        SpUtil.setChuniCurrentAvg(result.currentAverage)
    }
}
