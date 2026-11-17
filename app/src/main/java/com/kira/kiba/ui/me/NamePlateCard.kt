package com.kira.kiba.ui.me

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.best50.NamePlateRenderer

/**
 * B50 页顶部那张「姓名框」卡片 —— 与导出的 Best 50 图片用的是**同一个渲染器**
 * （[NamePlateRenderer]），保证页面上看到的就是图里印的那张。
 *
 * 尺寸完全跟随名牌素材比例（720×116），头像是账户页那套「自定义 > 抓取 > 无」，
 * 名牌按本地成绩从已拿全的版本牌里按天随机，没有就默认牌。
 */
@Composable
fun NamePlateCard(
    nickname: String,
    rating: Int,
    oldRa: Int,
    newRa: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 渲染倍率：卡片实际宽 ~1080px（360dp@3x），1.6 倍足够清晰又不过分占内存
    val bitmap by produceState<android.graphics.Bitmap?>(null, nickname, rating, oldRa, newRa) {
        value = NamePlateRenderer.renderForAccount(
            context = context,
            nickname = nickname,
            rating = rating,
            oldRa = oldRa,
            newRa = newRa,
            scale = 1.6f,
        )
    }

    val bmp = bitmap
    if (bmp == null) {
        // 首帧 / 渲染失败：占位保持同比例，避免列表跳动
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(NamePlateRenderer.ASPECT)
                .clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color(0xFFE9EDF4)),
        )
        return
    }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "玩家姓名框",
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(NamePlateRenderer.ASPECT)
            .clip(RoundedCornerShape(14.dp)),
    )
}
