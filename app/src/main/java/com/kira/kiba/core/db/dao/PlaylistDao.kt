package com.kira.kiba.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kira.kiba.core.db.entity.PlaylistEntity
import com.kira.kiba.core.db.entity.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「我的歌单」DAO（顶层类，勿嵌套——嵌套 @Dao 会炸 KSP，见项目记忆）。
 *
 * 读侧全部走 Flow（歌单/歌曲增删实时刷新 UI）；写侧提供事务级的
 * 「建歌单 + 首批歌曲」与「追加歌曲」入口。
 */
@Dao
interface PlaylistDao {

    // ── 歌单 ──

    @Query("SELECT * FROM playlist ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlist")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    /** 建歌单并写入首批歌曲（单事务：失败整体回滚，不留空歌单）。 */
    @Transaction
    suspend fun createWithSongs(playlist: PlaylistEntity, songs: List<PlaylistSongEntity>): Long {
        val playlistId = insertPlaylist(playlist)
        if (songs.isNotEmpty()) insertSongs(songs.map { it.copy(playlistId = playlistId) })
        return playlistId
    }

    // ── 歌单内歌曲 ──

    @Query(
        "SELECT * FROM playlist_song WHERE playlist_id = :playlistId " +
            "ORDER BY position ASC, added_at ASC",
    )
    fun observeSongs(playlistId: Long): Flow<List<PlaylistSongEntity>>

    /** 所有歌单的歌曲汇总（听页按歌单分组展示用，一次查回省 N+1）。 */
    @Query("SELECT * FROM playlist_song ORDER BY playlist_id, position, added_at")
    fun observeAllSongs(): Flow<List<PlaylistSongEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    /** 追加单曲到歌单尾部。 */
    @Transaction
    suspend fun appendSong(playlistId: Long, songId: Int) {
        val maxPos = maxPosition(playlistId) ?: -1
        insertSongs(
            listOf(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    position = maxPos + 1,
                    addedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    @Query("SELECT MAX(position) FROM playlist_song WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("DELETE FROM playlist_song WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removeSong(playlistId: Long, songId: Int)

    @Query("DELETE FROM playlist_song WHERE playlist_id = :playlistId")
    suspend fun clearSongs(playlistId: Long)
}
