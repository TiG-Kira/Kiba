package com.kira.kiba.core.data

import com.google.gson.JsonObject
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.model.DifficultyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MaimaiLxnsSync.mapScore] 曲目 id / type 映射与 ra 补算的单元测试。
 *
 * 覆盖曾经的真实故障（落雪源 Best50 数据错误 + 闪退）：
 * - 落雪曲目 id 是**自家体系**（SD/DX 谱共用曲目 id，全部 < 10000，宴谱除外），
 *   本地曲库/水鱼体系是**谱面类型由 id 决定**（DX 谱 = SD id + 10000）。旧实现
 *   `% 10000` 直接把两套体系焊死，DX 新曲的 isNew 判定 / 定数补算 / DX 满分
 *   全部错位 → B50 分池错乱、Rating 不正确；
 * - type 小写 "dx" 与曲库大写 "DX" 失配 → chartKey 永远查不到 ds；
 * - dx_rating 缺失时静默 ra=0 → B50 Rating 开天窗。
 *
 * 用例数据以真实曲库校准：落雪 1920 = Hurtling Boys ↔ 本地曲库 11920；
 * 落雪 70 = ジングルベル ↔ 本地 70（SD）/ 10070（DX 觉醒谱）。
 */
class MaimaiLxnsSyncTest {

    // ── 测试曲库（与真实曲库同构的最小集）──

    private val charts = listOf(
        // Hurtling Boys [DX] MASTER：本地 id 11920，定数 14.5（落雪 id = 1920）
        chart(songId = 11920, difficultyType = DifficultyType.MASTER, type = "DX", ds = 14.5),
        // ジングルベル SD MASTER：本地 id 70
        chart(songId = 70, difficultyType = DifficultyType.MASTER, type = "SD", ds = 12.4),
        // ジングルベル DX 觉醒 MASTER：本地 id 10070
        chart(songId = 10070, difficultyType = DifficultyType.MASTER, type = "DX", ds = 13.9),
        // 宴谱曲目（本地 id 与落雪一致，≥100000；难度索引按 0..4 口径对齐成绩键）
        chart(songId = 100018, difficultyType = DifficultyType.REMASTER, type = "DX", ds = 15.1),
    ).associateBy { MaimaiLxnsSyncTestAccess.chartKeyForTest(it.songId, it.difficultyType.ordinal, it.type) }

    private val songs = listOf(
        song(11920, "Hurtling Boys"),
        song(70, "ジングルベル"),
        song(10070, "ジングルベル"),
        song(100018, "宴会場テスト"),
    ).associateBy { it.id }

    // ── 用例 ──

