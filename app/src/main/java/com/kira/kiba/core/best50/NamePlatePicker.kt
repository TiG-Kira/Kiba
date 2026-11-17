package com.kira.kiba.core.best50

import com.kira.kiba.core.stats.PlateProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 姓名框名牌的选取：**按本地成绩判定「某版本某牌已拿全」，再从已拿全的里面挑一张**。
 *
 * 判定口径沿用 [PlateProgress]（与「版本名牌进度」页同一套算法）：
 * 某版本组该牌种的 `done == total`（且 total > 0）即视为这块牌子已获得。
 * 拿全一个版本的所有谱面是很硬的条件，所以大多数账号会落回默认牌
 * （`デフォルト`，浅蓝波点）—— 与游戏里「一个都没拿到就戴默认牌」一致。
 *
 * 选取本身是**按天稳定**的随机：同一天进多少次 B50 页、导出的图片，用的都是同一张
 * 牌（避免重组刷新时闪图）；跨天才会换。种子 = 日期 + 昵称，换账号即换池。
 */
object NamePlatePicker {

    /**
     * 短期结果缓存：`pick()` 背后是 [PlateProgress.loadOverview]（全曲库 join），
     * 页面卡片与导出图常常前后脚各调一次，不缓存等于白扫两遍。
     *
     * 只缓存 60 秒 —— 刚同步完成绩就拿到新版本牌的情况要能立刻生效；选取本身
     * 按天稳定，所以 60 秒内两次调用必然给出同一张。
     */
    @Volatile
    private var cached: Triple<String, Long, String>? = null

    private const val CACHE_MS = 60_000L

    /** 返回素材名（不含目录与扩展名），如 `np_055101` / `np_000001`。 */
    suspend fun pick(nickname: String): String {
        val key = LocalDate.now().toEpochDay().toString() + "|" + nickname
        val now = System.currentTimeMillis()
        cached?.let { if (it.first == key && now - it.second < CACHE_MS) return it.third }
        val picked = withContext(Dispatchers.IO) {
            runCatching {
                val pool = achievedPlates0()
                if (pool.isEmpty()) return@runCatching NamePlateCatalog.DEFAULT
                pool[Math.floorMod(seed(nickname), pool.size)]
            }.getOrDefault(NamePlateCatalog.DEFAULT)
        }
        cached = Triple(key, now, picked)
        return picked
    }

    /** 当前成绩下「已拿全」的全部版本牌素材（同一代号多张牌会一起进来）。 */
    suspend fun achievedPlates(): List<String> = withContext(Dispatchers.IO) {
        runCatching { achievedPlates0() }.getOrDefault(emptyList())
    }

    private suspend fun achievedPlates0(): List<String> =
        PlateProgress.loadOverview().flatMap { summary ->
            summary.group.availablePlates.flatMap { kind ->
                val p = summary.progressOf(kind)
                if (p.total > 0 && p.done == p.total) {
                    NamePlateCatalog.candidates(summary.group.key, kind.name)
                } else {
                    emptyList()
                }
            }
        }

    private fun seed(nickname: String): Int =
        LocalDate.now().toEpochDay().hashCode() * 31 + nickname.hashCode()
}

/** [PlateProgress.GroupSummary] 按牌种取进度。 */
private fun PlateProgress.GroupSummary.progressOf(kind: PlateProgress.PlateKind): PlateProgress.Progress =
    when (kind) {
        PlateProgress.PlateKind.FC -> fc
        PlateProgress.PlateKind.SSS -> sss
        PlateProgress.PlateKind.AP -> ap
        PlateProgress.PlateKind.FSD -> fsd
        PlateProgress.PlateKind.MASTER -> master
    }
