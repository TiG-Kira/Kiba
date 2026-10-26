package com.kira.kiba.core.db.dao

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartStatsEntity

@Dao
interface ChartStatsDao {

    /**
     * 批量替换所有水鱼谱面统计数据。
     * @param chartStatsList 水鱼谱面统计列表
     * @return 操作结果
     */
    @Transaction
    fun replaceAllChartStats(chartStatsList: List<ChartStatsEntity>): Boolean {
        try {
            clearChartStats()
            insertAllChartStats(chartStatsList)
            return true
        } catch (e: Exception) {
            Log.e(
                AppDataBase.DATABASE_NAME, "Transaction replaceAllChartStats failed: ${e.message}"
            )
            return false
        }
    }

    /**
     * 根据谱面 ID 和难度索引查询水鱼谱面统计数据
     * @param songId 谱面 ID
     * @param index 普通谱面 0 = basic,1 = advanced,2 = expert,3 = master,4 = remaster，宴会场 0 = 单人谱面或者1p谱面 , 1 = 2p谱面
     * @return 水鱼谱面统计数据
     */
    @Query("SELECT * FROM chart_stats WHERE song_id = :songId AND level_index = :index")
    fun getChartStatsBySongIdAndDifficultyIndex(songId: Int, index: Int): LiveData<ChartStatsEntity>

    /** 单次查询（分享图等非响应式场景）。 */
    @Query("SELECT * FROM chart_stats WHERE song_id = :songId AND level_index = :index LIMIT 1")
    suspend fun getChartStatsOnce(songId: Int, index: Int): ChartStatsEntity?

    /** 全量查询（推荐算法 / 热门乐曲排行）。 */
    @Query("SELECT * FROM chart_stats")
    suspend fun getAllChartStatsList(): List<ChartStatsEntity>


    @Insert
    fun insertAllChartStats(list: List<ChartStatsEntity>)

    @Query("DELETE FROM chart_stats")
    fun clearChartStats()
}