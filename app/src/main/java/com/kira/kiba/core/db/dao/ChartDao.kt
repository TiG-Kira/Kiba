package com.kira.kiba.core.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.model.MaxNotesStats

@Dao
interface ChartDao {

    /**
     * 查询 chart 表中各类音符的最大值（排除 UTAGE 和 UTAGE_PLAYER2）。
     * 返回一个包含最大值的 MaxNotesStats。
     */
    @Query(
        """
        SELECT 
            MAX(notes_tap) AS tap,
            MAX(notes_hold) AS hold,
            MAX(notes_slide) AS slide,
            MAX(notes_touch) AS touch,
            MAX(notes_break) AS break_,
            MAX(notes_total) AS total
        FROM chart
        WHERE difficulty_type NOT IN ('UTAGE', 'UTAGE_PLAYER2')
    """
    )
    fun getMaxNotes(): LiveData<MaxNotesStats>


    @Insert
    fun insertAllCharts(chartList: List<ChartEntity>)


    @Query("DELETE FROM chart")
    fun clearCharts()

    /** 全量谱面（Agent / 进度统计用，内存join）。 */
    @Query("SELECT * FROM chart")
    suspend fun getAllChartsList(): List<ChartEntity>

    /** 按歌曲查谱面（Agent 查歌详情用）。 */
    @Query("SELECT * FROM chart WHERE song_id = :songId")
    suspend fun getChartsBySongId(songId: Int): List<ChartEntity>
}