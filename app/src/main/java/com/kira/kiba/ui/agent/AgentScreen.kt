package com.kira.kiba.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kira.kiba.core.share.ShareService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.agent.AgentSession
import com.kira.kiba.core.agent.KibaAgent
import com.kira.kiba.core.agent.LlmParse
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.MarkdownContent
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.material.KibaGlassDialog
import com.kira.kiba.ui.navigation.LocalNavBarBottomPadding
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent 对话页：
 * - 引擎 [KibaAgent]：**聊天一律走 LLM**（模型可 tool-call 本地技能）；
 *   本地技能逻辑只在**从「功能」菜单点选**时直接执行（不消耗 LLM）；
 * - 原顶部横滑技能卡已全部迁入**顶栏「功能」按钮**的二级级联弹层
 *   （舞萌功能 / 中二节奏功能 / 娱乐功能 / 清除对话），页内不再放按钮或卡片；
 *   分类只改 UI 入口，[KibaAgent.SKILLS] 仍是完整的 LLM tool 表，模型调用不受影响；
 * - 会话记录持久化（[AgentSession]），重启后恢复；
 * - 布局：与曲目 / 我 / 设置页一致 —— 内容从 y=0 铺满，玻璃顶栏悬浮其上；
 *   顶部留白放在消息列表的 contentPadding（初始防遮挡、滚动时可被滚走，
 *   文字能从顶栏下穿过产生玻璃/折叠效果）+ 底部输入栏（含悬浮底栏留白与键盘避让）。
 *
 * 「功能」按钮位于 [com.kira.kiba.ui.navigation.KibaMainScreen] 的顶栏动作区
 * （与曲目页排序 / 搜索按钮同款 KibaActionButton），开关由外层持有；
 * [OverlayCascadingListPopup] 依赖 Scaffold 的 MiuixPopupHost，必须留在本页
 * Scaffold content 内，用一个右上角零尺寸锚点让菜单落在顶栏按钮下方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int, game: String) -> Unit,
    showFunctionPopup: Boolean = false,
    onDismissFunctionPopup: () -> Unit = {},
) {
    val context = LocalContext.current
    val agent = remember { KibaAgent(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    // 带参技能：从「功能」菜单点选后先弹输入层（离线可用，不依赖 LLM）
    var paramSkill by remember { mutableStateOf<KibaAgent.AgentSkill?>(null) }
    // 「功能」弹层开关由顶栏所在的外层持有（见 showFunctionPopup 参数）

    // 会话持久化：首次进入恢复历史
    AgentSession.ensureLoaded()
    val messages by AgentSession.turns.collectAsState()
    // 猜歌对局进行中：底部输入框切换成答题卡（歌名/别名/ID），提交走本地判定
    val guessPending by KibaAgent.pendingGuess.collectAsState()
    val guessing = guessPending != null

    // 顶栏遮挡量：只用在「消息列表 contentPadding 顶部」与「功能菜单锚点」两处，
    // 绝不写成容器固定 padding —— 那会变成一条滚不走的不透明死带。
    val clearance = LocalTopBarClearance.current
    val navBarBottom = LocalNavBarBottomPadding.current
    // 输入行贴键盘上沿：Column 的 imePadding 已把内容顶起 IME 高度，让位里
    // 把已顶起的部分扣掉（下限 12dp 贴边）。直接用 isImeVisible 二选一会在
    // **浮动键盘**（AVD/无障碍模式，ime inset = 0）下翻车：键在弹着但 inset
    // 为零， imePadding 顶不动内容，输入框反而沉底撞上悬浮底栏。
    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val inputRowBottom = (navBarBottom + 22.dp - imeBottomDp).coerceAtLeast(12.dp)
    // 已配置 LLM 才允许自由提问；否则只能从「功能」按钮点技能
    val llmReady = remember {
        SpUtil.isLlmEnabled() && (SpUtil.getLlmApiKey().isNotBlank() || SpUtil.getLlmBaseUrl().isNotBlank())
    }

    LaunchedEffect(Unit) {
        if (AgentSession.turns.value.isEmpty()) {
            AgentSession.add(
                AgentSession.Turn(
                    fromUser = false,
                    text = "点顶栏右侧的「功能」图标按钮即可直接使用（不消耗 LLM）：\n" +
                        "· 舞萌功能 —— Best 50 / 推分 / 随机选曲 / 查歌 / 运势 / 分数线 / 猜歌\n" +
                        "· 中二节奏功能 —— 中二查歌 / 定数 / 随机 / Rating / 推分\n" +
                        "· 娱乐功能 —— 随机数 / 骰子 / 今日性癖 / 今天吃什么 / 低情商高情商 / gocho 梗图\n" +
                        "· 清除对话 —— 清空聊天记录\n" +
                        "带参数的技能点开后会先弹出输入框填条件。\n" +
                        "配置 LLM 后还可以直接提问，我会自动调用对应技能；\n" +
                        "问「我该怎么推分」这类问题时，我会先读你的 Best 50 再给针对性结论。\n" +
                        "机制类问题（定数 / Rating 公式 / 判定档位 / 段位 / 宴会场 / 术语）我会先查" +
                        "**内置的萌娘百科知识库**（舞萌DX / 舞萌系列 / CHUNITHM），查不到才联网。\n" +
                        "（Best 50 / 推分需要先在「我」页面登录查分器）",
                )
            )
        }
        // 技能卡迁移提示：只在对话页出现一次（SP 标记），清除对话后也不会反复刷屏。
        // 老用户已有历史，不会看到上面改写过的欢迎语，这条提示就是给他们的。
        if (!SpUtil.isAgentToolsMigrationHintShown()) {
            SpUtil.setAgentToolsMigrationHintShown()
            AgentSession.add(
                AgentSession.Turn(
                    fromUser = false,
                    text = "功能卡已迁移到顶栏右侧的「功能」图标按钮里了：\n" +
                        "点开后按「舞萌功能 / 中二节奏功能 / 娱乐功能 / 清除对话」分类查找，" +
                        "原来的横滑技能卡不再显示。\n" +
                        "所有技能都还在，直接打字提问时我照样会自动调用，功能没有减少。",
                )
            )
        }
    }

    // ── 自动贴底后的顶栏策略 ──────────────────────────────────────────────
    // 发送消息 / 技能回复都会把列表自动滚到最新一条，按用户定稿分两档：
    //   ① 底部本来就在屏幕内（内容不足一屏）→ 顶栏一个字都不写，保持当前状态；
    //   ② 底部原本在屏幕外（贴底后列表仍能往回滚）→ 折叠顶栏让出大标题。
    // 「回到底部」浮动按钮不在此列：它只在 canScrollForward（底部在屏幕外）时
    // 出现，点它必然属于 ②，直接折到底即可。
    /**
     * 把顶栏压到完全折叠 —— 贴底时把大标题让出来，多露一屏聊天。
     *
     * - 折叠量取 `scrollBehavior.state.heightOffsetLimit`（负值，顶栏自己登记的大标题高度）。
     *   heightOffset 的 setter 会 clamp 到 [limit, 0]，写 limit 就是 100% 折叠；液态皮肤读
     *   collapsedFraction，同一份数值同时驱动玻璃带，不必分材质各写一套。材质关闭时顶栏
     *   没登记 limit（为 0），跳过即可，不会崩。
     * - contentOffset 同步置负：它既是「内容已滚离原位」的信号（液态按钮材质、详情页封面
     *   判定都看它），也是 nestedScroll 下一次累计的起点。只改 heightOffset 的话，用户接着
     *   滚一下就会按旧起点把顶栏弹回展开态。
     * - 已折到底直接返回：流式跟随每个 chunk 都会贴一次底，不能每片都重启 Animatable。
     */
    fun collapseTopBar() {
        val topBarState = scrollBehavior.state
        val collapsed = topBarState.heightOffsetLimit
        if (collapsed >= 0f) return
        if (topBarState.heightOffset <= collapsed + 1f &&
            topBarState.contentOffset <= collapsed + 1f
        ) return
        topBarState.contentOffset = collapsed
        scope.launch {
            Animatable(topBarState.heightOffset).animateTo(
                targetValue = collapsed,
                animationSpec = tween(durationMillis = 240),
            ) { topBarState.heightOffset = value }
        }
    }

    /** 贴底后的条件判定：只有底部真的超出过屏幕才动顶栏，否则保持原状。 */
    fun maybeCollapseTopBarAfterBottom() {
        if (!shouldCollapseTopBarAfterScrollToBottom(
                canScrollBackward = listState.canScrollBackward,
                heightOffsetLimit = scrollBehavior.state.heightOffsetLimit,
            )
        ) return
        collapseTopBar()
    }

    /**
     * 自动滚底动作之后的判定入口。新气泡是刚插入的，等一帧让 LazyColumn 完成
     * remeasure，否则读到的是上一帧 layoutInfo，会把「刚好撑出一屏」判成没超出。
     */
    suspend fun maybeCollapseTopBarAfterFrame() {
        withFrameNanos { }
        maybeCollapseTopBarAfterBottom()
    }

    // 流式输出视口跟随。
    //
    // 旧实现有两个坑（用户实测「流式过程中划不动」）：
    // ① LaunchedEffect 的 key 是正文长度 → 每来一个 chunk 就重启一次副作用，
    //    里面的 scrollToItem 又走 scroll 互斥锁，会把用户正在进行的拖拽**直接取消**，
    //    表现为整段流式期间完全滑不动；
    // ② 无条件贴底 → 用户想上翻看历史时被强行拽回底部。
    // 现在：一个常驻副作用订阅增量（collectLatest，不重建协程），且只在
    // 「用户没有在滑动 + 视口还贴在底部」时才跟随。
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val t = AgentSession.turns.value.lastOrNull()
            Triple(t?.text?.length ?: 0, t?.reasoning?.length ?: 0, t?.streaming == true)
        }.collectLatest { (_, _, streaming) ->
            if (!streaming || !autoFollow) return@collectLatest
            // 用户手指按着屏幕时绝不抢滚动（scrollToItem 会取消手势）
            if (listState.isScrollInProgress) return@collectLatest
            val last = AgentSession.turns.value.lastIndex
            if (last >= 0) listState.scrollToItem(last)
            // 回复边收边长，一旦把底部撑出屏幕就折叠；始终不足一屏则保持顶栏原状。
            // 这里不等帧（chunk 很密，等帧会被 collectLatest 取消掉），由各滚底动作
            // 之后的 maybeCollapseTopBarAfterFrame() 兜底补判。
            maybeCollapseTopBarAfterBottom()
        }
    }
    // 用户手势期间判定是否还该跟随：只在贴底时继续跟随，上滑即脱钩
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { dragging ->
            if (!dragging) return@collect
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
            autoFollow = lastVisible >= AgentSession.turns.value.lastIndex - 1
        }
    }

    // 「回到对话底部」浮动按钮：只有还能继续往下滚（说明用户翻上去看历史了）才出现。
    // canScrollForward 是滚动期间的高频 State，用 derivedStateOf 收一层，
    // 否则每帧滚动都会把整个对话页带着重组一遍。
    val showJumpToBottom by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }

    /**
     * 回到最新一条消息。按钮只在「底部还在屏幕外」时出现，所以直接把顶栏折到底；
     * 发送消息 / 技能回复那类自动贴底走的是 [maybeCollapseTopBarAfterBottom] 的两档判定，
     * 不在这里。autoFollow 复位：上翻时视口跟随会脱钩，不复位的话下一条流式消息不会贴底。
     */
    fun jumpToBottom() {
        autoFollow = true
        collapseTopBar()
        scope.launch {
            // 用列表自身的 totalItemsCount 而不是 turns.lastIndex：thinking 时列表末尾
            // 还挂着一条「思考中…」，按会话数算会差一项、滚不到真正的底部。
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    /** 直接执行技能（功能菜单点选专用，不走 LLM）。[arg] 为带参技能的输入。 */
    fun runSkill(skill: KibaAgent.AgentSkill, arg: String = "") {
        if (thinking) return
        // 开新猜歌局：清掉输入框残留，免得上一局没发出去的话被当成答案误提交
        if (skill.id == "guess_song") input = ""
        thinking = true
        scope.launch {
            val reply = runCatching { agent.runSkill(skill.id, arg) }.getOrElse {
                KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}")
            }
            thinking = false
            AgentSession.add(
                AgentSession.Turn(
                    fromUser = false,
                    text = reply.text,
                    songs = reply.songs,
                    // 技能说明卡不可点击，整份技能目录铺出来就是「弹一串卡片」。
                    skills = if (LlmParse.shouldRenderSkillCards(reply.skills.size)) {
                        reply.skills
                    } else {
                        emptyList()
                    },
                    b50 = reply.b50,
                    imageUri = reply.imageUri,
                )
            )
            val last = AgentSession.turns.value.lastIndex
            if (last >= 0) listState.animateScrollToItem(last)
            maybeCollapseTopBarAfterFrame()
        }
    }

    /**
     * 从「功能」菜单点选技能：无参直跑、带参先弹输入层。
     * 与旧技能卡的点击路径完全同一条，保证迁移不改行为。
     */
    fun openSkill(skill: KibaAgent.AgentSkill) {
        onDismissFunctionPopup()
        if (skill.params.isEmpty()) runSkill(skill) else paramSkill = skill
    }

    /** 清除对话（原技能卡区末尾那张卡的动作）。 */
    fun clearChat() {
        onDismissFunctionPopup()
        AgentSession.clear()
        scope.launch {
            AgentSession.add(
                AgentSession.Turn(
                    fromUser = false,
                    text = "对话已清除。点顶栏「功能」按钮重新开始，或直接提问。",
                )
            )
        }
    }

    /**
     * 「功能」按钮的二级级联菜单。
     *
     * 分类只作用于 UI 入口：**不改动、不删减 [KibaAgent.SKILLS]** —— 它同时是
     * LLM 的 tool 定义与 `skillHint()` 的技能说明书，模型 tool-calling 照常能点到
     * 全部技能。这里用「id 前缀 + 娱乐白名单」做划分而不是硬编码完整清单，
     * 这样以后往 SKILLS 里加新技能时，菜单也不会漏掉任何一个。
     */
    val functionEntries: List<DropdownEntry> = buildList {
        val funIds = setOf(
            "random_number", "roll_dice", "today_kink",
            "eat_what", "low_high_eq", "gocho",
        )
        val chuniSkills = KibaAgent.SKILLS.filter { it.id.startsWith("chuni_") }
        val funSkills = KibaAgent.SKILLS.filter { it.id in funIds }
        val classifiedIds = (chuniSkills.map { it.id } + funSkills.map { it.id }).toSet()
        // 其余一律归舞萌（含未来新增的舞萌技能），保证 SKILLS 全覆盖
        val maimaiSkills = KibaAgent.SKILLS.filter { it.id !in classifiedIds }
        fun children(skills: List<KibaAgent.AgentSkill>): List<DropdownItem> =
            skills.map { skill ->
                DropdownItem(
                    text = skill.title,
                    summary = skill.summary,
                    onClick = { openSkill(skill) },
                )
            }
        fun category(name: String, hint: String, skills: List<KibaAgent.AgentSkill>): DropdownEntry {
            val items = children(skills)
            return DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = name,
                        summary = "$hint（${items.size} 项）",
                        children = items,
                    ),
                ),
            )
        }
        add(category("舞萌功能", "查分 / 推分 / 选曲", maimaiSkills))
        add(category("中二节奏功能", "中二查歌 / 定数 / Rating", chuniSkills))
        add(category("娱乐功能", "随机数 / 梗图 / 整活", funSkills))
        add(
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = "清除对话",
                        summary = "清空聊天记录",
                        onClick = { clearChat() },
                    ),
                ),
            )
        )
    }

    /** 发送消息 → 全量走 LLM（流式输出：正文边收边显示，思考过程单独成卡）。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || thinking) return
        // 猜歌对局进行中：输入框优先当答题卡 —— 本地判定（确定、不烧 LLM token），
        // 用户消息正常入历史，答案对错由 submitGuess 给结论。
        if (KibaAgent.pendingGuess.value != null) {
            AgentSession.add(AgentSession.Turn(fromUser = true, text = trimmed))
            input = ""
            thinking = true
            scope.launch {
                listState.scrollToItem(AgentSession.turns.value.lastIndex)
                maybeCollapseTopBarAfterFrame()
            }
            scope.launch {
                val reply = runCatching { agent.submitGuess(trimmed) }.getOrElse {
                    KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}")
                }
                thinking = false
                AgentSession.add(
                    AgentSession.Turn(
                        fromUser = false,
                        text = reply.text,
                        songs = reply.songs,
                        skills = if (LlmParse.shouldRenderSkillCards(reply.skills.size)) {
                            reply.skills
                        } else {
                            emptyList()
                        },
                        b50 = reply.b50,
                        imageUri = reply.imageUri,
                        calls = reply.calls,
                    )
                )
                val idx = AgentSession.turns.value.lastIndex
                if (idx >= 0) listState.animateScrollToItem(idx)
                maybeCollapseTopBarAfterFrame()
            }
            return
        }
        AgentSession.add(AgentSession.Turn(fromUser = true, text = trimmed))
        // 先插一条空的 Agent 记录，流式往里追加
        AgentSession.add(AgentSession.Turn(fromUser = false, text = "", streaming = true))
        input = ""
        thinking = true
        // 立刻滚到底部露出新气泡，否则流式内容在屏幕外增长，看起来就像没流式
        scope.launch {
            listState.scrollToItem(AgentSession.turns.value.lastIndex)
            maybeCollapseTopBarAfterFrame()
        }
        scope.launch {
            val reply = runCatching {
                agent.handleStream(trimmed) { chunk ->
                    AgentSession.appendToLast(
                        text = chunk.text,
                        reasoning = chunk.reasoning,
                        reset = chunk.reset,
                        calls = chunk.calls,
                    )
                }
            }.getOrElse {
                KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}")
            }
            AgentSession.finishStream()
            // 技能数据（歌曲 / B50 / 梗图）等非流式产物，合并到最后一条。
            // 注意：技能调用卡（calls）已在流式过程中实时写入，这里不再覆盖，避免清空。
            val last = AgentSession.turns.value.lastOrNull()
            if (last != null && !last.fromUser) {
                AgentSession.replaceLast(
                    last.copy(
                        songs = reply.songs,
                        // 同上：LLM 回复里不铺整份技能目录
                        skills = if (LlmParse.shouldRenderSkillCards(reply.skills.size)) {
                            reply.skills
                        } else {
                            emptyList()
                        },
                        b50 = reply.b50,
                        imageUri = reply.imageUri.ifBlank { last.imageUri },
                    )
                )
            }
            thinking = false
            val idx = AgentSession.turns.value.lastIndex
            if (idx >= 0) listState.animateScrollToItem(idx)
            maybeCollapseTopBarAfterFrame()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // 与曲目 / 我 / 设置页同一套结构：内容从 y=0 铺满，玻璃顶栏悬浮在内容之上，
        // 顶部留白交给消息列表的 contentPadding（**可被滚走**）。
        //
        // 旧实现把 clearance 写成外层 Column 的固定 padding，等于在顶栏下面垫了一条
        // 永远不参与滚动的不透明死带：消息文字压根进不了顶栏背后，玻璃折射/大标题
        // 折叠时「内容从顶栏下穿过」的效果全丢，看起来顶栏下方有一块实体。
        // 现在文字初始仍在 clearance + 8.dp（不会顶进 TopBar），滑动时却可以滚到
        // 顶栏底下被玻璃采样，与其它 Tab 完全一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 消息列表 + 「回到底部」浮动按钮。按钮浮在列表底部、输入框正上方；
                // 放进同一个 Box 才能跟着外层 imePadding 一起被键盘顶起，不会压住输入栏。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        // nestedScroll 必须挂在**真正滚动的列表**上：本页列表外套了
                        // Box→Column→Box(weight) 四层，挂在 Scaffold 时滚动增量会被
                        // 顶栏折叠优先吃掉，state.contentOffset 到不了负值 —— 而
                        // GlassTopAppBar 的磨砂背板 / 按钮材质正是由 contentOffset < 0
                        // 触发的，结果就是「文字穿透、顶栏没有背景效果」。
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = clearance + 8.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages.size) { index ->
                            ChatBubble(messages[index], onOpenSong = onOpenSong)
                        }
                        if (thinking) {
                            item {
                                ChatBubble(AgentSession.Turn(text = "思考中…"), onOpenSong = onOpenSong)
                            }
                        }
                    }

                    // 已在最底部（含内容不足一屏）时整颗按钮淡出；上翻看历史才浮出来。
                    // 完全限定顶层函数：带隐式 receiver 的布局作用域会把简写解析成
                    // ColumnScope.AnimatedVisibility 扩展，触发 Compose 的 DSL 作用域报错。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showJumpToBottom,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.8f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.8f),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp, bottom = 12.dp)
                                .size(40.dp)
                                .shadow(6.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary)
                                .clickable { jumpToBottom() },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 玻璃图标库没有独立的 ArrowDown；拿右向 chevron 顺时针转 90°
                            // 得到干净的向下箭头，比语义为「下载」的 Download 更贴切。
                            Icon(
                                MiuixGlassIcons.Regular.ChevronForward,
                                contentDescription = "回到对话底部",
                                modifier = Modifier
                                    .size(22.dp)
                                    .rotate(90f),
                                tint = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }

                // ── 底部输入区：猜歌对局中 → 答题卡（无需 LLM）；
                //    否则已配置 LLM 才是聊天输入框；都没有则提示走「功能」按钮 ──
                when {
                    guessing -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = inputRowBottom),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            label = "答案是…（歌名 / 别名 / ID）",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        // 放弃：直接公布答案、结束对局（输入「放弃 / 不猜了」等效）
                        TextButton(text = "放弃", onClick = { send("放弃") })
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { send(input) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(text = if (thinking) "…" else "猜猜看")
                        }
                    }
                    llmReady -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = inputRowBottom),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            label = "问问 Kiba…",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { send(input) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(text = if (thinking) "…" else "发送")
                        }
                    }
                    else -> Text(
                        text = "未配置 LLM · 点顶栏「功能」按钮可直接使用（设置 → Agent 模型配置 可开启对话）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = navBarBottom + 16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // ── 顶栏「功能」按钮的级联弹层锚点 ──
            // 锚点必须是外层 Box 里的 overlay，绝不能排进 Column 占高度：
            // 那会把消息列表整体推下 clearance，又变回顶栏下面那条不透明死带。
            // 位置仍落在 y = clearance（顶栏右侧按钮下方）；
            // OverlayCascadingListPopup 依赖 Scaffold 的 MiuixPopupHost，
            // 留在 content 内即可，renderInRootScaffold=true 负责坐标补偿。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = clearance)
                    .size(1.dp),
            ) {
                OverlayCascadingListPopup(
                    show = showFunctionPopup,
                    entries = functionEntries,
                    onDismissRequest = onDismissFunctionPopup,
                    collapseOnSelection = true,
                )
            }
        }
    }

    // ── 带参技能的输入弹层（例：查歌名 / 定数区间 / 分数线 / 随机条件）──
    paramSkill?.let { skill ->
        TextEditDialog(
            title = skill.title,
            summary = skill.params.joinToString("\n") { p ->
                "· ${p.name}（${if (p.required) "必填" else "可选"}）：${p.description}"
            },
            initial = "",
            placeholder = skill.paramHint,
            singleLine = true,
            onDismiss = { paramSkill = null },
            onConfirm = { value ->
                paramSkill = null
                runSkill(skill, value)
            },
        )
    }
}

/** 气泡内的一个渲染单元（按真实发生时序排列）。 */
private sealed interface ChatBlock {
    data class Text(val text: String) : ChatBlock
    data class Reasoning(val text: String) : ChatBlock
    data class Calls(val calls: List<KibaAgent.ToolInvocation>) : ChatBlock
}

