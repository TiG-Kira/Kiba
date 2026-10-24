package com.kira.kiba.ui.me

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.stats.ProgressDetail
import com.kira.kiba.ui.common.DxRatingTag
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * “我”页面（miuix preference 规范 + miuix Card 分组包裹）：
 * - 置顶 [ArrowPreference]：已登录展示昵称 + DX Rating 段位徽章（官方色表），
 *   点击进 Best 50；未登录展示引导，点击进登录页（账号密码 / OAuth 双通道）；
 * - 查分器入口：Best 50 / 成绩记录；
 * - 我的收藏：收藏的歌曲列表；
 * - 进度入口：版本进度 / 等级进度（可下钻到歌曲明细）；
 * - 工具：DX 分数转换 / 退出登录。
 */
@Composable
fun MeScreen(
    scrollBehavior: ScrollBehavior,
    onOpenLogin: () -> Unit,
    onOpenBest50: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenVersionProgress: () -> Unit,
    onOpenLevelProgress: () -> Unit,
    onOpenDxConverter: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenProgressDetail: (ProgressDetail.GroupType, String) -> Unit,
) {
    val account by DivingFishAccount.state.collectAsState()

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
            contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 置顶：DX Rating 徽章入口（ArrowPreference + 官方段位色 tag）──
            item(key = "rating") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = if (account.bound) {
                            account.nickname.ifBlank { "查分器用户" }
                        } else {
                            "未绑定查分器"
                        },
                        summary = when {
                            !account.bound -> "OAuth 授权或账号密码登录，解锁 Best 50 / 推分推荐"
                            account.loading -> "正在获取成绩…"
                            account.error != null -> "${account.error}（点击查看 Best 50）"
                            else -> "成绩 ${account.recordCount} 条 · 点击查看 Best 50"
                        },
                        onClick = {
                            if (account.bound) onOpenBest50() else onOpenLogin()
                        },
                        endActions = {
                            DxRatingTag(rating = if (account.bound) account.rating else 0)
                        },
                    )
                }
            }

            item(key = "checker_title") { SectionTitle("查分器") }

            item(key = "checker_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = if (account.bound) "重新登录 / 切换账户" else "登录查分器账户",
                        summary = if (account.bound) {
                            "当前：${account.nickname.ifBlank { "查分器用户" }}（Rating ${account.rating}）"
                        } else {
                            "支持水鱼账号密码（原版）与 OAuth 授权两种方式"
                        },
                        onClick = onOpenLogin,
                    )
                    ArrowPreference(
                        title = "Best 50",
                        summary = "官方口径旧曲 35 + 新曲 15，ra 之和即 DX Rating",
                        onClick = onOpenBest50,
                    )
                    ArrowPreference(
                        title = "成绩记录",
                        summary = "全量成绩列表，顶栏刷新按钮可从查分器更新",
                        onClick = onOpenRecords,
                    )
                }
            }

            item(key = "fav_title") { SectionTitle("我的曲库") }

            item(key = "fav_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "我的收藏",
                        summary = "在歌曲详情页标记过收藏的歌曲列表",
                        onClick = onOpenFavorites,
                    )
                }
            }

            item(key = "progress_title") { SectionTitle("进度") }

            item(key = "progress_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "版本进度",
                        summary = "各版本歌曲的收集 / 游玩情况（点击进入歌曲明细）",
                        onClick = onOpenVersionProgress,
                    )
                    ArrowPreference(
                        title = "等级进度",
                        summary = "各难度等级谱面的游玩情况（点击进入歌曲明细）",
                        onClick = onOpenLevelProgress,
                    )
                }
            }

            item(key = "tools_title") { SectionTitle("工具") }
            item(key = "tools_card") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "DX 分数转换",
                        summary = "由判定数量反推达成率与单曲 Rating",
                        onClick = onOpenDxConverter,
                    )
                    if (account.bound) {
                        ArrowPreference(
                            title = "退出登录",
                            summary = "清除本机成绩缓存与登录凭证；远端授权可在 auth.diving-fish.com/apps 撤销",
                            onClick = { DivingFishAccount.unbind() },
                        )
                    }
                }
            }
        }
    }
}
