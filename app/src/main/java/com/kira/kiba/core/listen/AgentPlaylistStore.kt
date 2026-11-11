package com.kira.kiba.core.listen

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.utils.SpUtil
import java.io.File

/**
 * Agent 专属歌单的**历史存档**（2.6.0）。
 *
 * 之前只存「最新一份」：重新生成就把上一份抹掉，用户点一次「重新生成」就再也找不回
 * 之前那套选曲。现在每张歌单都留档（含封面 PNG），「畅听」页按歌单逐个入口展示，
 * 支持单条删除与一键清空。
 *
 * 存储：SP 里一段 JSON 数组（封面是 filesDir 里的 PNG，条目只存绝对路径）。
 * 上限 [MAX_ITEMS]：这是个「最近生成」列表，不是资料库，超了丢最旧的。
 */
object AgentPlaylistStore {

    private const val MAX_ITEMS = 20

    private val gson = Gson()

    /** 一张存档歌单。 */
    data class Item(
        /** 稳定 id（生成时刻的毫秒串），同时是封面文件名。 */
        val id: String,
        /** 列表与详情页展示的歌单名（封面主标题，可能被截断）。 */
        val name: String,
        /** 整段推荐语。 */
        val summary: String = "",
        val ids: List<Int> = emptyList(),
        /** 每首歌的推荐理由（与 [ids] 同序，可能更短）。 */
        val reasons: List<String> = emptyList(),
        /** 封面 PNG 路径（空 = 没有封面，UI 回落首曲封面）。 */
        val coverPath: String = "",
        /** 模板标识（预留：以后换模板时区分）。 */
        val template: String = PlaylistCover.TEMPLATE,
        val createdAt: Long = 0L,
    ) {
        val coverFile: File? get() = coverPath.takeIf { it.isNotBlank() }?.let { File(it) }
    }

    fun load(): List<Item> {
        val json = SpUtil.getAgentPlaylistHistoryJson()
        if (json.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Item>>() {}.type
            gson.fromJson<List<Item>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Item? = load().firstOrNull { it.id == id }

    /** 新歌单入档（头插，超上限丢最旧）。 */
    fun add(item: Item) {
        val next = (listOf(item) + load().filterNot { it.id == item.id }).take(MAX_ITEMS)
        save(next)
    }

    /** 替换已有条目（重新设计封面用）。 */
    fun update(item: Item) {
        val list = load().map { if (it.id == item.id) item else it }
        save(list)
    }

    /** 删除单条（连带删掉封面文件）。 */
    fun delete(item: Item) {
        runCatching { item.coverFile?.takeIf { it.exists() }?.delete() }
        save(load().filterNot { it.id == item.id })
    }

    /** 清空全部（连带删掉封面文件）。 */
    fun clear() {
        load().forEach { item ->
            runCatching { item.coverFile?.takeIf { it.exists() }?.delete() }
        }
        save(emptyList())
    }

    /**
     * 旧版单条歌单迁移：v2.5 及以前只存了一份 `AgentPlaylist.Saved`。
     * 第一次进「畅听」时补一张封面并入档，避免升级后历史凭空消失。
     */
    fun migrateLegacy(context: Context) {
        if (SpUtil.isAgentPlaylistHistoryMigrated()) return
        SpUtil.setAgentPlaylistHistoryMigrated(true)
        val legacy = AgentPlaylist.load() ?: return
        if (legacy.ids.isEmpty()) return
        val id = "legacy_${legacy.ids.size}_${legacy.summary.hashCode().toUInt()}"
        val spec = PlaylistCover.Spec(
            title = legacy.summary.ifBlank { "Agent 专属歌单" }.take(12),
            subtitle = legacy.summary,
            mood = "夜行",
            songCount = legacy.ids.size,
        )
        val cover = PlaylistCover.render(context, spec, id)
        add(
            Item(
                id = id,
                name = spec.title,
                summary = legacy.summary,
                ids = legacy.ids,
                reasons = legacy.reasons,
                coverPath = cover?.absolutePath.orEmpty(),
                createdAt = SpUtil.getAgentPlaylistGeneratedAt(),
            ),
        )
    }

    private fun save(list: List<Item>) {
        runCatching { SpUtil.setAgentPlaylistHistoryJson(gson.toJson(list)) }
    }
}
