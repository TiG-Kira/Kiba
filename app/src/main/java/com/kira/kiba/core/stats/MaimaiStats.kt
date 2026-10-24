package com.kira.kiba.core.stats

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 舞萌本地统计（版本进度 / 等级进度）。
 * 数据源：Room 曲库 + 本地成绩缓存，全部可在离线状态下计算。
 */
object MaimaiStats {

    /**
     * 进度条目（对齐上游 MaimaiData checklist 的判定维度）：
     * - [played] 已游玩
     * - [fc] 极（Full Combo）
     * - [sss] 将（SSS 及以上，含 SSS+）
     * - [ap] 神（AP / AP+）
     * - [fdx] FDX（Full Combo DX / FULL SYNC）
     * - 细档：[geS] >=S、[geSP] >=S+、[geSS] >=SS、[geSSP] >=SS+、[geSSS] >=SSS、[sssp] SSS+
     */
    data class ProgressItem(
        val key: String,
        val total: Int,
        val played: Int,
        val fc: Int = 0,
        val sss: Int = 0,
        val ap: Int = 0,
        val fdx: Int = 0,
        val geS: Int = 0,
        val geSP: Int = 0,
        val geSS: Int = 0,
        val geSSP: Int = 0,
        val geSSS: Int = 0,
        val sssp: Int = 0,
    ) {
        val ratio: Float get() = if (total == 0) 0f else played.toFloat() / total
    }

    /** 版本进度：按 `from`（添加版本）分组，歌曲粒度统计（原版 VersionCheck 口径）。 */
    suspend fun versionProgress(): List<ProgressItem> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val songs = db.songDao().getAllSongsList().filter { it.genre != "宴会場" }
            val records = db.recordDao().getAllRecordList().filter { it.songId > 0 }
            val bySong = records.groupBy { it.songId }
            songs.groupBy { it.from }
                .map { (version, list) ->
                    val ids = list.map { it.id }.toSet()
                    val songRecords = ids.mapNotNull { bySong[it] }
                    ProgressItem(
                        key = version,
                        total = list.size,
                        played = songRecords.count { rs -> rs.isNotEmpty() },
                        fc = songRecords.count { rs -> rs.any { it.isFc } },
                        sss = songRecords.count { rs -> rs.any { it.isSss } },
                        ap = songRecords.count { rs -> rs.any { it.isAp } },
                        fdx = songRecords.count { rs -> rs.any { it.isFdx } },
                        geS = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.S) } },
                        geSP = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.SP) } },
                        geSS = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.SS) } },
                        geSSP = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.SSP) } },
                        geSSS = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.SSS) } },
                        sssp = songRecords.count { rs -> rs.any { it.reaches(JudgeTier.SSSP) } },
                    )
                }
                .sortedBy { it.key }
        }.getOrDefault(emptyList())
    }

    /** 等级进度：按谱面等级（"13" / "13+" / "14"）分组，谱面粒度统计。 */
    suspend fun levelProgress(): List<ProgressItem> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val charts = db.chartDao().getAllChartsList()
                .filter {
                    it.difficultyType != DifficultyType.UTAGE &&
                        it.difficultyType != DifficultyType.UTAGE_PLAYER2
                }
            val records = db.recordDao().getAllRecordList()
            val byChart = records.associateBy { it.songId to it.levelIndex }
            charts.groupBy { it.level }
                .map { (level, list) ->
                    val recs = list.mapNotNull { byChart[it.songId to it.difficultyType.ordinal] }
                    ProgressItem(
                        key = level,
                        total = list.size,
                        played = recs.size,
                        fc = recs.count { it.isFc },
                        sss = recs.count { it.isSss },
                        ap = recs.count { it.isAp },
                        fdx = recs.count { it.isFdx },
                        geS = recs.count { it.reaches(JudgeTier.S) },
                        geSP = recs.count { it.reaches(JudgeTier.SP) },
                        geSS = recs.count { it.reaches(JudgeTier.SS) },
                        geSSP = recs.count { it.reaches(JudgeTier.SSP) },
                        geSSS = recs.count { it.reaches(JudgeTier.SSS) },
                        sssp = recs.count { it.reaches(JudgeTier.SSSP) },
                    )
                }
                // levelSortKey 返回 Pair（非 Comparable），拆成两个 selector 分别比较
                .sortedWith(compareBy({ levelSortKey(it.key).first }, { it.key }))
        }.getOrDefault(emptyList())
    }

    private fun levelSortKey(level: String): Pair<Int, String> {
        val base = level.replace("+", "").toIntOrNull() ?: 0
        return base to level
    }

}
