package com.kira.kiba.core.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 会话持久化。
 *
 * 聊天记录（气泡 + 歌曲卡 + 技能卡 + B50 卡）序列化进 SpUtil，
 * App 重启后恢复；「清除对话」卡片调用 [clear] 清空。
 *
 * 同时作为 LLM 的上下文来源：[turns] 取最近若干轮拼成 messages，
 * 让模型记得上文。
 */
object AgentSession {

    /** 回复内一个分段的类型。 */
    enum class SegKind { TEXT, REASONING, CALLS }

    /**
     * 回复内部的一个进度分段。
     *
     * 为什么需要它：Agent 一次回复会「思考 → 调技能 → 再思考 → 再调 → 最终答复」，
     * 过去把整轮拍平成 text / reasoning / calls 三个字段，多轮内容全被粘成一坨，
     * 渲染顺序还被固定成「技能卡 → 思考 → 正文」，用户根本看不出先发生了什么。
     * 现在按真实发生顺序逐段累积，UI 依次铺开即可。
     */
    data class Segment(
        val kind: SegKind = SegKind.TEXT,
        val text: String = "",
        val calls: List<KibaAgent.ToolInvocation> = emptyList(),
    )

    /** 一条对话记录。 */
    data class Turn(
        val fromUser: Boolean = false,
        val text: String = "",
        val songs: List<KibaAgent.AgentSong> = emptyList(),
        val skills: List<KibaAgent.AgentSkill> = emptyList(),
        val b50: KibaAgent.Best50Info? = null,
        /** 梗图等图片产物的 Uri 字符串（可能已失效，加载失败则忽略）。 */
        val imageUri: String = "",
        /** 深度思考（reasoning）内容，单独成卡展示，不混进正文。 */
        val reasoning: String = "",
        /** 流式输出中：正文还没收尾。 */
        val streaming: Boolean = false,
        /** 这一轮真正调用过的工具（WorkBuddy 式「技能调用」展示）。 */
        val calls: List<KibaAgent.ToolInvocation> = emptyList(),
        /**
         * 按时序排列的进度分段（正文 / 思考 / 技能调用交替）。
         * 旧记录没有这个字段 → 空列表，UI 退化为原来的固定顺序。
         */
        val segments: List<Segment> = emptyList(),
    )

    private val gson = Gson()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private var loaded = false

    /** 首次访问时从本地恢复历史。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = SpUtil.getAgentMessages()
        if (json.isBlank()) return
        runCatching {
            val type = object : TypeToken<List<Turn>>() {}.type
            _turns.value = gson.fromJson<List<Turn>>(json, type) ?: emptyList()
        }
    }

    /** 追加一条记录并落盘。 */
    fun add(turn: Turn) {
        ensureLoaded()
        _turns.value = _turns.value + turn
        persist()
    }

    /**
     * 流式输出：把增量追加到最后一条（Agent）记录上。
     * 不落盘（避免高频写 SP），流式结束后由 [finishStream] 收尾。
     *
     * @param reset 清掉当前轮的叙事（正文 + 思考）：用于丢弃「我打算调用 X」这类
     *   没有结论的空谈，或流式失败退回一次性请求时避免新旧文本拼接重复。
     *   **已发生的技能调用不会被撤回**——技能确实执行过，卡片该留着。
     * @param calls 本轮刚执行完的工具（增量，不是累计列表）
     */
    fun appendToLast(
        text: String = "",
        reasoning: String = "",
        reset: Boolean = false,
        calls: List<KibaAgent.ToolInvocation> = emptyList(),
    ) {
        ensureLoaded()
        val list = _turns.value
        val last = list.lastOrNull() ?: return
        if (last.fromUser) return

        var segs = last.segments
        if (reset) {
            // 只丢「最后一个技能调用之后」的叙事：那是本轮的空谈 / 半截流式内容。
            // 更早的进度段是真实发生过的，不能一起抹掉。
            while (segs.isNotEmpty() && segs.last().kind != SegKind.CALLS) {
                segs = segs.dropLast(1)
            }
        }

        // 只有紧邻的同类型段才续写；中间夹了技能调用就另起一段，
        // 这样「查完 → 说一句 → 再查 → 再总结」不会被粘成一大坨。
        fun put(kind: SegKind, piece: String) {
            if (piece.isEmpty()) return
            val tail = segs.lastOrNull()
            segs = if (tail != null && tail.kind == kind) {
                segs.dropLast(1) + tail.copy(text = tail.text + piece)
            } else {
                segs + Segment(kind = kind, text = piece)
            }
        }

        put(SegKind.REASONING, reasoning)
        put(SegKind.TEXT, text)

        if (calls.isNotEmpty()) {
            segs = segs + Segment(kind = SegKind.CALLS, calls = calls)
        }

        // 扁平字段只是分段的投影（历史上下文 / 旧 UI 兜底用），保持一致不会再对不上
        _turns.value = list.dropLast(1) + last.copy(
            text = segs.filter { it.kind == SegKind.TEXT }.joinToString("") { it.text },
            reasoning = segs.filter { it.kind == SegKind.REASONING }.joinToString("") { it.text },
            calls = segs.flatMap { it.calls },
            segments = segs,
            streaming = true,
        )
    }

    /** 整体替换最后一条（用于流式结束后补上卡片类数据）。 */
    fun replaceLast(turn: Turn) {
        ensureLoaded()
        val list = _turns.value
        if (list.isEmpty()) return
        _turns.value = list.dropLast(1) + turn
        persist()
    }

    /** 流式结束：清掉 streaming 标记并落盘。 */
    fun finishStream() {
        ensureLoaded()
        val list = _turns.value
        val last = list.lastOrNull() ?: return
        if (!last.streaming) return
        _turns.value = list.dropLast(1) + last.copy(streaming = false)
        persist()
    }

    /** 清空对话。 */
    fun clear() {
        ensureLoaded()
        _turns.value = emptyList()
        SpUtil.clearAgentMessages()
    }

    /**
     * 给 LLM 的历史上下文（最近 [maxTurns] 轮，去掉纯卡片轮次）。
     * 角色映射：用户 → user，Agent → assistant。
     */
    fun llmHistory(maxTurns: Int = 10): List<KibaLlm.Message> {
        ensureLoaded()
        return _turns.value
            .filter { it.text.isNotBlank() }
            .takeLast(maxTurns)
            .map {
                if (it.fromUser) {
                    KibaLlm.Message.user(it.text)
                } else {
                    KibaLlm.Message.assistant(it.text)
                }
            }
    }

    private fun persist() {
        runCatching { SpUtil.setAgentMessages(gson.toJson(_turns.value)) }
    }
}
