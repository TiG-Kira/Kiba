package com.kira.kiba.core.stats

import com.kira.kiba.core.data.ChuniRating

/**
 * 中二节奏推分推荐（纯算法，不依赖 Android，可跑 JVM 单测）。
 *
 * ## 一条原则：宁可换高定数的谱，也不要死磕高 rank
 * 中二 SSS+ 要 **1009000**，而满分只有 **1010000** —— 余量只有 **1000 分**，而一个 note 就值
 * 上千分（按谱面物量摊），**漏一个 note 基本就掉线**，实际要几乎全 JUSTICE CRITICAL。而它的收益只有：
 * ```
 * SSS  (1007500) → ds + 2.00
 * SSS+ (1009000) → ds + 2.15   ← 多打 1500 分（精力可能翻倍），只多 0.15 ra
 * ```
 * 0.15 ra 折进 Rating 只是 0.003（÷50），几乎看不见；同样的收益，换一首定数高 0.2 的谱面
 * 拿 SSS 就白赚回来了 —— 所以 SSS+ 目标必须**大幅降权**。
 *
 * 但**不能一刀切排除**：rating 17 往上时，能推的谱面本来就只剩「高定数 SSS」和
 * 「低定数 SSS+」两片，再往上几乎只能靠 SSS+。所以这里保留 SSS+，让它天然沉到队尾：
 * 它只会出现在「地板 − 2.15 ~ 地板 − 2.0」这段最低定数区间，只要还有任何更高定数的
 * 目标，就轮不到它。
 *
 * ## 推荐口径
 * 1. **档位从最省力的一档往上挑**：能 S 压过地板就绝不要 SS+，SSS+ 只在没得选时才成为目标；
 * 2. **按定数从高到低排**：定数越高，同一档位拿到的 ra 越高，换谱永远比在同一定数上硬顶 rank 划算；
 * 3. **同定数优先更低档位**：同等收益下挑更容易的那个（SSS+ 天然排在 SSS 之后）。
 *
 * 这样排出来的列表天然「下一条总不比上一条强」：定数递减、档位非降；
 * 只有在榜单已经推无可推（rating 17+）时，SSS+ 才会浮上来。
 *
 * 收益（gain）口径分三种情况，避免虚高：
 * - 未上榜（当前成绩的 ra ≤ 地板）：增益 = 潜在 ra − 地板（顶掉地板那首）；
 * - 已在榜内（当前 ra > 地板）：增益 = 潜在 ra − 当前 ra（涨多少加多少）；
 * - 枠没攒满（当前版本不足 20 首 / 非当前不足 30 首）：增益 = 潜在 ra 全额计入
 *   （没有地板曲被挤掉），这一点和舞萌 B35/B15 一致。
 */
object ChuniPushRecommender {

    /**
     * 可推荐档位（**按分数升序**）。
     *
     * 挑选目标时从这一头往上扫，第一个「ra 超过地板且还没打到」的档位即目标，
     * 所以天然取到最省力的那一档。
     *
     * SSS+ 留在表里但**权重极低**：它只会在「地板 − 2.15 ~ 地板 − 2.0」这段最低定数
     * 区间被命中，而排序按定数从高到低，所以只要还有更高定数的目标就轮不到它。
     * 真到 rating 17+ 推无可推时，它是唯一剩下的路 —— 那时不推荐它等于不给建议。
     */
    val TIERS: List<Tier> = listOf(
        Tier(950000, "AAA"),
        Tier(975000, "S"),
        Tier(990000, "S+"),
        Tier(1000000, "SS"),
        Tier(1005000, "SS+"),
        Tier(1007500, "SSS"),
        Tier(1009000, "SSS+"),
    )

    /** SSS+ 分数线。 */
    const val SSS_PLUS_SCORE = 1009000

    /** SSS 分数线（SSS+ 比它只多 0.15 ra，却要多打 1500 分）。 */
    const val SSS_SCORE = 1007500

    /** 中二满分（101 万）。 */
    const val MAX_SCORE = 1010000

    /** SSS+ 到满分的余量：**只有 1000 分**，漏一个 note 就掉线。 */
    const val SSS_PLUS_MARGIN = MAX_SCORE - SSS_PLUS_SCORE

    /** 最低可推荐难度（EXPERT 档号=2）；BASIC / ADVANCED 基本进不了 Rating 枠。 */
    const val MIN_LEVEL_INDEX = 2

    /** 最高可推荐难度（ULTIMA=4，World's End 由调用方排除）。 */
    const val MAX_LEVEL_INDEX = 4

    /**
     * 分数档。
     * @param score 达成该评级所需的最低分数。
     * @param name 官方评级名（AAA / S / … / SSS）。
     */
    data class Tier(val score: Int, val name: String)

    /**
     * 谱面（来自 chuni_chart 的投影）。
     * @param isCurrent 是否属于当前版本枠（当前 TOP20），由调用方按版本 / 官方分池判定。
     */
    data class Chart(
        val songId: Int,
        val levelIndex: Int,
        val level: String,
        val ds: Double,
        val isCurrent: Boolean,
    )

    /**
     * 成绩（来自 chuni_record 的投影）。
     * @param ra 数据源给出的单曲 ra（落雪 / 水鱼都会给；缺失时调用方可传 0）。
     */
    data class Score(
        val songId: Int,
        val levelIndex: Int,
        val score: Int,
        val ra: Double = 0.0,
    )

