package com.kira.kiba.core.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kira.kiba.core.db.entity.AliasEntity

@Dao
interface AliasDao {

    /**
     * 根据歌曲 ID 查询对应的别名列表。
     *
     * @param songId 歌曲的唯一标识 ID。
     * @return 包含该歌曲所有别名的 LiveData 列表，用于响应式更新 UI。
     */
    @Query("SELECT * FROM alias WHERE song_id = :songId")
    fun getAliasListBySongId(songId: Int): LiveData<List<AliasEntity>>

    /**
     * 根据歌曲 ID 查询对应的别名列表（挂起版，一次性读取）。
     *
     * 用于 Agent 猜歌判定：用户输入别名时要能和答案歌曲对上，
     * LiveData 版需要观察才有值，这里走挂起查询直接拿结果。
     */
    @Query("SELECT * FROM alias WHERE song_id = :songId")
    suspend fun getAliasesBySongIdSync(songId: Int): List<AliasEntity>

    @Insert
    fun insertAllAlias(aliasList: List<AliasEntity>)


    @Query("DELETE  FROM alias")
    fun clearAlias()
}