package com.kira.kiba.ui.listen

import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.core.utils.CoverSources

/**
 * 曲库实体 → 可播放 Track（「听」页 / 歌单 / 下载共用，口径与曲目详情页一致）。
 */
fun SongWithChartsEntity.toTrack(): SongPlaybackController.Track =
    SongPlaybackController.Track(
        songId = songData.id,
        title = songData.title,
        artist = songData.artist,
        // imageUrl 是官网镜像的相对路径口径，UI 侧经 CoverFrame 候选链加载
        imageUrl = songData.imageUrl,
        genre = songData.genre,
        coverCandidates = CoverSources.candidates(songData.id, songData.imageUrl),
        audioUrl = SongPlaybackController.audioUrlFor(songData.id),
    )
