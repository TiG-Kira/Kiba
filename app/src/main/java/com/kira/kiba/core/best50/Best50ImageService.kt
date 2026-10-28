package com.kira.kiba.core.best50

import android.content.Context
import android.net.Uri
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.utils.CacheDirs
import com.kira.kiba.core.utils.CacheCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生成类图片的统一产物描述：文件落在 [CacheDirs.generated]（cacheDir，系统可清理），
 * 用户若想长期保留，再显式「保存到相册」（复制到 MediaStore）。
 */
data class GeneratedImage(
    val file: File,
    val displayName: String,
)

/**
 * Best 50 图片生成业务入口：取成绩 → 生成 PNG → 暂存 cacheDir/generated/。
 *
 * 注意：生成产物落在缓存区（不再是公共相册），避免每次生成都往用户相册永久堆积造成
 * 「异常占用」；系统「清除缓存」或应用空闲时的自动清理（[CacheCleaner]）都会回收它们。
 * 需要长期保留时调用 [saveToGallery] 显式存入相册。
 */
object Best50ImageService {

    /**
     * 生成 Best 50 图片并暂存到缓存。
     * @return [GeneratedImage]；无成绩数据或绘制异常时返回 null（原因见 [Best50ImageBuilder.lastError]）
     */
    suspend fun generate(
        context: Context,
        old: List<RecordEntity>,
        new: List<RecordEntity>,
        nickname: String,
        rating: Int,
    ): GeneratedImage? = withContext(Dispatchers.IO) {
        CacheCleaner.markGenerationActive()
        if (old.isEmpty() && new.isEmpty()) return@withContext null

        val charts = runCatching {
            AppDataBase.getInstance().chartDao().getAllChartsList()
        }.getOrDefault(emptyList())

        val bytes = Best50ImageBuilder.build(
            context = context,
            old = old,
            new = new,
            nickname = nickname,
            rating = rating,
            charts = charts,
        ) ?: return@withContext null

        val dir = CacheDirs.generated(context)
        val name = "Kiba_Best50_${System.currentTimeMillis()}.png"
        val file = File(dir, name)
        file.writeBytes(bytes)
        GeneratedImage(file, name)
    }

    /** 显式存入系统相册（MediaStore，Pictures/Kiba）；返回 Uri，失败 null。 */
    fun saveToGallery(context: Context, img: GeneratedImage): Uri? =
        ImageSaver.savePng(context, img.file.readBytes(), img.displayName)
}
