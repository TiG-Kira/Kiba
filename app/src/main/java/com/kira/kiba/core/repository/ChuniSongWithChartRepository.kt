package com.kira.kiba.core.repository

import androidx.lifecycle.LiveData
import com.kira.kiba.core.db.dao.ChuniSongWithChartsDao
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 中二节奏歌曲 + 谱面仓储（对标 [SongWithChartRepository]），操作平行表 chuni_*。
 */
class ChuniSongWithChartRepository private constructor(
    private val dao: ChuniSongWithChartsDao
) {
    companion object {
        @Volatile
        private var instance: ChuniSongWithChartRepository? = null
        fun getInstance(dao: ChuniSongWithChartsDao): ChuniSongWithChartRepository {
            if (instance == null) {
                instance = ChuniSongWithChartRepository(dao)
            }
            return instance!!
        }
    }

    /** 全量替换中二节奏歌曲 + 谱面（事务）。 */
    suspend fun updateDatabase(
        songs: List<ChuniSongEntity>,
        charts: List<ChuniChartEntity>,
    ): Boolean = withContext(Dispatchers.IO) {
        dao.replaceAllChuniSongsAndCharts(songs, charts)
    }

    /** 全量歌曲 + 谱面（列表用）。 */
    fun getAllChuniSongsWithCharts(): LiveData<List<ChuniSongWithCharts>> =
        dao.getAllChuniSongsWithCharts()

    /**
     * 搜索中二节奏歌曲（对标 [SongWithChartRepository.searchSongsWithCharts] 的常用通道）。
     * @param searchText 主标题模糊匹配（空 = 全量）。
     * @param isFavor 仅收藏歌曲。
     * @param genreList 流派白名单（空 = 不过滤）。
     */
    fun searchChuniSongsWithCharts(
        searchText: String,
        isFavor: Boolean,
        genreList: List<String> = emptyList(),
    ): LiveData<List<ChuniSongWithCharts>> {
        val favIds = if (isFavor) SpUtil.getFavIds(com.kira.kiba.core.model.GameType.CHUNITHM) else emptyList()
        return dao.searchChuniSongsWithCharts(
            searchText = searchText,
            isGenreListEmpty = genreList.isEmpty(),
            genreList = genreList,
            isFavor = isFavor,
            favIdList = favIds,
        )
    }
}
