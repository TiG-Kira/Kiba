package com.kira.kiba.core.stats

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.SongVersionOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 版本名牌进度（参考 EasyMai achievement-progress / 可怜BOT 完成表口径）：
 *
 * - **极**：FC / FC+ / AP / AP+（Full Combo 及以上）；
 * - **将**：SSS 及以上（rate = sss / sssp）；
 * - **神**：AP / AP+；
 * - **舞舞**：FSD / FSD+（FULL SYNC DX 及以上）；
 * - **霸者**：达成率 > 80%（**仅旧框**，即「舞代」——所有非 DX 版本聚合；
 *   EasyMai 选霸者会强制切到舞代，此处沿用同口径）。
 *
 * 条件均作用于**谱面粒度**（song + difficulty 组成的每一张谱面），与 easymai
 * 「所有难度」模式一致：总进度 = 该版本下全部谱面中达成条件的比例。
 *
 * 版本分组以歌曲的 `from`（国服命名）为准，牌子代号对齐工具箱「版本对照表」
 * 与 EasyMai version-map（熊華 / 爽煌 / 宙星 / 祭祝 / 双宴…）。
 */
object PlateProgress {

    /** 牌子种类（EasyMai displayOptions 五态）。 */
    enum class PlateKind(val label: String) {
        FC("极"),
        SSS("将"),
        AP("神"),
        FSD("舞舞"),
        MASTER("霸者"),
        ;

        /** 单条成绩是否满足该牌子条件（成绩必须存在）。 */
        fun satisfies(record: RecordEntity): Boolean = when (this) {
            FC -> record.isFc
            SSS -> record.isSss
            AP -> record.isAp
            FSD -> record.fs == "fsd" || record.fs == "fsdp"
            MASTER -> record.achievements > 80.0
        }
    }

    /**
     * 一个「牌子版本组」：舞代（旧框聚合）或单个版本。
     *
     * @param key 组键（筛选回传用；舞代固定 "OLD"，其余为 from 小写原文）
     * @param displayName 显示名（from 原文，舞代显示「舞代」）
     * @param plateCode 牌子代号（舞 / 真 / 熊華 / 爽煌…；无代号显示「—」）
     * @param fromValues 命中的 from 小写集合
     * @param isOldFrame 是否旧框（霸者只在旧框组可选）
     */
    data class Group(
        val key: String,
        val displayName: String,
        val plateCode: String,
        val fromValues: Set<String>,
        val isOldFrame: Boolean,
    )

    /** 单个牌子进度。 */
    data class Progress(val done: Int, val total: Int) {
        val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
        val percentText: String get() = "${(fraction * 100).toInt()}%"
    }

    /** 一个组的四牌总览（total = 组内谱面总数，各牌共用）。 */
    data class GroupSummary(
        val group: Group,
        val total: Int,
        val fc: Progress,
        val sss: Progress,
        val ap: Progress,
        val master: Progress,
    )

    /** 明细条目：谱面 + 所属歌曲 + 本地成绩。 */
    data class Item(
        val song: SongDataEntity,
        val chart: ChartEntity,
        val record: RecordEntity?,
    ) {
        /** 是否达成当前牌子条件（未游玩必然未达成）。 */
        fun isDone(plate: PlateKind): Boolean = record != null && plate.satisfies(record)
    }

    // ── 牌子代号映射（from 小写 → 代号）；「—」表示该版本无独立牌子 ──
    private val PLATE_CODES: Map<String, String> = mapOf(
        "maimai" to "—",
        "maimai plus" to "真",
        "maimai green" to "超",
        "maimai green plus" to "檄",
        "maimai orange" to "橙",
        "maimai orange plus" to "暁",
        "maimai pink" to "桃",
        "maimai pink plus" to "櫻",
        "maimai murasaki" to "紫",
        "maimai murasaki plus" to "菫",
        "maimai milk" to "白",
        "milk plus" to "雪",
        "maimai milk plus" to "雪",
        "maimai finale" to "輝",
        "all finale" to "舞",
        // ── DX：国服年度版与日版副标题共用一个代号（熊華 / 爽煌 / …）──
        "maimai dx" to "熊華",
        "maimai でらっくす" to "熊華",
        "舞萌dx" to "熊華",
        "maimai dx splash" to "爽煌",
        "maimai dx splash plus" to "爽煌",
        "maimai でらっくす splash" to "爽煌",
        "maimai でらっくす splash plus" to "爽煌",
        "舞萌dx 2021" to "爽煌",
        "maimai dx universe" to "宙星",
        "maimai dx universe plus" to "宙星",
        "maimai でらっくす universe" to "宙星",
        "maimai でらっくす universe plus" to "宙星",
        "舞萌dx 2022" to "宙星",
        "maimai dx festival" to "祭祝",
        "maimai dx festival plus" to "祭祝",
        "maimai でらっくす festival" to "祭祝",
        "maimai でらっくす festival plus" to "祭祝",
        "舞萌dx 2023" to "祭祝",
        "maimai dx buddies" to "双宴",
        "maimai dx buddies plus" to "双宴",
        "maimai でらっくす buddies" to "双宴",
        "maimai でらっくす buddies plus" to "双宴",
        "舞萌dx 2024" to "双宴",
        "maimai dx prism" to "镜",
        "maimai dx prism plus" to "镜",
        "maimai でらっくす prism" to "镜",
        "maimai でらっくす prism plus" to "镜",
        "舞萌dx 2025" to "镜",
        "maimai dx circle" to "—",
        "maimai でらっくす circle" to "—",
        "舞萌dx 2026" to "—",
    )

