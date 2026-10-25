package com.kira.kiba.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.Flow

/**
 * 列表数据的进程级快照缓存。
 *
 * 为什么需要：列表页数据来自 Room → LiveData → Flow，发射是异步的。从二级页
 * （歌曲详情等）返回时页面重新组合，Flow 是新实例，collectAsState 首帧只能拿到
 * initial（空表）；LazyColumn 此时只有头部一两个 item，测量会把
 * SaveableStateProvider 恢复出来的滚动位置钳回 0
 * （LazyListScrollPosition.updateFromMeasureResult 在 item 数非空时直接按
 * firstVisibleItem.index 覆盖状态），等数据异步到达时位置已丢——表现为
 * 「从详情返回后列表回顶」（顶栏走 topBarMemory 手动恢复所以不丢，只有列表丢）。
 *
 * 做法：进程级缓存记住上次渲染过的列表，返回首帧即用快照作 initial，
 * LazyColumn 一测量就是完整 item 数，恢复出的滚动位置才生效。
 *
 * 注意：缓存实例必须是进程级单例（object / 顶层 val），**不能** remember 在页面里——
 * 页面组合随导航销毁，remember 出来的缓存活不到返回那一刻。
 */
open class ListSnapshotCache<T : List<*>>(private val fallback: T) {
    @Volatile
    private var snapshot: T = fallback

    /** 当前快照（从未发布过时返回 fallback，通常是空表）。 */
    val current: T get() = snapshot

    /** 发布新快照；空表不覆盖（搜索无命中等场景要保留上次有效数据兜底首帧）。 */
    fun publish(value: T) {
        if (value.isNotEmpty()) snapshot = value
    }
}

/**
 * [collectAsState] 的快照版：首帧用 [cache] 里的上次列表，数据到达后自动发布新快照。
 *
 * 用于所有「异步数据源 + 需要跨二级页保留滚动位置」的列表页。
 */
@Composable
fun <T : List<*>> Flow<T>.collectAsStateCached(cache: ListSnapshotCache<T>): State<T> {
    val state = collectAsState(initial = cache.current)
    LaunchedEffect(state.value) { cache.publish(state.value) }
    return state
}