    /**
     * 一个 Rating 枠的地板。
     * @param floor 地板 ra（枠内最低那首）；null = 该枠没有成绩，无法给目标。
     * @param size 当前枠内已有曲目数。
     * @param capacity 枠容量（非当前 30 / 当前 20）。
     */
    data class Pool(val floor: Double?, val size: Int, val capacity: Int) {
        /** 枠没攒满：新曲目全额计入，不会挤掉地板。 */
        val isFull: Boolean get() = size >= capacity
    }

    /** 推荐候选。 */
    data class Candidate(
        val songId: Int,
        val levelIndex: Int,
        val level: String,
        val ds: Double,
        val isCurrent: Boolean,
        /** 目标档位分数（如 975000）。 */
        val tierScore: Int,
        /** 目标档位名（如 S）。 */
        val tierName: String,
        /** 档位序号（0 = AAA，越大越难）。 */
        val tierRank: Int,
        /** 该档位下的单曲 ra。 */
        val potential: Double,
        /** 对应枠的地板 ra。 */
        val floor: Double,
        /** 实际可获得的 Rating 增益。 */
        val gain: Double,
        /** 当前分数（未游玩 = 0）。 */
        val currentScore: Int,
        /** 当前单曲 ra（未游玩 = null）。 */
        val currentRa: Double?,
        /** 当前成绩是否已经在该枠内（决定增益口径）。 */
        val inPool: Boolean,
        /** 距目标档位还差多少分。 */
        val gap: Int,
    ) {
        val played: Boolean get() = currentScore > 0
    }

    /**
     * 生成推分候选（已排序、已截断）。
     *
     * @param charts 全部可推荐谱面（调用方需排除 World's End；本方法再按难度下限过滤）
     * @param scores 全部成绩（World's End 已排除）
     * @param nonCurrent 非当前版本 TOP30 枠
     * @param current 当前版本 TOP20 枠
     * @param limit 候选上限
     */
    fun recommend(
        charts: List<Chart>,
        scores: List<Score>,
        nonCurrent: Pool,
        current: Pool,
        limit: Int = 10,
    ): List<Candidate> {
        val scoreByChart = scores.associateBy { ChuniRating.chartKey(it.songId, it.levelIndex) }
        val seen = HashSet<Long>()
        val out = ArrayList<Candidate>()
        for (chart in charts) {
            if (chart.levelIndex !in MIN_LEVEL_INDEX..MAX_LEVEL_INDEX) continue
            if (chart.ds <= 0.0) continue
            val key = ChuniRating.chartKey(chart.songId, chart.levelIndex)
            if (!seen.add(key)) continue // 曲库重复条目只留第一条

            val pool = if (chart.isCurrent) current else nonCurrent
            val floor = pool.floor ?: continue
            val record = scoreByChart[key]
            val currentScore = record?.score ?: 0
            // 从最省力的一档往上找：第一档「已超过地板、且还没打到」就是目标。
            // 扫到 SSS 还没命中 → 这首只能靠 SSS+ 过地板（低定数段）。
            // 仍保留为候选（rating 17+ 时可能只剩这种），靠排序自然沉底。
            val tierIndex = TIERS.indexOfFirst { t ->
                t.score > currentScore && ChuniRating.raOf(t.score, chart.ds) > floor
            }
            if (tierIndex < 0) continue

            val tier = TIERS[tierIndex]
            val potential = ChuniRating.raOf(tier.score, chart.ds)
            val currentRa = record?.ra
            val inPool = currentRa != null && currentRa > floor
            val gain = when {
                // 已在榜内：本曲涨多少，总分就加多少
                inPool -> potential - currentRa
                // 枠已满：顶掉地板那首
                pool.isFull -> potential - floor
                // 枠没攒满：全额计入
                else -> potential
            }
            if (gain <= 0.0) continue

            out += Candidate(
                songId = chart.songId,
                levelIndex = chart.levelIndex,
                level = chart.level,
                ds = chart.ds,
                isCurrent = chart.isCurrent,
                tierScore = tier.score,
                tierName = tier.name,
                tierRank = tierIndex,
                potential = potential,
                floor = floor,
                gain = gain,
                currentScore = currentScore,
                currentRa = currentRa,
                inPool = inPool,
                gap = tier.score - currentScore,
            )
        }
        return out.sortedWith(RANKING).take(limit)
    }

    /**
     * 排序：定数降序 → 档位升序（更低 rank）→ 收益降序 → 差距升序。
     *
     * 定数放第一位是刻意的：同一个档位下，定数每高 0.1 就多 0.1 ra，
     * 而档位每上一级只多 0.3~0.5 ra，却要在同一首谱上多练一截 ——
     * 换一首高定数的谱，永远比在同一定数上硬顶 rank 划算。
     * 同定数时才比档位（能 S 压过地板就别要 SS+）。
     *
     * SSS+ 的「降权」在这里是结构性完成的：同一个枠里，能靠 SSS+ 过地板的谱面
     * 定数必然比靠 SSS 的更低（差 0.15 一段），按定数排就永远垫在后面。
     */
    private val RANKING: Comparator<Candidate> = compareByDescending<Candidate> { it.ds }
        .thenBy { it.tierRank }
        .thenByDescending { it.gain }
        .thenBy { it.gap }

    /** 指定分数对应的档位序号（-1 = 低于 AAA）。仅用于展示，不做计算。 */
    fun tierRankOf(score: Int): Int = TIERS.indexOfLast { score >= it.score }
}
