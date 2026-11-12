package com.kira.kiba.core.listen

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kira.kiba.core.utils.SpUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /**
     * 迁移版本。
     * v1 = 3.0.0 的 boolean 标记（先置标记后迁移，踩过闪退的机器停在「已迁但没数据」）；
     * v2 = 手写解析 + 先入库后渲染 + 成功后置位，所有 v1 机器重新走一遍（幂等，不会重复入库）。
     */
    private const val MIGRATION_VERSION = 2

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

    /**
     * 读取全部存档。
     *
     * 逐字段手写解析（理由见 `JsonSafe.kt`）：这样即便某个条目的 JSON 被写坏、
     * 或来自更早版本缺字段，也只是**丢那一条**，不会让整个「畅听」页崩掉。
     */
    fun load(): List<Item> = parseItems(SpUtil.getAgentPlaylistHistoryJson())

    /**
     * 解析存档 JSON（纯函数，便于单测）。
     *
     * 逐字段手写解析（理由见 `JsonSafe.kt`）：这样即便某个条目的 JSON 被写坏、
     * 或来自更早版本缺字段，也只是**丢那一条**，不会让整个「畅听」页崩掉。
     */
    internal fun parseItems(json: String): List<Item> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { el ->
            runCatching { el.asJsonObject }.getOrNull()?.let { parseItem(it) }
        }
    }

    private fun parseItem(obj: JsonObject): Item? {
        // id 是唯一键，缺了没法去重/删除，直接丢弃这一条
        val id = obj.stringOrEmpty("id")
        if (id.isBlank()) return null
        val ids = obj.arrayOf("ids").mapNotNull { it.asIntOrNull() }
        return Item(
            id = id,
            name = obj.stringOrEmpty("name").ifBlank { "Agent 专属歌单" },
            summary = obj.stringOrEmpty("summary"),
            ids = ids,
            reasons = obj.arrayOf("reasons").mapNotNull { it.asStringOrNull() },
            coverPath = obj.stringOrEmpty("coverPath"),
            template = obj.stringOrEmpty("template").ifBlank { PlaylistCover.TEMPLATE },
            createdAt = obj.longOrZero("createdAt"),
        )
    }

    fun get(id: String): Item? = load().firstOrNull { it.id == id }

    /** 新歌单入档（头插，超上限丢最旧 —— 被挤掉的连封面一起删）。 */
    fun add(item: Item) {
        val existing = load().filterNot { it.id == item.id }
        val merged = listOf(item) + existing
        // 超出上限被挤掉的老歌单：存档条目一没，它的封面就再没人引用，
        // 当场回收，别留到下一次自动清理（用户可能很久不再生成新歌单）。
        merged.drop(MAX_ITEMS).forEach { dropped ->
            runCatching { dropped.coverFile?.takeIf { it.exists() }?.delete() }
        }
        save(merged.take(MAX_ITEMS))
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
     * 现存歌单引用的封面路径白名单 —— 顺手把旧键「最近一份」的封面也算进去。
     *
     * 旧键（`AgentPlaylist.load()`）在迁移完成前仍是「畅听」以外路径的唯一数据源，
     * 它的封面文件跟存档在同一个目录里；漏掉它就会在迁移前把那张图清掉。
     */
    fun liveCoverPaths(): Set<String> {
        val out = mutableSetOf<String>()
        load().forEach { item -> item.coverPath.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        AgentPlaylist.load()?.coverPath?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /**
     * 清理「没有对应歌单」的历史封面，返回释放的字节数。
     *
     * 调用点只有两处，都确保此刻没有正在生成的歌单：
     * 1. 自动生成歌单跑完之后（每次自动生成顺带打扫一次）；
     * 2. 设置页「清理图片缓存」。
     *
     * 单条删除 / 清空全部走 [delete] / [clear] 自己删，不需要经过这里。
     */
    suspend fun cleanupCovers(context: Context): Long = withContext(Dispatchers.IO) {
        val freed = PlaylistCover.pruneOrphans(context, liveCoverPaths())
        if (freed > 0) Log.i("AgentPlaylistStore", "pruned orphan covers, freed $freed bytes")
        freed
    }

    /**
     * 旧版单条歌单迁移：v2.5 及以前只存了一份 `AgentPlaylist.Saved`。
     * 第一次进「畅听」时入档并补一张封面。
     *
     * ## 三条硬约束（v3.0.1 修闪退的结论，别再改回去）
     * 1. **先入库，再渲染封面**。封面只是装饰，1080×1080 位图 + PNG 编码出任何问题
     *    （OOM / 存储满 / 路径不可写）都不该连累「歌单存在」这件事。
     * 2. **成功后才写迁移标记**。v3.0.0 是先置标记再迁移，中途 NPE 一次就永久跳过，
     *    而且每次进「畅听」都在同一步崩 —— 用户表现为「升级后这个页面打不开了」。
     * 3. **幂等**。id 取稳定值，重复跑只会重新补封面，不会重复入库。
     */
    suspend fun migrateLegacy(context: Context) {
        if (SpUtil.getAgentPlaylistMigrationVersion() >= MIGRATION_VERSION) return

        runCatching {
            val legacy = AgentPlaylist.load()
            if (legacy != null) {
                // 稳定 id：同样的旧数据每次算出来一样，配合下面的存在性判断实现幂等
                val id = "legacy_${legacy.ids.size}_${legacy.summary.hashCode().toUInt()}"
                if (get(id) == null) {
                    val title = legacy.summary.ifBlank { "Agent 专属歌单" }.take(12)
                    // ① 先入库（此时无封面，UI 会回落首曲封面）
                    add(
                        Item(
                            id = id,
                            name = title,
                            summary = legacy.summary,
                            ids = legacy.ids,
                            reasons = legacy.reasons,
                            coverPath = "",
                            createdAt = SpUtil.getAgentPlaylistGeneratedAt(),
                        ),
                    )
                    // ② 再补封面：IO 线程渲染，失败就保持无封面
                    val spec = PlaylistCover.Spec(
                        title = title,
                        subtitle = legacy.summary,
                        mood = legacy.mood.ifBlank { "夜行" },
                        songCount = legacy.ids.size,
                    )
                    val cover = withContext(Dispatchers.IO) {
                        PlaylistCover.render(context, spec, id)
                    }
                    if (cover != null) {
                        get(id)?.let { update(it.copy(coverPath = cover.absolutePath)) }
                    }
                }
            }
        }.onFailure {
            Log.w("AgentPlaylistStore", "legacy migrate failed: ${it.message}")
        }

        // 标记写在最后：上面即便失败也已经 runCatching 兜过，这里置位表示「努力过了」。
        // 万一真是环境问题（如存储不可写），下次升级版本号再给一次机会。
        SpUtil.setAgentPlaylistMigrationVersion(MIGRATION_VERSION)
    }

    /**
     * 手写序列化，与 [parseItems] 严格对称。
     *
     * 不用 `gson.toJson(list)`：那是按**反射字段名**写的，而 [Item] 没有 `@SerializedName`，
     * 一旦 R8 把字段名改掉，写出去的 key 就变了，再读（手写固定 key）会全部落空 ——
     * 用户视角就是「歌单莫名其妙全没了」。读写手写对称后，这条链路与混淆无关。
     */
    private fun save(list: List<Item>) {
        runCatching {
            val arr = JsonArray()
            list.forEach { item ->
                arr.add(
                    JsonObject().apply {
                        addProperty("id", item.id)
                        addProperty("name", item.name)
                        addProperty("summary", item.summary)
                        add("ids", JsonArray().apply { item.ids.forEach { add(it) } })
                        add("reasons", JsonArray().apply { item.reasons.forEach { add(it) } })
                        addProperty("coverPath", item.coverPath)
                        addProperty("template", item.template)
                        addProperty("createdAt", item.createdAt)
                    },
                )
            }
            SpUtil.setAgentPlaylistHistoryJson(arr.toString())
        }
    }
}
