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

    /**
     * 别名模糊搜索（Agent 查歌用：别名关键词 → 命中的别名行， songId 回表取歌）。
     * 精确命中与模糊命中都在结果里，优先级由调用层排序。
     */
    @Query("SELECT * FROM alias WHERE alias LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchAliasesSync(query: String, limit: Int = 50): List<AliasEntity>

    /** 按歌曲 ID 批量取别名（Agent 查歌回复里附带给模型做后续工具调用）。 */
    @Query("SELECT * FROM alias WHERE song_id IN (:songIds)")
    suspend fun getAliasesBySongIdsSync(songIds: List<Int>): List<AliasEntity>
}