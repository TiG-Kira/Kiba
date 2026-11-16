package com.kira.kiba.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.ChuniSongWithCharts
import com.kira.kiba.core.db.entity.SongWithChartsEntity
import com.kira.kiba.core.model.DifficultyType
import com.kira.kiba.core.model.GameType
import com.kira.kiba.core.model.SongGroupMode
import com.kira.kiba.core.model.SongSortMode
import com.kira.kiba.core.model.sortedBySongMode
import com.kira.kiba.core.repository.ChuniSongWithChartRepository
import com.kira.kiba.core.repository.SongWithChartRepository
import com.kira.kiba.core.utils.SpUtil
import com.kira.kiba.ui.common.ChuniCoverFrame
import com.kira.kiba.ui.common.CoverFrame
import com.kira.kiba.ui.common.collectAsStateCached
import com.kira.kiba.ui.common.chuniDifficultyColor
import com.kira.kiba.ui.common.difficultyColor
import com.kira.kiba.ui.common.EmptyStateCard
import com.kira.kiba.ui.common.kibaPressScale
import com.kira.kiba.ui.common.pageContentPadding
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
// 通配符导入：Regular.GridView 等为同包扩展属性，单导入 MiuixGlassIcons 无法解析
// （ChuniSongCard 同款坑，UNRESOLVED_REFERENCE 'Info' 实证）
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 归类分组子页面：展示某个版本（舞萌还有 MASTER 谱师 / 曲师）下的全部歌曲。
 *
 * 数据来源与曲目页同源（Room LiveData → Flow），仅按分组键过滤；歌曲卡复用
 * [SongCard] / [ChuniSongCard]（与曲目页平铺视图一致）。
 *
 * 显示方式（顶栏右上角菜单）：列表（默认，与曲目页平铺一致）或网格 3 / 4 列
 * （封面铺满格子 + 底部曲名，不提供 5 列——封面过小可读性差）。偏好经 [SpUtil]
 * 持久化，对所有分组生效。
 *
 * 游戏类型在**进页面那一刻**读一次：分组页只能从当前游戏的曲目列表点进来，
 * 不存在「人在子页里切游戏」的路径，所以不需要响应式；也正因为如此，只查
 * 当前游戏那套曲库，不做双份查询。
 */
