package com.kira.kiba.core.stats

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 推分推荐融合（Kiba × EasyMai）。
 *
 * Kiba 原生算法（[KibaAgent.pushRating]）：B35/B15 地板 + 官方 Rating 档位表，
 * 找「最低的、ra 能超过地板的档位」→ 回答"打哪一档最划算"。
 *
 * EasyMai 算法（移植自 recommendationUtils.js + ratingUtils.js）：
 * - 平均定数推档：rating / (22.4 × 50) ≈ 全服平均口径下的"你的实力定数"，
 *   推荐定数范围 [平均定数, 平均定数 + 0.5]；
 * - 拟合定数热门推荐（fit）：定数范围内，按「全体玩家平均达成率 × 游玩次数加权」
 *   打分 —— 高均分 + 高热度 = 大众打得动又爱打的谱面；
 * - 定数差值性价比推荐（diff）：官标定数 − 拟合定数 > 0 的谱面，
 *   差值越大 = 谱面实际越比定数容易 = 同样达成率下更容易拿高分。
 *
 * 融合策略：Kiba 档位候选为主回答（最直接的可执行目标），
 * EasyMai 两路候选作为「水鱼大数据参考」并列输出，去重后不覆盖主列表。
 *
 * 本文件为纯 Kotlin 算法，不依赖 Android（可跑 JVM 单元测试）。
 */
object RecommendFusion {

    /** 谱面统计数据（来自 chart_stats 表的投影）。 */
    data class StatChart(
        val songId: Int,
        val levelIndex: Int,
        /** 游玩（上传）样本数。 */
        val cnt: Double,
        /** 全体玩家平均达成率（%）。 */
        val avg: Double,
        /** 拟合定数（水鱼回归出的实际难度）。 */
        val fitDiff: Double?,
    )

    /** 谱面定数信息（来自 chart 表的投影）。 */
    data class ChartDs(
        val songId: Int,
        val levelIndex: Int,
        /** 官标定数。 */
        val ds: Double,
    )

    /** EasyMai 推荐候选（带来源与理由，供融合输出）。 */
    data class EasyMaiCandidate(
        val songId: Int,
        val levelIndex: Int,
        val ds: Double,
        /** 全体玩家平均达成率。 */
        val avg: Double,
        /** 游玩样本数。 */
        val cnt: Double,
        /** 拟合定数（fit 路线为 null 时不展示）。 */
        val fitDiff: Double?,
        /** 定数差值（官标 − 拟合，diff 路线 > 0）。 */
        val dsDifference: Double?,
        /** EasyMai 综合得分（fit 路线）/ null（diff 路线）。 */
        val score: Double?,
        /** "fit" = 热门高均分；"diff" = 定数虚高性价比。 */
        val source: String,
    )

    /** 水鱼大数据推荐结果：推荐定数范围 + 两路候选。 */
    data class FusionResult(
        /** 平均定数（rating / 22.4 / 50）。 */
        val averageDs: Double,
        /** 推荐定数范围 [min, max]。 */
        val rangeMin: Double,
        val rangeMax: Double,
        /** 热门高均分候选（按 EasyMai 综合得分降序）。 */
        val fitCandidates: List<EasyMaiCandidate>,
        /** 定数虚高性价比候选（按差值降序）。 */
        val diffCandidates: List<EasyMaiCandidate>,
    )

    // ── EasyMai ratingUtils.js：平均定数与推荐范围 ──

    /** 平均定数 = rating / (22.4 × 50)，保留 1 位小数。 */
    fun averageDs(rating: Int): Double {
        if (rating <= 0) return 0.0
        return (rating / (22.4 * 50) * 10).roundToInt() / 10.0
    }

    /** 推荐定数范围：下限 = 平均定数（不低于 1），上限 = 平均定数 + 0.5（不超过 15）。 */
    fun recommendedRange(averageDs: Double): Pair<Double, Double> {
        if (averageDs <= 0.0) return 0.0 to 0.0
        val min = maxOf(1.0, (averageDs * 10).roundToInt() / 10.0)
        val max = min(15.0, ((averageDs + 0.5) * 10).roundToInt() / 10.0)
        return min to max
    }

