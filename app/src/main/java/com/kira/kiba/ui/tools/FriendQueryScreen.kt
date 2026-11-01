package com.kira.kiba.ui.tools

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.network.LxnsApi
import com.kira.kiba.core.network.LxnsDevApi
import com.kira.kiba.core.security.LxnsVault
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.chuniDifficultyLabel
import com.kira.kiba.ui.common.difficultyLabel
import com.kira.kiba.ui.common.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 查朋友（落雪**开发者 API**，按好友码查询他人公开数据）。
 *
 * 与「我的 Best」的根本区别：
 * - 走开发者密钥（打包在 App 里），**不需要**对方登录、也不需要自己登录；
 * - 但能不能查到取决于**对方**在落雪设置里打开「允许他人查看我的玩家信息 / 成绩」，
 *   没开就是 403。这个 403 最容易被误判成「App 坏了 / 好友码错了」，所以：
 *   错误卡片直接把原因、去哪个开关、以及「这不是密钥问题」写清楚；
 * - 落雪限流每秒 5 次，一次查询打 3 个请求（信息 / Best / 全量），连查多人要缓一下。
 *
 * 本页面的定位是「顺手看一眼朋友成绩」，不做成绩落库 —— 别人的成绩不进我本地 Room，
 * 免得和自己的 Best 混在一起。
 */
@Composable
fun FriendQueryScreen(
    scrollBehavior: ScrollBehavior,
    onOpenSong: (songId: Int, chuni: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var game by remember { mutableStateOf(GameType.MAIMAI) }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<LxnsDevApi.FriendSnapshot?>(null) }
    val available = remember { LxnsVault.hasDevKey() }

    fun query() {
        if (loading) return
        val fc = code.filter { it.isDigit() }.toLongOrNull()
        if (fc == null || fc <= 0) {
            error = "好友码是 15 位数字，先在游戏里「玩家信息」页确认一下。"
            snapshot = null
            return
        }
        loading = true
        error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LxnsDevApi.fetchFriendBlocking(game.key, fc) }
            }
            loading = false
            result.onSuccess { snapshot = it }
                .onFailure { e ->
                    snapshot = null
                    error = when (e) {
                        is LxnsApi.LxnsException -> e.message
                        else -> e.message ?: "查询失败"
                    }
                }
        }
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
            item(key = "intro") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "输入好友码即可查看对方公开的落雪数据（玩家信息 + Best + 全量成绩统计）。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "前提是对方在落雪设置里打开了「允许他人查看我的玩家信息 / 成绩」。" +
                                "没打开会提示 403 —— 那不是好友码写错，也不是本机密钥坏了，" +
                                "让 TA 去落雪网页版设置里开一下就好。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item(key = "form") {
                Card(modifier = (Modifier.fillMaxWidth())) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GameOption(
                                text = "舞萌 DX",
                                selected = game == GameType.MAIMAI,
                                modifier = Modifier.weight(1f),
                            ) { game = GameType.MAIMAI }
                            GameOption(
                                text = "中二节奏",
                                selected = game == GameType.CHUNITHM,
                                modifier = Modifier.weight(1f),
                            ) { game = GameType.CHUNITHM }
                        }
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = code,
                            onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(15) },
                            label = "好友码（15 位数字）",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { query() },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                enabled = available && !loading,
                            ) {
                                Text(text = if (loading) "查询中…" else "查询")
                            }
                        }
                        if (!available) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "这个安装包没有打包落雪开发者密钥，查朋友不可用" +
                                    "（公开仓库构建即为这种情况，不影响自己的成绩同步）。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            if (error.isNotBlank()) {
                item(key = "error") {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "查询失败",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = error,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            val data = snapshot
            if (data != null) {
                item(key = "player") {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = data.player.name.ifBlank { "（无昵称）" },
                                style = MiuixTheme.textStyles.headline1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (data.game == GameType.CHUNITHM.key) "Rating " else "DX Rating ",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = data.player.ratingText,
                                    style = MiuixTheme.textStyles.headline2,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "好友码 ${data.player.friendCode}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (data.player.badge.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = data.player.badge,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (data.player.trophy.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "称号：${data.player.trophy}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (data.player.uploadTime.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "落雪上次同步：${data.player.uploadTime}（UTC）",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }

                if (data.scoreCount > 0) {
                    item(key = "overview") {
                        Card(modifier = (Modifier.fillMaxWidth())) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "全量成绩 ${data.scoreCount} 条",
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(8.dp))
                                data.levelCounts.filter { it.second > 0 }.forEach { (name, count) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = name,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            text = "$count",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                data.groups.forEach { group ->
                    item(key = "group_${group.label}") {
                        SectionTitle("${group.label}（共 ${group.scores.size} 首，ra 合计 ${"%.2f".format(group.total)}）")
                    }
                    item(key = "group_card_${group.label}") {
                        Card(modifier = (Modifier.fillMaxWidth())) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                group.scores.forEachIndexed { index, score ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onOpenSong(
                                                    score.songId,
                                                    data.game == GameType.CHUNITHM.key,
                                                )
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.width(24.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = score.title,
                                                style = MiuixTheme.textStyles.body1,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = levelLabel(data.game, score.levelIndex),
                                                    style = MiuixTheme.textStyles.footnote2,
                                                    color = levelColor(data.game, score.levelIndex),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = score.level,
                                                    style = MiuixTheme.textStyles.footnote2,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = score.valueText,
                                                    style = MiuixTheme.textStyles.footnote2,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                )
                                                if (score.fc.isNotBlank()) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = score.fc,
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        color = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "%.2f".format(score.ra),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                    if (index != group.scores.lastIndex) {
                                        Spacer(Modifier.height(1.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "tip") {
                    Card(modifier = (Modifier.fillMaxWidth())) {
                        Text(
                            text = "点任意一条成绩可以打开本地曲库里的同 id 曲目详情；" +
                                "落雪侧数据更新有延迟，刚打完的歌可能要等官方同步后才看得到。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 游戏分段选项（选中的用主色，未选中的用次要色）。 */
@Composable
private fun GameOption(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, onClick = onClick) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 难度名（舞萌 Re:MASTER / 中二 ULTIMA 都要显示对）。 */
private fun levelLabel(game: String, index: Int): String =
    if (game == GameType.CHUNITHM.key) chuniDifficultyLabel(index) else difficultyLabel(index)

@Composable
private fun levelColor(game: String, index: Int) = if (game == GameType.CHUNITHM.key) {
    chuniDifficultyColor(index)
} else {
    MiuixTheme.colorScheme.primary
}
