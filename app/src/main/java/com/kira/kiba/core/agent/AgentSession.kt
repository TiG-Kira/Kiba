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
     * @param reset 清空已累积的正文与思考内容（重试 / 兜底执行前丢弃上一轮的空谈）
     */
    fun appendToLast(text: String = "", reasoning: String = "", reset: Boolean = false) {
        ensureLoaded()
        val list = _turns.value
        val last = list.lastOrNull() ?: return
        if (last.fromUser) return
        _turns.value = list.dropLast(1) + last.copy(
            text = if (reset) "" else last.text + text,
            reasoning = if (reset) "" else last.reasoning + reasoning,
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
