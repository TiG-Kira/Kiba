package com.kira.kiba.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kira.kiba.ui.navigation.LocalTopBarClearance
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SongItem(val title: String, val level: String, val rating: String)

private val sampleSongs = listOf(
    SongItem("ジングルベル", "14.0", "15.62"),
    SongItem("テクノポリス", "13.7", "15.20"),
    SongItem("ゴーゴー幽霊船", "12.9", "14.05"),
    SongItem("愛のレッスン", "11.4", "12.80"),
)

/** 曲目页：搜索 + 歌曲列表，保留舞萌特色的封面框 / 难度等级分类 / Rating 展示。 */
@Composable
fun SongsScreen() {
    val clearance = LocalTopBarClearance.current
    var query by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = clearance)
            .padding(horizontal = 12.dp)
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索歌曲 / 别名 / ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sampleSongs) { SongCard(it) }
        }
    }
}

@Composable
fun SongCard(song: SongItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面框（舞萌特色：方形封面 + 描边）
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            // 难度 / 等级分类样式
            Row {
                Chip("DX", MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Chip("Lv ${song.level}", MiuixTheme.colorScheme.primary)
            }
        }
        // Rating 展示组件
        Text("★ ${song.rating}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}
