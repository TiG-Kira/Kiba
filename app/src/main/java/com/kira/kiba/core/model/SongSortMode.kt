package com.kira.kiba.core.model

/**
 * 曲目列表排序方式（舞萌 / 中二节奏**通用**）。
 *
 * 历史上曲目页顶栏的 Sort 按钮只做「归类」（[SongGroupMode]），而归类被
 * `gameType == MAIMAI` 硬门控；中二节奏列表走的是 ChuniDao 写死的
 * `ORDER BY id DESC`，从头到尾没有任何排序环节，所以切到中二节奏后点
 * 排序按钮选什么都毫无反应。
 *
 * 这里把排序抽成与游戏无关的 [SongSortKey]：两套曲库各自把实体映射成键，
 * 再走同一套比较器，两个游戏都能真正重排列表。归类仍只对舞萌生效
 * （分组子页是舞萌专用），但排序不再受它限制。
 */
enum class SongSortMode(val key: String, val label: String) {
    DEFAULT("default", "默认（曲库顺序）"),
    DS_DESC("ds_desc", "定数 高 → 低"),
    DS_ASC("ds_asc", "定数 低 → 高"),
    LEVEL_DESC("level_desc", "等级 高 → 低"),
    TITLE_ASC("title_asc", "曲名 A → Z"),
    ;

    companion object {
        fun fromKey(key: String?): SongSortMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 与游戏无关的最小排序键。
 *
 * `maxDs` / `maxLevel` 取该曲全部谱面里的最大值（定数优先、等级兜底），
 * 这样同一个排序选项在舞萌（5 难度 + 宴会场）和中二节奏（5 难度 + WORLD'S END）
 * 上都有确定含义。
 */
data class SongSortKey(
    val id: Int,
    val title: String,
    val maxDs: Double,
    val maxLevel: Double,
)

/**
 * 等级字符串 → 可比较数值：`"13+"` → 13.5、`"14"` → 14.0。
 * 无法解析的等级（World's End 的汉字等级、`"?"` 等）返回 -1.0，
 * 降序时自然沉底，不会混进正常难度里。
 */
fun parseSongLevelValue(level: String): Double {
    val number = Regex("""\d+(?:\.\d+)?""").find(level)?.value?.toDoubleOrNull()
        ?: return -1.0
    return number + if (level.contains("+")) 0.5 else 0.0
}

/**
 * 按 [mode] 排序任意歌曲列表；[keyOf] 负责把具体实体映射成 [SongSortKey]。
 * 纯函数，不依赖 Android / Room，便于单测。
 */
fun <T> List<T>.sortedBySongMode(
    mode: SongSortMode,
    keyOf: (T) -> SongSortKey,
): List<T> {
    if (mode == SongSortMode.DEFAULT || size < 2) return this
    return map { it to keyOf(it) }
        .sortedWith(Comparator { a, b -> mode.compareKeys(a.second, b.second) })
        .map { it.first }
}

/** 全部比较都带 id 兜底，保证同键时顺序稳定（Room 结果顺序不保证）。 */
private fun SongSortMode.compareKeys(a: SongSortKey, b: SongSortKey): Int = when (this) {
    SongSortMode.DEFAULT -> 0
    SongSortMode.DS_DESC ->
        compareValuesBy(a, b, { -it.maxDs }, { -it.maxLevel }, { it.id })
    SongSortMode.DS_ASC ->
        compareValuesBy(a, b, { it.maxDs }, { it.maxLevel }, { it.id })
    SongSortMode.LEVEL_DESC ->
        compareValuesBy(a, b, { -it.maxLevel }, { -it.maxDs }, { it.id })
    SongSortMode.TITLE_ASC ->
        compareValuesBy(a, b, { it.title.lowercase() }, { it.id })
}
