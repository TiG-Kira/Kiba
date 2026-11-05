package com.kira.kiba.core.listen

import kotlin.random.Random

/**
 * 「听」页本地智能推荐（纯算法，无 Android 依赖，可单测）。
 *
 * ## 口径
 * 从用户的 **Best 40 成绩** 与 **收藏** 两条信号提取口味画像：
 * - Best 40（ra 最高的 40 条）：每条成绩的流派计权重 1；
 * - 收藏：每首收藏的流派计权重 1.5（收藏是更强的主动信号）；
 * - 版本画像顺带统计，用于「新曲偏置」的弱信号。
 *
 * 候选打分：流派匹配为主轴，叠三个修正项——
 * - **收藏优先**：收藏的歌必进候选前排（用户明确喜欢）；
 * - **未游玩偏置**：没打过分的歌轻微加分（推荐的意义在发现新歌）；
 * - **随机扰动**：分数同档时随机洗牌，每次进页不重样。
 *
 * 冷启动：无成绩也无收藏时退化为均匀随机取样。
 */
object SongRecommender {

    /** 推荐条数上限（与 Agent 歌单同口径）。 */
    const val LIMIT = 15

    /** 推荐输入的轻量曲目（UI 层从曲库实体转换，避免纯算法层依赖 Room）。 */
    data class SongLite(
        val id: Int,
        val genre: String,
        /** 曲库版本号（弱信号，仅用于新曲微偏置，可为 null）。 */
        val version: Int?,
    )

    /** 推荐输入的轻量成绩。 */
    data class RecordLite(
        val songId: Int,
        /** 单曲 Rating（排序取 Best 40 用）。 */
        val ra: Int,
        val genre: String,
    )

    /**
     * 生成推荐曲目 id 列表（按推荐度降序，最多 [LIMIT] 首）。
     *
     * @param songs 全量曲库（轻量口径）
     * @param records 用户成绩（任意难度，算法内部取 ra 前 40）
     * @param favoriteIds 收藏曲目 id 集合
     * @param seed 随机种子（默认系统随机；单测传固定值）
     */
    fun recommend(
        songs: List<SongLite>,
        records: List<RecordLite>,
        favoriteIds: Set<Int>,
        seed: Long = System.nanoTime(),
    ): List<Int> {
        if (songs.isEmpty()) return emptyList()
        val random = Random(seed)

        // ── 画像：流派权重（Best 40 各计 1，收藏各计 1.5）──
        val genreWeight = HashMap<String, Double>()
        records.sortedByDescending { it.ra }
            .take(40)
            .forEach { if (it.genre.isNotBlank()) genreWeight.merge(it.genre, 1.0, Double::plus) }
        songs.filter { it.id in favoriteIds }
            .forEach { if (it.genre.isNotBlank()) genreWeight.merge(it.genre, 1.5, Double::plus) }
        val playedIds = records.map { it.songId }.toSet()

        // 新曲微偏置：曲库出现过的最大 version 视为「当前版本」
        val maxVersion = songs.mapNotNull { it.version }.maxOrNull()

        // ── 候选打分 ──
        val scored = songs.map { song ->
            var score = (genreWeight[song.genre] ?: 0.0) * 2.0
            if (song.id in favoriteIds) score += 6.0        // 收藏：用户明确喜欢
            if (song.id !in playedIds) score += 1.0         // 未游玩：偏置发现新歌
            if (maxVersion != null && song.version == maxVersion) score += 0.3 // 新曲微偏置
            score += random.nextDouble() * 1.2              // 同档洗牌
            song.id to score
        }

        return scored.sortedByDescending { it.second }
            .take(LIMIT)
            .map { it.first }
    }
}
