package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.account.LxnsAccount
import com.kira.kiba.core.account.RatingHistory
import com.kira.kiba.core.account.formatRatingValue
import com.kira.kiba.core.data.ChuniRecordSync
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.ScoreSource
import com.kira.kiba.core.utils.BrowserJump
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniRatingTag
import com.kira.kiba.ui.common.DxRatingTag
import com.kira.kiba.ui.common.RatingTrendCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.KibaGlassDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「账户」页面 ArrowPreference 的左侧功能图标（HyperOS 设置风格：次要色单色图标）。
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
 * 「账户」页面（原「我」页；miuix preference 规范 + miuix Card 分组包裹）：
 * - 置顶 [ArrowPreference]：已登录展示昵称 + DX Rating 段位徽章（官方色表），
 *   点击进 Best 50；未登录展示引导，点击进登录页；
 * - 查分器组：水鱼 / 落雪账户、机台更新、二维码导入（**仅舞萌**，中二不显示）、成绩查询，
 *   以及**合并后的退出登录**（弹层里按查分器多选，不再为每个源各挂一行）；
 * - 我的曲库：收藏；
 * - 工具箱：**单入口**，站外网站与本地小工具全部收进 [com.kira.kiba.ui.tools.ToolboxScreen]。
 *
 * 2.6.0 起「我」更名为「账户」：这页现在纯账户与工具属性，曲库浏览在「曲目」，
 * 听歌在「畅听」，不必再叫「我」。
 */
