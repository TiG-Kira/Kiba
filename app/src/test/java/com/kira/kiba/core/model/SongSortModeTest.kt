package com.kira.kiba.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SongSortModeTest {

    private data class FakeSong(
        val name: String,
        val id: Int,
        val ds: Double,
        val level: String,
    )

    private fun keyOf(song: FakeSong) = SongSortKey(
        id = song.id,
        title = song.name,
        maxDs = song.ds,
        maxLevel = parseSongLevelValue(song.level),
    )

    private val songs = listOf(
        FakeSong("Beta", 100, 12.8, "13"),
        FakeSong("Alpha", 11920, 14.7, "14+"),
        FakeSong("Delta", 70, 9.3, "10+"),
        FakeSong("Unknown", 200, 0.0, "?"),
    )

    @Test
    fun `等级字符串解析与加号口径`() {
        assertEquals(13.5, parseSongLevelValue("13+"), 0.001)
        assertEquals(14.0, parseSongLevelValue("14"), 0.001)
        assertEquals(15.5, parseSongLevelValue("15+"), 0.001)
        assertEquals(7.0, parseSongLevelValue("7"), 0.001)
        // WORLD'S END 的汉字等级 / 未知等级：不能混进正常难度排序
        assertEquals(-1.0, parseSongLevelValue("狂"), 0.001)
        assertEquals(-1.0, parseSongLevelValue("?"), 0.001)
    }

    @Test
    fun `默认模式保持曲库顺序`() {
        assertEquals(songs, songs.sortedBySongMode(SongSortMode.DEFAULT, ::keyOf))
    }

    @Test
    fun `定数降序与升序互为镜像`() {
        val desc = songs.sortedBySongMode(SongSortMode.DS_DESC, ::keyOf).map { it.name }
        val asc = songs.sortedBySongMode(SongSortMode.DS_ASC, ::keyOf).map { it.name }
        assertEquals(listOf("Alpha", "Beta", "Delta", "Unknown"), desc)
        assertEquals(desc.reversed(), asc)
    }

    @Test
    fun `等级降序按解析后的等级值排`() {
        val got = songs.sortedBySongMode(SongSortMode.LEVEL_DESC, ::keyOf).map { it.name }
        assertEquals(listOf("Alpha", "Beta", "Delta", "Unknown"), got)
    }

    @Test
    fun `曲名升序且同键用 id 兜底保证稳定`() {
        // 曲名相同（大小写不同）时按 id 升序，结果可复现，不随 Room 返回顺序漂移
        val withTie = songs + FakeSong("alpha", 5, 12.8, "13")
        val got = withTie.sortedBySongMode(SongSortMode.TITLE_ASC, ::keyOf)
        assertEquals(listOf(5, 11920, 100, 70, 200), got.map { it.id })
    }

    @Test
    fun `未知 key 回落默认且所有模式都不丢歌`() {
        assertEquals(SongSortMode.DEFAULT, SongSortMode.fromKey("not-exist"))
        assertEquals(SongSortMode.DS_ASC, SongSortMode.fromKey("ds_asc"))
        SongSortMode.entries.forEach { mode ->
            assertEquals(songs.size, songs.sortedBySongMode(mode, ::keyOf).size)
        }
    }
}
