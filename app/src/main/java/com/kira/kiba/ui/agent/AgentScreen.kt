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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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

private data class ChatMessage(val fromUser: Boolean, val text: String)

private val quickPrompts = listOf("我的 Best 50", "推荐推分曲目", "随机来一首", "帮助")

/**
 * Agent 对话页：
 * - 引擎 [KibaAgent]：本地舞萌逻辑（Best 50 / 推分推荐 / 随机 / 查歌 / 游玩建议），
 *   未授权或网络异常时降级为提示，不崩溃；
 * - 布局：顶部顶栏 clearance（防遮挡）+ 消息列表 + 底部输入栏（含悬浮底栏留白与键盘避让）。
 */
@Composable
fun AgentScreen(scrollBehavior: ScrollBehavior) {
    val context = LocalContext.current
    val agent = remember { KibaAgent(context) }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val clearance = LocalTopBarClearance.current
    val navBarBottom = LocalNavBarBottomPadding.current

    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    false,
                    "我是 Kiba Agent。可以问我：\n" +
                        "· 「我的 Best 50」查看成绩单\n" +
                        "· 「推荐推分曲目」针对性上分\n" +
                        "· 「随机来一首」选曲\n" +
                        "· 「查歌 + 歌名/ID」查定数难度\n" +
                        "· 「新手建议」舞萌游玩攻略\n" +
                        "查分器数据需先在「我」页面完成 OAuth 绑定。",
                )
            )
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
            messages.add(ChatMessage(false, reply.text))
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
                    ChatBubble(messages[index])
                }
                if (thinking) {
                    item {
                        ChatBubble(ChatMessage(false, "思考中…"))
                    }
                }
            }

            // 快捷指令
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickPrompts.forEach { prompt ->
                    Box(
                        modifier = Modifier
                            .background(
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { send(prompt) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = prompt,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 输入栏（底部预留悬浮底栏高度）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = navBarBottom),
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
        }
    }
}

/** 消息气泡（用户右 / Agent 左；颜色走 miuix 配色字段）。 */
@Composable
private fun ChatBubble(msg: ChatMessage) {
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
