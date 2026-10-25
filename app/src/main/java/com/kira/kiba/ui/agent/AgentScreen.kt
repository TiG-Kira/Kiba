package com.kira.kiba.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.agent.AgentSession
import com.kira.kiba.core.agent.KibaAgent
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChartTypeChip
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.MarkdownContent
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.navigation.LocalNavBarBottomPadding
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent 对话页：
 * - 引擎 [KibaAgent]：**聊天一律走 LLM**（模型可 tool-call 本地技能）；
 *   本地技能逻辑只在**点击技能卡**时直接执行（不消耗 LLM）；
 * - 技能卡区常驻对话框上方，末尾附「清除对话」卡片（非技能）；
 * - 会话记录持久化（[AgentSession]），重启后恢复；
 * - 布局：顶部顶栏 clearance（防遮挡）+ 消息列表 + 底部输入栏（含悬浮底栏留白与键盘避让）。
 */
@Composable
fun AgentScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int) -> Unit,
) {
    val context = LocalContext.current
    val agent = remember { KibaAgent(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    // 带参技能：点击卡片先弹输入层（离线可用，不依赖 LLM）
    var paramSkill by remember { mutableStateOf<KibaAgent.AgentSkill?>(null) }

    // 会话持久化：首次进入恢复历史
    AgentSession.ensureLoaded()
    val messages by AgentSession.turns.collectAsState()

    val clearance = LocalTopBarClearance.current
    val navBarBottom = LocalNavBarBottomPadding.current
    // 已配置 LLM 才允许自由提问；否则只能点技能卡
    val llmReady = remember {
        SpUtil.isLlmEnabled() && (SpUtil.getLlmApiKey().isNotBlank() || SpUtil.getLlmBaseUrl().isNotBlank())
    }

    LaunchedEffect(Unit) {
        if (AgentSession.turns.value.isEmpty()) {
            AgentSession.add(
                AgentSession.Turn(
                    fromUser = false,
                    text = "点上方技能卡即可直接使用（不消耗 LLM）：\n" +
                        "· 我的 Best 50 —— 查分器成绩单（总分 + 旧曲/新曲）\n" +
                        "· 推荐推分曲目 —— 针对性上分\n" +
                        "· 随机来一首 —— 曲库随机选曲\n" +
                        "· 查歌曲信息 —— 按歌名/别名/ID 查谱面\n" +
                        "· 今日舞萌运势 —— 今日运势与推荐曲目\n" +
                        "· 定数查歌 / 分数线 / 随机条件曲 / 猜歌 —— KibaBot 移植技能（带参数，点卡片填条件）\n" +
                        "配置 LLM 后还可以直接提问，我会自动调用对应技能；\n" +
                        "问「我该怎么推分」这类问题时，我会先读你的 Best 50 再给针对性结论。\n" +
                        "（Best 50 / 推分需要先在「我」页面登录查分器）",
                )
            )
        }
    }

    // 流式输出视口跟随：最后一条正在流式且其内容已在视野内（用户贴底阅读）时，
    // 正文/思考每有增量就贴底；用户手动上滑翻历史时不再抢滚动。
    val lastTurn = messages.lastOrNull()
    LaunchedEffect(lastTurn?.text?.length, lastTurn?.reasoning?.length, lastTurn?.streaming) {
        val turn = messages.lastOrNull() ?: return@LaunchedEffect
        if (!turn.streaming) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= messages.lastIndex - 1) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    /** 直接执行技能卡（不走 LLM）。[arg] 为带参技能的输入。 */
    fun runSkill(skill: KibaAgent.AgentSkill, arg: String = "") {
        if (thinking) return
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
                    skills = reply.skills,
                    b50 = reply.b50,
                    imageUri = reply.imageUri,
                )
            )
            val last = AgentSession.turns.value.lastIndex
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    /** 发送消息 → 全量走 LLM（流式输出：正文边收边显示，思考过程单独成卡）。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || thinking) return
        AgentSession.add(AgentSession.Turn(fromUser = true, text = trimmed))
        // 先插一条空的 Agent 记录，流式往里追加
        AgentSession.add(AgentSession.Turn(fromUser = false, text = "", streaming = true))
        input = ""
        thinking = true
        // 立刻滚到底部露出新气泡，否则流式内容在屏幕外增长，看起来就像没流式
        scope.launch { listState.scrollToItem(AgentSession.turns.value.lastIndex) }
        scope.launch {
            val reply = runCatching {
                agent.handleStream(trimmed) { chunk ->
                    AgentSession.appendToLast(
                        text = chunk.text,
                        reasoning = chunk.reasoning,
                        reset = chunk.reset,
                    )
                }
            }.getOrElse {
                KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}", skills = KibaAgent.SKILLS)
            }
            AgentSession.finishStream()
            // 技能数据（歌曲 / B50 / 梗图）等非流式产物，合并到最后一条
            val last = AgentSession.turns.value.lastOrNull()
            if (last != null && !last.fromUser) {
                AgentSession.replaceLast(
                    last.copy(
                        songs = reply.songs,
                        skills = reply.skills,
                        b50 = reply.b50,
                        imageUri = reply.imageUri.ifBlank { last.imageUri },
                    )
                )
            }
            thinking = false
            val idx = AgentSession.turns.value.lastIndex
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = clearance)
                .imePadding(),
        ) {
            // ── 技能卡区（对话框上方）：点击直接执行，不经过 LLM ──
            SkillStrip(
                skills = KibaAgent.SKILLS,
                onSkillClick = { skill ->
                    if (skill.params.isEmpty()) runSkill(skill) else paramSkill = skill
                },
                onClear = {
                    AgentSession.clear()
                    scope.launch {
                        AgentSession.add(
                            AgentSession.Turn(
                                fromUser = false,
                                text = "对话已清除。点上方技能卡重新开始，或直接提问。",
                            )
                        )
                    }
                },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
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

            // ── 底部：已配置 LLM 才有输入框；否则提示用技能卡 ──
            if (llmReady) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = navBarBottom + 22.dp),
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
                    Button(onClick = { send(input) }) {
                        Text(text = if (thinking) "…" else "发送")
                    }
                }
            } else {
                Text(
                    text = "未配置 LLM · 点上方技能卡可直接使用（设置 → Agent 模型配置 可开启对话）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = navBarBottom + 16.dp),
                    textAlign = TextAlign.Center,
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

/**
 * 技能卡区（顶部横滑）：点击直接执行技能。
 * 末尾附「清除对话」卡片（不是技能，仅清空会话记录）。
 */
@Composable
private fun SkillStrip(
    skills: List<KibaAgent.AgentSkill>,
    onSkillClick: (KibaAgent.AgentSkill) -> Unit,
    onClear: () -> Unit,
) {
    // 统一卡宽：按「所有卡片（含清除卡）中，说明完整显示（单行不截断）所需的
    // 最大文本宽度」设定每张卡的宽度，保证任何一张卡里说明都完整可见。
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleStyle = MiuixTheme.textStyles.subtitle
    val summaryStyle = MiuixTheme.textStyles.footnote2
    val uniformCardWidth = remember(skills, titleStyle, summaryStyle) {
        val titlePx = (skills.map { it.title } + "清除对话").maxOf {
            textMeasurer.measure(it, titleStyle).size.width
        }
        val summaryPx = (skills.map { it.summary } + "清空聊天记录").maxOf {
            textMeasurer.measure(it, summaryStyle).size.width
        }
        with(density) {
            maxOf(titlePx, summaryPx).toDp() + 24.dp /* 左右 padding */ + 1.dp /* 取整余量 */
        }
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(skills.size) { index ->
            val skill = skills[index]
            Card(
                modifier = Modifier.width(uniformCardWidth),
                onClick = { onSkillClick(skill) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = skill.title,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skill.summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        softWrap = false,
                    )
                }
            }
        }
        // ── 清除对话（非技能）──
        item {
            Card(
                modifier = Modifier.width(uniformCardWidth),
                onClick = onClear,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "清除对话",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.primary,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "清空聊天记录",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** 消息气泡（用户右 / Agent 左；颜色走 miuix 配色字段），并承载歌曲 / 技能 / B50 卡片。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(msg: AgentSession.Turn, onOpenSong: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 正文与思考内容去重：部分模型把同一段话同时塞进 content 和 reasoning_content，
        // 不去重的话「深度思考」卡片和正文气泡会显示一模一样的两段话。
        val bodyText = remember(msg.text, msg.reasoning) {
            if (msg.reasoning.isNotBlank() && msg.text.trim() == msg.reasoning.trim()) {
                ""
            } else {
                msg.text
            }
        }
        // 深度思考单独成卡，不和正文混在一起
        if (msg.reasoning.isNotBlank()) {
            ReasoningCard(text = msg.reasoning, streaming = msg.streaming)
            Spacer(Modifier.height(8.dp))
        }
        if (bodyText.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (msg.fromUser) 0.82f else 0.92f)
                        .background(
                            if (msg.fromUser) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MiuixTheme.colorScheme.surface
                            },
                            RoundedCornerShape(
                                topStart = 14.dp,
                                topEnd = 14.dp,
                                bottomStart = if (msg.fromUser) 14.dp else 4.dp,
                                bottomEnd = if (msg.fromUser) 4.dp else 14.dp,
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    if (msg.fromUser) {
                        // 用户消息保持原样（原文本，不做 markdown 解析）
                        Text(
                            text = bodyText,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    } else {
                        // Agent 回复走 GFM 渲染：标题 / 列表 / 表格 / 代码块 / 粗斜体等
                        MarkdownContent(
                            text = bodyText,
                            modifier = Modifier.fillMaxWidth(),
                            bodyFontSizeSp = 15,
                        )
                    }
                }
            }
        }

        // 歌曲卡：点击进入歌曲详情
        msg.songs.forEach { song ->
            Spacer(Modifier.height(8.dp))
            SongAgentCard(song = song, onClick = { onOpenSong(song.id) })
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

        // 梗图：技能产出的图片（已存相册）
        if (msg.imageUri.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            coil.compose.AsyncImage(
                model = msg.imageUri,
                contentDescription = "生成的图片",
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** 深度思考（reasoning）：弱化样式的独立卡片，标题「深度思考」。 */
@Composable
private fun ReasoningCard(text: String, streaming: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MiuixTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = if (streaming) "深度思考中…" else "深度思考",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = if (streaming) 12 else Int.MAX_VALUE,
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
        modifier = Modifier.fillMaxWidth(),
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
                ChartTypeChip(type = song.type)
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
