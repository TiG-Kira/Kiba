package com.kira.kiba.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.best50.Best50ImageService
import com.kira.kiba.core.state.KibaState
import com.kira.kiba.ui.agent.AgentScreen
import com.kira.kiba.ui.agent.LlmConfigScreen
import com.kira.kiba.ui.me.Best50Screen
import com.kira.kiba.ui.me.FavoritesScreen
import com.kira.kiba.ui.me.LevelProgressScreen
import com.kira.kiba.ui.me.LoginScreen
import com.kira.kiba.ui.me.MeScreen
import com.kira.kiba.ui.me.ProgressDetailScreen
import com.kira.kiba.ui.me.RecordsScreen
import com.kira.kiba.ui.me.VersionProgressScreen
import com.kira.kiba.ui.settings.AboutScreen
import com.kira.kiba.ui.settings.SettingsScreen
import com.kira.kiba.ui.songdetail.ChartDetailScreen
import com.kira.kiba.ui.tools.DxConverterScreen
import com.kira.kiba.ui.tools.MachineUpdateScreen
import com.kira.kiba.ui.songdetail.SongDetailScreen
import com.kira.kiba.ui.songdetail.SongDetailTopBarSlot
import com.kira.kiba.ui.songs.SongsScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*

/**
 * Kiba 主框架：Termux Ultra 同款玻璃导航体系。
 *
 * - 顶栏：原生 miuix-glass [GlassTopAppBar]（大标题 + 滚动塌缩），采样 [glassNavBackdrop]；
 * - 底栏：**Termux Ultra 默认悬浮底栏** [GlassNavigationBar]（玻璃胶囊，非液态玻璃那套）；
 * - 取景层：单一 miuix-blur layerBackdrop，顶栏与底栏共用，内容区注册到同一层。
 *
 * 二级页面（歌曲详情 / Best 50 / 成绩记录 / 版本进度 / 等级进度 / 模型配置 / 关于）
 * 以 [KibaSubPage] 全屏覆盖当前 Tab：顶栏切「返回 + 标题」，底栏隐藏，
 * 系统返回与顶栏返回都先退子页面（BackHandler + KibaBackAction）。
 *
 * 关键坑位规避（与 Termux Ultra 一致）：
 * - `contentWindowInsets = WindowInsets(0, 0, 0, 0)`：内容满高铺开，不被顶/底栏推开；
 * - 页面顶部留白不硬编码，取 Scaffold 内容 lambda 的 `padding.calculateTopPadding()`
 *   （顶栏塌缩时其测量高度同步收缩，常量会留下一条不透带）；
 * - 内容 Box 挂 `.layerBackdrop(glassNavBackdrop)` 供顶栏/底栏采样。
 */

/** 顶栏玻璃材质共享 MainScreen 的取景层，各页无需各自持有。 */
val LocalGlassTopAppBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 顶栏占位高度：页面把首个 item 压到此值之下，避免顶栏遮挡。
 * 由 MainScreen 从 Scaffold padding 现算后注入，随顶栏塌缩同步。
 */
val LocalTopBarClearance = staticCompositionLocalOf<Dp> { 0.dp }

/** 悬浮底栏占位高度（54dp 栏高 + 系统导航栏 inset），页面底部需至少留出此值。 */
val LocalNavBarBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

/** 仅系统导航栏 inset 高度：二级页面（无悬浮底栏）底部留白用。 */
val LocalSystemNavBarBottom = staticCompositionLocalOf<Dp> { 0.dp }

enum class KibaTab(val title: String) {
    Songs("曲目"),
    Agent("Agent"),
    Me("我"),
    Settings("设置")
}

/** 二级页面（覆盖当前 Tab 的全屏子页）。 */
sealed interface KibaSubPage {
    val title: String

    /** 歌曲详情（歌曲库点击进入）。 */
    data class SongDetail(val songId: Int) : KibaSubPage {
        override val title: String = "歌曲详情"
    }