@Composable
fun MeScreen(
    scrollBehavior: ScrollBehavior,
    onOpenLogin: () -> Unit,
    onOpenLxnsLogin: () -> Unit,
    onOpenBest50: () -> Unit,
    onOpenScoreQuery: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenMachineUpdate: () -> Unit,
    onOpenQrImport: () -> Unit,
    onOpenToolbox: () -> Unit,
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
    val lxnsState by LxnsAccount.state.collectAsState()
    val lxnsBound = lxnsState.bound
    val lxnsName = lxnsState.name
    val lxnsOauth = lxnsState.oauth
    // 数据源响应式：切源后本页立刻跟着变（源标签 / 引导文案 / 分支判定），
    // 不能停留在进入页面那一刻读到的旧值（否则切到落雪源还显示「水鱼源」）。
    val maimaiSource by SpUtil.maimaiSourceFlow.collectAsState()
    val chuniSource by SpUtil.chuniSourceFlow.collectAsState()
    val chuniReady = remember(chuniSource, chuniJwtPresent) {
        if (chuniSource == ScoreSource.LXNS) LxnsAccount.isBound() else chuniJwtPresent
    }
    val chuniRating = remember(chuniSyncState) { ChuniRecordSync.getLocalRating() }
    // 昵称搬到顶栏大标题了（MeTopBarSlot 自己读），本页不再需要
    val chuniRecordCount = remember(chuniSyncState) { ChuniRecordSync.getLocalRecordCount() }

    // 进中二「账户」页自动同步一次（本进程内仅一次；顶栏手动刷新不受限）
    LaunchedEffect(isChunithm, account.bound, chuniReady) {
        if (isChunithm && chuniReady && ChuniRecordSync.shouldAutoRefresh()) {
            // 应用级作用域触发：LaunchedEffect 是 UI 作用域，离开页面即取消，
            // 会把同步掐死在半路（Syncing 永卡「正在同步中二成绩…」）
            ChuniRecordSync.refreshAsync()
        }
    }

    // ── 退出登录（合并入口）：弹层里按查分器多选 ──
    var showLogoutDialog by remember { mutableStateOf(false) }
    var logoutWaterFish by remember { mutableStateOf(false) }
    var logoutLxns by remember { mutableStateOf(false) }

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
            // ── 置顶：Best 50 入口（按游戏切换；中二走账号密码通道同步成绩）──
            // 昵称与登录态已经搬到顶栏（大标题 / 副标题），这张卡只负责「Best 50 本身」：
            // 标题固定 Best 50，副标题给出两个分池的得分，点进去看逐条明细。
            item(key = "rating") {
                if (isChunithm) {
                    // 中二 Rating = (非当前版本 TOP30 + 当前版本 TOP20) / 50，
                    // 所以两个分池展示的是**平均 ra**（总和除下去才是 rating，直接报总和会让人误读）。
                    val b30 = remember(chuniSyncState) { SpUtil.getChuniNonCurrentAvg() }
                    val b20 = remember(chuniSyncState) { SpUtil.getChuniCurrentAvg() }
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        ArrowPreference(
                            title = "Best 50",
                            summary = when {
                                !chuniReady && chuniSource == ScoreSource.LXNS ->
                                    "落雪源需要先登录落雪账号：点击进入落雪账户登录"
                                !chuniReady ->
                                    "水鱼 OAuth 暂不支持中二节奏，请使用水鱼账号密码登录"
                                chuniSyncing -> "正在同步中二成绩…"
                                chuniError != null -> "$chuniError（点击重试）"
                                chuniRecordCount > 0 ->
                                    "Best 30 均 ra ${formatRatingValue(GameType.CHUNITHM, b30)}" +
                                        " · Best 20 均 ra ${formatRatingValue(GameType.CHUNITHM, b20)}"
                                chuniNoData -> "该账号暂无中二节奏成绩数据（点击重试）"
                                else -> "点击同步中二成绩"
                            },
                            onClick = {
                                when {
                                    !chuniReady && chuniSource == ScoreSource.LXNS -> onOpenLxnsLogin()
                                    !chuniReady -> onOpenLogin()
                                    chuniRecordCount > 0 -> onOpenChuniBest()
                                    else -> ChuniRecordSync.refreshAsync()
                                }
                            },
                            endActions = {
                                if (chuniRating > 0.0) ChuniRatingTag(rating = chuniRating)
                            },
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Contacts) },
                        )
                    }
                } else {
                    // 舞萌 Best 50 = 旧曲 35 + 新曲 15，Rating 就是两段 ra 之和 ——
                    // 直接摊开这两个数，用户一眼能看出「新曲池还有多少空间」。
                    val best = remember(account.bound, account.recordCount, account.rating) {
                        if (account.bound) DivingFishAccount.best50() else DivingFishAccount.Best50()
                    }
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        ArrowPreference(
                            title = "Best 50",
                            summary = when {
                                // 舞萌侧选了落雪但没登录：别再把人往水鱼登录页带
                                !account.bound && maimaiSource == ScoreSource.LXNS ->
                                    "落雪源需要先登录落雪账号：点击进入落雪账户登录"
                                !account.bound -> "水鱼账号密码登录，解锁 Best 50 / 推分推荐"
                                account.loading -> "正在获取成绩…"
                                account.error != null -> "${account.error}（点击查看 Best 50）"
                                else ->
                                    "Best 35 ${best.oldRa} 分 · Best 15 ${best.newRa} 分"
                            },
                            onClick = {
                                when {
                                    account.bound -> onOpenBest50()
                                    maimaiSource == ScoreSource.LXNS -> onOpenLxnsLogin()
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

            // ── Rating 历史曲线：跟随**当前游戏 + 当前数据源** ──
            // 水鱼与落雪是两套口径，中二又是另一套公式，所以四条曲线各自独立记录；
            // 这里只画用户当下正在用的那一条，避免「切了源曲线突然跳变」的困惑。
            item(key = "rating_trend") {
                val trendGame = if (isChunithm) GameType.CHUNITHM else GameType.MAIMAI
                val trendSource = if (isChunithm) chuniSource else maimaiSource
                val history by RatingHistory.entries(trendGame, trendSource).collectAsState()
                if (history.isNotEmpty()) {
                    RatingTrendCard(
                        title = if (isChunithm) "中二 Rating 变化" else "最近 Rating 变化",
                        entries = history.takeLast(10),
                        subtitle = "${trendSource.shortLabel}源 · " + if (history.size > 10) {
                            "最近 10 次 · 共 ${history.size} 次"
                        } else {
                            "共 ${history.size} 次更新"
                        },
                        valueText = { formatRatingValue(trendGame, it) },
                    )
                }
            }

            item(key = "checker_title") { SectionTitle("查分器") }

            item(key = "checker_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        // 水鱼卡只看**水鱼凭据**：落雪源下 account.bound / account.nickname /
                        // account.rating 会被落雪数据覆盖，若直接读 account 就会把落雪账户
                        // 显示成「当前水鱼账户」。这里固定读水鱼自己的持久化身份。
                        title = when {
                            account.dfLoggedIn -> "重新登录水鱼账户"
                            else -> "水鱼账户登录"
                        },
                        summary = when {
                            account.dfLoggedIn ->
                                "当前水鱼账户：${SpUtil.getDivingFishOAuthNickname().ifBlank { "水鱼用户" }}（Rating ${SpUtil.getDivingFishRating()}）· 点击重新登录"
                            else -> "水鱼源专用：支持水鱼账号密码登录（OAuth 授权已暂时停用）"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Lock) },
                        onClick = onOpenLogin,
                    )
                    ArrowPreference(
                        title = when {
                            lxnsBound -> "管理落雪账户"
                            else -> "落雪账户登录"
                        },
                        summary = when {
                            lxnsBound ->
                                "当前落雪账户：${lxnsName.ifBlank { "落雪玩家" }} · ${if (lxnsOauth) "账号授权" else "个人 API 密钥"} · 点击管理"
                            else ->
                                "落雪源专用：推荐用落雪账号授权，一步到位；也可手填个人 API 密钥"
                        },
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill) },
                        onClick = onOpenLxnsLogin,
                    )
                    // ── 查分器工具（2.3.0 起从设置页整组迁到这里：登录态与工具入口同一屏）──
                    ArrowPreference(
                        title = "机台成绩更新（VPN）",
                        summary = "在机台 WiFi 下抓包，按各游戏当前选的数据源上传（水鱼 / 落雪）",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Update) },
                        onClick = onOpenMachineUpdate,
                    )
                    // 二维码导入是**舞萌专用**通道（官方登录二维码 + 水鱼导入令牌），
                    // 中二节奏既没有对应二维码体系、也没有水鱼导入通道，切到中二时整行隐藏，
                    // 免得用户点进去看到一条走不通的路。
                    if (!isChunithm) {
                        ArrowPreference(
                            title = "二维码导入成绩",
                            summary = "仅舞萌 DX：免 VPN —— 先在「工具箱 → 接入 Easymai 账号」登录，" +
                                "再粘贴官方登录二维码（公众号「舞萌 DX」生成），全难度成绩会同步进查分器账号后上传水鱼",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Scan) },
                            onClick = onOpenQrImport,
                        )
                    }
                    if (maimaiSource == ScoreSource.LXNS || chuniSource == ScoreSource.LXNS) {
                        // 落雪官方的同步方式是「代理同步」：把 WLAN 代理指向他们的服务器，
                        // 由落雪代抓。它和本 App 的本地 VPN 抢同一块网络接管，只能二选一，
                        // 所以这里只做备选入口 —— App 内抓包走不通时用户还有路可走。
                        ArrowPreference(
                            title = "落雪官方同步（备选）",
                            summary = "App 内抓包走不通时用这条：把 WLAN 代理设为 proxy.maimai.lxns.net:8080，" +
                                "由落雪服务器代抓（与 App 的 VPN 不能同时开）",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.CloudFill) },
                            onClick = { BrowserJump.openUrl(context, LxnsAccount.SYNC_URL) },
                        )
                    }
                    if (!isChunithm) {
                        ArrowPreference(
                            title = "成绩查询",
                            summary = "筛选 / 排序 / 网格视图浏览全量成绩，顶栏刷新可更新",
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Notes) },
                            onClick = onOpenScoreQuery,
                        )
                    }
                    // ── 退出登录（水鱼 / 落雪合并成一个入口）──
                    // 两行分开挂的做法在只登一边时信息量尚可，两边都登录后就是两块「退出」，
                    // 用户得先判断点哪个。合并成一次弹层、勾选要退的查分器，一眼看清。
                    if (account.dfLoggedIn || lxnsBound) {
                        ArrowPreference(
                            title = "退出登录",
                            summary = buildString {
                                val logged = buildList {
                                    if (account.dfLoggedIn) add("水鱼")
                                    if (lxnsBound) add("落雪")
                                }
                                append("当前登录：")
                                append(logged.joinToString(" / "))
                                append(" · 点击选择要退出的查分器")
                            },
                            startAction = { PrefIcon(MiuixGlassIcons.Regular.Unlock) },
                            onClick = {
                                // 默认全选「已登录的那几项」，用户取消勾选即可保留
                                logoutWaterFish = account.dfLoggedIn
                                logoutLxns = lxnsBound
                                showLogoutDialog = true
                            },
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

            // ── 工具箱：单入口（站外网站 + 本地小工具都收进子页面）──
            item(key = "toolbox_title") { SectionTitle("工具箱") }

            item(key = "toolbox_card") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    ArrowPreference(
                        title = "工具箱",
                        summary = "查分器官网 / 音游地图等站外入口，以及 Rating 计算、达成率反推、" +
                            "热门排行、版本对照、查朋友等本地工具",
                        startAction = { PrefIcon(MiuixGlassIcons.Regular.Tune) },
                        onClick = onOpenToolbox,
                    )
                }
            }
        }
    }

    // ── 退出登录弹层：按查分器多选 ──
    if (showLogoutDialog) {
        val nothingSelected = !logoutWaterFish && !logoutLxns
        KibaGlassDialog(
            show = true,
            title = "退出登录",
            summary = "勾选要退出登录的查分器。退出只会清除本机的登录凭据与账号缓存，" +
                "已同步到本地的成绩与收藏不会删除。",
            onDismissRequest = { showLogoutDialog = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (account.dfLoggedIn) {
                    LogoutChoiceRow(
                        label = "水鱼查分器",
                        detail = if (logoutWaterFish) {
                            "将清除水鱼登录凭据与本机水鱼成绩缓存"
                        } else {
                            "保留水鱼登录"
                        },
                        checked = logoutWaterFish,
                        onToggle = { logoutWaterFish = !logoutWaterFish },
                    )
                }
                if (lxnsBound) {
                    LogoutChoiceRow(
                        label = "落雪查分器",
                        detail = if (logoutLxns) {
                            "将清除落雪登录凭据（已同步进本地的成绩保留）"
                        } else {
                            "保留落雪登录"
                        },
                        checked = logoutLxns,
                        onToggle = { logoutLxns = !logoutLxns },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = { showLogoutDialog = false })
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "确认退出",
                        enabled = !nothingSelected,
                        onClick = {
                            if (logoutWaterFish) DivingFishAccount.unbind()
                            if (logoutLxns) LxnsAccount.logout()
                            showLogoutDialog = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 退出登录弹层里的单个查分器选项：整行可点，右侧方框勾选态。
 *
 * 用自绘方框而不是 SwitchPreference：这里语义是「选中要处理的对象」而非「开关某项功能」，
 * 且弹层里 miuix preference 的左右内边距会把行挤得很窄。
 */
@Composable
private fun LogoutChoiceRow(
    label: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (checked) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = MiuixGlassIcons.Regular.Ok,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
