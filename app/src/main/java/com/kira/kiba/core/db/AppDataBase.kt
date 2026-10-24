package com.kira.kiba.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import com.kira.kiba.BuildConfig
import com.kira.kiba.core.db.AppDataBase.Companion.DATABASE_VERSION
import com.kira.kiba.core.db.dao.AliasDao
import com.kira.kiba.core.db.dao.ChartDao
import com.kira.kiba.core.db.dao.ChartStatsDao
import com.kira.kiba.core.db.dao.RecordDao
import com.kira.kiba.core.db.dao.SongDao
import com.kira.kiba.core.db.dao.SongWithChartsDao
import com.kira.kiba.core.db.entity.AliasEntity
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.ChartStatsEntity
import com.kira.kiba.core.db.entity.ListIntConverter
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.utils.SpUtil

@Database(
    entities = [SongDataEntity::class, ChartEntity::class, AliasEntity::class, RecordEntity::class, ChartStatsEntity::class],
    version = DATABASE_VERSION
)
@TypeConverters(ListIntConverter::class)
abstract class AppDataBase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun chartDao(): ChartDao
    abstract fun songWithChartDao(): SongWithChartsDao
    abstract fun aliasDao(): AliasDao
    abstract fun recordDao(): RecordDao
    abstract fun chartStatsDao(): ChartStatsDao


    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "maimaidata_db"

        @Volatile
        private lateinit var instance: AppDataBase


        fun getInstance(): AppDataBase {
            if (!::instance.isInitialized) {
                throw IllegalStateException("AppDataBase must be initialized first. Call init(context) before getInstance().")
            }
            return instance
        }

        fun init(context: Context): AppDataBase {
            instance = Room.databaseBuilder(
                context.applicationContext,
                AppDataBase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration(BuildConfig.DEBUG)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(connection: SQLiteConnection) {
                        SpUtil.setDataVersion("0")
                    }
                })
                .build()
            return instance
        }
    }


}
