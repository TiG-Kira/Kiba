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
import com.kira.kiba.core.db.dao.ChuniSongWithChartsDao
import com.kira.kiba.core.db.dao.ChuniRecordDao
import com.kira.kiba.core.db.dao.RecordDao
import com.kira.kiba.core.db.dao.SongDao
import com.kira.kiba.core.db.dao.SongWithChartsDao
import com.kira.kiba.core.db.entity.AliasEntity
import com.kira.kiba.core.db.entity.ChuniChartEntity
import com.kira.kiba.core.db.entity.ChuniRecordEntity
import com.kira.kiba.core.db.entity.ChuniSongEntity
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.ChartStatsEntity
import com.kira.kiba.core.db.entity.ListIntConverter
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.utils.SpUtil
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongDataEntity::class, ChartEntity::class, AliasEntity::class, RecordEntity::class,
        ChartStatsEntity::class, ChuniSongEntity::class, ChuniChartEntity::class, ChuniRecordEntity::class,
    ],
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
    abstract fun chuniSongWithChartDao(): ChuniSongWithChartsDao
    abstract fun chuniRecordDao(): ChuniRecordDao


    companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "maimaidata_db"

        /** 1 → 2：新增中二节奏三张平行表（chuni_song / chuni_chart / chuni_record），不动舞萌原有表。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chuni_song` (
                        `id` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `titleKana` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `imageUrl` TEXT NOT NULL,
                        `genre` TEXT NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `from` TEXT NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chuni_chart` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `song_id` INTEGER NOT NULL,
                        `difficulty_index` INTEGER NOT NULL,
                        `difficulty` TEXT NOT NULL,
                        `level` TEXT NOT NULL,
                        `level_label` TEXT,
                        `ds` REAL NOT NULL,
                        `charter` TEXT NOT NULL,
                        `combo` INTEGER NOT NULL,
                        FOREIGN KEY(`song_id`) REFERENCES `chuni_song`(`id`) ON DELETE CASCADE)"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chuni_record` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `song_id` INTEGER NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `level_index` INTEGER NOT NULL,
                        `level_label` TEXT NOT NULL,
                        `level` TEXT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `fc` TEXT NOT NULL,
                        `ra` INTEGER NOT NULL,
                        `ds` REAL NOT NULL,
                        `is_recent` INTEGER NOT NULL DEFAULT 0)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chuni_chart_song_id` ON `chuni_chart`(`song_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chuni_record_song_id` ON `chuni_record`(`song_id`)")
            }
        }

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
                .addMigrations(MIGRATION_1_2)
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
