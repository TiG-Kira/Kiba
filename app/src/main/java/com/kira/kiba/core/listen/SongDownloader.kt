package com.kira.kiba.core.listen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.utils.CoverFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 乐曲下载管理（播放页「下载」入口 + 听页「我的下载」）。
 *
 * ## 落盘口径
 * 整轨 mp3 从落雪 CDN 拉回后，先用 [Id3Writer] 拼 ID3v2.3 头
 * （标题 / 艺术家 / 专辑 / PNG 封面，口径与曲目详情页一致——标题用官方曲名、
 * 艺术家用曲师名、封面走 CoverFetcher 候选链），再经 MediaStore 写入
 * `Music/Kiba/`，系统音乐 App 可直接识别完整元数据。
 *
 * ## 索引
 * songId → MediaStore uri 记在自管 SP（与 SpUtil 解耦，避免主配置文件膨胀）；
 * 查询时逐条校验文件仍存在（用户手动删了文件就视为未下载，不会出现
 * 点了播不出的死链）。
 */
object SongDownloader {

    private const val TAG = "SongDownloader"
    private const val PREF = "listen_downloads"
    private const val KEY_INDEX = "download_index"
    private const val RELATIVE_DIR = "Music/Kiba"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 一条已下载记录（听页「我的下载」列表项）。 */
    data class DownloadedSong(
        val songId: Int,
        val uri: Uri,
        val title: String,
        val artist: String,
        /** 文件大小（bytes），UI 展示用。 */
        val sizeBytes: Long,
    )

    // ── 索引读写 ──

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun loadIndex(context: Context): MutableMap<Int, String> {
        val json = prefs(context).getString(KEY_INDEX, "{}") ?: "{}"
        return runCatching {
            gson.fromJson<MutableMap<Int, String>>(json, object : TypeToken<MutableMap<Int, String>>() {}.type)
        }.getOrNull() ?: mutableMapOf()
    }

    private fun saveIndex(context: Context, index: Map<Int, String>) {
        prefs(context).edit().putString(KEY_INDEX, gson.toJson(index)).apply()
    }

    /** 是否已下载（校验文件真实存在，防 SP 死链）。 */
    fun isDownloaded(context: Context, songId: Int): Boolean =
        query(context, songId) != null

    /** 查单条下载记录（文件被手动删则从索引摘除）。 */
    fun query(context: Context, songId: Int): DownloadedSong? {
        val uriStr = loadIndex(context)[songId] ?: return null
        val uri = Uri.parse(uriStr)
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.SIZE,
                ),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    DownloadedSong(
                        songId = songId,
                        uri = uri,
                        title = c.getString(0) ?: "未知曲目",
                        artist = c.getString(1) ?: "未知艺术家",
                        sizeBytes = c.getLong(2),
                    )
                } else null
            }
        }.getOrNull().also {
            if (it == null) {
                // 文件已不在：摘除死链
                val index = loadIndex(context)
                index.remove(songId)
                saveIndex(context, index)
            }
        }
    }

    /** 全部下载记录（听页「我的下载」）。 */
    fun listAll(context: Context): List<DownloadedSong> =
        loadIndex(context).keys.mapNotNull { query(context, it) }.sortedBy { it.title }

    // ── 下载 ──

    /**
     * 下载整轨并落盘（IO 线程，调用方在协程里）。
     *
     * @param songId 曲目 id（本地口径，含 DX +10000）
     * @param title / artist 元数据口径与曲目详情页一致
     * @param audioUrl 整轨 mp3 直链（[SongPlaybackController.audioUrlFor]）
     * @param coverCandidates 封面候选链（CoverFetcher 同款口径）
     * @return 落盘后的 MediaStore uri；失败抛异常由 UI 提示
     */
    suspend fun download(
        context: Context,
        songId: Int,
        title: String,
        artist: String,
        audioUrl: String,
        coverCandidates: List<String>,
    ): Uri = withContext(Dispatchers.IO) {
        // 1) 拉整轨
        val mp3 = client.newCall(
            Request.Builder().url(audioUrl).build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "音源 HTTP ${resp.code}" }
            resp.body?.bytes() ?: throw IllegalStateException("空响应")
        }

        // 2) 封面（候选链逐个试；拿不到就只写文本元数据）
        val coverPng = runCatching {
            CoverFetcher.fetch(context, coverCandidates)?.let { bmp ->
                java.io.ByteArrayOutputStream().use { bos ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos)
                    bmp
                    bos.toByteArray()
                }
            }
        }.getOrNull()

        // 3) 拼 ID3 + 写 MediaStore
        val tagged = Id3Writer.withTags(mp3, title, artist, "Kiba · maimai", coverPng)
        val fileName = sanitize(title) + "_" + (songId % 10000) + ".mp3"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, "Kiba · maimai")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            // Q 以下走真实路径写文件后扫描（minSdk 33，此分支理论不可达，兜底而已）
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore 写入被拒绝")
        try {
            resolver.openOutputStream(uri)?.use { os -> os.write(tagged) }
                ?: throw IllegalStateException("输出流打开失败")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // 写坏即撤，不留 0 字节垃圾
            throw e
        }

        val index = loadIndex(context)
        index[songId] = uri.toString()
        saveIndex(context, index)
        uri
    }

    /** 删除下载文件并摘除索引。返回是否真删了东西。 */
    fun delete(context: Context, songId: Int): Boolean {
        val uriStr = loadIndex(context)[songId] ?: return false
        val deleted = runCatching {
            context.contentResolver.delete(Uri.parse(uriStr), null, null) > 0
        }.getOrDefault(false)
        val index = loadIndex(context)
        index.remove(songId)
        saveIndex(context, index)
        return deleted
    }

    /** 文件名消毒：去掉文件系统保留字符（曲名含 `/?:*"<>|` 很常见）。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim()
}
