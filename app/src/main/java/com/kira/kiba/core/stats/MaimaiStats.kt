package com.kira.kiba.core.stats

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 舞萌本地统计（版本进度 / 等级进度 / DX 分数换算）。
 * 数据源：Room 曲库 + 本地成绩缓存，全部可在离线状态下计算。
 */
object MaimaiStats {

    data class ProgressItem(val key: String, val total: Int, val played: Int) {
        val ratio: Float get() = if (total == 0) 0f else played.toFloat() / total
    }

    /** 版本进度：按 `from`（添加版本）分组，played = 该版本下有任意成绩的歌曲数。 */
    suspend fun versionProgress(): List<ProgressItem> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val songs = db.songDao().getAllSongsList().filter { it.genre != "宴会場" }
            val records = db.recordDao().getAllRecordList()
            val playedIds = records.map { it.songId }.toSet()
            songs.groupBy { it.from }
                .map { (version, list) ->
                    ProgressItem(
                        key = version,
                        total = list.size,
                        played = list.count { it.id in playedIds },
                    )
                }
                .sortedBy { it.key }
        }.getOrDefault(emptyList())
    }

    /** 等级进度：按谱面等级（"13" / "13+" / "14"）分组，played = 有对应 (歌曲, 难度) 成绩的谱面数。 */
    suspend fun levelProgress(): List<ProgressItem> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val charts = db.chartDao().getAllChartsList()
                .filter {
                    it.difficultyType != DifficultyType.UTAGE &&
                        it.difficultyType != DifficultyType.UTAGE_PLAYER2
                }
            val records = db.recordDao().getAllRecordList()
            val playedKeys = records.map { it.songId to it.levelIndex }.toSet()
            charts.groupBy { it.level }
                .map { (level, list) ->
                    ProgressItem(
                        key = level,
                        total = list.size,
                        played = list.count { (it.songId to it.difficultyType.ordinal) in playedKeys },
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

    /**
     * DX 分数 → 估算达成率。
     * 舞萌 DX 谱面每音符最大 DX：tap 1 / hold 2 / slide 1 / touch 1 / break 2。
     * 达成率与 DX 分数并非严格线性，此处用业界常用估算 dxScore / maxDx × 100。
     */
    fun estimateAchievement(
        notesTap: Int,
        notesHold: Int,
        notesSlide: Int,
        notesTouch: Int,
        notesBreak: Int,
        dxScore: Int,
    ): Double {
        val maxDx = notesTap * 1 + notesHold * 2 + notesSlide * 1 + notesTouch * 1 + notesBreak * 2
        if (maxDx <= 0) return 0.0
        return dxScore.toDouble() / maxDx * 100.0
    }

    /** 单曲 ra 估算：⌊ds × min(达成率, 100.5) / 100⌋。 */
    fun estimateRa(ds: Double, achievement: Double): Int {
        val capped = achievement.coerceAtMost(100.5)
        return (ds * capped / 100.0).toInt()
    }
}
