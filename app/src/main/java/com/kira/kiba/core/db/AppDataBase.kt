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
        const val DATABASE_VERSION = 5
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

        /**
         * 2 → 3：chuni_record.ra 由 INTEGER 改为 REAL。
         *
         * 中二单曲 ra 是浮点（14.53），之前落库被截断成整数，玩家 Rating 累加后整体偏低
         * （机台 14 多的号在 App 里只显示 7）。SQLite 无法直接改列类型，按官方推荐做法
         * 「建新表 → 搬数据 → 删旧表 → 改名」重建；只有中二表受影响，舞萌表不动。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 中二谱面表本次一并补上落雪的物量细分列（chuni_chart 是空表风险低，
                // 但仍按官方 addColumn 套路走，避免破坏已有数据）
                db.execSQL("ALTER TABLE `chuni_chart` ADD COLUMN `notes_tap` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chuni_chart` ADD COLUMN `notes_hold` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chuni_chart` ADD COLUMN `notes_slide` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chuni_chart` ADD COLUMN `notes_air` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chuni_chart` ADD COLUMN `notes_flick` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chuni_record_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `song_id` INTEGER NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `level_index` INTEGER NOT NULL,
                        `level_label` TEXT NOT NULL,
                        `level` TEXT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `fc` TEXT NOT NULL,
                        `ra` REAL NOT NULL,
                        `ds` REAL NOT NULL,
                        `is_recent` INTEGER NOT NULL DEFAULT 0)"""
                )
                db.execSQL(
                    """INSERT INTO `chuni_record_new`
                       (`id`,`song_id`,`cid`,`title`,`level_index`,`level_label`,`level`,`score`,`fc`,`ra`,`ds`,`is_recent`)
                       SELECT `id`,`song_id`,`cid`,`title`,`level_index`,`level_label`,`level`,`score`,`fc`,
                              CAST(`ra` AS REAL),`ds`,`is_recent`
                       FROM `chuni_record`"""
                )
                db.execSQL("DROP TABLE IF EXISTS `chuni_record`")
                db.execSQL("ALTER TABLE `chuni_record_new` RENAME TO `chuni_record`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chuni_record_song_id` ON `chuni_record`(`song_id`)"
                )
            }
        }

        /**
         * 3 → 4：chuni_chart 新增 version 列（谱面版本编码）。
         *
         * 该列用于官方 Rating 公式（VERSE 起）区分「当前版本 / 非当前版本」谱面。
         * 落雪 lxns 提供版本编码（24000 = X-VERSE 档，含 X-VERSE-X 新曲），曲库同步时补打标。
         * 用 ALTER ADD COLUMN 追加，仅中二表受影响，舞萌表与已有数据不动。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chuni_chart` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 4 → 5：chuni_record 新增 is_current_version 列（当前版本枠标记）。
         *
         * 落雪源的 `player/bests` 直接给官方分池（new_bests = 当前版本 B20），比按
         * `chuni_chart.version` 猜更权威；水鱼源没有这份数据，全列保持 0 走版本推断。
         * 追加列不影响已有成绩，老用户升级后 Best 页仍按原口径展示，只是多了一个可用信号。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chuni_record` ADD COLUMN `is_current_version` INTEGER NOT NULL DEFAULT 0"
                )
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
