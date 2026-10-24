package com.kira.kiba.core.agent

import com.google.gson.JsonParser

/**
 * LLM 响应解析的纯算法部分（不依赖 Android / 网络，便于单测）。
 *
 * 覆盖三件事：
 * 1. **思考与正文分离** —— 部分模型把同一段话同时塞进 content 和 reasoning_content，
 *    不去重的话「深度思考」卡片和正文气泡会显示一模一样的两段话；
 * 2. **文本内联工具调用** —— provider 不支持原生 function calling 时，
 *    模型会把 `{"name":"best50","arguments":{}}` 写进正文，这里要能抠出来执行；
 * 3. **参数解析容错** —— 模型给的 arguments 可能是空串 / 非法 JSON / 非对象。
 */
object LlmParse {

    /** 文本内联工具调用的解析结果。 */
    data class InlineToolCalls(
        val calls: List<Call>,
        /** 去掉调用 JSON 之后的干净正文。 */
        val cleanedText: String,
    )

    /** 解析出来的工具调用（与 [KibaLlm.ToolCall] 同构，避免此处依赖 Android 侧类型）。 */
    data class Call(
        val id: String = "",
        val name: String = "",
        val arguments: String = "",
    )

    /**
     * 把「思考内容」从正文里剔掉。
     *
     * @return 去重后的正文；完全重复时返回空串（正文气泡不显示，只留思考卡）
     */
    fun stripReasoningEcho(text: String, reasoning: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val r = reasoning.trim()
        if (r.isEmpty()) return text
        if (t == r) return ""
        if (t.startsWith(r)) return t.removePrefix(r).trimStart()
        // 正文被思考完整包含（长句场景）：只保留正文里多出来的部分
        if (r.contains(t) && t.length >= 20) return ""
        return text
    }

    /**
     * 解析「写在正文里的工具调用」。
     *
     * 只认 [knownTools] 里的技能 id，避免把正常回答里的 JSON 误判成调用。
     */
    fun parseInlineToolCalls(text: String, knownTools: Set<String>): InlineToolCalls {
        if (text.isBlank() || knownTools.isEmpty()) return InlineToolCalls(emptyList(), text)
        val calls = mutableListOf<Call>()
        var cleaned = text
        for ((start, end) in findJsonBlocks(text)) {
            val block = text.substring(start, end)
            val obj = runCatching { JsonParser.parseString(block).asJsonObject }.getOrNull() ?: continue
            val name = (obj.get("name") ?: obj.get("tool") ?: obj.get("function"))?.let { el ->
                if (el.isJsonPrimitive) el.asString else el.asJsonObject.get("name")?.asString
            }?.trim().orEmpty()
            if (name.isEmpty() || name !in knownTools) continue
            val argsEl = obj.get("arguments") ?: obj.get("parameters") ?: obj.get("args") ?: obj.get("input")
            val arguments = when {
                argsEl == null || argsEl.isJsonNull -> "{}"
                argsEl.isJsonPrimitive -> argsEl.asString
                else -> argsEl.toString()
            }
            calls += Call(id = "", name = name, arguments = arguments)
            cleaned = cleaned.removeRange(start, end)
        }
        // 清掉调用时常见的包裹残留（```json / <tool_call> 标签）
        cleaned = cleaned
            .replace(Regex("```(?:json)?\\s*```"), "")
            .replace(Regex("</?tool_call>"), "")
            .trim()
        return InlineToolCalls(calls, cleaned)
    }

    /**
     * 找出文本里所有「平衡的 JSON 对象」区间。
     * 简单括号配对（跳过字符串内部的花括号），够用且不引三方解析库。
     */
    fun findJsonBlocks(text: String): List<Pair<Int, Int>> {
        val blocks = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') {
                i++
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            while (j < text.length) {
                val c = text[j]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    inString && c == '"' -> inString = false
                    !inString && c == '"' -> inString = true
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            if (depth == 0 && j < text.length) {
                blocks += i to (j + 1)
                i = j + 1
            } else {
                i++
            }
        }
        return blocks
    }

    /**
     * 把模型给出的 arguments 字符串解析成扁平 Map（值统一转成字符串）。
     *
     * 容错：
     * - 空串 / 非法 JSON → 空 Map
     * - arguments 本身不是对象 → 放到 `raw` 键
     */
    fun parseArguments(argumentsJson: String): Map<String, String> {
        val raw = argumentsJson.trim()
        if (raw.isEmpty() || raw == "{}") return emptyMap()
        val el = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return mapOf("raw" to raw)
        if (!el.isJsonObject) return mapOf("raw" to el.asString)
        val out = linkedMapOf<String, String>()
        el.asJsonObject.entrySet().forEach { (k, v) ->
            if (v == null || v.isJsonNull) return@forEach
            out[k] = if (v.isJsonPrimitive) v.asString else v.toString()
        }
        return out
    }
}
