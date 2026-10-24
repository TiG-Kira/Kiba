package com.kira.kiba.ui.settings

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

private val settingsGroups = listOf(
    "查分器" to listOf("账户与登录", "查分器代理更新"),
    "外观" to listOf("主题", "动态取色"),
    "关于" to listOf("版本", "开源协议"),
)

/** 设置页：集中收纳全部设置项（含查分器代理更新）。 */
@Composable
fun SettingsScreen(onItemClick: (String) -> Unit = {}) {
    val clearance = LocalTopBarClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = clearance)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        settingsGroups.forEach { (group, items) ->
            Text(group, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surface)
            ) {
                Column {
                    items.forEachIndexed { i, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(name) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
                        }
                        if (i < items.lastIndex) {
                            HorizontalDivider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}
