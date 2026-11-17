package com.kira.kiba.ui.common

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.db.AppDataBase
import com.kira.kiba.core.db.entity.PlaylistEntity
import com.kira.kiba.core.db.entity.SongDataEntity
import com.kira.kiba.core.playback.SongPlaybackController
import com.kira.kiba.ui.listen.toTrack
import com.kira.kiba.ui.material.KibaGlassDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
// 通配符导入：Regular.Info / Regular.Copy 等为同包扩展属性，单导入 MiuixGlassIcons 无法解析
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌曲卡片长按菜单里的一项（文案 + 彩色图标 + 动作）。
 *
 * 之前放在 ui.songs 包内，Best 50 / 成绩查询 / 名牌进度页的长按菜单
 * 也要用同一套（视觉与行为完全一致），所以上移到 ui.common 供全工程复用。
 */
data class SongCardMenuAction(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: KibaIconTint,
    val onClick: () -> Unit,
)

/**
 * 歌曲卡片长按菜单状态：卡片只负责在 onLongClick 里调 [show]，
 * 弹层本体由 [SongCardMenuHost] 统一渲染（经根 Scaffold 弹层宿主，不被列表裁剪）。
 *
 * 用 lambda 惰性传参：卡片渲染时机变化（网格复用等）不会丢菜单数据。
 */
class SongCardMenuState {
    internal var visible by mutableStateOf(false)
    internal var showAddPlaylist by mutableStateOf(false)

    internal var songId: Int = 0
    internal var songTitle: String = ""
    internal var openDetail: (() -> Unit)? = null

    /** 长按触发：记下这首歌的 id / 标题 / 详情动作，弹出菜单。 */
    fun show(songId: Int, songTitle: String, onOpenDetail: () -> Unit) {
        this.songId = songId
        this.songTitle = songTitle
        this.openDetail = onOpenDetail
        visible = true
    }
}

@Composable
fun rememberSongCardMenuState(): SongCardMenuState = remember { SongCardMenuState() }

/**
 * 歌曲长按菜单宿主：渲染 miuix [OverlayListPopup] 小菜单 + 「加入歌单」弹层。
 *
 * 四项操作（与曲目页 SongCard 同款）：
 * 显示歌曲详情 / 复制歌曲名 / 播放歌曲 / 加入歌单；
 * [includePlayAndPlaylist] = false 时只保留前两项（中二节奏无本地播放与歌单）。
 */
@Composable
fun SongCardMenuHost(
    menu: SongCardMenuState,
    includePlayAndPlaylist: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }

    fun copyTitle() {
        val ok = context.copyTextToClipboard(menu.songTitle, label = "kiba_song")
        Toast.makeText(
            context,
            if (ok) "已复制「${menu.songTitle}」" else "复制失败",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun playSong() {
        scope.launch {
            val song = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().songDao().getSongById(menu.songId)
                }.getOrNull()
            }
            if (song != null) {
                SongPlaybackController.play(context, song.toTrack())
            } else {
                Toast.makeText(context, "本地曲库暂无该歌曲，无法播放", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 长按小菜单（miuix OverlayListPopup）──
    OverlayListPopup(
        show = menu.visible,
        onDismissRequest = { menu.visible = false },
        popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
    ) {
        val actions = buildList {
            add(
                SongCardMenuAction(
                    text = "显示歌曲详情",
                    icon = MiuixGlassIcons.Regular.Info,
                    tint = KibaIconTint.BLUE,
                ) {
                    menu.visible = false
                    menu.openDetail?.invoke()
                }
            )
            add(
                SongCardMenuAction(
                    text = "复制歌曲名",
                    icon = MiuixGlassIcons.Regular.Copy,
                    tint = KibaIconTint.CYAN,
                ) {
                    menu.visible = false
                    copyTitle()
                }
            )
            if (includePlayAndPlaylist) {
                add(
                    SongCardMenuAction(
                        text = "播放歌曲",
                        icon = MiuixGlassIcons.Regular.Play,
                        tint = KibaIconTint.GREEN,
                    ) {
                        menu.visible = false
                        playSong()
                    }
                )
                add(
                    SongCardMenuAction(
                        text = "加入歌单",
                        icon = MiuixGlassIcons.Regular.Add,
                        tint = KibaIconTint.PINK,
                    ) {
                        menu.visible = false
                        menu.showAddPlaylist = true
                    }
                )
            }
        }
        ListPopupColumn {
            actions.forEachIndexed { index, action ->
                DropdownImpl(
                    item = DropdownItem(
                        text = action.text,
                        icon = { m ->
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = kibaIconTint(action.tint),
                                modifier = m,
                            )
                        },
                    ),
                    optionSize = actions.size,
                    isSelected = false,
                    index = index,
                    onSelectedIndexChange = { action.onClick() },
                )
            }
        }
    }

    // ── 加入歌单弹层：与曲目页同款（列出歌单点选加入）──
    LaunchedEffect(menu.showAddPlaylist) {
        if (menu.showAddPlaylist) {
            playlists = withContext(Dispatchers.IO) {
                runCatching {
                    AppDataBase.getInstance().playlistDao().observeAll().firstOrNull().orEmpty()
                }.getOrDefault(emptyList())
            }
        }
    }
    if (menu.showAddPlaylist) {
        KibaGlassDialog(
            show = true,
            title = "加入歌单",
            summary = "把《${menu.songTitle}》收进哪个歌单？",
            onDismissRequest = { menu.showAddPlaylist = false },
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
                if (playlists.isEmpty()) {
                    Text(
                        text = "还没有歌单\n去「畅听」页点「新建歌单」创建一个",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    ) {
                        itemsIndexed(playlists) { _, pl ->
                            TextButton(
                                text = pl.name,
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                AppDataBase.getInstance().playlistDao()
                                                    .appendSong(pl.id, menu.songId)
                                            }
                                        }
                                        Toast.makeText(
                                            context,
                                            "已加入「${pl.name}」",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        menu.showAddPlaylist = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
