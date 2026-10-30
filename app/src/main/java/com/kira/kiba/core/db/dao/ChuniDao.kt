package com.kira.kiba.core.db.dao

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import androidx.room.ColumnInfo

@Dao
interface ChuniSongDao {
    @Insert
    fun insertAllChuniSongs(list: List<ChuniSongEntity>)

    @Query("DELETE FROM chuni_song")
    fun clearChuniSong()

    @Query("SELECT * FROM chuni_song")
    suspend fun getAllChuniSongsList(): List<ChuniSongEntity>

    @Query("SELECT * FROM chuni_song WHERE id = :songId LIMIT 1")
    suspend fun getChuniSongById(songId: Int): ChuniSongEntity?
}

@Dao
interface ChuniChartDao {
    @Insert
    fun insertAllChuniCharts(list: List<ChuniChartEntity>)

    @Update
    fun updateChuniCharts(list: List<ChuniChartEntity>)

    @Query("DELETE FROM chuni_chart")
    fun clearChuniChart()

    @Query("SELECT * FROM chuni_chart WHERE song_id = :songId")
    suspend fun getChuniChartsBySongId(songId: Int): List<ChuniChartEntity>

    /** 全量谱面（落雪物量合并时用来挑出需要补齐的谱面）。 */
    @Query("SELECT * FROM chuni_chart")
    suspend fun getAllChuniChartsList(): List<ChuniChartEntity>

    /**
     * 轻量查询：返回所有谱面的版本编码（song_id, difficulty_index, version）。
     * 用于成绩同步时把每条记录的谱面 version 关联出来，喂给官方 Rating 公式。
     */
    @Query("SELECT song_id, difficulty_index, version FROM chuni_chart")
    suspend fun getChartVersions(): List<ChartVersionRow>
}

/** 谱面版本编码行（见 [ChuniChartDao.getChartVersions]）。 */
data class ChartVersionRow(
    @ColumnInfo(name = "song_id") val songId: Int,
    @ColumnInfo(name = "difficulty_index") val difficultyIndex: Int,
    @ColumnInfo(name = "version") val version: Int,
)

@Dao
interface ChuniRecordDao {
    @Transaction
    fun replaceAllChuniRecord(recordList: List<ChuniRecordEntity>): Boolean {
        return try {
            clearChuniRecord()
            insertAllChuniRecord(recordList)
            true
        } catch (e: Exception) {
            Log.e(AppDataBase.DATABASE_NAME, "replaceAllChuniRecord failed: ${e.message}")
            false
        }
    }

    @Insert
    fun insertAllChuniRecord(list: List<ChuniRecordEntity>)

    @Query("DELETE FROM chuni_record")
    fun clearChuniRecord()

    @Query("SELECT * FROM chuni_record")
    suspend fun getAllChuniRecordList(): List<ChuniRecordEntity>

    @Query("SELECT * FROM chuni_record WHERE song_id = :songId")
    fun getChuniRecordsBySongId(songId: Int): LiveData<List<ChuniRecordEntity>>
}

/**
 * 中二节奏歌曲 + 谱面联表 DAO（对标 [SongWithChartsDao]）。
 *
 * 中二节奏无宴会場（WORLD'S END 不在标准 music_data 内），故不做 genre 排除；
 * 列表默认按 id 降序（与舞萌一致）。
 */
@Dao
interface ChuniSongWithChartsDao :
    ChuniSongDao, ChuniChartDao, ChuniRecordDao {

    @Transaction
    fun replaceAllChuniSongsAndCharts(
        songList: List<ChuniSongEntity>,
        chartList: List<ChuniChartEntity>,
    ): Boolean {
        return try {
            clearChuniSong()
            clearChuniChart()
            insertAllChuniSongs(songList)
            insertAllChuniCharts(chartList)
            true
        } catch (e: Exception) {
            Log.e(AppDataBase.DATABASE_NAME, "replaceAllChuniSongsAndCharts failed: ${e.message}")
            false
        }
    }

    @Query("SELECT * FROM chuni_song ORDER BY id DESC")
    fun getAllChuniSongsWithCharts(): LiveData<List<ChuniSongWithCharts>>

    /**
     * 按搜索文本 / 收藏 / 流派筛选中二节奏歌曲。
     * @param searchText 主标题模糊匹配（空串 = 不过滤）。
     * @param isFavor 仅返回收藏歌曲（id 在 favIdList 中）。
     * @param favIdList 收藏歌曲 id 列表（仅 isFavor=1 时生效）。
     * @param isGenreListEmpty 流派筛选是否关闭。
     * @param genreList 流派白名单（chunithm 的 genre 如 "其他游戏" / "niconico"）。
     */
    @Query(
        """
        SELECT * FROM chuni_song
        WHERE (:searchText = '' OR title LIKE '%' || :searchText || '%')
          AND (:isGenreListEmpty = 1 OR genre IN (:genreList))
          AND ((:isFavor = 1 AND id IN (:favIdList)) OR :isFavor = 0)
        ORDER BY id DESC
        """
    )
    fun searchChuniSongsWithCharts(
        searchText: String,
        isGenreListEmpty: Boolean,
        genreList: List<String>,
        isFavor: Boolean,
        favIdList: List<String>,
    ): LiveData<List<ChuniSongWithCharts>>
}
