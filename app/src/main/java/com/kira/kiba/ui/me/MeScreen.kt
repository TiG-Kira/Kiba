package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniRatingTag
import com.kira.kiba.ui.common.DxRatingTag
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「我」页面 ArrowPreference 的左侧功能图标（HyperOS 设置风格：次要色单色图标）。
 */
@Composable
private fun PrefIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(26.dp),
    )
}

/**
 * “我”页面（miuix preference 规范 + miuix Card 分组包裹）：
 * - 置顶 [ArrowPreference]：已登录展示昵称 + DX Rating 段位徽章（官方色表），
 *   点击进 Best 50；未登录展示引导，点击进登录页（账号密码，OAuth 已暂时停用）；
 * - 查分器入口：成绩查询（EasyMai 式筛选 / 排序 / 网格视图，Best 50 由顶部卡片承载）；
 * - 我的收藏：收藏的歌曲列表；
 * - 工具箱（移植自 EasyMai 并合并原「工具」组）：实用网站 + 数据工具 + 登录机台等。
 */
@Composable
fun MeScreen(
    scrollBehavior: ScrollBehavior,
    onOpenLogin: () -> Unit,
    onOpenBest50: () -> Unit,
    onOpenScoreQuery: () -> Unit,
    onOpenDxConverter: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenMachineLogin: () -> Unit,
    onOpenGatewayAccount: () -> Unit,
    onOpenRatingCalc: () -> Unit,
    onOpenVersionMap: () -> Unit,
    onOpenHotCharts: () -> Unit,
    onOpenFriendQuery: () -> Unit,
    onOpenChuniBest: () -> Unit,
) {
    val account by DivingFishAccount.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 当前游戏（曲目页分段开关写入 SpUtil；进入本页时重新读取，切 Tab 会重组所以总能拿到新值）
    var gameType by remember { mutableStateOf(SpUtil.getGameType()) }
    val isChunithm = gameType == GameType.CHUNITHM

    // ── 中二成绩同步状态（本地缓存值以同步状态为 key 读取，同步完成后自动刷新展示）──
    val chuniSyncState by ChuniRecordSync.state.collectAsState()
    val chuniSyncing = chuniSyncState is ChuniRecordSync.SyncState.Syncing
    val chuniError = (chuniSyncState as? ChuniRecordSync.SyncState.Failed)?.message
    // 同步成功但账号无任何中二成绩（best 为空数组）——不是错误，需单独文案
    val chuniNoData = (chuniSyncState as? ChuniRecordSync.SyncState.Done)?.empty == true
    val chuniJwtPresent = remember { SpUtil.getDivingFishJwt().isNotBlank() }
    // 数据源就绪判定：落雪看登录凭据（OAuth 或个人 API Key），水鱼看各自凭据
    val maimaiSource = remember { SpUtil.getScoreSource(GameType.MAIMAI) }
    val chuniSource = remember { SpUtil.getScoreSource(GameType.CHUNITHM) }
    val chuniReady = remember(chuniSource, chuniJwtPresent) {
        if (chuniSource == ScoreSource.LXNS) LxnsAccount.isBound() else chuniJwtPresent
    }
    val chuniRating = remember(chuniSyncState) { ChuniRecordSync.getLocalRating() }
    val chuniNickname = remember(chuniSyncState) { ChuniRecordSync.getLocalNickname() }
    val chuniRecordCount = remember(chuniSyncState) { ChuniRecordSync.getLocalRecordCount() }

    // 进中二「我」页自动同步一次（本进程内仅一次；顶栏手动刷新不受限）
    LaunchedEffect(isChunithm, account.bound, chuniReady) {
        if (isChunithm && chuniReady && ChuniRecordSync.shouldAutoRefresh()) {
            // 兜底：刷新内部已自捕获，这里再包一层，确保任何漏网异常都不会打死 UI 协程
            runCatching { ChuniRecordSync.refresh() }
        }
    }

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
            contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 置顶：Rating 徽章入口（按游戏切换；中二走账号密码通道同步成绩）──
            item(key = "rating") {
                if (isChunithm) {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        ArrowPreference(
                            title = if (chuniReady) {
                                chuniNickname.ifBlank { account.nickname.ifBlank { "查分器用户" } }
                            } else {
                                "未绑定查分器"
                            },
                            summary = when {
                                !chuniReady && chuniSource == ScoreSource.LXNS ->
                                    "落雪源需要先登录落雪账号：设置 → 查分器数据源 → 登录落雪账号"
                                !chuniReady ->
                                    "水鱼 OAuth 暂不支持中二节奏，请使用账号密码登录"
                                chuniSyncing -> "正在同步中二成绩…"
                                chuniError != null -> "$chuniError（点击重试）"
                                chuniRecordCount > 0 ->
                                    "${chuniSource.shortLabel}源 · 成绩 $chuniRecordCount 条" +
                                        " · 点击查看中二节奏 Best 50"
                                chuniNoData -> "该账号暂无中二节奏成绩数据（点击重试）"
                                else -> "点击同步中二成绩"
                            },
                            onClick = {
                                when {
                                    !chuniReady && chuniSource == ScoreSource.LXNS -> Toast.makeText(
                                        context,
                                        "请到「设置 → 查分器数据源」登录落雪账号",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    !chuniReady -> onOpenLogin()
                                    chuniRecordCount > 0 -> onOpenChuniBest()
                                    else -> scope.launch { runCatching { ChuniRecordSync.refresh() } }
                                }
                            },
                            endActions = {
                                if (chuniRating > 0.0) ChuniRatingTag(rating = chuniRating)
                            },
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Contacts) },
                        )
                    }
                } else {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        ArrowPreference(
                            title = if (account.bound) {
                                account.nickname.ifBlank { "查分器用户" }
                            } else {
                                "未绑定查分器"
                            },
                            summary = when {
                                // 舞萌侧选了落雪但没登录：别再把人往水鱼登录页带
                                !account.bound && maimaiSource == ScoreSource.LXNS ->
                                    "落雪源需要先登录落雪账号：设置 → 查分器数据源 → 登录落雪账号"
                                !account.bound -> "账号密码登录，解锁 Best 50 / 推分推荐"
                                account.loading -> "正在获取成绩…"
                                account.error != null -> "${account.error}（点击查看 Best 50）"
                                else ->
                                    "${maimaiSource.shortLabel}源 · 成绩 ${account.recordCount} 条" +
                                        " · 点击查看 Best 50"
                            },
                            onClick = {
                                when {
                                    account.bound -> onOpenBest50()
                                    maimaiSource == ScoreSource.LXNS -> Toast.makeText(
                                        context,
                                        "请到「设置 → 查分器数据源」登录落雪账号",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    else -> onOpenLogin()
                                }
                            },
                            endActions = {
                                DxRatingTag(rating = if (account.bound) account.rating else 0)
                            },
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Contacts) },
                        )
                    }
                }
            }

            item(key = "checker_title") { SectionTitle("查分器") }

            item(key = "checker_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = when {
                            maimaiSource == ScoreSource.LXNS -> "落雪账户（在设置页管理）"
                            account.bound -> "重新登录 / 切换账户"
                            else -> "登录查分器账户"
                        },
                        summary = when {
                            maimaiSource == ScoreSource.LXNS ->
                                "舞萌当前用落雪源 · 一个落雪账号两个游戏通用，可在「设置 → 查分器数据源」切换"
                            account.bound ->
                                "当前：${account.nickname.ifBlank { "查分器用户" }}（Rating ${account.rating}）"
                            else -> "支持水鱼账号密码登录（OAuth 授权已暂时停用）"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Lock) },
                        onClick = onOpenLogin,
                    )
                    if (!isChunithm) {
                        ArrowPreference(
                            title = "成绩查询",
                            summary = "筛选 / 排序 / 网格视图浏览全量成绩，顶栏刷新可更新",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Notes) },
                            onClick = onOpenScoreQuery,
                        )
                    }
                }
            }

            item(key = "fav_title") { SectionTitle("我的曲库") }

            item(key = "fav_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "我的收藏",
                        summary = "在歌曲详情页标记过收藏的歌曲列表",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Favorites) },
                        onClick = onOpenFavorites,
                    )
                }
            }

            // ── 工具箱（移植自 EasyMai toolbox）──
            item(key = "toolbox_title") { SectionTitle("工具箱") }

            item(key = "toolbox_sites") {
                Card(modifier = (Modifier.fillMaxWidth())) {
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

            item(key = "toolbox_tools") {
                Card(modifier = (Modifier.fillMaxWidth())) {
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
                    }
                    ArrowPreference(
                        title = "查朋友",
                        summary = "输入好友码查看对方公开的落雪数据（玩家信息 / Best / 全量成绩统计）",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.ContactsCircle) },
                        onClick = onOpenFriendQuery,
                    )
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
                        ArrowPreference(
                            title = "达成率计算",
                            summary = "由判定数量反推达成率与单曲 Rating",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.ConvertFile) },
                            onClick = onOpenDxConverter,
                        )
                    }
                    if (account.bound) {
                        ArrowPreference(
                            title = "退出登录",
                            summary = "清除本机成绩缓存与登录凭证；远端授权可在 auth.diving-fish.com/apps 撤销",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Unlock) },
                            onClick = { DivingFishAccount.unbind() },
                        )
                    }
                }
            }
        }
    }
}
