package com.kira.kiba.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ChatMessage(val fromUser: Boolean, val text: String)

private val seed = listOf(
    ChatMessage(false, "我是 Kiba Agent，可帮你查 Best 50、推分、随机选曲、查歌曲、给舞萌游玩建议。"),
    ChatMessage(true, "帮我推荐一首推分曲目"),
    ChatMessage(false, "（示例）已根据你的 Rating 生成推荐，联网/授权读取账户后这里会给出真实建议。"),
)

/** Agent 对话页：聊天式交互外壳。底层引擎（联网搜索 + 账户授权读取 + 本地舞萌逻辑）为后续任务。 */
@Composable
fun AgentScreen() {
    val clearance = LocalTopBarClearance.current
    var input by remember { mutableStateOf("") }
    val messages = remember { seed }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = clearance)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (msg.fromUser) 260.dp else 280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (msg.fromUser) MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MiuixTheme.colorScheme.surface
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            msg.text,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("问问 Kiba Agent…") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
