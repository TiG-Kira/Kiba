package com.kira.kiba.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户自建歌单（「听」页「我的歌单」）。
 *
 * 歌单只存名称与创建时间；歌曲关系在 [PlaylistSongEntity]（一首歌可在多个歌单，
 * 用 position 记录用户拖排的顺序）。
 */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 歌单名
    val name: String,

    // 创建时间（epoch ms）
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 歌单 ↔ 曲目 关联表：songId 直接指向曲库 song_data.id（舞萌曲目）。
 *
 * position 保证歌单内顺序稳定（Room 查询按它排序）；删除歌单时级联清理歌曲。
 */
@Entity(
    tableName = "playlist_song",
    primaryKeys = ["playlist_id", "song_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlist_id"), Index("song_id")],
)
data class PlaylistSongEntity(
    // 所属歌单
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    // 曲库曲目 id（song_data.id）
    @ColumnInfo(name = "song_id")
    val songId: Int,

    // 歌单内顺序
    val position: Int,

    // 加入时间（epoch ms，同 position 打平手时兜底排序）
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
