package com.kira.kiba.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.size

/**
 * 工具箱页（2.6.0 起从「账户」页的两个 Card 合并成子页面）。
 *
 * 之前「工具箱」在账户页占了两个分组卡、十来行，把账户信息本身挤到需要滚动；
 * 而工具是低频入口 —— 收进一个子页，账户页只留一行「工具箱」。
 *
 * 分组口径：
 * - **站外入口**：水鱼查分器官网、DXRating.net、音游地图；
 * - **成绩工具**：机台抓包之外的本地计算类（Rating 计算 / 达成率反推 / 热门排行 / 版本对照）；
 * - **社区与账号**：查朋友（落雪开发者 API）、登录机台（微信二维码）、接入 Easymai 账号。
 */
@Composable
fun ToolboxScreen(
    scrollBehavior: ScrollBehavior,
    gameType: GameType,
    onOpenRatingCalc: () -> Unit,
    onOpenDxConverter: () -> Unit,
    onOpenHotCharts: () -> Unit,
    onOpenVersionMap: () -> Unit,
    onOpenFriendQuery: () -> Unit,
    onOpenGatewayAccount: () -> Unit,
    onOpenMachineLogin: () -> Unit,
) {
    val context = LocalContext.current
    val isChunithm = gameType == GameType.CHUNITHM

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
            item(key = "sites_title") { SectionTitle("站外入口") }
            item(key = "sites") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (isChunithm) {
                        ArrowPreference(
                            title = "水鱼中二节奏查分器",
                            summary = "本应用接口的本家，中二节奏成绩查询官网",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Search) },
                            onClick = {
                                BrowserJump.openUrl(
                                    context,
                                    "https://www.diving-fish.com/chunithm/prober/",
                                )
                            },
                        )
                    } else {
                        ArrowPreference(
                            title = "水鱼查分器",
                            summary = "本应用接口的本家，成绩上传与查询官网",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Search) },
                            onClick = {
                                BrowserJump.openUrl(
                                    context,
                                    "https://www.diving-fish.com/maimaidx/prober/",
                                )
                            },
                        )
                        ArrowPreference(
                            title = "DXRating.net",
                            summary = "包含 ZXZR 曲目信息的查询网站",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Layers) },
                            onClick = { BrowserJump.openUrl(context, "https://dxrating.net/search") },
                        )
                    }
                    ArrowPreference(
                        title = "音游地图",
                        summary = "查查你地区的机台",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.MapAlbum) },
                        onClick = { BrowserJump.openUrl(context, "https://map.bemanicn.com/") },
                    )
                }
            }

            item(key = "tools_title") { SectionTitle("成绩工具") }
            item(key = "tools") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (!isChunithm) {
                        ArrowPreference(
                            title = "单曲 Rating 计算",
                            summary = "根据定数及达成率算出 Rating（附 RANK 系数表）",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune) },
                            onClick = onOpenRatingCalc,
                        )
                        ArrowPreference(
                            title = "热门乐曲排行",
                            summary = "按水鱼游玩样本数排序，看看最火爆的谱面是哪些",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.TopDownloads) },
                            onClick = onOpenHotCharts,
                        )
                        ArrowPreference(
                            title = "版本名称对照表",
                            summary = "版本名称与牌子代号对照，妈妈再也不担心分不清版本",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Album) },
                            onClick = onOpenVersionMap,
                        )
                        ArrowPreference(
                            title = "达成率计算",
                            summary = "由判定数量反推达成率与单曲 Rating",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.ConvertFile) },
                            onClick = onOpenDxConverter,
                        )
                    }
                    ArrowPreference(
                        title = "查朋友",
                        summary = "输入好友码查看对方公开的落雪数据（玩家信息 / Best / 全量成绩统计）",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.ContactsCircle) },
                        onClick = onOpenFriendQuery,
                    )
                }
            }

            item(key = "account_title") { SectionTitle("账号与机台") }
            item(key = "account") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "登录机台",
                        summary = "打开微信「舞萌 | 中二」公众号，点底部「玩家二维码」菜单即可扫码上机",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Phone) },
                        onClick = onOpenMachineLogin,
                    )
                    if (!isChunithm) {
                        ArrowPreference(
                            title = "接入 Easymai 账号",
                            summary = "二维码导入成绩的前置账号：登录/注册、手动同步、注销",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.ContactsBook) },
                            onClick = onOpenGatewayAccount,
                        )
                    }
                }
            }
        }
    }
}

/** 工具箱行左侧图标（与账户页同一套次要色单色图标口径）。 */
@Composable
private fun PrefIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(26.dp),
    )
}
