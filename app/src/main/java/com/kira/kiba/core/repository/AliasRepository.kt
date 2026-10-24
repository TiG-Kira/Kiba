package com.kira.kiba.core.repository

import androidx.lifecycle.LiveData
import com.kira.kiba.core.db.dao.AliasDao
import com.kira.kiba.core.db.entity.AliasEntity

class AliasRepository private constructor(private val aliasDao: AliasDao) {
    companion object {
        @Volatile
        private var instance: AliasRepository? = null
        fun getInstance(songChartDao: AliasDao): AliasRepository {
            if (instance == null) {
                instance = AliasRepository(songChartDao)
            }
            return instance!!
        }
    }

    /**
     * 通过歌曲id搜索别名列表
     */
    fun getAliasListBySongId(id: Int): LiveData<List<AliasEntity>> {
        return aliasDao.getAliasListBySongId(id)
    }
}

