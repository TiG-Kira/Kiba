package com.kira.kiba.ui.songs

import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.model.SongVersionOrder

/**
 * 曲目列表的一个归类分组。
 *
 * @param key 分组键（版本名 / 谱师名 / 曲师名，唯一）
 * @param name 展示名（当前与 key 相同，保留字段以便后续本地化）
 * @param songIds 组内歌曲 id（用于子页面过滤）
 * @param rank 排序分值（越大越靠前）；版本分组用版本时间轴，其余分组用歌曲数
 */
data class SongGroupEntry(
    val key: String,
    val name: String,
    val songIds: List<Int>,
    val rank: Int = 0,
) {
    val count: Int get() = songIds.size
}

/**
 * 按归类方式把舞萌歌曲列表分组。
 *
 * - [SongGroupMode.VERSION] 按版本大类（`from`，如「舞萌DX 2024」「maimai GreeN」）；
 * - [SongGroupMode.CHARTER] 按 MASTER 谱师（无 MASTER 谱面或谱师为「-」的歌曲不进组）；
 * - [SongGroupMode.ARTIST] 按曲师（艺术家，空名归「—」）；
 * - [SongGroupMode.NONE] 返回空列表（调用方走平铺分支）。
 *
 * 排序：**按版本 → 版本时间轴倒序（新 → 旧）**；谱师 / 曲师 → 组内歌曲数降序，
 * 同数按名称排（中文按 Unicode，稳定即可）。
 */
fun buildSongGroups(
    songs: List<SongWithChartsEntity>,
    mode: SongGroupMode,
): List<SongGroupEntry> {
    if (mode == SongGroupMode.NONE) return emptyList()
    if (mode == SongGroupMode.VERSION) {
        return songs
            .groupBy { it.songData.from.ifBlank { UNKNOWN_VERSION } }
            .map { (key, list) ->
                SongGroupEntry(
                    key = key,
                    name = key,
                    songIds = list.map { it.songData.id },
                    rank = SongVersionOrder.rank(GameType.MAIMAI, key),
                )
            }
            // 版本倒序：越新的版本越靠上（分值相同时按名字，保证稳定）
            .sortedWith(compareByDescending<SongGroupEntry> { it.rank }.thenBy { it.name })
    }
    val grouped = when (mode) {
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
        // VERSION 走上面的时间轴分支；NONE 调用方根本不该进来
        SongGroupMode.VERSION, SongGroupMode.NONE -> return emptyList()
    }
    return grouped.map { (key, list) ->
        SongGroupEntry(key = key, name = key, songIds = list.map { it.songData.id })
    }.sortedWith(compareByDescending<SongGroupEntry> { it.count }.thenBy { it.name })
}

/**
 * 按归类方式把中二节奏歌曲列表分组（目前只支持 [SongGroupMode.VERSION]）。
 *
 * 排序口径与舞萌完全一致：版本时间轴倒序（新 → 旧）。中二曲库没有「MASTER 谱师 /
 * 曲师」这两个归类的产品口径，UI 侧只给「不归类 / 按版本」两项，故其余 mode 返回空。
 */
fun buildChuniSongGroups(
    songs: List<ChuniSongWithCharts>,
    mode: SongGroupMode,
): List<SongGroupEntry> {
    if (mode != SongGroupMode.VERSION) return emptyList()
    return songs
        .groupBy { it.song.from.ifBlank { UNKNOWN_VERSION } }
        .map { (key, list) ->
            SongGroupEntry(
                key = key,
                name = key,
                songIds = list.map { it.song.id },
                rank = SongVersionOrder.rank(GameType.CHUNITHM, key),
            )
        }
        .sortedWith(compareByDescending<SongGroupEntry> { it.rank }.thenBy { it.name })
}

/** 判断歌曲是否属于某个分组（子页面过滤用）。 */
fun songInGroup(song: SongWithChartsEntity, mode: SongGroupMode, key: String): Boolean =
    when (mode) {
        SongGroupMode.VERSION -> song.songData.from.ifBlank { UNKNOWN_VERSION } == key
        SongGroupMode.CHARTER ->
            song.charts.firstOrNull { it.difficultyType == DifficultyType.MASTER }
                ?.charter?.trim() == key
        SongGroupMode.ARTIST -> song.songData.artist.ifBlank { "—" } == key
        SongGroupMode.NONE -> false
    }

/** 中二节奏版的 [songInGroup]（目前只有版本维度）。 */
fun chuniSongInGroup(song: ChuniSongWithCharts, mode: SongGroupMode, key: String): Boolean =
    mode == SongGroupMode.VERSION && song.song.from.ifBlank { UNKNOWN_VERSION } == key

/** 版本名缺失时的兜底组名（与 [SongVersionOrder] 的 -1 分值一起沉到列表底部）。 */
private const val UNKNOWN_VERSION = "未知版本"
