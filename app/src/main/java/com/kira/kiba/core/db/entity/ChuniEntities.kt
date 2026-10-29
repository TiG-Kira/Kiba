package com.kira.kiba.core.db.entity

import android.os.Parcelable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.kira.kiba.core.model.ChuniDiffType
import kotlinx.parcelize.Parcelize

/**
 * 中二节奏歌曲实体（平行表 chuni_song，与舞萌 song_data 互不干扰）。
 *
 * 字段取自水鱼 chunithmprober/music_data 的 basic_info：
 * title / artist / genre / bpm / from（版本，如 "CHUNITHM" / "CHUNITHM AIR"）。
 * 中二节奏无 SD/DX 区分、无 catcode / is_new / 宴会場等舞萌专属字段。
 */
@Parcelize
@Entity(tableName = "chuni_song")
data class ChuniSongEntity(
    @PrimaryKey
    val id: Int,

    val title: String,

    val titleKana: String,

    val artist: String,

    val imageUrl: String,

    val genre: String,

    val bpm: Int,

    /** 版本（如 CHUNITHM / CHUNITHM AIR PLUS …），对应舞萌的 from。 */
    val from: String,
) : Parcelable

/**
 * 中二节奏谱面实体（平行表 chuni_chart）。
 *
 * 中二节奏谱面只有 5 个标准难度（BASIC/ADVANCED/EXPERT/MASTER/ULTIMA），
 * music_data 中未提供 TAP/HOLD/SLIDE 等音符细分，仅有「最大连击 combo」与谱师 charter，
 * 故谱面详情页做成精简版（见 ui 层），不展示音符构成 / BREAK 扣分线。
 */
@Parcelize
@Entity(
    tableName = "chuni_chart",
    foreignKeys = [androidx.room.ForeignKey(
        entity = ChuniSongEntity::class,
        parentColumns = ["id"],
        childColumns = ["song_id"],
        onDelete = androidx.room.ForeignKey.CASCADE
    )]
)
data class ChuniChartEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @androidx.room.ColumnInfo(name = "song_id", index = true)
    val songId: Int,

    /** 难度索引 0..4（与 chunithmprober records 的 level_index 一致）。 */
    @androidx.room.ColumnInfo(name = "difficulty_index")
    val difficultyIndex: Int,

    /** 难度名（BASIC/ADVANCED/EXPERT/MASTER/ULTIMA）。 */
    @androidx.room.ColumnInfo(name = "difficulty")
    val difficulty: String,

    /** 等级（如 "13+"）。 */
    val level: String,

    /** 等级标签（部分乐曲的等级后缀，可空）。 */
    @androidx.room.ColumnInfo(name = "level_label")
    val levelLabel: String?,

    /** 定数。 */
    val ds: Double,

    /** 谱师。 */
    val charter: String,

    /** 最大连击数（music_data 提供，替代舞萌的 notesTotal）。 */
    val combo: Int,
) : Parcelable {
    /** 难度类型枚举（UI 配色 / 标签用）。 */
    val diffType: ChuniDiffType get() = ChuniDiffType.fromIndex(difficultyIndex)
}

/**
 * 中二节奏成绩实体（平行表 chuni_record）。
 *
 * 取自水鱼 chunithmprober/player/records：mid / cid / title / level_index / level_label /
 * level / score / fc / ra / ds。中二成绩用 score（0–1009000）而非舞萌的 achievements 百分比，
 * 也无 dx_score / rate（SSS 等）字段。
 */
@Parcelize
@Entity(tableName = "chuni_record")
data class ChuniRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** 歌曲 id（records 中的 mid）。 */
    @androidx.room.ColumnInfo(name = "song_id", index = true)
    val songId: Int,

    /** 谱面 id（records 中的 cid）。 */
    val cid: Int,

    val title: String,

    @androidx.room.ColumnInfo(name = "level_index")
    val levelIndex: Int,

    @androidx.room.ColumnInfo(name = "level_label")
    val levelLabel: String,

    val level: String,

    /** 分数（0 – 1009000）。 */
    val score: Int,

    val fc: String,

    /** 该谱面获得的 Rating 贡献值。 */
    val ra: Int,

    val ds: Double,

    /** 是否为「最近游玩」成绩（中二 Rating = (best30 + recent10) / 40）。 */
    @androidx.room.ColumnInfo(name = "is_recent", defaultValue = "0")
    val isRecent: Boolean = false,
) : Parcelable

/** 中二节奏歌曲 + 谱面联表（对标 SongWithChartsEntity，用于列表 / 详情渲染）。 */
@Parcelize
data class ChuniSongWithCharts(
    @Embedded val song: ChuniSongEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "song_id"
    )
    val charts: List<ChuniChartEntity>,
) : Parcelable