    /** 旧框（舞代）覆盖的 from 集合：所有非 DX 版本。 */
    private val OLD_FRAME_VALUES: Set<String> = PLATE_CODES.keys.filter {
        !it.startsWith("舞萌dx") && !it.startsWith("maimai dx") && !it.startsWith("maimai でらっくす")
    }.toSet()

    /** 谱面是否参与牌子统计：排除未出难度（level 带「?」）与空等级（宴会场曲目在曲目层排除）。 */
    private fun ChartEntity.countable(): Boolean =
        level.isNotBlank() && !level.contains("?") && difficultyType != DifficultyType.UTAGE

    private fun normalizeFrom(from: String): String = from.trim().lowercase()

    private fun rankOf(fromLower: String): Int =
        SongVersionOrder.rank(GameType.MAIMAI, fromLower)

    /** 组内谱面 → 条目（含成绩）；难度过滤后的明细数据源。 */
    private fun collectItems(
        songs: List<SongDataEntity>,
        chartsBySong: Map<Int, List<ChartEntity>>,
        records: Map<Pair<Int, Int>, RecordEntity>,
        group: Group,
        difficulty: DifficultyType?,
    ): List<Item> = buildList {
        songs.forEach { song ->
            if (normalizeFrom(song.from) !in group.fromValues) return@forEach
            chartsBySong[song.id]?.forEach { chart ->
                if (!chart.countable()) return@forEach
                if (difficulty != null && chart.difficultyType != difficulty) return@forEach
                add(Item(song, chart, records[chart.songId to chart.difficultyType.ordinal]))
            }
        }
    }

    /**
     * 全量版本组：舞代（旧框聚合）置顶，其余按版本新旧倒序（[SongVersionOrder] 口径）。
     * 只包含曲库里实际存在的版本；曲库未同步时返回空。
     */
    suspend fun loadGroups(): List<Group> = withContext(Dispatchers.IO) {
        runCatching {
            val present = AppDataBase.getInstance().songDao().getAllSongsList()
                .map { normalizeFrom(it.from) }
                .filter { it.isNotBlank() }
                .toSet()
            buildList {
                if (OLD_FRAME_VALUES.any { it in present }) {
                    add(
                        Group(
                            key = "OLD",
                            displayName = "舞代",
                            plateCode = "舞",
                            fromValues = OLD_FRAME_VALUES,
                            isOldFrame = true,
                        )
                    )
                }
                present.mapNotNull { from ->
                    PLATE_CODES[from]?.let {
                        Group(
                            key = from,
                            displayName = from,
                            plateCode = it,
                            fromValues = setOf(from),
                            isOldFrame = false,
                        )
                    }
                }.sortedWith { a, b ->
                    // 新版本在前；同 rank（日版别名与国服年度版并排）按名字稳定排序
                    val ra = rankOf(a.key)
                    val rb = rankOf(b.key)
                    if (ra != rb) rb.compareTo(ra) else a.key.compareTo(b.key)
                }.forEach { add(it) }
            }
        }.getOrDefault(emptyList())
    }

    /** 全版本总览：每个组的谱面总数与四牌达成数。 */
    suspend fun loadOverview(): List<GroupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val songs = db.songDao().getAllSongsList().filter { it.genre != "宴会場" }
            val records = db.recordDao().getAllRecordList()
                .associateBy { it.songId to it.levelIndex }
            val chartsBySong = db.chartDao().getAllChartsList().groupBy { it.songId }

            loadGroups().mapNotNull { group ->
                val items = collectItems(songs, chartsBySong, records, group, difficulty = null)
                if (items.isEmpty()) return@mapNotNull null
                fun progressOf(kind: PlateKind): Progress =
                    Progress(items.count { it.isDone(kind) }, items.size)
                GroupSummary(
                    group = group,
                    total = items.size,
                    fc = progressOf(PlateKind.FC),
                    sss = progressOf(PlateKind.SSS),
                    ap = progressOf(PlateKind.AP),
                    master = progressOf(PlateKind.MASTER),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 指定组 + 牌子 + 难度的明细列表（已按难度降序、组内定数降序）。
     *
     * @param group 版本组（[loadGroups] 产物）
     * @param difficulty 难度筛选（null = 所有难度）
     */
    suspend fun loadDetail(
        group: Group,
        difficulty: DifficultyType?,
    ): List<Item> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDataBase.getInstance()
            val songs = db.songDao().getAllSongsList().filter { it.genre != "宴会場" }
            val records = db.recordDao().getAllRecordList()
                .associateBy { it.songId to it.levelIndex }
            val chartsBySong = db.chartDao().getAllChartsList().groupBy { it.songId }
            collectItems(songs, chartsBySong, records, group, difficulty)
                .sortedWith(
                    compareBy(
                        // 难度降序（从难到易）：等级数值大在前，同数值带「+」在前（+0.5）
                        { -levelRank(it.chart.level) },
                        // 组内按定数高到低，再按歌名稳定排序
                        { -it.chart.ds },
                        { it.song.title },
                    )
                )
        }.getOrDefault(emptyList())
    }

    /** 等级排序值：数值部分；带「+」的加 0.5 保证排在同数值裸等级之前。 */
    fun levelRank(level: String): Double =
        level.replace("+", "").toDoubleOrNull()
            ?.let { if (level.contains("+")) it + 0.5 else it } ?: -1.0
}
