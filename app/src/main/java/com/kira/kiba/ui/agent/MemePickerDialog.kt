// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.ui.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.meme.MemeCatalog
import com.kira.kiba.core.meme.MemeTemplate
import com.kira.kiba.ui.material.KibaGlassDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「表情包生成器」手动触发入口（两步式）。
 *
 * 第一步浏览/搜索模板目录（只列**真能离线生成**的那些，做不了的已在导出阶段剔除）；
 * 选中后**列表换成填字页**，输入框与「生成」按钮始终固定在弹层可见区域。
 * 早期版本把输入框和按钮也塞进几百项的列表尾部，用户得滚到底才能生成，
 * 已在 AVD 上实测发现并改掉。
 */
@Composable
internal fun MemePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (MemeTemplate, List<String>, List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val all = remember { MemeCatalog.all(context) }
    val animCount = remember(all) { all.count { it.animated } }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MemeTemplate?>(null) }
    val texts = remember { mutableStateListOf<String>() }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filtered = remember(query, all) {
        if (query.isBlank()) all else MemeCatalog.search(context, query, onlyRenderable = false)
    }
    val required = selected?.requiredImages ?: 0
    val currentRequired by rememberUpdatedState(required)
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        picked = if (currentRequired > 0) uris.take(currentRequired) else uris
    }

    // 换模板时重置输入：预填上游 default_texts，省得用户面对一堆空框
    LaunchedEffect(selected?.key) {
        val t = selected ?: return@LaunchedEffect
        texts.clear()
        repeat(t.textSlots) { i -> texts.add(t.defaultTexts.getOrNull(i).orEmpty()) }
        picked = emptyList()
    }

    KibaGlassDialog(
        show = true,
        title = "表情包生成器",
        summary = "模板来自 MemeCrafters/meme-generator，$animCount 个动图，全部本地离线生成",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val t = selected
                if (t == null) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索模板（名称 / 别名 / key）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "第 1 步 / 共 2 步 · 选一个模板，点一下进入填字页",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "共 ${all.size} 个模板（含 $animCount 个动图）；" +
                            "带「动图」标记的合成需要几秒，会显示等待提示。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(filtered, key = { _, item -> item.key }) { _, item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { selected = item },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (item.animated) "${item.name} · 动图" else item.name,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = buildString {
                                                append(item.key)
                                                if (item.textSlots > 0) append(" · ${item.textSlots} 句文字")
                                                if (item.requiredImages > 0) append(" · ${item.requiredImages} 张图片")
                                                if (item.aliases.size > 1) {
                                                    append(" · ${item.aliases.take(2).joinToString("/")}")
                                                }
                                            },
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        )
                                    }
                                    Text(
                                        text = "›",
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "第 2 步 / 共 2 步 · 填好文字后点右下「生成」",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(text = "「${t.name}」", color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        repeat(t.textSlots) { i ->
                            TextField(
                                value = texts.getOrElse(i) { "" },
                                onValueChange = { v ->
                                    while (texts.size <= i) texts.add("")
                                    texts[i] = v
                                },
                                label = "第 ${i + 1} 句",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                        }
                        if (t.requiredImages > 0) {
                            TextButton(
                                text = if (picked.isEmpty()) {
                                    "选择 ${t.requiredImages} 张图片"
                                } else "已选 ${picked.size} 张（点击重选）",
                                onClick = { pickImages.launch("image/*") },
                            )
                            if (picked.isNotEmpty() && picked.size < t.requiredImages) {
                                Text(
                                    text = "还差 ${t.requiredImages - picked.size} 张",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                val cur = selected
                val canGenerate = cur != null && picked.size >= cur.requiredImages
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = if (cur != null) "← 换模板" else "取消",
                        onClick = { if (cur != null) selected = null else onDismiss() },
                    )
                    Button(
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        enabled = canGenerate,
                        onClick = {
                            val target = selected ?: return@Button
                            if (picked.size < target.requiredImages) return@Button
                            onConfirm(target, texts.toList(), picked)
                        },
                    ) {
                        Text(text = "生成", textAlign = TextAlign.Center)
                    }
                }
            }
        },
    )
}