/**
 * 把一条记录拆成按时序排列的渲染单元。
 *
 * 新记录走 [AgentSession.Turn.segments]（流式过程中逐段累积）；
 * 旧记录没有分段，退化成原来的固定顺序（技能卡 → 思考 → 正文）保持向后兼容。
 */
private fun chatBlocks(msg: AgentSession.Turn): List<ChatBlock> {
    val raw = mutableListOf<ChatBlock>()
    if (msg.segments.isNotEmpty()) {
        msg.segments.forEach { seg ->
            when (seg.kind) {
                AgentSession.SegKind.CALLS ->
                    if (seg.calls.isNotEmpty()) raw += ChatBlock.Calls(seg.calls)
                AgentSession.SegKind.REASONING ->
                    if (seg.text.isNotBlank()) raw += ChatBlock.Reasoning(seg.text)
                AgentSession.SegKind.TEXT ->
                    if (seg.text.isNotBlank()) raw += ChatBlock.Text(seg.text)
            }
        }
    } else {
        if (msg.calls.isNotEmpty()) raw += ChatBlock.Calls(msg.calls)
        if (msg.reasoning.isNotBlank()) raw += ChatBlock.Reasoning(msg.reasoning)
        if (msg.text.isNotBlank()) raw += ChatBlock.Text(msg.text)
    }
    // 去重：部分模型把同一段话同时塞进 content 和 reasoning_content，
    // 不去重的话「深度思考」卡和正文气泡会显示一模一样的两段话。
    return raw.filterIndexed { i, block ->
        block !is ChatBlock.Text ||
            (raw.getOrNull(i - 1) as? ChatBlock.Reasoning)?.text?.trim() != block.text.trim()
    }
}