    /** EasyMai 综合得分：avgScore × (0.7 + 0.3 × cntScore)。 */
    fun fitScore(avg: Double, cnt: Double): Double {
        // 平均达成率归一：以 95% 为零点、每 1% 记 1 分、上限 5（ EasyMai 原式 min(avg-95, 5)）
        val avgScore = (avg - 95.0).coerceAtMost(5.0)
        // 游玩次数归一：10000 次记满（EasyMai 原式 min(cnt / 10000, 1)）
        val cntScore = (cnt / 10000.0).coerceAtMost(1.0)
        return avgScore * (0.7 + 0.3 * cntScore)
    }

    /**
     * 水鱼大数据两路推荐（EasyMai recommendSongsByFitDs / ByDsDifference 移植）。
     *
     * @param stats 谱面统计（chart_stats 全表）
     * @param dsByChart 谱面官标定数索引（chart 表，过滤掉宴会场后传入）
     * @param rating 玩家 DX Rating（用于推导推荐定数范围）
     * @param rangeOverride 手动指定定数范围（覆盖 rating 推导）
     * @param limit 每路候选上限
     */
    fun recommend(
        stats: List<StatChart>,
        dsByChart: Map<Pair<Int, Int>, Double>,
        rating: Int,
        rangeOverride: Pair<Double, Double>? = null,
        limit: Int = 15,
    ): FusionResult {
        val averageDs = averageDs(rating)
        val (min, max) = rangeOverride ?: recommendedRange(averageDs)

        // 只统计有官标定数的谱面（宴会场等在调用侧已排除）
        val candidates = stats.mapNotNull { s ->
            val ds = dsByChart[s.songId to s.levelIndex] ?: return@mapNotNull null
            Triple(s, ds, ds >= min && ds <= max)
        }

        // ── fit 路线：定数范围内，高均分 + 高热度优先 ──
        val fit = candidates.asSequence()
            .filter { it.third }
            .filter { it.first.avg > 0.0 }
            .map { (s, ds, _) ->
                EasyMaiCandidate(
                    songId = s.songId,
                    levelIndex = s.levelIndex,
                    ds = ds,
                    avg = s.avg,
                    cnt = s.cnt,
                    fitDiff = s.fitDiff,
                    dsDifference = null,
                    score = fitScore(s.avg, s.cnt),
                    source = "fit",
                )
            }
            .sortedByDescending { it.score ?: 0.0 }
            .take(limit)
            .toList()

        // ── diff 路线：官标定数 − 拟合定数 > 0（谱面比定数容易），差值大者优先；
        //    与 EasyMai 原版一致：同样限定在推荐定数范围内（它遍历的就是
        //    getDifficultiesByDsRange 的结果），fit_diff 缺失的谱面跳过。──
        val diff = candidates.asSequence()
            .filter { it.third }
            .mapNotNull { (s, ds, _) ->
                val fitDiff = s.fitDiff ?: return@mapNotNull null
                val d = ds - fitDiff
                if (d > 0.0) {
                    EasyMaiCandidate(
                        songId = s.songId,
                        levelIndex = s.levelIndex,
                        ds = ds,
                        avg = s.avg,
                        cnt = s.cnt,
                        fitDiff = fitDiff,
                        dsDifference = d,
                        score = null,
                        source = "diff",
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { it.dsDifference ?: 0.0 }
            .take(limit)
            .toList()

        return FusionResult(averageDs, min, max, fit, diff)
    }

    // ── Kiba 档位算法与 EasyMai 候选的融合排序辅助 ──

    /**
     * 融合排序权重：Kiba 档位候选（gap 小者优先）天然在前；
     * EasyMai fit 候选按 score 归一到 0~1 的 tie-breaker。
     * 仅供展示层参考——主回答始终是 Kiba 档位列表。
     */
    fun fusedTieBreaker(score: Double?): Double = when {
        score == null -> 0.0
        // score 理论范围约 [-x, 5]：压到 0~1，只做同 gap 平局裁决
        else -> (score / 5.0).coerceIn(0.0, 1.0)
    }

    /** 单曲 ra（floor 口径，与水鱼 computeRa / KibaAgent.tierRa 一致）：floor(ds × 达成率/100 × 乘数)。 */
    fun singleRa(ds: Double, achievement: Double, multiplier: Double): Int =
        floor(ds * (achievement.coerceAtMost(100.5) / 100.0) * multiplier).toInt()
}
