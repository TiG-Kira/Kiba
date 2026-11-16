package com.kira.kiba.core.stats

import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
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
 * 版本分组完全由本对象内的 **VERSION_DEFS 配置表**驱动（牌子代号 / 覆盖版本 /
 * 可选牌子种类 / 新旧排序均在表内声明）：**maimai 新版本上线后只需在表末尾追加
 * 一行**，版本列表、舞代聚合、霸者口径与筛选可选项即自动生效。
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
     * 一个「牌子版本组」：舞代（旧框聚合）、真代（maimai 与 maimai plus 合并）
     * 或单个 DX 版本；均由 [VERSION_DEFS] 配置表推导。
     *
     * @param key 组键（筛选回传用；舞代固定 "OLD"，其余为牌子代号，如 "真" / "熊華"）
     * @param displayName 显示名（配置表 name，舞代显示「舞代」）
     * @param plateCode 牌子代号（舞 / 真 / 熊華 / 爽煌…；无代号显示「—」）
     * @param fromValues 命中的 from 小写集合
     * @param isOldFrame 是否旧框（霸者只在旧框组可选）
     * @param sortRank 版本时间线序号（越大越新，用于组排序；舞代恒在最前）
     * @param availablePlates 该组可用的牌子种类（真代无将、霸者仅旧框）
     */
    data class Group(
        val key: String,
        val displayName: String,
        val plateCode: String,
        val fromValues: Set<String>,
        val isOldFrame: Boolean,
        val sortRank: Int = 0,
        val availablePlates: List<PlateKind> =
            listOf(PlateKind.FC, PlateKind.SSS, PlateKind.AP, PlateKind.FSD),
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

    // ══════════════════════════════════════════════════════════════════
    // 版本名牌配置表 —— **新增版本只需在 VERSION_DEFS 末尾追加一行**，
    // 组排序、舞代聚合、霸者口径、筛选可选项全部由本表自动推导，无需改其他代码。
    //
    // 字段说明：
    // - code   牌子代号（显示用；「—」= 新版本暂未公布牌子名，公布后替换即可）
    // - name   显示名（筛选器 / 总览卡片标题）
    // - from   归属的版本 from 值（小写；同一牌子可挂多个别名，多代合并也写在一起）
    // - dx     是否 DX 世代；false = 旧框，自动计入「舞代」聚合与霸者口径
    // - plates 该牌子可选的牌子种类（缺省 = 极/将/神/舞舞；特殊牌如真代无将则显式指定）
    // ══════════════════════════════════════════════════════════════════

    /** 常规可选牌子：极 / 将 / 神 / 舞舞（霸者仅旧框聚合组提供，不写在版本行里）。 */
    private val DEFAULT_PLATES: List<PlateKind> =
        listOf(PlateKind.FC, PlateKind.SSS, PlateKind.AP, PlateKind.FSD)

    private data class PlateVersionDef(
        val code: String,
        val name: String,
        val from: Set<String>,
        val dx: Boolean,
        val plates: List<PlateKind> = DEFAULT_PLATES,
    )

    /** 版本时间线（旧 → 新）：行号即排序依据，新版本追加到末尾即可。 */
    private val VERSION_DEFS: List<PlateVersionDef> = listOf(
        // ── 旧框：maimai 与 maimai plus 共用一块「真」牌（两代都完成才能拿牌），真代无将 ──
        PlateVersionDef(
            code = "真",
            name = "maimai / maimai plus",
            from = setOf("maimai", "maimai plus"),
            dx = false,
            plates = listOf(PlateKind.FC, PlateKind.AP, PlateKind.FSD),
        ),
        PlateVersionDef("超", "maimai green", setOf("maimai green"), dx = false),
        PlateVersionDef("檄", "maimai green plus", setOf("maimai green plus"), dx = false),
        PlateVersionDef("橙", "maimai orange", setOf("maimai orange"), dx = false),
        PlateVersionDef("暁", "maimai orange plus", setOf("maimai orange plus"), dx = false),
        PlateVersionDef("桃", "maimai pink", setOf("maimai pink"), dx = false),
        PlateVersionDef("櫻", "maimai pink plus", setOf("maimai pink plus"), dx = false),
        PlateVersionDef("紫", "maimai murasaki", setOf("maimai murasaki"), dx = false),
        PlateVersionDef("菫", "maimai murasaki plus", setOf("maimai murasaki plus"), dx = false),
        PlateVersionDef("白", "maimai milk", setOf("maimai milk", "milk"), dx = false),
        PlateVersionDef("雪", "maimai milk plus", setOf("maimai milk plus", "milk plus"), dx = false),
        PlateVersionDef("輝", "maimai finale", setOf("maimai finale"), dx = false),
        PlateVersionDef("舞", "all finale", setOf("all finale"), dx = false),
        // ── DX：国服年度版与日版副标题共用一个代号 ──
        PlateVersionDef(
            "熊華", "舞萌DX",
            setOf("maimai dx", "maimai でらっくす", "舞萌dx"), dx = true,
        ),
        PlateVersionDef(
            "爽煌", "舞萌DX 2021",
            setOf(
                "maimai dx splash", "maimai dx splash plus",
                "maimai でらっくす splash", "maimai でらっくす splash plus",
                "舞萌dx 2021",
            ), dx = true,
        ),
        PlateVersionDef(
            "宙星", "舞萌DX 2022",
            setOf(
                "maimai dx universe", "maimai dx universe plus",
                "maimai でらっくす universe", "maimai でらっくす universe plus",
                "舞萌dx 2022",
            ), dx = true,
        ),
        PlateVersionDef(
            "祭祝", "舞萌DX 2023",
            setOf(
                "maimai dx festival", "maimai dx festival plus",
                "maimai でらっくす festival", "maimai でらっくす festival plus",
                "舞萌dx 2023",
            ), dx = true,
        ),
        PlateVersionDef(
            "双宴", "舞萌DX 2024",
            setOf(
                "maimai dx buddies", "maimai dx buddies plus",
                "maimai でらっくす buddies", "maimai でらっくす buddies plus",
                "舞萌dx 2024",
            ), dx = true,
        ),
        PlateVersionDef(
            "镜", "舞萌DX 2025",
            setOf(
                "maimai dx prism", "maimai dx prism plus",
                "maimai でらっくす prism", "maimai でらっくす prism plus",
                "舞萌dx 2025",
            ), dx = true,
        ),
        // ── 新版本：牌子名公布后把「—」替换为正式代号即可 ──
        PlateVersionDef(
            "—", "舞萌DX 2026",
            setOf("maimai dx circle", "maimai でらっくす circle", "舞萌dx 2026"), dx = true,
        ),
    )

    /** 谱面是否参与牌子统计：排除未出难度（level 带「?」）与空等级（宴会场曲目在曲目层排除）。 */
    private fun ChartEntity.countable(): Boolean =
        level.isNotBlank() && !level.contains("?") && difficultyType != DifficultyType.UTAGE

    private fun normalizeFrom(from: String): String = from.trim().lowercase()

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
     * 全量版本组（完全由 [VERSION_DEFS] 推导，新增版本无需改这里）：
     * 舞代（旧框聚合，霸者只在此可选）置顶，其后按表中行号新版本在前；
     * 只包含曲库里实际存在的版本；曲库未同步时返回空。
     */
    suspend fun loadGroups(): List<Group> = withContext(Dispatchers.IO) {
        runCatching {
            val present = AppDataBase.getInstance().songDao().getAllSongsList()
                .map { normalizeFrom(it.from) }
                .filter { it.isNotBlank() }
                .toSet()
            buildList {
                // ── 舞代：所有旧框版本聚合（from = 全部非 DX 行的 from 并集）──
                val oldFroms = VERSION_DEFS.filter { !it.dx }.flatMap { it.from }.toSet()
                if (oldFroms.any { it in present }) {
                    add(
                        Group(
                            key = "OLD",
                            displayName = "舞代",
                            plateCode = "舞",
                            fromValues = oldFroms,
                            isOldFrame = true,
                            availablePlates = PlateKind.entries,
                        )
                    )
                }
                // ── 各版本组：倒序遍历配置表（新版本在前），行号即 sortRank ──
                VERSION_DEFS.asReversed().forEachIndexed { i, def ->
                    val froms = def.from.filter { it in present }.toSet()
                    if (froms.isNotEmpty()) {
                        add(
                            Group(
                                key = def.code,
                                displayName = def.name,
                                plateCode = def.code,
                                fromValues = froms,
                                isOldFrame = false,
                                sortRank = VERSION_DEFS.size - i,
                                availablePlates = def.plates,
                            )
                        )
                    }
                }
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
