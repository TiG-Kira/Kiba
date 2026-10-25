package com.kira.kiba.ui.songs

import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.model.SongGroupMode

/**
 * 曲目列表的一个归类分组。
 *
 * @param key 分组键（版本名 / 谱师名 / 曲师名，唯一）
 * @param name 展示名（当前与 key 相同，保留字段以便后续本地化）
 * @param songIds 组内歌曲 id（用于子页面过滤）
 */
data class SongGroupEntry(
    val key: String,
    val name: String,
    val songIds: List<Int>,
) {
    val count: Int get() = songIds.size
}

/**
 * 按归类方式把歌曲列表分组。
 *
 * - [SongGroupMode.VERSION] 按版本大类（`from`，如「舞萌DX 2024」「maimai GreeN」）；
 * - [SongGroupMode.CHARTER] 按 MASTER 谱师（无 MASTER 谱面或谱师为「-」的歌曲不进组）；
 * - [SongGroupMode.ARTIST] 按曲师（艺术家，空名归「—」）；
 * - [SongGroupMode.NONE] 返回空列表（调用方走平铺分支）。
 *
 * 排序：组内歌曲数降序，同数按名称排（中文按 Unicode，稳定即可）。
 */
fun buildSongGroups(
    songs: List<SongWithChartsEntity>,
    mode: SongGroupMode,
): List<SongGroupEntry> {
    if (mode == SongGroupMode.NONE) return emptyList()
    val grouped = when (mode) {
        SongGroupMode.VERSION -> songs.groupBy { it.songData.from.ifBlank { "未知版本" } }
        SongGroupMode.CHARTER -> songs.mapNotNull { song ->
            val charter = song.charts
                .firstOrNull { it.difficultyType == DifficultyType.MASTER }
                ?.charter
                ?.trim()
            if (charter.isNullOrEmpty() || charter == "-") {
                null
            } else {
                charter to song
            }
        }.groupBy({ it.first }, { it.second })
        SongGroupMode.ARTIST -> songs.groupBy { it.songData.artist.ifBlank { "—" } }
        SongGroupMode.NONE -> return emptyList()
    }
    return grouped.map { (key, list) ->
        SongGroupEntry(key = key, name = key, songIds = list.map { it.songData.id })
    }.sortedWith(compareByDescending<SongGroupEntry> { it.count }.thenBy { it.name })
}

/** 判断歌曲是否属于某个分组（子页面过滤用）。 */
fun songInGroup(song: SongWithChartsEntity, mode: SongGroupMode, key: String): Boolean =
    when (mode) {
        SongGroupMode.VERSION -> song.songData.from.ifBlank { "未知版本" } == key
        SongGroupMode.CHARTER ->
            song.charts.firstOrNull { it.difficultyType == DifficultyType.MASTER }
                ?.charter?.trim() == key
        SongGroupMode.ARTIST -> song.songData.artist.ifBlank { "—" } == key
        SongGroupMode.NONE -> false
    }
