package com.kira.kiba.core.repository

import androidx.lifecycle.LiveData
import com.kira.kiba.core.db.dao.ChartStatsDao
import com.kira.kiba.core.db.entity.ChartStatsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChartStatsRepository private constructor(val chartStatsDao: ChartStatsDao) {
    companion object {
        @Volatile
        private var instance: ChartStatsRepository? = null

        fun getInstance(chartStatsDao: ChartStatsDao): ChartStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: ChartStatsRepository(chartStatsDao).also { instance = it }
            }
        }
    }

    suspend fun replaceAllChartStats(list: List<ChartStatsEntity>): Boolean {
        return withContext(Dispatchers.IO) {
            chartStatsDao.replaceAllChartStats(list)
        }
    }

    fun getChartStatsBySongIdAndDifficultyIndex(
        songId: Int,
        index: Int
    ): LiveData<ChartStatsEntity> {
        return chartStatsDao.getChartStatsBySongIdAndDifficultyIndex(songId, index)
    }
}