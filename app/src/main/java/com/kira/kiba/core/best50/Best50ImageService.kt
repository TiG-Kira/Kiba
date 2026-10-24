package com.kira.kiba.core.best50

import android.content.Context
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChartEntity
import com.kira.kiba.core.db.entity.RecordEntity
import com.kira.kiba.core.model.DifficultyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best 50 图片生成业务入口：取成绩 → 生成 PNG → 存入系统相册。
 */
object Best50ImageService {

    /**
     * 生成 Best 50 图片并保存到相册。
     *
     * @return 人类可读的结果文案（成功说明保存位置，失败说明原因）
     */
    suspend fun generateAndSave(
        context: Context,
        old: List<RecordEntity>,
        new: List<RecordEntity>,
        nickname: String,
        rating: Int,
    ): String? = withContext(Dispatchers.IO) {
        if (old.isEmpty() && new.isEmpty()) return@withContext "生成失败：没有成绩数据"

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
        ) ?: return@withContext buildString {
            append("生成失败：图片绘制异常")
            Best50ImageBuilder.lastError.takeIf { it.isNotBlank() }?.let { append("（$it）") }
        }

        val name = "Kiba_Best50_${System.currentTimeMillis()}.png"
        val uri = ImageSaver.savePng(context, bytes, name)
            ?: return@withContext "生成失败：无法写入相册（检查存储权限）"
        "已保存到相册：Pictures/Kiba/$name"
    }
}
