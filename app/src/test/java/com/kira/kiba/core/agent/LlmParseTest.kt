package com.kira.kiba.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmParse] 的单元测试。
 *
 * 重点覆盖真实故障场景：
 * - provider 把同一段思考同时塞进 content 与 reasoning_content → 正文必须去重；
 * - provider 不支持原生 function calling → 模型写在正文里的调用 JSON 必须能被执行；
 * - 正常回答里带 JSON → 不能误判成工具调用。
 */
class LlmParseTest {

    private val known = setOf("best50", "push_rating", "song_query", "ds_query", "score_line")

    // ---------- 思考内容去重 ----------

    @Test
    fun `思考与正文完全重复时正文被清空`() {
        val thinking = "The user wants to know what songs they should push. " +
            "I should call both best50 and push_rating to get a comprehensive view."
        assertEquals("", LlmParse.stripReasoningEcho(thinking, thinking))
    }

    @Test
    fun `正文以思考开头时去掉前缀保留结论`() {
        val reasoning = "I should call best50."
        val text = "I should call best50.\n\n根据你的 Best 50，总分 12345，建议先补这首。"
        val result = LlmParse.stripReasoningEcho(text, reasoning)
        assertTrue(result.startsWith("根据你的 Best 50"))
        assertTrue(!result.contains("I should call"))
    }

    @Test
    fun `正文与思考互不相同则原样保留`() {
        val result = LlmParse.stripReasoningEcho("最终答案在这里。", "这是另一段思考。")
        assertEquals("最终答案在这里。", result)
    }

    @Test
    fun `空正文保持为空`() {
        assertEquals("", LlmParse.stripReasoningEcho("", "一段思考"))
    }

    @Test
    fun `无思考内容时正文原样返回`() {
        assertEquals("正文", LlmParse.stripReasoningEcho("正文", ""))
        assertEquals("正文", LlmParse.stripReasoningEcho("正文", "   "))
    }

    // ---------- 文本内联工具调用 ----------

    @Test
    fun `识别正文里的无参调用`() {
        val parsed = LlmParse.parseInlineToolCalls("{\"name\":\"best50\",\"arguments\":{}}", known)
        assertEquals(1, parsed.calls.size)
        assertEquals("best50", parsed.calls[0].name)
        assertEquals("{}", parsed.calls[0].arguments)
        // 内联调用没有 id —— 用来区分两套协议
        assertEquals("", parsed.calls[0].id)
        assertEquals("", parsed.cleanedText)
    }

    @Test
    fun `识别正文里的带参调用`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "{\"name\":\"song_query\",\"arguments\":{\"query\":\"群青\"}}",
            known,
        )
        assertEquals(1, parsed.calls.size)
        assertEquals("song_query", parsed.calls[0].name)
        assertTrue(parsed.calls[0].arguments.contains("群青"))
    }

    @Test
    fun `识别代码围栏包裹的调用`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "```json\n{\"name\":\"push_rating\",\"arguments\":{}}\n```",
            known,
        )
        assertEquals(1, parsed.calls.size)
        assertEquals("push_rating", parsed.calls[0].name)
        assertTrue(parsed.cleanedText.isBlank())
    }

    @Test
    fun `一次识别多个调用`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "{\"name\":\"best50\",\"arguments\":{}}\n{\"name\":\"push_rating\",\"arguments\":{}}",
            known,
        )
        assertEquals(2, parsed.calls.size)
        assertEquals(listOf("best50", "push_rating"), parsed.calls.map { it.name })
    }

    @Test
    fun `夹在正文中间的调用也能识别且其余正文保留`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "我先查一下成绩。\n{\"name\":\"best50\",\"arguments\":{}}\n稍等我总结。",
            known,
        )
        assertEquals(1, parsed.calls.size)
        assertTrue(parsed.cleanedText.contains("我先查一下成绩"))
        assertTrue(parsed.cleanedText.contains("稍等我总结"))
    }

    @Test
    fun `普通 JSON 不被误判为调用`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "你的成绩是 {\"total\": 12345, \"old\": 9000} 这样。",
            known,
        )
        assertTrue(parsed.calls.isEmpty())
        assertTrue(parsed.cleanedText.contains("12345"))
    }

    @Test
    fun `未知工具名不被执行`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "{\"name\":\"some_other_tool\",\"arguments\":{}}",
            known,
        )
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun `参数里的花括号不破坏解析`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "{\"name\":\"song_query\",\"arguments\":{\"query\":\"a{b}c\"}}",
            known,
        )
        assertEquals(1, parsed.calls.size)
        assertTrue(parsed.calls[0].arguments.contains("a{b}c"))
    }

    @Test
    fun `tool_call 标签包裹的调用也能识别`() {
        val parsed = LlmParse.parseInlineToolCalls(
            "<tool_call>{\"name\":\"best50\",\"arguments\":{}}</tool_call>",
            known,
        )
        assertEquals(1, parsed.calls.size)
        assertTrue(parsed.cleanedText.isBlank())
    }

    @Test
    fun `空正文与空技能集安全返回`() {
        assertTrue(LlmParse.parseInlineToolCalls("", known).calls.isEmpty())
        assertTrue(LlmParse.parseInlineToolCalls("随便什么", emptySet()).calls.isEmpty())
    }

    // ---------- 参数解析容错 ----------

    @Test
    fun `空参数串返回空 Map`() {
        assertTrue(LlmParse.parseArguments("").isEmpty())
        assertTrue(LlmParse.parseArguments("{}").isEmpty())
    }

    @Test
    fun `非法 JSON 落到 raw 键`() {
        assertEquals("not json", LlmParse.parseArguments("not json")["raw"])
    }

    @Test
    fun `非对象参数落到 raw 键`() {
        assertEquals("hello", LlmParse.parseArguments("\"hello\"")["raw"])
    }

    @Test
    fun `数字参数转字符串`() {
        val args = LlmParse.parseArguments("{\"min\":12,\"max\":13}")
        assertEquals("12", args["min"])
        assertEquals("13", args["max"])
    }
}
