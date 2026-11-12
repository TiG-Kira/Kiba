package com.kira.kiba.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 大封面顶栏（歌曲详情 / 中二详情 / 集合页 / 账户页）折叠时的前景色同步。
 *
 * ## 为什么不能再用 `contentOffset < 0`
 *
 * 官方 `MiuixScrollBehavior` 在顶栏折叠阶段**独占**滚动量：手指上滑时
 * `heightOffset` 一路收到 `heightOffsetLimit`，列表一帧都不滚，
 * `contentOffset` 恒为 0。旧实现用它当「已折叠」的布尔，结果暗色封面
 * 折叠成浅色蒙版后，字 / 按钮 / 状态栏图标仍停在 `Color.White`，
 * 浅底白字完全读不出来；液态按钮材质也永远不启用。
 *
 * ## 同源、连续、但不逐帧重组
 *
 * 与 vendored `GlassTopAppBar` 里的 `coverSyncProgress` 读同一条
 * `collapsedFraction`（0=展开 / 1=完全折叠）—— 封面 alpha 是 `1-p`、
 * 蒙版 alpha 是 `p`，前景色也按 `p` 从封面色插值到系统色，三者同帧，
 * 不存在「动画先跑完、折叠慢半拍」或「折完再上滑才变色」。
 *
 * 但 `titleColor` / 图标 tint 是**组合期**参数，若在组合期直接读
 * `collapsedFraction` 并逐帧算 Color，整棵含 `glass()` 的顶栏（连带封面
 * 大图）会每帧重组，滑动中正好撞上封面淡出，表现成卡顿。所以这里用
 * `derivedStateOf` 把进度投影成 [COVER_TINT_STEPS] 档快照：只在跨档时
 * 重组（折叠全程最多 16 次），档间由 `BlurTopAppBar` 内既有的
 * `animateColorAsState(tween(50))` 抹平，视觉仍是连续渐变。
 *
 * @param coverColor 展开态按封面亮度取的前景色（暗封面→白，亮封面→深色）。
 * @param systemColor 折叠态的系统前景色，一般传 `MiuixTheme.colorScheme.onSurface`。
 * @param limitKey 顶栏展开量锚点（各页传 `minExpansionPx`）：`heightOffsetLimit`
 *   是普通字段不是 snapshot state，换页重新测量时靠它让 `derivedStateOf` 重建，
 *   避免卡在上一页的 limit 上算错进度。
 */
@Composable
fun rememberCoverTint(
    scrollBehavior: ScrollBehavior,
    coverColor: Color,
    systemColor: Color,
    limitKey: Int = 0,
): CoverTint {
    val color = remember(scrollBehavior, coverColor, systemColor, limitKey) {
        derivedStateOf {
            val p = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
            val q = (p * COVER_TINT_STEPS).toInt() / COVER_TINT_STEPS.toFloat()
            lerp(coverColor, systemColor, q)
        }
    }
    // 状态栏图标与液态按钮材质是布尔量，无法插值，在折叠过半时切到系统态。
    // 阈值取 0.5：此时蒙版 alpha 已过半，浅色底占主导，深色图标更可读。
    val systemAppearance = remember(scrollBehavior, limitKey) {
        derivedStateOf {
            scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f) >= SYSTEM_APPEARANCE_FRACTION
        }
    }
    return remember(color, systemAppearance) { CoverTint(color, systemAppearance) }
}

/** [rememberCoverTint] 的投影结果。 */
class CoverTint(
    /** 标题 / 副标题 / 返回 / 收藏 / 分享等前景色，随折叠从封面色渐变到系统色。 */
    val color: State<Color>,
    /** 是否已进入系统态：驱动状态栏图标外观与液态按钮材质。 */
    val systemAppearance: State<Boolean>,
)

/** 颜色插值档数：16 档在 200~400ms 的折叠里足够平滑，又把重组压到 16 次以内。 */
private const val COVER_TINT_STEPS = 16

/** 布尔外观（状态栏图标 / 液态按钮）的切换点。 */
const val SYSTEM_APPEARANCE_FRACTION = 0.5f

/** 封面亮度阈值：压暗系数作用后仍 >= 此值视为亮封面，用深色前景。 */
const val COVER_LIGHT_LUMINANCE = 0.60f

/** 亮封面上的深色前景（与系统 onSurface 接近，但固定不随主题反转）。 */
val COVER_DARK_FOREGROUND: Color = Color(0xFF1C1C1E)