/** 消息气泡（用户右 / Agent 左），按发生时序依次铺开进度段与卡片。 */
@Composable
private fun ChatBubble(msg: AgentSession.Turn, onOpenSong: (Int, String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 关键：逐段铺开，而不是「技能卡整块 + 思考整块 + 正文整块」。
        // 于是一次回复里的「查了再说 → 又说要再查 → 最终答复」各占一段，
        // 技能卡天然夹在相邻两段进展之间，顺序就是真实发生顺序。
        val blocks = remember(msg) { chatBlocks(msg) }
        blocks.forEachIndexed { index, block ->
            // 「进行中」动效只给最后一段，前面的进度段已经定型
            val live = msg.streaming && index == blocks.lastIndex
            when (block) {
                is ChatBlock.Calls -> SkillInvocationCard(calls = block.calls, streaming = live)
                is ChatBlock.Reasoning -> ReasoningCard(text = block.text, streaming = live)
                is ChatBlock.Text -> ChatTextBubble(text = block.text, fromUser = msg.fromUser)
            }
            if (index != blocks.lastIndex) Spacer(Modifier.height(8.dp))
        }

        // 歌曲卡：点击进入歌曲详情
        msg.songs.forEach { song ->
            Spacer(Modifier.height(8.dp))
            SongAgentCard(song = song, onClick = { onOpenSong(song.id, song.game) })
        }

        // 技能卡：点击直接执行
        msg.skills.forEach { skill ->
            Spacer(Modifier.height(8.dp))
            SkillAgentCard(skill = skill)
        }

        // B50 卡：总分 + 旧曲 35 / 新曲 15
        msg.b50?.let { b50 ->
            Spacer(Modifier.height(8.dp))
            Best50AgentCard(info = b50)
        }

        // 梗图：技能产出的图片（缓存里，可长按保存 / 复制 / 分享）
        if (msg.imageUri.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            AgentImage(uri = msg.imageUri)
        }
    }
}

