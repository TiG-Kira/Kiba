package com.kira.kiba.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kira.kiba.core.db.entity.SongDataEntity

@Dao
interface SongDao {

    @Insert
    fun insertAllSongs(songDataList: List<SongDataEntity>)

    @Query("DELETE FROM song_data")
    fun clearSongData()

    /** 全量歌曲（Agent / 进度统计用，内存join）。 */
    @Query("SELECT * FROM song_data")
    suspend fun getAllSongsList(): List<SongDataEntity>

    /** 标题模糊搜索（Agent 查歌用；别名匹配在上一层做）。 */
    @Query("SELECT * FROM song_data WHERE title LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchSongsList(query: String, limit: Int = 50): List<SongDataEntity>

    /** 随机一首（Agent 随机选曲用）。 */
    @Query("SELECT * FROM song_data ORDER BY RANDOM() LIMIT 1")
    suspend fun randomSong(): SongDataEntity?

    /** 按 id 取单首（Agent 猜歌判定用：提交答案时按 id 拿回答案歌曲）。 */
    @Query("SELECT * FROM song_data WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: Int): SongDataEntity?

}