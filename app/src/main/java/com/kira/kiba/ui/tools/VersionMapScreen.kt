package com.kira.kiba.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * maimai 版本名称对照表（工具箱，移植自 EasyMai version-map）：
 * 版本名称 ↔ 牌子代号（真 / 超 / 檄 / 橙 / …），旧街机与 DX 时代全覆盖。
 */
@Composable
fun VersionMapScreen(scrollBehavior: ScrollBehavior) {
    // (版本名称, 牌子代号)，与 EasyMai version-map 数据一致；第一代 maimai 无牌子
    val versions = listOf(
        "maimai" to "—",
        "maimai PLUS" to "真",
        "maimai GreeN" to "超",
        "maimai GreeN PLUS" to "檄",
        "maimai ORANGE" to "橙",
        "maimai ORANGE PLUS" to "暁",
        "maimai PiNK" to "桃",
        "maimai PiNK PLUS" to "櫻",
        "maimai MURASAKi" to "紫",
        "maimai MURASAKi PLUS" to "菫",
        "maimai MiLK" to "白",
        "MiLK PLUS" to "雪",
        "maimai FiNALE" to "輝",
        "ALL FiNALE" to "舞",
        "maimai DX" to "熊",
        "maimai DX PLUS" to "華",
        "maimai DX Splash" to "爽",
        "maimai DX Splash PLUS" to "煌",
        "maimai DX UNiVERSE" to "宙",
        "maimai DX UNiVERSE PLUS" to "星",
        "maimai DX FESTiVAL" to "祭",
        "maimai DX FESTiVAL PLUS" to "祝",
        "maimai DX BUDDiES" to "双",
        "maimai DX BUDDiES PLUS" to "宴",
        "maimai DX PRiSM" to "镜",
    )

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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "版本名称与牌子（姓名框）代号对照。第一代 maimai 没有牌子，" +
                                "DX 时代一作一个牌子。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item(key = "list_title") { SectionTitle("版本对照") }
            item(key = "list") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        versions.forEachIndexed { index, (name, code) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = name,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = code,
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                            if (index != versions.lastIndex) {
                                Spacer(Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