/**
 * 单段文本气泡。长按复制只复制这一段（进度段 / 最终答复各自可复制）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatTextBubble(text: String, fromUser: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (fromUser) 0.82f else 0.92f)
                // 长按整段气泡复制文本：模型给的答案经常要粘到别处，之前只能手拖选中
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        if (cm == null) {
                            Toast.makeText(context, "剪贴板不可用", Toast.LENGTH_SHORT).show()
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("Kiba", text))
                            Toast.makeText(context, "已复制这段文字", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .background(
                    if (fromUser) {
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (fromUser) 14.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 14.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (fromUser) {
                // 用户消息保持原样（原文本，不做 markdown 解析）
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            } else {
                // Agent 回复走 GFM 渲染：标题 / 列表 / 表格 / 代码块 / 粗斜体等
                MarkdownContent(
                    text = text,
                    modifier = Modifier.fillMaxWidth(),
                    bodyFontSizeSp = 15,
                )
            }
        }
    }
}

/**
 * 技能调用展示卡（WorkBuddy 风格）。
 *
 * 一个折叠式的分组卡：标题行说「调用了 N 个技能」，展开后逐条列出
 * 「技能名 · 参数 · 结果摘要」。技能执行中（流式还没收尾）时标题显示「正在调用」。
 *
 * 为什么不做成每技能一张大卡：模型一轮可能连调 3~4 个工具（best50 → push_rating →
 * song_query），铺开会把正文挤到屏幕外，反而不像「过程展示」而像刷屏。
 */
