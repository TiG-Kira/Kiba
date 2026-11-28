package com.kira.kiba.core.host

import android.content.Context
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.SpUtil

/**
 * 宿主能力内置实现（[AppExtensions.HostServices] 的 app 侧装配）。
 *
 * 契约本体在 core-host 模块（纯接口）；这里绑定 app 内部资源：
 * 曲库 / 谱面只读检索、外部成绩落库管线、常规源持久化读取。
 * KibaApplication.onCreate 调用 [install] 装配。
 */
object HostDefaults {

    /** 宿主能力的默认实现：曲库 / 谱面只读检索 + 轻提示。 */
    class DefaultHostServices : AppExtensions.HostServices {
        override suspend fun songSearch(query: String, limit: Int): List<AppExtensions.ExtSong> =
            runCatching {
                AppDataBase.getInstance().songDao().searchSongsList(query, limit).map {
                    AppExtensions.ExtSong(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        isNew = it.isNew,
                        type = it.type,
                        genre = it.genre,
                    )
                }
            }.getOrDefault(emptyList())

        override suspend fun songById(songId: Int): AppExtensions.ExtSong? = runCatching {
            AppDataBase.getInstance().songDao().getSongById(songId)?.let {
                AppExtensions.ExtSong(
                    id = it.id,
                    title = it.title,
                    artist = it.artist,
                    isNew = it.isNew,
                    type = it.type,
                    genre = it.genre,
                )
            }
        }.getOrNull()

        override suspend fun chartInfo(songId: Int, levelIndex: Int): AppExtensions.ExtChartInfo? =
            runCatching {
                // levelIndex 与官方难度序号对齐：0..4 = BASIC..REMASTER，10 = 宴会场
                val difficulty = when (levelIndex) {
                    0 -> DifficultyType.BASIC
                    1 -> DifficultyType.ADVANCED
                    2 -> DifficultyType.EXPERT
                    3 -> DifficultyType.MASTER
                    4 -> DifficultyType.REMASTER
                    10 -> DifficultyType.UTAGE
                    else -> return@runCatching null
                }
                AppDataBase.getInstance().chartDao().getChartsBySongId(songId)
                    .firstOrNull { it.difficultyType == difficulty }
                    ?.let { AppExtensions.ExtChartInfo(it.songId, levelIndex, it.ds, it.level) }
            }.getOrNull()

        override suspend fun chartNotes(songId: Int, levelIndex: Int): AppExtensions.ExtChartNotes? =
            runCatching {
                // levelIndex 与官方难度序号对齐：0..4 = BASIC..REMASTER，10 = 宴会场
                val difficulty = when (levelIndex) {
                    0 -> DifficultyType.BASIC
                    1 -> DifficultyType.ADVANCED
                    2 -> DifficultyType.EXPERT
                    3 -> DifficultyType.MASTER
                    4 -> DifficultyType.REMASTER
                    10 -> DifficultyType.UTAGE
                    else -> return@runCatching null
                }
                AppDataBase.getInstance().chartDao().getChartsBySongId(songId)
                    .firstOrNull { it.difficultyType == difficulty }
                    ?.let {
                        AppExtensions.ExtChartNotes(
                            songId = it.songId,
                            levelIndex = levelIndex,
                            tap = it.notesTap,
                            hold = it.notesHold,
                            slide = it.notesSlide,
                            touch = it.notesTouch,
                            breakCount = it.notesBreak,
                        )
                    }
            }.getOrNull()

        override fun toast(context: Context, message: String) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }

        override suspend fun publishRecords(
            records: List<AppExtensions.ExtRecord>,
            nickname: String,
            historySourceKey: String?,
        ) {
            if (historySourceKey == "direct") {
                // 机台直连源：独立 Rating 历史桶（与水鱼 / 落雪口径互不相干）
                com.kira.kiba.core.account.DivingFishAccount.publishExternalRecords(
                    records,
                    nickname,
                    historyKey = "maimai:direct",
                )
            } else {
                com.kira.kiba.core.account.DivingFishAccount.publishExternalRecords(
                    records,
                    nickname,
                    historySourceKey?.let { ScoreSource.fromKey(it) },
                )
            }
        }

        override fun publishError(message: String) {
            com.kira.kiba.core.account.DivingFishAccount.publishExternalError(message)
        }

        override fun rawScoreSourceKey(game: GameType): String? = runCatching {
            SpUtil.getScoreSource(game).key
        }.getOrNull()
    }

    /** KibaApplication.onCreate 装配；完整服务仅经 onHostReady 注入官方签名插件。 */
    fun install() {
        AppExtensions.attachHost(DefaultHostServices())
    }
}