    /** 谱面详情（歌曲详情页点击难度卡进入）。 */
    data class ChartDetail(val songId: Int, val difficultyOrdinal: Int) : KibaSubPage {
        override val title: String = "谱面详情"
    }

    /** Best 50（官方口径 sd 35 + dx 15）。 */
    data object Best50 : KibaSubPage {
        override val title: String = "Best 50"
    }

    /** 成绩记录（查分器全量成绩 + 手动刷新）。 */
    data object Records : KibaSubPage {
        override val title: String = "成绩记录"
    }

    /** 版本进度（各版本歌曲收集 / 游玩情况）。 */
    data object VersionProgress : KibaSubPage {
        override val title: String = "版本进度"
    }

    /** 等级进度（各难度等级谱面游玩情况）。 */
    data object LevelProgress : KibaSubPage {
        override val title: String = "等级进度"
    }

    /** Agent 模型配置（LLM）。 */
    data object LlmConfig : KibaSubPage {
        override val title: String = "模型配置"
    }

    /** 查分器登录（账号密码 / OAuth 双通道；独立子页避免弹层与软键盘冲突）。 */
    data object Login : KibaSubPage {
        override val title: String = "登录查分器"
    }

    /** 进度明细（版本 / 等级分类下的歌曲列表）。 */
    data class ProgressDetail(
        val groupType: com.kira.kiba.core.stats.ProgressDetail.GroupType,
        val groupKey: String,
    ) : KibaSubPage {
        override val title: String = groupKey
    }

    /** 机台成绩更新（VPN 抓包方案）。 */
    data object MachineUpdate : KibaSubPage {
        override val title: String = "机台成绩更新"
    }

    /** DX 分数转换（判定数量 → 达成率 / Rating）。 */
    data object DxConverter : KibaSubPage {
        override val title: String = "DX 分数转换"
    }

    /** 我的收藏（收藏的歌曲列表）。 */
    data object Favorites : KibaSubPage {
        override val title: String = "我的收藏"
    }

    /** 关于。 */
    data object About : KibaSubPage {
        override val title: String = "关于"
    }
}

private fun tabIcon(tab: KibaTab) = when (tab) {
    KibaTab.Songs -> MiuixGlassIcons.Regular.Music
    KibaTab.Agent -> MiuixGlassIcons.Regular.AiScan
    KibaTab.Me -> MiuixGlassIcons.Regular.Contacts
    KibaTab.Settings -> MiuixGlassIcons.Regular.Settings
}

/** Termux Ultra 浮动玻璃底栏的固定栏高（GlassNavigationBarDefaults.Height）。 */
private val GLASS_NAV_HEIGHT = 54.dp

