package com.kira.kiba.core.repository

import androidx.lifecycle.LiveData
import com.kira.kiba.core.db.dao.ChartDao
import com.kira.kiba.core.model.MaxNotesStats

class ChartRepository private constructor(private val chartDao: ChartDao) {
    companion object {
        @Volatile
        private var instance: ChartRepository? = null
        fun getInstance(chartDao: ChartDao): ChartRepository {
            if (instance == null) {
                instance = ChartRepository(chartDao)
            }
            return instance!!
        }
    }

    /**
     * 获取最大音符数据，用于展示图表
     */
    fun getMaxNotes(): LiveData<MaxNotesStats> {
        return chartDao.getMaxNotes()
    }

}
