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

}