@Composable
fun KibaMainScreen() {
    val context = LocalContext.current
    val tabs = KibaTab.values()
    var selectedTab by remember { mutableStateOf(KibaTab.Songs) }

    // 二级页面栈：非空时覆盖当前 Tab（顶栏带返回、底栏隐藏），支持多层逐级返回
    var subStack by remember { mutableStateOf<List<KibaSubPage>>(emptyList()) }
    // 导航方向：true=前进入栈，false=后退出栈（驱动页面切换动画方向）
    var navForward by remember { mutableStateOf(true) }

    // 顶栏塌缩行为：rememberSaveable 状态，切 tab / 进程恢复都不丢
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior: ScrollBehavior = MiuixScrollBehavior(topAppBarState)

    // ── 页面状态保留（返回不回顶）──
    // - 页面内容包 SaveableStateProvider：push 子页时列表 state 存入 holder，
    //   返回时原样恢复（滚动位置不丢）；
    // - 顶栏塌缩状态按页面记忆：push 时存旧页，返回时恢复旧页（不强制展开）；
    // - 强制「回顶 + 展开顶栏」只保留在两处：切主页 Tab、push 进新子页。
    val pageStateHolder = rememberSaveableStateHolder()
    val topBarMemory = remember { mutableMapOf<Any, Pair<Float, Float>>() }
    val frameScope = rememberCoroutineScope()

    // SaveableStateProvider 的 key 必须是 Bundle 可存类型（data class 会直接抛
    // IllegalArgumentException 崩溃），所以统一用稳定字符串作页面 key，
    // topBarMemory 也用同一个 key，保证存取一致。
    fun pageKey(page: Any): String = when (page) {
        is KibaTab -> "tab:${page.name}"
        is KibaSubPage.SongDetail -> "sub:song:${page.songId}"
        is KibaSubPage.ChartDetail -> "sub:chart:${page.songId}:${page.difficultyOrdinal}"
        is KibaSubPage.ProgressDetail -> "sub:progress:${page.groupType}:${page.groupKey}"
        is KibaSubPage -> "sub:${page.javaClass.simpleName}"
        else -> page.toString()
    }

    fun saveTopBar(page: Any) {
        topBarMemory[pageKey(page)] = topAppBarState.heightOffset to topAppBarState.contentOffset
    }

    fun restoreTopBar(page: Any) {
        val (h, c) = topBarMemory[pageKey(page)] ?: (0f to 0f)
        topAppBarState.heightOffset = h
        topAppBarState.contentOffset = c
        // 返回后的第一帧，新页顶栏尚未重新测量出自己的 heightOffsetLimit，
        // 恢复值会被上一页顶栏残留的（更小的）limit clamp 掉——表现为
        // 从收窄态返回时顶栏半展开、大标题残影悬在内容上（不清不楚的 ghost）。
        // 等两帧（新顶栏完成测量）后重新写入目标值。
        frameScope.launch {
            for (i in 0 until 2) withFrameNanos { }
            topAppBarState.heightOffset = h
            topAppBarState.contentOffset = c
        }
    }

    // 每进入新页面（入栈/切 Tab）都把共享的顶栏状态归零：旧页面列表 state 已存入
    // holder（返回时恢复），新页面顶栏必须回到展开态，否则折叠状态会泄漏到新页面。
    fun resetTopBar() {
        topAppBarState.contentOffset = 0f
        topAppBarState.heightOffset = 0f
    }

    fun pushPage(page: KibaSubPage) {
        navForward = true
        saveTopBar(subStack.lastOrNull() ?: selectedTab)
        // 进新子页 = 新导航：清掉该页残留的旧状态（比如之前访问过同一首歌）
        pageStateHolder.removeState(page)
        topBarMemory.remove(page)
        subStack = subStack + page
        resetTopBar()
    }

    fun popPage() {
        if (subStack.isNotEmpty()) {
            navForward = false
            val back = subStack.dropLast(1).lastOrNull() ?: selectedTab
            subStack = subStack.dropLast(1)
            // 返回上一页：恢复其滚动位置与顶栏塌缩状态，不强制回顶/展开
            restoreTopBar(back)
        }
    }

    // 顶栏搜索按钮：每次点击 +1，曲目页据此聚焦搜索框
    var songsSearchTrigger by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // 隐藏手势提示线后 navigationBars inset 可能为 0，保底 12dp 贴屏幕底边
    val systemNavBarsHeight = with(density) {
        maxOf(WindowInsets.navigationBars.getBottom(density), 12.dp.roundToPx()).toDp()
    }
    val navBarBottomPadding = GLASS_NAV_HEIGHT + systemNavBarsHeight

    // 顶栏与底栏共用的取景层（miuix-blur）
    val glassNavBackdrop = rememberLayerBackdrop()

    // 系统返回：先退二级页面（出栈），清空后再放行给 Activity
    BackHandler(enabled = subStack.isNotEmpty()) {
        popPage()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 关键：禁用默认 contentWindowInsets，让内容满高铺开
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides glassNavBackdrop) {
                val sub = subStack.lastOrNull()
                if (sub == null) {
                    KibaTopBar(
                        title = selectedTab.title,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            when (selectedTab) {
                                KibaTab.Songs -> KibaSearchAction { songsSearchTrigger++ }
                                KibaTab.Me -> KibaRefreshAction { KibaState.refreshRecords() }
                                else -> Unit
                            }
                        },
                    )
                } else if (sub is KibaSubPage.SongDetail) {
                    // 歌曲详情页魔改顶栏：封面背景 + 歌名/艺术家 + 收藏 star（详见 SongDetailTopBar）
                    SongDetailTopBarSlot(
                        songId = sub.songId,
                        scrollBehavior = scrollBehavior,
                        onBack = { popPage() },
                    )
                } else {
                    KibaTopBar(
                        title = sub.title,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { KibaBackAction { popPage() } },
                        actions = {
                            if (sub is KibaSubPage.Records) {
                                KibaRefreshAction { KibaState.refreshRecords() }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (subStack.isEmpty()) {
                // Termux Ultra 默认悬浮底栏：GlassNavigationBar
                // 左右各 24dp 内缩成浮动胶囊，底部贴系统导航栏上边缘
                GlassNavigationBar(
                    items = tabs.map { GlassNavigationItem(icon = tabIcon(it), label = it.title) },
                    selectedIndex = selectedTab.ordinal,
                    onSelect = { index ->
                        val target = tabs.getOrElse(index) { selectedTab }
                        if (target != selectedTab) {
                            // 切 Tab = 进新页面：目标页残留状态清掉，列表回顶 + 顶栏展开
                            pageStateHolder.removeState(target)
                            topBarMemory.remove(target)
                            selectedTab = target
                            resetTopBar()
                        }
                    },
                    backdrop = glassNavBackdrop,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = systemNavBarsHeight),
                )
            }
        }
    ) { padding ->
        // 顶栏遮挡量从 Scaffold 自身 padding 现取：顶栏塌缩时测量高度同步收缩，
        // 常量会留下一条不透带（Termux Ultra GlassPageHost 同款做法）。
        val topBarClearance = padding.calculateTopPadding()
        CompositionLocalProvider(
            LocalTopBarClearance provides topBarClearance,
            LocalNavBarBottomPadding provides navBarBottomPadding,
            LocalSystemNavBarBottom provides systemNavBarsHeight,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 内容注册到取景层：顶栏/底栏透过它折射页面内容
                    .layerBackdrop(glassNavBackdrop)
            ) {
                val sub = subStack.lastOrNull()
                // 页面切换动画：Tab 间淡入淡出 + 轻微横向位移；二级页面从右滑入、返回时滑出
                AnimatedContent(
                    targetState = subStack.lastOrNull() ?: selectedTab,
                    transitionSpec = {
                        val isSub = targetState is KibaSubPage
                        val forward = navForward
                        if (isSub) {
                            if (forward) {
                                (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(200)))
                                    .togetherWith(fadeOut(tween(160)))
                            } else {
                                (fadeIn(tween(200)))
                                    .togetherWith(slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(200)))
                            }
                        } else {
                            (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 8 })
                                .togetherWith(fadeOut(tween(160)))
                        }
                    },
                    label = "kiba-page",
                ) { target ->
                    // 按页面隔离保存状态：push 子页时当前页 state 存入 holder，
                    // 返回时恢复（滚动位置/输入内容不丢）；切 Tab 时已在 onSelect 清空目标页。
                    pageStateHolder.SaveableStateProvider(pageKey(target)) {
                        when (target) {
                            is KibaSubPage -> when (target) {
                            is KibaSubPage.SongDetail -> SongDetailScreen(
                                songId = target.songId,
                                scrollBehavior = scrollBehavior,
                                onOpenChart = { songId, difficultyOrdinal ->
                                    pushPage(KibaSubPage.ChartDetail(songId, difficultyOrdinal))
                                },
                            )
                            is KibaSubPage.ChartDetail -> ChartDetailScreen(
                                songId = target.songId,
                                difficultyOrdinal = target.difficultyOrdinal,
                                scrollBehavior = scrollBehavior,
                            )
                            is KibaSubPage.Best50 -> Best50Screen(
                                scrollBehavior = scrollBehavior,
                                onOpenSong = { songId -> pushPage(KibaSubPage.SongDetail(songId)) },
                                onSaveImage = {
                                    val b50 = DivingFishAccount.best50()
                                    val account = DivingFishAccount.state.value
                                    Best50ImageService.generateAndSave(
                                        context = context,
                                        old = b50.old,
                                        new = b50.new,
                                        nickname = account.nickname.ifBlank { "Kiba" },
                                        rating = b50.rating,
                                    )
                                },
                            )
                            is KibaSubPage.Records -> RecordsScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenSong = { songId -> pushPage(KibaSubPage.SongDetail(songId)) },
                            )
                            is KibaSubPage.VersionProgress -> VersionProgressScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenDetail = { type, key ->
                                    pushPage(KibaSubPage.ProgressDetail(type, key))
                                },
                            )
                            is KibaSubPage.LevelProgress -> LevelProgressScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenDetail = { type, key ->
                                    pushPage(KibaSubPage.ProgressDetail(type, key))
                                },
                            )
                            is KibaSubPage.LlmConfig -> LlmConfigScreen(scrollBehavior = scrollBehavior)
                            is KibaSubPage.Login -> LoginScreen(
                                scrollBehavior = scrollBehavior,
                                onLoginSuccess = { popPage() },
                            )
                            is KibaSubPage.ProgressDetail -> ProgressDetailScreen(
                                groupType = target.groupType,
                                groupKey = target.groupKey,
                                scrollBehavior = scrollBehavior,
                                onOpenSong = { songId -> pushPage(KibaSubPage.SongDetail(songId)) },
                            )
                            is KibaSubPage.DxConverter -> DxConverterScreen(scrollBehavior = scrollBehavior)
                            is KibaSubPage.MachineUpdate -> MachineUpdateScreen(scrollBehavior = scrollBehavior)
                            is KibaSubPage.Favorites -> FavoritesScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenSong = { song -> pushPage(KibaSubPage.SongDetail(song.songData.id)) },
                            )
                            is KibaSubPage.About -> AboutScreen(scrollBehavior = scrollBehavior)
                        }

                        else -> when (target) {
                            KibaTab.Songs -> SongsScreen(
                                scrollBehavior = scrollBehavior,
                                searchTrigger = songsSearchTrigger,
                                onSongClick = { song ->
                                    pushPage(KibaSubPage.SongDetail(song.songData.id))
                                },
                            )
                            KibaTab.Agent -> AgentScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenSong = { songId -> pushPage(KibaSubPage.SongDetail(songId)) },
                            )
                            KibaTab.Me -> MeScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenLogin = { pushPage(KibaSubPage.Login) },
                                onOpenBest50 = { pushPage(KibaSubPage.Best50) },
                                onOpenRecords = { pushPage(KibaSubPage.Records) },
                                onOpenVersionProgress = { pushPage(KibaSubPage.VersionProgress) },
                                onOpenLevelProgress = { pushPage(KibaSubPage.LevelProgress) },
                                onOpenProgressDetail = { type, key ->
                                    pushPage(KibaSubPage.ProgressDetail(type, key))
                                },
                                onOpenDxConverter = { pushPage(KibaSubPage.DxConverter) },
                                onOpenFavorites = { pushPage(KibaSubPage.Favorites) },
                            )
                            KibaTab.Settings -> SettingsScreen(
                                scrollBehavior = scrollBehavior,
                                onOpenLlmConfig = { pushPage(KibaSubPage.LlmConfig) },
                                onOpenMachineUpdate = { pushPage(KibaSubPage.MachineUpdate) },
                                onOpenAbout = { pushPage(KibaSubPage.About) },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
