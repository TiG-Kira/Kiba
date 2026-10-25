package com.kira.kiba.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.kira.kiba.core.best50.ImageSaver
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.stats.statsLevelIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 详情分享图业务入口：取数据 → 生成 PNG → 保存相册 / 分享给好友。
 *
 * 与 Best50ImageService 同模式：全部挂 IO，失败返回人类可读文案。
 */
object ShareService {

    private const val TAG = "ShareService"

    /** 分享图暂存目录（cacheDir/share，FileProvider cache-path 已覆盖）。 */
    private const val SHARE_DIR = "share"

    /**
     * 生成歌曲详情分享图。
     * @return PNG 字节；失败返回 null（原因见 [ShareImageBuilder.lastError]）
     */
    suspend fun buildSongPng(context: Context, songId: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val db = AppDataBase.getInstance()
            val song = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }
                .getOrNull() ?: return@withContext null
            val charts = runCatching { db.chartDao().getChartsBySongId(songId) }
                .getOrDefault(emptyList())
            ShareImageBuilder.buildSongImage(context, song, charts)
        }

    /**
     * 生成谱面详情分享图。
     * @return PNG 字节；失败返回 null
     */
    suspend fun buildChartPng(context: Context, songId: Int, difficultyOrdinal: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val db = AppDataBase.getInstance()
            val song = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }
                .getOrNull() ?: return@withContext null
            val chart = runCatching { db.chartDao().getChartsBySongId(songId) }
                .getOrDefault(emptyList())
                .firstOrNull { it.difficultyType.ordinal == difficultyOrdinal }
                ?: return@withContext null
            // 拟合定数（水鱼 chart_stats）：相对难度数据源；未同步 / 无数据时为 null → 底板显示「暂时缺少数据」
            val fitDiff = runCatching {
                db.chartStatsDao().getChartStatsOnce(songId, chart.difficultyType.statsLevelIndex())
            }.getOrNull()?.fitDiff
            ShareImageBuilder.buildChartImage(context, song, chart, fitDiff)
        }

    /** 保存 PNG 到系统相册（Pictures/Kiba）。 */
    fun saveToGallery(context: Context, bytes: ByteArray, displayName: String): Uri? =
        ImageSaver.savePng(context, bytes, displayName)

    /**
     * 分享 PNG 给其他应用（QQ / 微信 / 邮件…任何图片式分享路径）。
     *
     * 走 FileProvider（authorities = applicationId.fileprovider）：
     * 先把字节写进 cacheDir/share/，再以 content:// URI 发出，
     * 授予临时读权限——不需要任何存储权限。
     */
    fun sharePng(context: Context, bytes: ByteArray, fileName: String): Boolean = runCatching {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享到"))
        true
    }.onFailure { Log.w(TAG, "share failed", it) }.getOrDefault(false)

    /** 生成默认文件名（歌曲 / 谱面 + 时间戳）。 */
    fun defaultName(tag: String): String = "Kiba_${tag}_${System.currentTimeMillis()}.png"
}
