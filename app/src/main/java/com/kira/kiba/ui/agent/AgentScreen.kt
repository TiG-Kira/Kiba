package com.kira.kiba.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.agent.KibaAgent
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.CoverFrame
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

private data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val songs: List<KibaAgent.AgentSong> = emptyList(),
    val skills: List<KibaAgent.AgentSkill> = emptyList(),
    val b50: KibaAgent.Best50Info? = null,
)

private val quickPrompts = listOf("我的 Best 50", "推荐推分曲目", "随机来一首", "帮助")

/**
 * Agent 对话页：
 * - 引擎 [KibaAgent]：本地舞萌逻辑（Best 50 / 推分推荐 / 随机 / 查歌 / 游玩建议），
 *   未授权或网络异常时降级为提示，不崩溃；
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
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val clearance = LocalTopBarClearance.current
    val navBarBottom = LocalNavBarBottomPadding.current
    // 已配置 LLM 才允许自由提问；否则只能点技能卡
    val llmReady = remember { SpUtil.isLlmEnabled() && SpUtil.getLlmApiKey().isNotBlank() } ||
        remember { SpUtil.isLlmEnabled() && SpUtil.getLlmBaseUrl().isNotBlank() }

    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    false,
                    "点上方技能卡即可直接使用：\n" +
                        "· 我的 Best 50 —— 查分器成绩单（总分 + 旧曲/新曲）\n" +
                        "· 推荐推分曲目 —— 针对性上分\n" +
                        "· 随机来一首 —— 曲库随机选曲\n" +
                        "· 查歌曲信息 —— 按歌名/别名/ID 查谱面\n" +
                        "· 舞萌建议 —— 通用攻略\n" +
                        "配置 LLM 后还可以直接提问，我会自动调用对应技能。\n" +
                        "（Best 50 / 推分需要先在「我」页面登录查分器）",
                )
            )
        }
    }

    /** 直接执行技能卡（不走 LLM）。 */
    fun runSkill(skill: KibaAgent.AgentSkill) {
        if (thinking) return
        thinking = true
        scope.launch {
            val reply = runCatching { agent.runSkill(skill.id) }.getOrElse {
                KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}")
            }
            thinking = false
            messages.add(
                ChatMessage(
                    fromUser = false,
                    text = reply.text,
                    songs = reply.songs,
                    skills = reply.skills,
                    b50 = reply.b50,
                )
            )
            val last = messages.lastIndex
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || thinking) return
        messages.add(ChatMessage(true, trimmed))
        input = ""
        thinking = true
        scope.launch {
            val reply = runCatching { agent.handle(trimmed) }.getOrElse {
                KibaAgent.AgentReply("出错了：${it.message ?: "未知错误"}")
            }
            thinking = false
            messages.add(
                ChatMessage(
                    fromUser = false,
                    text = reply.text,
                    songs = reply.songs,
                    skills = reply.skills,
                    b50 = reply.b50,
                )
            )
            val last = messages.lastIndex
            if (last >= 0) listState.animateScrollToItem(last)
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
                onSkillClick = { skill -> runSkill(skill) },
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
                        ChatBubble(ChatMessage(false, "思考中…"), onOpenSong = onOpenSong)
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
}

/** 技能卡区（顶部横滑）：点击直接执行技能。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillStrip(
    skills: List<KibaAgent.AgentSkill>,
    onSkillClick: (KibaAgent.AgentSkill) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(skills.size) { index ->
            val skill = skills[index]
            Card(
                modifier = Modifier.width(132.dp),
                onClick = { onSkillClick(skill) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = skill.title,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skill.summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** 消息气泡（用户右 / Agent 左；颜色走 miuix 配色字段），并承载歌曲 / 技能 / B50 卡片。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(msg: ChatMessage, onOpenSong: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (msg.text.isNotBlank()) {
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
                    Text(
                        text = msg.text,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
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
    }
}

/** 歌曲卡：封面 + 歌曲信息，点击进入详情。 */
@Composable
private fun SongAgentCard(song: KibaAgent.AgentSong, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(song.type)
                        if (song.difficulty.isNotBlank()) {
                            append(" · ")
                            append(song.difficulty)
                        }
                        if (song.topDs > 0) {
                            append(" · 定数 ")
                            append(song.topDs)
                        }
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
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

/** 空状态（当前未使用，保留给后续能力扩展）。 */
@Composable
private fun AgentEmptyHint(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
        )
    }
}
