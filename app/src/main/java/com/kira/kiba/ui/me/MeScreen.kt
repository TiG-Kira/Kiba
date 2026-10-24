package com.kira.kiba.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.theme.MiuixTheme

// TODO: 真实登录态由查分器账户层提供；此处先以未登录展示登录引导
private val loggedIn = false

private val entries = listOf(
    "更换账户",
    "版本进度",
    "等级进度",
    "DX 分数转换",
)

/**
 * “我”页面（重设计）：
 * - 置顶卡片显示查分器实时 Rating 与对外昵称（非用户名），点击进入查分详情；未登录则显示登录引导。
 * - 下方依次提供 更换账户 / 版本进度 / 等级进度 / DX 分数转换 入口。
 * - 已删除原有“查分”按钮（查分逻辑并入 Agent 与详情页）。
 */
@Composable
fun MeScreen(onEntryClick: (String) -> Unit = {}) {
    val clearance = LocalTopBarClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = clearance)
            .padding(horizontal = 12.dp)
    ) {
        if (loggedIn) {
            RatingCard(nickname = "舞舞", rating = "15.62", onClick = { /* TODO 查分详情 */ })
        } else {
            LoginGuideCard(onClick = { /* TODO 登录页 */ })
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surface)
        ) {
            entries.forEachIndexed { i, name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(name) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
                }
                if (i < entries.lastIndex) {
                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun RatingCard(nickname: String, rating: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.primary)
            .clickable { onClick() }
            .padding(18.dp)
    ) {
        Column {
            Text(nickname, color = MiuixTheme.colorScheme.onSurface, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("Rating $rating", color = MiuixTheme.colorScheme.onSurface, fontSize = 22.sp)
        }
    }
}

@Composable
private fun LoginGuideCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(18.dp)
    ) {
        Column {
            Text("未登录查分器账户", color = MiuixTheme.colorScheme.onSurface, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("点击登录以查看实时 Rating 与昵称", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
        }
    }
}
