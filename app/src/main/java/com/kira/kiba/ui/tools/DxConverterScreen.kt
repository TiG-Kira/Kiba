package com.kira.kiba.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kira.kiba.core.stats.DxScoreConverter
import com.kira.kiba.core.stats.SongRating
import com.kira.kiba.ui.common.SectionTitle
import com.kira.kiba.ui.common.pageContentPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * DX 分数转换（对齐上游 MaimaiData FinaleToDxActivity）：
 *
 * 输入各 note 类型的判定数量（Perfect / Great / Good / Miss），
 * 反推该次游玩的达成率区间；再填入 BREAK 实得分可得到 50 落 ~ 100 落之间的精确达成率。
 *
 * 权重：TAP ×10、HOLD ×20、SLIDE ×30、BREAK ×50；
 * 判定得分：Perfect 全权重、Great 80%、Good 50%、Miss 0；
 * BREAK 额外达成加成：Perfect 1.0（大 G 0.4 / Good 0.3）。
 */
@Composable
fun DxConverterScreen(scrollBehavior: ScrollBehavior) {
    var tapP by remember { mutableStateOf("") }
    var tapG by remember { mutableStateOf("") }
    var tapGo by remember { mutableStateOf("") }
    var tapM by remember { mutableStateOf("") }
    var holdP by remember { mutableStateOf("") }
    var holdG by remember { mutableStateOf("") }
    var holdGo by remember { mutableStateOf("") }
    var holdM by remember { mutableStateOf("") }
    var slideP by remember { mutableStateOf("") }
    var slideG by remember { mutableStateOf("") }
    var slideGo by remember { mutableStateOf("") }
    var slideM by remember { mutableStateOf("") }
    var brkP by remember { mutableStateOf("") }
    var brkG by remember { mutableStateOf("") }
    var brkGo by remember { mutableStateOf("") }
    var brkM by remember { mutableStateOf("") }
    var breakScore by remember { mutableStateOf("") }
    var dsInput by remember { mutableStateOf("") }

    var result by remember { mutableStateOf<DxScoreConverter.Conversion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ratingDs by remember { mutableStateOf<Double?>(null) }

    fun num(s: String) = s.trim().toIntOrNull() ?: 0

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = pageContentPadding(withNavBar = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "由判定数量反推达成率：TAP ×10、HOLD ×20、SLIDE ×30、BREAK ×50；" +
                                "Perfect 得满权重、Great 80%、Good 50%、Miss 0。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item(key = "tap_title") { SectionTitle("TAP") }
            item(key = "tap") {
                CountRow(
                    perfect = tapP, great = tapG, good = tapGo, miss = tapM,
                    onPerfect = { tapP = it }, onGreat = { tapG = it },
                    onGood = { tapGo = it }, onMiss = { tapM = it },
                )
            }
            item(key = "hold_title") { SectionTitle("HOLD") }
            item(key = "hold") {
                CountRow(
                    perfect = holdP, great = holdG, good = holdGo, miss = holdM,
                    onPerfect = { holdP = it }, onGreat = { holdG = it },
                    onGood = { holdGo = it }, onMiss = { holdM = it },
                )
            }
            item(key = "slide_title") { SectionTitle("SLIDE") }
            item(key = "slide") {
                CountRow(
                    perfect = slideP, great = slideG, good = slideGo, miss = slideM,
                    onPerfect = { slideP = it }, onGreat = { slideG = it },
                    onGood = { slideGo = it }, onMiss = { slideM = it },
                )
            }
            item(key = "brk_title") { SectionTitle("BREAK") }
            item(key = "brk") {
                CountRow(
                    perfect = brkP, great = brkG, good = brkGo, miss = brkM,
                    onPerfect = { brkP = it }, onGreat = { brkG = it },
                    onGood = { brkGo = it }, onMiss = { brkM = it },
                )
            }

            item(key = "extra_title") { SectionTitle("精确换算 / Rating") }
            item(key = "extra") {
                Card(
                modifier = (Modifier.fillMaxWidth()),
                cornerRadius = CardDefaults.CornerRadius,
                    insideMargin = CardDefaults.InsideMargin,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = breakScore,
                            onValueChange = { if (it.all { c -> c.isDigit() }) breakScore = it },
                            label = "BREAK 实得分（可选，0~2600/个）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = dsInput,
                            onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) dsInput = it },
                            label = "谱面定数（可选，用于算 Rating / ra）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    error = null
                                    val ds = dsInput.trim().toDoubleOrNull()
                                    DxScoreConverter.convert(
                                        tap = DxScoreConverter.Counts(num(tapP), num(tapG), num(tapGo), num(tapM)),
                                        hold = DxScoreConverter.Counts(num(holdP), num(holdG), num(holdGo), num(holdM)),
                                        slide = DxScoreConverter.Counts(num(slideP), num(slideG), num(slideGo), num(slideM)),
                                        brk = DxScoreConverter.Counts(num(brkP), num(brkG), num(brkGo), num(brkM)),
                                        breakScore = num(breakScore),
                                    ).onSuccess { r ->
                                        result = r
                                        ratingDs = ds
                                    }.onFailure { e ->
                                        result = null
                                        error = e.message ?: "换算失败"
                                    }
                                },
                            ) {
                                Text(text = "换算")
                            }
                        }
                    }
                }
            }

            error?.let { msg ->
                item(key = "error") {
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }

            result?.let { r ->
                item(key = "result_title") { SectionTitle("换算结果") }
                item(key = "result") {
                    Card(
                    modifier = (Modifier.fillMaxWidth()),
                    cornerRadius = CardDefaults.CornerRadius,
                        insideMargin = CardDefaults.InsideMargin,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (r.exact) {
                                Text(
                                    text = "达成率 %.4f%%".format(r.maxAchievement),
                                    style = MiuixTheme.textStyles.title3,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = "达成率 %.4f%% ~ %.4f%%".format(
                                        r.minAchievement,
                                        r.maxAchievement,
                                    ),
                                    style = MiuixTheme.textStyles.title3,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "区间来自 BREAK 落点（50 落 ~ 100 落）与大 G 分档差异",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            ratingDs?.let { ds ->
                                if (ds > 0) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "单曲 Rating（取上界）：" +
                                            DxScoreConverter.ratingOf(ds, r.ratingAchievement),
                                        style = MiuixTheme.textStyles.body1,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "单曲 ra（取上界）：" +
                                            DxScoreConverter.raOf(ds, r.ratingAchievement),
                                        style = MiuixTheme.textStyles.body1,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "档位：${SongRating.rankOf(r.ratingAchievement)}",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一行四个判定数量输入。 */
@Composable
private fun CountRow(
    perfect: String,
    great: String,
    good: String,
    miss: String,
    onPerfect: (String) -> Unit,
    onGreat: (String) -> Unit,
    onGood: (String) -> Unit,
    onMiss: (String) -> Unit,
) {
    Card(
    modifier = (Modifier.fillMaxWidth()),
    cornerRadius = CardDefaults.CornerRadius,
        insideMargin = CardDefaults.InsideMargin,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CountField("Perfect", perfect, onPerfect, Modifier.weight(1f))
            CountField("Great", great, onGreat, Modifier.weight(1f))
            CountField("Good", good, onGood, Modifier.weight(1f))
            CountField("Miss", miss, onMiss, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = { if (it.all { c -> c.isDigit() }) onValueChange(it) },
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
