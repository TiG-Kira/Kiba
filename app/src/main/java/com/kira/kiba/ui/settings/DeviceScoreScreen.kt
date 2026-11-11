package com.kira.kiba.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.utils.DeviceGrade
import com.kira.kiba.core.utils.DeviceScore
import com.kira.kiba.core.utils.DeviceTier
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.theme.KibaMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备性能评分页（2.6.0 从设置页摘要里独立出来）。
 *
 * 之前评分把「分数 + 档位 + 六项明细 + 重新评分」全塞进一行 preference 的副标题里，
 * 三行小字挤在卡片行高内，既看不清也不好点。现在：
 * - 设置页那一行只留「点击了解设备性能」；
 * - 本页展示总分 / 档位、逐项得分条、重新评分按钮、以及**被禁用的功能清单**
 *   （低于 55 分禁用真·高级材质、低于 15 分禁用全部装饰性动画）。
 */
@Composable
fun DeviceScoreScreen(
    scrollBehavior: ScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 进页面直接用缓存的评测结果；没有就现算一次（评分本身是读系统静态信息，很快）
    var score by remember { mutableStateOf(DeviceGrade.cachedOrEvaluate(context)) }
    var scoring by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "total") { TotalScoreCard(score, scoring) }

            item(key = "detail_title") { SectionTitle("评分明细") }
            item(key = "detail") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        score.details.forEachIndexed { index, (label, value) ->
                            val max = score.detailMax.getOrNull(index) ?: 0
                            DetailRow(
                                label = label,
                                value = value,
                                max = max,
                                isLast = index == score.details.lastIndex,
                            )
                        }
                    }
                }
            }

            item(key = "rescore") {
                Button(
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !scoring,
                    onClick = {
                        if (scoring) return@Button
                        scoring = true
                        notice = null
                        scope.launch {
                            val grade = withContext(Dispatchers.IO) { DeviceGrade.evaluate(context) }
                            SpUtil.setDeviceScore(grade.total)
                            KibaMotion.syncFromScore(grade.total)
                            withContext(Dispatchers.Main) {
                                score = grade
                                scoring = false
                                // 掉到材质门槛以下：顺手关掉开关，别等下次进设置页才提示
                                val wasOn = SpUtil.isAdvancedMaterialEnabled()
                                if (grade.total < DeviceGrade.PASS_SCORE && wasOn) {
                                    SpUtil.setAdvancedMaterialEnabled(false)
                                    notice = "评分低于 ${DeviceGrade.PASS_SCORE} 分，已自动关闭真·高级材质" +
                                        "（重启 App 后完全生效）"
                                } else if (grade.total < KibaMotion.ANIMATION_FLOOR_SCORE) {
                                    notice = "评分低于 ${KibaMotion.ANIMATION_FLOOR_SCORE} 分，" +
                                        "App 内装饰性动画已关闭"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (scoring) "正在重新评测…" else "重新评分")
                }
            }

            // 重新评分的结果提示（掉分导致的自动关材质 / 关动画）
            notice?.let { msg ->
                item(key = "notice") {
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }

            item(key = "features_title") { SectionTitle("受评分影响的功能") }
            item(key = "features") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        FeatureGateRow(
                            title = "真·高级材质",
                            threshold = "≥ ${DeviceGrade.PASS_SCORE} 分",
                            enabled = score.total >= DeviceGrade.PASS_SCORE,
                            detail = if (score.total >= DeviceGrade.PASS_SCORE) {
                                "已解锁：顶栏 / 底栏 / 弹层可使用玻璃材质与折射效果"
                            } else {
                                "未解锁：玻璃材质链路（模糊 + 多次着色器采样）在低分设备上会明显掉帧，" +
                                    "统一回落标准 miuix 质感"
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        FeatureGateRow(
                            title = "界面动画",
                            threshold = "≥ ${KibaMotion.ANIMATION_FLOOR_SCORE} 分",
                            enabled = score.total >= KibaMotion.ANIMATION_FLOOR_SCORE,
                            detail = if (score.total >= KibaMotion.ANIMATION_FLOOR_SCORE) {
                                "已开启：页面转场 / 顶栏折叠 / 按压缩放"
                            } else {
                                "已关闭：页面转场、顶栏下沉、按压缩放等装饰性动画全部停用；" +
                                    "加载圈与进度条保留（它们代表「正在处理」）"
                            },
                        )
                    }
                }
            }

            item(key = "note") {
                Text(
                    text = "评分口径：内存 ${DeviceGrade.W_RAM} 分、CPU 核数 ${DeviceGrade.W_CORES} 分、" +
                        "主频 ${DeviceGrade.W_FREQ} 分、刷新率 ${DeviceGrade.W_REFRESH} 分、" +
                        "GL ES ${DeviceGrade.W_GLES} 分、系统版本 ${DeviceGrade.W_SDK} 分，合计 100 分。" +
                        "只读取系统静态信息，不做跑分，几毫秒即可完成。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }

}

/** 顶部总分卡：大数字 + 档位 + 一句结论。 */
@Composable
private fun TotalScoreCard(score: DeviceScore, scoring: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = score.total.toString(),
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = if (score.total >= DeviceGrade.PASS_SCORE) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = " / 100",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                TierBadge(score)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    scoring -> "正在重新评测…"
                    score.total >= 75 -> "性能宽裕：完整材质与动画都可以开"
                    score.total >= DeviceGrade.PASS_SCORE -> "达到材质门槛：玻璃材质可用（按标准档运行）"
                    score.total >= KibaMotion.ANIMATION_FLOOR_SCORE ->
                        "未达材质门槛（${DeviceGrade.PASS_SCORE} 分），材质保持标准质感；动画正常"
                    else -> "极低分设备：材质与装饰性动画均已关闭，保证基础操作流畅"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 档位徽章（高 / 中 / 低），颜色随档位走。 */
@Composable
private fun TierBadge(score: DeviceScore) {
    val (label, color) = when (score.tier) {
        DeviceTier.HIGH -> "高性能" to Color(0xFF34C759)
        DeviceTier.MID -> "标准" to Color(0xFFFF9F0A)
        DeviceTier.LOW -> "低性能" to Color(0xFFFF3B30)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
        )
    }
}

/** 单项明细：名称 + 百分比进度条 + 得分 / 满分。 */
@Composable
private fun DetailRow(label: String, value: Int, max: Int, isLast: Boolean) {
    val fraction = if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (max > 0) "$value / $max" else "$value",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.75f)),
            )
        }
        if (!isLast) Spacer(Modifier.height(12.dp))
    }
}

/** 功能闸门行：功能名 + 门槛 + 开关状态图标 + 说明。 */
@Composable
private fun FeatureGateRow(
    title: String,
    threshold: String,
    enabled: Boolean,
    detail: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (enabled) "✓" else "×",
                style = MiuixTheme.textStyles.footnote2,
                color = if (enabled) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$title（$threshold）",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
