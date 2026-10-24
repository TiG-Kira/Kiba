package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.stats.ProgressDetail
import com.kira.kiba.core.stats.isAp
import com.kira.kiba.core.stats.isFc
import com.kira.kiba.core.stats.isFdx
import com.kira.kiba.core.stats.isSss
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.DifficultyChip
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 进度明细页：某个分类（版本 / 等级）下的歌曲列表。
 *
 * - 难度筛选（默认 MASTER，可切换）；
 * - 判定筛选（全部 / 未游玩 / 已游玩 / 极 / 将 / 神）；
 * - 排序：未完成在前（定数高到低），已完成的垫底并带「已完成」标记。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressDetailScreen(
    groupType: ProgressDetail.GroupType,
    groupKey: String,
    scrollBehavior: ScrollBehavior,
) {
    var difficulty by remember { mutableStateOf<DifficultyType?>(DifficultyType.MASTER) }
    var filter by remember { mutableStateOf(ProgressDetail.JudgeFilter.ALL) }

    val items by produceState(initialValue = emptyList<ProgressDetail.Item>(), groupType, groupKey, difficulty, filter) {
        value = ProgressDetail.load(groupType, groupKey, difficulty, filter)
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
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = groupKey,
                            style = MiuixTheme.textStyles.headline1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "共 ${items.size} 首（${difficulty?.let { difficultyLabel(it) } ?: "全部难度"}）" +
                                "　已完成 ${items.count { it.satisfies(filter) }}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))

                        // 难度筛选
                        Text(
                            text = "难度",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DifficultyType.entries
                                .filter {
                                    it != DifficultyType.UTAGE_PLAYER2 && it != DifficultyType.UNKNOWN
                                }
                                .forEach { type ->
                                    SelectPill(
                                        text = difficultyLabel(type),
                                        selected = difficulty == type,
                                        onClick = { difficulty = if (difficulty == type) null else type },
                                    )
                                }
                        }
                        Spacer(Modifier.height(10.dp))

                        // 判定筛选
                        Text(
                            text = "判定",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProgressDetail.JudgeFilter.entries.forEach { f ->
                                SelectPill(
                                    text = f.label,
                                    selected = filter == f,
                                    onClick = { filter = f },
                                )
                            }
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(text = "该筛选条件下没有歌曲")
                }
            }

            items.forEach { item ->
                item(key = "song_${item.song.id}_${item.difficulty.ordinal}") {
                    ProgressDetailRow(
                        item = item,
                        done = item.satisfies(filter),
                        filter = filter,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressDetailRow(
    item: ProgressDetail.Item,
    done: Boolean,
    filter: ProgressDetail.JudgeFilter,
) {
    val diffColor = difficultyColor(item.difficulty)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverFrame(
                songId = item.song.id,
                imageUrl = item.song.imageUrl,
                genre = item.song.genre,
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (done) {
                        Spacer(Modifier.width(6.dp))
                        DoneTag()
                    }
                }
                Spacer(Modifier.height(3.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DifficultyChip(
                        text = "${difficultyLabel(item.difficulty)} ${item.level}",
                        color = diffColor,
                    )
                    InfoPill("定数 ${item.ds}")
                    item.record?.let { r ->
                        if (r.isAp) InfoPill("神")
                        else if (r.isFc) InfoPill("极")
                        else if (r.isSss) InfoPill("将")
                        if (r.isFdx) InfoPill("FDX")
                        InfoPill("%.2f%%".format(r.achievements))
                    }
                }
            }
        }
    }
}

/** 「已完成」标记。 */
@Composable
private fun DoneTag() {
    Box(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "已完成",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 可选中的筛选胶囊。 */
@Composable
private fun SelectPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

/** 小信息胶囊。 */
@Composable
private fun InfoPill(text: String) {
    Box(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