@Composable
fun SongGroupScreen(
    mode: SongGroupMode,
    groupKey: String,
    scrollBehavior: ScrollBehavior,
    onSongClick: (songId: Int) -> Unit,
    onChuniSongClick: (ChuniSongWithCharts) -> Unit = {},
    /** 顶栏「显示方式」按钮唤起的菜单开关（状态由 KibaMainScreen 持有）。 */
    showDisplayPicker: Boolean = false,
    onDismissDisplayPicker: () -> Unit = {},
) {
    val gameType = remember { SpUtil.getGameType() }
    // 与曲目页保持同一套排序方式（分组内也按用户选的定数 / 等级 / 曲名排序）
    val sortMode = remember { SongSortMode.fromKey(SpUtil.getSongSortMode()) }
    val isChuni = gameType == GameType.CHUNITHM

    // 显示方式：SpUtil 持久化（对所有分组通用），菜单选择后立即写回
    var viewMode by remember { mutableStateOf(SpUtil.getSongGroupViewMode()) }
    var gridColumns by remember { mutableStateOf(SpUtil.getSongGroupGridColumns()) }

    // 条件组合是可接受的：isChuni 在本页生命周期内恒定（见函数注释）
    val maimaiSongs = if (isChuni) emptyList() else rememberMaimaiSongs()
    val chuniSongs = if (isChuni) rememberChuniSongs() else emptyList()

    val filteredMaimai = remember(maimaiSongs, sortMode, mode, groupKey) {
        maimaiSongs.filter { songInGroup(it, mode, groupKey) }
            .sortedBySongMode(sortMode, ::maimaiSongSortKey)
    }
    val filteredChuni = remember(chuniSongs, sortMode, mode, groupKey) {
        chuniSongs.filter { chuniSongInGroup(it, mode, groupKey) }
            .sortedBySongMode(sortMode, ::chuniSongSortKey)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 列表 / 网格统一走 LazyVerticalGrid：列表 = 1 列（与原 LazyColumn 等价），
            // 网格 = 3 / 4 列，两种视图共享同一套滚动状态与数据源。
            LazyVerticalGrid(
                columns = GridCells.Fixed(
                    if (viewMode == "grid") gridColumns.coerceIn(3, 4) else 1
                ),
                modifier = Modifier.fillMaxSize(),
                contentPadding = pageContentPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isChuni) {
                    if (filteredChuni.isEmpty()) {
                        item(key = "empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            EmptyStateCard(text = "这个分组下还没有歌曲")
                        }
                    } else {
                        items(items = filteredChuni, key = { "c${it.song.id}" }) { song ->
                            if (viewMode == "grid") {
                                ChuniSongGridCell(
                                    song = song,
                                    onClick = { onChuniSongClick(song) },
                                )
                            } else {
                                ChuniSongCard(song = song, onClick = { onChuniSongClick(song) })
                            }
                        }
                    }
                } else {
                    if (filteredMaimai.isEmpty()) {
                        item(key = "empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            EmptyStateCard(text = "这个分组下还没有歌曲")
                        }
                    } else {
                        items(items = filteredMaimai, key = { "m${it.songData.id}" }) { song ->
                            if (viewMode == "grid") {
                                MaimaiSongGridCell(
                                    song = song,
                                    onClick = { onSongClick(song.songData.id) },
                                )
                            } else {
                                SongCard(song = song, onClick = { onSongClick(song.songData.id) })
                            }
                        }
                    }
                }
            }

            // ── 顶栏「显示方式」按钮的菜单锚点 ──
            // 与 AgentScreen 的功能菜单同款接线：1dp 透明锚点钉在顶栏按钮正下方
            // （TopEnd + 顶栏净高），OverlayListPopup 经根 Scaffold 弹层宿主渲染。
            // 必须是 overlay，不能排进网格内容，否则会把列表整体推下去一条死带。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = LocalTopBarClearance.current)
                    .size(1.dp),
            ) {
                OverlayListPopup(
                    show = showDisplayPicker,
                    onDismissRequest = onDismissDisplayPicker,
                    popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                ) {
                    val options = listOf(
                        Triple("list", 0, "按列表显示"),
                        Triple("grid", 3, "网格 · 3 列"),
                        Triple("grid", 4, "网格 · 4 列"),
                    )
                    ListPopupColumn {
                        options.forEachIndexed { index, (mode_, columns, label) ->
                            val isSelected = viewMode == mode_ &&
                                (mode_ == "list" || gridColumns == columns)
                            DropdownImpl(
                                item = DropdownItem(
                                    text = label,
                                    icon = { m ->
                                        Icon(
                                            imageVector = if (mode_ == "grid") {
                                                MiuixGlassIcons.Regular.GridView
                                            } else {
                                                MiuixGlassIcons.Regular.Sort
                                            },
                                            contentDescription = null,
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = m,
                                        )
                                    },
                                ),
                                optionSize = options.size,
                                isSelected = isSelected,
                                index = index,
                                onSelectedIndexChange = {
                                    viewMode = mode_
                                    if (mode_ == "grid") gridColumns = columns
                                    SpUtil.setSongGroupViewMode(mode_)
                                    SpUtil.setSongGroupGridColumns(gridColumns)
                                    onDismissDisplayPicker()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网格单曲格（舞萌）：封面铺满整格，描边按**该曲最高难度**上色（与列表卡难度口径一致），
 * 底部轻 scrim 衬白色曲名。自拼 clickable + kibaPressScale（同 GridCoverCell 口径）。
 */
@Composable
private fun MaimaiSongGridCell(
    song: SongWithChartsEntity,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // 描边色取该曲存在的最高难度（真格有宗师按宗师算，其余取最难一档）
    val stroke = remember(song.charts) {
        song.charts.maxOfOrNull { it.difficultyType.ordinal }
            ?.let { difficultyColor(DifficultyType.entries.getOrElse(it) { DifficultyType.BASIC }) }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .kibaPressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        CoverFrame(
            songId = song.songData.id,
            imageUrl = song.songData.imageUrl,
            genre = song.songData.genre,
            size = maxWidth,
            strokeColor = stroke,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(38.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                    )
                ),
        )
        Text(
            text = song.songData.title,
            style = MiuixTheme.textStyles.footnote2,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 网格单曲格（中二节奏）：[ChuniCoverFrame] 封面 + 底部曲名，描边随流派。 */
@Composable
private fun ChuniSongGridCell(
    song: ChuniSongWithCharts,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // 描边色：该曲最高难度的中二难度色（与列表卡 chip 口径一致）
    val stroke = remember(song.charts) {
        song.charts.maxOfOrNull { it.difficultyIndex }?.let { chuniDifficultyColor(it) }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .kibaPressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // 中二封面按流派描边（ChuniCoverFrame 无 strokeColor 参数，走默认流派色）
        ChuniCoverFrame(
            songId = song.song.id,
            genre = song.song.genre,
            size = maxWidth,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(38.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                    )
                ),
        )
        Text(
            text = song.song.title,
            style = MiuixTheme.textStyles.footnote2,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 舞萌全量曲库（分组页只需要一份完整列表，过滤在调用方做）。 */
@Composable
private fun rememberMaimaiSongs(): List<SongWithChartsEntity> {
    val repo = remember {
        SongWithChartRepository.getInstance(AppDataBase.getInstance().songWithChartDao())
    }
    val flow = remember {
        repo.searchSongsWithCharts(
            searchText = "",
            genreList = emptyList(),
            versionList = emptyList(),
            selectLevel = null,
            sequencing = null,
            ds = null,
            isFavor = false,
            isMatchAlias = true,
            isMatchCharter = false,
            isMatchSongId = true,
        ).asFlow()
    }
    val songs by flow.collectAsStateCached(SongsListCache)
    return songs
}

/** 中二节奏全量曲库。 */
@Composable
private fun rememberChuniSongs(): List<ChuniSongWithCharts> {
    val repo = remember {
        ChuniSongWithChartRepository.getInstance(AppDataBase.getInstance().chuniSongWithChartDao())
    }
    val flow = remember {
        repo.searchChuniSongsWithCharts(searchText = "", isFavor = false).asFlow()
    }
    val songs by flow.collectAsStateCached(ChuniSongsListCache)
    return songs
}
