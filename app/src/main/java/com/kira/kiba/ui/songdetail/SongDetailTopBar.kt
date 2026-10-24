package com.kira.kiba.ui.songdetail

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.coverCandidateUrls
import com.kira.kiba.ui.navigation.KibaBackAction
import com.kira.kiba.ui.navigation.LocalGlassTopAppBarBackdrop
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌曲详情页魔改顶栏（基于 miuix-glass GlassTopAppBar）：
 * - 展开态：封面原图铺满顶栏区域作背景（**不模糊、不暗化**，封面偏白时自动切黑字、
 *   偏暗时切白字）；大标题 = 歌名，小标题 = 艺术家；返回按钮沿用现有 glass 返回样式
 *   （未滚动裸图标、滚动后背板浮现）；actions = 收藏 star 按钮（代替原收藏开关）。
 * - 折叠：沿用现有 [ScrollBehavior] 折叠逻辑，折叠后立刻显示现有渐隐 TopBar 背板
 *   （封面背景与背板 band 共用同一 mask 时钟，此消彼长）。
 * - [GlassTopAppBar.background] 槽位为本页在 vendored miuix-glass 中新增的扩展。
 */
@Composable
fun SongDetailTopBarSlot(
    songId: Int,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
) {
    val db = remember { AppDataBase.getInstance() }

    val song by produceState<SongDataEntity?>(initialValue = null, songId) {
        value = runCatching { db.songDao().getAllSongsList().firstOrNull { it.id == songId } }.getOrNull()
    }
    val current = song

    var favor by remember(songId) { mutableStateOf(SpUtil.isFavorite(songId.toString())) }

    // 封面探测：加载首个命中的候选封面，同时算平均亮度决定字色（白底→黑字，暗底→白字）
    val context = LocalContext.current
    val probe by produceState<CoverProbe?>(initialValue = null, songId, current?.imageUrl) {
        val s = current ?: return@produceState
        for (url in coverCandidateUrls(s.id, s.imageUrl)) {
            val bmp = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // 要读像素算亮度，禁用 hardware bitmap
                        .build()
                ).drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
            }.getOrNull()
            if (bmp != null) {
                value = CoverProbe(bmp, averageLuminance(bmp))
                break
            }
        }
    }

    // 字色：封面平均亮度高（偏白）→ 黑字；否则白字。未加载时退回主题色。
    val probeValue = probe
    val onCoverColor = when {
        probeValue == null -> MiuixTheme.colorScheme.onSurface
        probeValue.luminance >= 0.60f -> Color(0xFF1C1C1E)
        else -> Color.White
    }

    // 展开态高度：保证完整露出正方形封面（顶栏宽 ≈ 屏宽，高度 = 状态栏 + 屏宽）。
    // minExpansion = 屏宽 - 状态栏 - CollapsedHeight（collapsed 部分 BlurTopAppBar 自带）。
    val density = LocalDensity.current
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val minExpansionPx = (screenWidthPx - statusBarPx - collapsedPx).coerceAtLeast(0)

    GlassTopAppBar(
        title = current?.title ?: "",
        largeTitle = current?.title ?: "",
        subtitle = current?.artist ?: "",
        titleColor = onCoverColor,
        largeTitleColor = onCoverColor,
        subtitleColor = onCoverColor.copy(alpha = 0.72f),
        backdrop = LocalGlassTopAppBarBackdrop.current,
        scrollBehavior = scrollBehavior,
        navigationIcon = { KibaBackAction(onClick = onBack, tint = onCoverColor) },
        actions = {
            GlassIconButton(onClick = {
                favor = !favor
                SpUtil.setFavorite(songId.toString(), favor)
            }) {
                Icon(
                    if (favor) MiuixGlassIcons.Regular.Starred else MiuixGlassIcons.Regular.Unstar,
                    contentDescription = if (favor) "取消收藏" else "收藏",
                    tint = onCoverColor,
                )
            }
        },
        background = {
            probeValue?.let { p ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = p.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        minExpansionPx = minExpansionPx,
    )
}

/** 封面背景探测结果：位图（直接用于背景显示）+ 平均亮度（0~1）。 */
private data class CoverProbe(val bitmap: Bitmap, val luminance: Float)

/** 24×24 下采样平均亮度（忽略透明像素），用于白底封面切黑字。 */
private fun averageLuminance(src: Bitmap): Float {
    val small = Bitmap.createScaledBitmap(src, 24, 24, true)
    val px = IntArray(24 * 24)
    small.getPixels(px, 0, 24, 0, 0, 24, 24)
    var total = 0f
    var count = 0
    for (p in px) {
        val a = (p ushr 24) and 0xFF
        if (a < 128) continue
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        total += 0.2126f * r + 0.7152f * g + 0.0722f * b
        count++
    }
    if (count == 0) return 0f
    return (total / count).coerceIn(0f, 1f)
}
