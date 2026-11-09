package com.kira.kiba.core.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.utils.SpUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kiba Agent 的长期记忆。
 *
 * 和 [AgentSession] 的区别：会话记录只服务「上下文连贯」（最近 8 轮，重启后仍是历史但不是指令），
 * 长期记忆是**跨会话稳定生效的事实与偏好**——用户叫什么、主玩哪个游戏、口味与雷点、
 * 明确要求「以后都这样」。每次对话都拼进 system prompt，所以数量与字数必须有硬上限，
 * 否则会把 prompt 撑爆、还会把真正重要的信息挤到模型注意力之外。
 *
 * 两个写入方：
 * - 用户手动写（设置 → Agent → 长期记忆，[Source.USER]）
 * - Agent 自己写（用户说「记住…」时调 `memory` 工具，[Source.AGENT]）
 *
 * 用户永远有最终解释权：UI 里能改、能删、能一键清空，也能整体关掉注入。
 */
object AgentMemory {

    /** 记忆来源：UI 上用来区分「你写的」和「Agent 记下的」。 */
    enum class Source {
        USER,
        AGENT,
        ;

        val label: String
            get() = when (this) {
                USER -> "手动添加"
                AGENT -> "Agent 记录"
            }
    }

    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val text: String = "",
        /** 首次记住的时间（epoch ms）。 */
        val createdAt: Long = System.currentTimeMillis(),
        /** 最后一次修改时间（epoch ms）。 */
        val updatedAt: Long = createdAt,
        val source: Source = Source.USER,
    )

    /** 条目数上限：再多模型也记不住，只会互相干扰。 */
    const val MAX_ITEMS = 40

    /** 单条字数上限（超长自动截断，避免一条占满整个预算）。 */
    const val MAX_ITEM_CHARS = 200

    /** 拼进 prompt 的正文预算（字符）；按「新的优先」装填，装不下就丢掉最老的。 */
    const val MAX_PROMPT_CHARS = 1600

    private val gson = Gson()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private var loaded = false

    /** 首次访问时从本地恢复。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = SpUtil.getAgentMemoryJson()
        if (json.isBlank()) return
        runCatching {
            val type = object : TypeToken<List<Item>>() {}.type
            _items.value = gson.fromJson<List<Item>>(json, type)
                ?.filter { it.text.isNotBlank() }
                ?: emptyList()
        }
    }

    // ---------- 开关 ----------

    fun isEnabled(): Boolean = SpUtil.isAgentMemoryEnabled()

    fun setEnabled(enabled: Boolean) {
        SpUtil.setAgentMemoryEnabled(enabled)
    }

    // ---------- 增删改 ----------

    /**
     * 记住一条。
     *
     * @return 真正落库的条目；内容为空 / 与已有条目重复 / 未启用时返回 null。
     */
    fun add(text: String, source: Source = Source.USER): Item? {
        ensureLoaded()
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val list = _items.value
        // 已经记过同一件事：不新增，只把时间刷新到最新（保留原 id，UI 不会跳动）
        val existing = list.firstOrNull { it.text.equals(normalized, ignoreCase = true) }
        if (existing != null) {
            val bumped = existing.copy(updatedAt = System.currentTimeMillis())
            _items.value = list.map { if (it.id == existing.id) bumped else it }
            persist()
            return bumped
        }
        val item = Item(text = normalized, source = source)
        // 超上限：挤掉最老的一条（按最后修改时间），保证新记的永远在
        val kept = (list + item)
            .sortedByDescending { it.updatedAt }
            .take(MAX_ITEMS)
            .sortedBy { it.createdAt }
        _items.value = kept
        persist()
        return item
    }

    /** 改某一条的文字（id 不存在时忽略）。 */
    fun update(id: String, text: String): Boolean {
        ensureLoaded()
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        val list = _items.value
        val target = list.firstOrNull { it.id == id } ?: return false
        _items.value = list.map {
            if (it.id == id) target.copy(text = normalized, updatedAt = System.currentTimeMillis()) else it
        }
        persist()
        return true
    }

    fun remove(id: String): Boolean {
        ensureLoaded()
        val list = _items.value
        if (list.none { it.id == id }) return false
        _items.value = list.filterNot { it.id == id }
        persist()
        return true
    }

    /** 按关键词批量忘掉（同一行同时含关键词即命中，不区分大小写）。 */
    fun forgetKeyword(keyword: String): Int {
        ensureLoaded()
        val key = keyword.trim()
        if (key.isEmpty()) return 0
        val list = _items.value
        val kept = list.filterNot { it.text.contains(key, ignoreCase = true) }
        val removed = list.size - kept.size
        if (removed > 0) {
            _items.value = kept
            persist()
        }
        return removed
    }

    fun clear() {
        loaded = true
        _items.value = emptyList()
        persist()
    }

    // ---------- 给 LLM / UI 的投影 ----------

    /**
     * 拼进 system prompt 的记忆段。
     *
     * 未启用 / 没有任何条目时返回空串（调用方据此决定要不要拼）。
     */
    fun promptBlock(): String {
        if (!isEnabled()) return ""
        ensureLoaded()
        return buildPromptBlock(_items.value)
    }

    /** 给工具返回的清单（带 id，模型才能 update / forget 指定条目）。 */
    fun describeForTool(): String {
        ensureLoaded()
        val list = _items.value
        if (list.isEmpty()) return "长期记忆里没有任何条目。"
        return buildString {
            append("共 ${list.size} 条长期记忆（新建在前）：\n")
            list.sortedByDescending { it.updatedAt }.forEachIndexed { i, it ->
                append("${i + 1}. [${it.id.take(8)}] ${it.text}\n")
            }
        }.trimEnd()
    }

    /** 设置项副标题用的一句话摘要。 */
    fun summary(): String {
        if (!isEnabled()) return "已关闭 · Agent 不会记住跨会话信息"
        ensureLoaded()
        val n = _items.value.size
        return if (n == 0) "已开启 · 还没有任何记忆，点进去添加" else "已开启 · 已记住 $n 条，点进去编辑"
    }

    // ---------- 纯逻辑（可单测） ----------

    /**
     * 清洗一条记忆：去首尾空白、换行/连续空白压成单个空格（每行在 prompt 里占一行，
     * 内部换行会把「· xxx」的列表结构打断），再按 [MAX_ITEM_CHARS] 截断。
     */
    fun normalize(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace(Regex("[\\n\\t\\u3000]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
        .let { if (it.length > MAX_ITEM_CHARS) it.take(MAX_ITEM_CHARS) + "…" else it }

    /**
     * 组装记忆段正文。
     *
     * 装填顺序是**新的优先**：预算不够时先牺牲最老的条目（长期偏好通常被后来的
     * 修正覆盖）；装完再按时间正序输出，保证 prompt 里的顺序稳定、可缓存。
     */
    fun buildPromptBlock(items: List<Item>, maxChars: Int = MAX_PROMPT_CHARS): String {
        val valid = items
            .mapNotNull { it.copy(text = normalize(it.text)).takeIf { i -> i.text.isNotBlank() } }
            .sortedByDescending { it.updatedAt }
        if (valid.isEmpty()) return ""

        val picked = mutableListOf<Item>()
        var used = 0
        for (item in valid) {
            val cost = item.text.length + 3 // "· " + 换行
            if (picked.isNotEmpty() && used + cost > maxChars) break
            picked += item
            used += cost
        }
        val lines = picked.sortedBy { it.createdAt }

        return buildString {
            append("【长期记忆】你在以往对话里记住的关于用户的事，跨会话一直有效；\n")
            append("与用户本次说法冲突时以本次为准，并顺手用 memory 工具改正旧记忆。\n")
            lines.forEach { append("· ").append(it.text).append('\n') }
            append("（共 ${lines.size} 条；新增 / 修改 / 删除都通过 memory 工具；")
            append("用户说「记住…」就保存，说「忘掉…」就删除，不要只在嘴上答应。）")
        }
    }

    /** 时间戳 → UI 上的短日期（例：11-10 14:22）。 */
    fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun persist() {
        runCatching {
            SpUtil.setAgentMemoryJson(gson.toJson(_items.value))
        }
    }
}