    @Test
    fun `落雪 DX 成绩映射到本地 id+10000`() {
        val o = score(id = 1920, type = "dx", levelIndex = 3, dxRating = 15.09, achievements = 100.5)
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)
        assertNotNull(r)
        // Hurtling Boys：落雪 1920 → 本地 11920
        assertEquals(11920, r!!.songId)
        assertEquals("DX", r.type)
        assertEquals(15, r.ra) // 15.09 取整
        assertEquals(14.5, r.ds, 1e-9) // 曲库 11920 MASTER 命中
        assertEquals("Hurtling Boys", r.title)
    }

    @Test
    fun `落雪 standard 成绩 id 原样`() {
        val o = score(id = 70, type = "standard", levelIndex = 3, dxRating = 12.0, achievements = 99.0)
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)!!
        assertEquals(70, r.songId)
        assertEquals("SD", r.type)
        assertEquals(12.4, r.ds, 1e-9)
        assertEquals("ジングルベル", r.title)
    }

    @Test
    fun `落雪 DX 觉醒谱成绩映射到 id+10000 并命中觉醒谱定数`() {
        // 同一首 ジングルベル 的 DX 谱成绩：落雪 id=70 + type=dx → 本地 10070
        val o = score(id = 70, type = "dx", levelIndex = 3, dxRating = 13.2, achievements = 100.0)
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)!!
        assertEquals(10070, r.songId)
        assertEquals("DX", r.type)
        assertEquals(13.9, r.ds, 1e-9)
    }

    @Test
    fun `宴谱成绩两套体系同 id 原样保留`() {
        val o = score(id = 100018, type = "dx", levelIndex = 4, dxRating = 15.0, achievements = 99.5)
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)!!
        assertEquals(100018, r.songId)
        assertEquals(15.1, r.ds, 1e-9)
    }

    @Test
    fun `dx_rating 缺失时用本地定数补算 ra`() {
        // 100.5% × 定数 14.5，spp 口径：floor(14.5 × 1.005 × 22.4) = 326
        val o = score(id = 1920, type = "dx", levelIndex = 3, dxRating = 0.0, achievements = 100.5)
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)!!
        assertEquals(326, r.ra)
    }

    @Test
    fun `type 大小写容错统一转大写`() {
        val r1 = MaimaiLxnsSyncTestAccess.map(
            score(id = 1920, type = "DX", levelIndex = 3, dxRating = 15.0, achievements = 100.0),
            charts, songs,
        )!!
        assertEquals("DX", r1.type)
        val r2 = MaimaiLxnsSyncTestAccess.map(
            score(id = 70, type = "", levelIndex = 3, dxRating = 12.0, achievements = 99.0),
            charts, songs,
        )!!
        // type 缺省按 standard 处理
        assertEquals("SD", r2.type)
    }

    @Test
    fun `难度索引越界的成绩被丢弃`() {
        assertNull(
            MaimaiLxnsSyncTestAccess.map(
                score(id = 1920, type = "dx", levelIndex = 5, dxRating = 15.0, achievements = 100.0),
                charts, songs,
            )
        )
    }

    @Test
    fun `曲库未命中时标题回落落雪歌名且 ds 为 0`() {
        val o = score(id = 9999, type = "dx", levelIndex = 3, dxRating = 15.0, achievements = 100.0)
            .apply { addProperty("song_name", "落雪侧歌名") }
        val r = MaimaiLxnsSyncTestAccess.map(o, charts, songs)!!
        assertEquals(19999, r.songId)
        assertEquals("落雪侧歌名", r.title)
        assertEquals(0.0, r.ds, 1e-9)
    }

    // ── 构造辅助 ──

    private fun score(
        id: Int,
        type: String,
        levelIndex: Int,
        dxRating: Double,
        achievements: Double,
    ) = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type)
        addProperty("level_index", levelIndex)
        addProperty("dx_rating", dxRating)
        addProperty("achievements", achievements)
        addProperty("level", "14")
        addProperty("rate", "sssp")
        addProperty("fc", "fc")
        addProperty("fs", "fs")
    }

    private fun chart(
        songId: Int,
        difficultyType: DifficultyType,
        type: String,
        ds: Double,
    ) = ChartEntity(
        0,
        songId,
        difficultyType,
        type,
        ds,
        null,          // old_ds
        "14",          // level
        "-",           // charter
        0, 0, 0, 0, 0, // note1..5
        0,             // totalNotes
    )

    private fun song(id: Int, title: String) = SongDataEntity(
        id = id,
        title = title,
        titleKana = "",
        artist = "",
        imageUrl = "",
        genre = "",
        catCode = "",
        bpm = 0,
        from = "",
        type = if (id >= 10000) "DX" else "SD",
        version = "",
        isNew = false,
        kanji = null,
        comment = null,
        buddy = null,
    )
}

/**
 * 测试访问桥：[MaimaiLxnsSync.mapScore] 与 chartKey 是 internal + private，
 * 测试通过同包桥接对象调用（JVM 单测与主代码同模块，internal 可见）。
 */
object MaimaiLxnsSyncTestAccess {
    fun map(o: JsonObject, charts: Map<Long, ChartEntity>, songs: Map<Int, SongDataEntity>) =
        MaimaiLxnsSync.mapScore(o, charts, songs)

    fun chartKeyForTest(songId: Int, levelIndex: Int, type: String): Long =
        MaimaiLxnsSync.chartKey(songId, levelIndex, type)
}