@Composable
private fun SkillInvocationCard(
    calls: List<KibaAgent.ToolInvocation>,
    streaming: Boolean,
) {
    var expanded by remember(calls.size) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "技",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append(if (streaming) "正在调用 " else "调用了 ")
                        append(calls.size)
                        append(" 个技能")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (expanded) {
                calls.forEach { call ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        // 状态点：成功 = 主色，失败 = error 色
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (call.ok) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(call.title)
                                    if (call.args.isNotBlank()) {
                                        append("（")
                                        append(call.args)
                                        append("）")
                                    }
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            if (call.summary.isNotBlank()) {
                                Text(
                                    text = call.summary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 深度思考（reasoning）：弱化样式的独立卡片。
 *
 * 之前流式期间用 `maxLines = 12` 硬切，用户看到的就是「思考到一半被截断」。
 * 现在：流式中**完整显示**（跟随列表滚动），收尾后长内容折叠到 6 行并给「展开」，
 * 既不截断信息，也不会让十几屏的思考把正文顶走。
 */
@Composable
private fun ReasoningCard(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    Card(
        modifier = (Modifier
            .fillMaxWidth()
            .background(
                MiuixTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !streaming) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (streaming) "深度思考中…" else "深度思考",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (!streaming && lineCount > 6) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 流式中不设上限（截断就是这次要修的问题）；收尾后默认折叠到 6 行可展开
                maxLines = if (!streaming && !expanded) 6 else Int.MAX_VALUE,
            )
        }
    }
}

/**
 * 歌曲卡：封面 + 歌曲信息 + 徽标行，点击进入详情。
 *
 * 徽标换行：标题过长时徽标不会挤到卡片边缘 —— FlowRow 承载徽标，
 * 宽度不足自动换行；标题行与徽标行分离，各占满整行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongAgentCard(song: KibaAgent.AgentSong, onClick: () -> Unit) {
    Card(
        modifier = (Modifier.fillMaxWidth()),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverFrame(
                    songId = song.id,
                    imageUrl = song.imageUrl,
                    genre = song.genre,
                    size = 48.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = song.artist,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 徽标单独一行，FlowRow 保证换行不溢出
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 舞萌才有 SD/DX；中二的 type 是空串，别渲染成空白徽标
                if (song.type.isNotBlank()) ChartTypeChip(type = song.type)
                if (song.difficulty.isNotBlank()) {
                    DifficultyChip(
                        text = "${song.difficulty} ${song.topLevel}",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                if (song.topDs > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "定数 ${song.topDs}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/** 技能卡（回复内）：展示技能说明。 */
@Composable
private fun SkillAgentCard(skill: KibaAgent.AgentSkill) {
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "技能：${skill.title}",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = skill.summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** B50 卡：总分 + 旧曲 35 / 新曲 15 分数。 */
@Composable
private fun Best50AgentCard(info: KibaAgent.Best50Info) {
    Card(modifier = (Modifier.fillMaxWidth())) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DX Rating ${info.total}",
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "旧曲 ${info.oldCount} 首 = ${info.b35}　新曲 ${info.newCount} 首 = ${info.b15}",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (info.entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                info.entries.take(6).forEachIndexed { i, r ->
                    Text(
                        text = "${i + 1}. ${r.title} [${difficultyLabel(r.levelIndex)} ${r.level}] " +
                            "%.4f%%".format(r.achievements) + "  ra ${r.ra}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// ══════════════════════ 对话内图片：点击看大图 / 长按保存·复制·分享 ══════════════════════
//
// 之前只是一个裸 AsyncImage：既不能长按保存，也不能复制，用户想留图只能截图。
// 现在：
// - **点击** → 全屏预览（黑底、Fit 居中，点任意处关闭）；
// - **长按**（气泡里或预览里都行）→ 保存到相册 / 复制图片 / 分享给好友。
//
// 图片来自技能生成（cacheDir/generated，FileProvider 的 content:// URI），
// 所以「复制」只能放 URI 进剪贴板 —— 部分应用支持粘贴图片，部分只认文字，
// 这一点在提示里直说，别让用户以为坏了。

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentImage(uri: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** 图片字节（IO 读取，供保存 / 分享复用）。 */
    suspend fun bytesOf(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }.getOrNull()
    }

    coil.compose.AsyncImage(
        model = uri,
        contentDescription = "生成的图片",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { preview = true },
                onLongClick = { actions = true },
            ),
    )

    if (preview) {
        Dialog(
            onDismissRequest = { preview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .combinedClickable(
                        onClick = { preview = false },
                        onLongClick = { actions = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = "点击任意处关闭 · 长按图片可保存 / 复制 / 分享",
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
                )
            }
        }
    }

    if (actions) {
        KibaGlassDialog(
            show = true,
            title = "图片",
            summary = "保存到相册、复制图片，或发送给好友",
            onDismissRequest = { actions = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "生成的图片存在应用缓存里，长期不用会被自动清理；" +
                        "想留着就保存到相册。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(text = "取消", onClick = { actions = false })
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "复制图片", onClick = {
                        actions = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (cm == null) {
                            toast("剪贴板不可用")
                        } else {
                            val clip = ClipData.newUri(context.contentResolver, "Kiba 图片", Uri.parse(uri))
                            cm.setPrimaryClip(clip)
                            toast("已复制图片，去聊天框长按粘贴（部分应用只认文字）")
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "分享", onClick = {
                        actions = false
                        scope.launch {
                            val bytes = bytesOf()
                            if (bytes == null) {
                                toast("读取图片失败")
                            } else {
                                val name = "Kiba_image_${System.currentTimeMillis()}.png"
                                if (!ShareService.sharePng(context, bytes, name)) toast("分享失败")
                            }
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "保存到相册", onClick = {
                        actions = false
                        scope.launch {
                            val bytes = bytesOf()
                            if (bytes == null) {
                                toast("读取图片失败")
                                return@launch
                            }
                            val name = "Kiba_image_${System.currentTimeMillis()}.png"
                            val saved = ShareService.saveToGallery(context, bytes, name)
                            toast(
                                if (saved != null) "已保存到相册：Pictures/Kiba/$name"
                                else "保存失败：无法写入相册"
                            )
                        }
                    })
                }
            }
        }
    }
}
