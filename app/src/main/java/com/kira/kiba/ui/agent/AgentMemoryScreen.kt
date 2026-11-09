package com.kira.kiba.ui.agent

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.agent.AgentMemory
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent 长期记忆编辑页（设置 → Agent → 长期记忆）。
 *
 * 只做三件事：看有哪些记忆、逐条改 / 删、整体开关。
 * 数据源是 [AgentMemory]（SpUtil 落盘的单一副本），改完立刻生效——
 * 下一次对话的 system prompt 就会带新内容，不需要重启。
 */
@Composable
fun AgentMemoryScreen(scrollBehavior: ScrollBehavior) {
    LaunchedEffect(Unit) { AgentMemory.ensureLoaded() }
    val items by AgentMemory.items.collectAsState()
    var enabled by remember { mutableStateOf(AgentMemory.isEnabled()) }
    // 正在编辑的条目（null = 不在编辑）；新增用单独的布尔量，避免和「编辑空条目」混淆
    var editing by remember { mutableStateOf<AgentMemory.Item?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AgentMemory.Item?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
            item(key = "switch_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            AgentMemory.setEnabled(it)
                        },
                        title = "启用长期记忆",
                        summary = if (enabled) {
                            "每次对话都会把下面的内容拼进系统提示词；关闭后条目仍保留，只是不再使用"
                        } else {
                            "已关闭：Agent 不会读到这些记忆，也不会再自动记新内容"
                        },
                    )
                }
            }

            item(key = "intro") {
                Text(
                    text = "Agent 会在你说「记住…」时自己记一条，" +
                        "你也可以在这里手动写：称呼、主玩游戏、口味偏好、明确要求（例如「以后都推荐 13 级以上」）。" +
                        "最多 ${AgentMemory.MAX_ITEMS} 条、单条 ${AgentMemory.MAX_ITEM_CHARS} 字，" +
                        "超出时最老的一条会被挤掉。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            item(key = "add_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "新增一条记忆",
                        summary = "写一句 Agent 需要一直记得的话",
                        startAction = {
                            Icon(
                                imageVector = MiuixGlassIcons.Regular.Add,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                        onClick = { adding = true },
                    )
                }
            }

            item(key = "list_title") {
                SectionTitle(if (items.isEmpty()) "已记住" else "已记住（${items.size} / ${AgentMemory.MAX_ITEMS}）")
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        text = "还没有任何记忆。\n" +
                            "在 Agent 聊天里说一句「记住：我叫 Kira，主玩舞萌」，它就会出现在这里。",
                    )
                }
            } else {
                items(items.sortedByDescending { it.updatedAt }, key = { it.id }) { item ->
                    MemoryItemCard(
                        item = item,
                        onEdit = { editing = item },
                        onDelete = { deleting = item },
                    )
                }
            }

            item(key = "clear_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "清空全部记忆",
                        summary = if (items.isEmpty()) "当前没有可清空的条目" else "删除全部 ${items.size} 条（不可撤销）",
                        startAction = {
                            Icon(
                                imageVector = MiuixGlassIcons.Regular.Delete,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                        onClick = { showClearConfirm = true },
                        enabled = items.isNotEmpty(),
                    )
                }
            }
        }
    }

    if (adding) {
        TextEditDialog(
            title = "新增记忆",
            summary = "一句话写清一件稳定的事实或要求。" +
                "例：「用户叫 Kira，主玩舞萌 DX，中二节奏是副游」「推分只推荐 13 级以上」。",
            initial = "",
            placeholder = "例：用户叫 Kira，主玩舞萌 DX",
            singleLine = false,
            onDismiss = { adding = false },
            onConfirm = { text ->
                AgentMemory.add(text, AgentMemory.Source.USER)
                adding = false
            },
        )
    }

    editing?.let { target ->
        TextEditDialog(
            title = "编辑记忆",
            summary = "改完保存后，下一次对话就会用新内容。清空文字等于删除这一条。",
            initial = target.text,
            placeholder = "记忆内容",
            singleLine = false,
            onDismiss = { editing = null },
            onConfirm = { text ->
                if (text.isBlank()) AgentMemory.remove(target.id) else AgentMemory.update(target.id, text)
                editing = null
            },
        )
    }

    deleting?.let { target ->
        KibaGlassDialog(
            show = true,
            title = "删除这条记忆",
            summary = target.text,
            onDismissRequest = { deleting = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "取消", onClick = { deleting = null })
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        AgentMemory.remove(target.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "删除")
                }
            }
        }
    }

    if (showClearConfirm) {
        KibaGlassDialog(
            show = true,
            title = "清空全部记忆",
            summary = "将删除全部 ${items.size} 条长期记忆，Agent 之后不再记得这些事。此操作不可撤销。",
            onDismissRequest = { showClearConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = { showClearConfirm = false })
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        AgentMemory.clear()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = "清空")
                }
            }
        }
    }
}

/** 一条记忆：正文 + 来源/时间 + 编辑 / 删除。 */
@Composable
private fun MemoryItemCard(
    item: AgentMemory.Item,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = item.text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.source.label} · ${AgentMemory.formatTime(item.updatedAt)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "编辑",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = "删除",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}
