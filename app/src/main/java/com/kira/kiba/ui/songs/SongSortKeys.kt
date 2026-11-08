package com.kira.kiba.ui.songs

import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.SongSortKey
import com.kira.kiba.core.model.parseSongLevelValue

/**
 * 两套曲库实体 → 通用排序键的适配层。
 *
 * 两个函数刻意取不同名字：同包跨文件的同名 private 顶层声明在本项目里踩过
 * JVM 签名冲突的坑，这里沿用「中二侧一律 Chuni 前缀」的约定。
 */

/** 舞萌：全谱面最高定数 / 最高等级。 */
fun maimaiSongSortKey(song: SongWithChartsEntity): SongSortKey = SongSortKey(
    id = song.songData.id,
    title = song.songData.title,
    maxDs = song.charts.maxOfOrNull { it.ds } ?: 0.0,
    maxLevel = song.charts.maxOfOrNull { parseSongLevelValue(it.level) } ?: -1.0,
)

/** 中二节奏：口径与舞萌完全一致（WORLD'S END 的汉字等级解析失败会沉底）。 */
fun chuniSongSortKey(song: ChuniSongWithCharts): SongSortKey = SongSortKey(
    id = song.song.id,
    title = song.song.title,
    maxDs = song.charts.maxOfOrNull { it.ds } ?: 0.0,
    maxLevel = song.charts.maxOfOrNull { parseSongLevelValue(it.level) } ?: -1.0,
)
