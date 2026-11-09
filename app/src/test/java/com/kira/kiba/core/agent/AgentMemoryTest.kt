package com.kira.kiba.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentMemory] 纯算法单测：清洗、预算装填、prompt 组装。
 * 这些逻辑直接进 system prompt——超预算会把上下文撑爆，换行没压平会把列表结构打断。
 */
class AgentMemoryTest {

    private fun item(text: String, updatedAt: Long, id: String = text) =
        AgentMemory.Item(id = id, text = text, createdAt = updatedAt, updatedAt = updatedAt)

    // ---------- normalize ----------

    @Test
    fun `换行与连续空白压成单空格`() {
        assertEquals("主玩 舞萌 DX", AgentMemory.normalize("主玩\n舞萌\t DX  "))
        assertEquals("a b", AgentMemory.normalize("a\u3000\u3000b"))
    }

    @Test
    fun `超长截断并带省略号`() {
        val long = "记".repeat(AgentMemory.MAX_ITEM_CHARS + 50)
        val normalized = AgentMemory.normalize(long)
        assertEquals(AgentMemory.MAX_ITEM_CHARS + 1, normalized.length)
        assertTrue(normalized.endsWith("…"))
    }

    @Test
    fun `纯空白等于空`() {
        assertEquals("", AgentMemory.normalize("  \n\t "))
    }

    // ---------- buildPromptBlock ----------

    @Test
    fun `空列表返回空串`() {
        assertEquals("", AgentMemory.buildPromptBlock(emptyList()))
    }

    @Test
    fun `条目按时间正序输出并带说明头`() {
        val block = AgentMemory.buildPromptBlock(
            listOf(item("用户叫 Kira", 2000L), item("主玩舞萌 DX", 1000L)),
        )
        assertTrue(block.startsWith("【长期记忆】"))
        val idxA = block.indexOf("· 主玩舞萌 DX")
        val idxB = block.indexOf("· 用户叫 Kira")
        assertTrue(idxA >= 0 && idxB > idxA)
        assertFalse(block.contains("["))
    }

    @Test
    fun `预算不足时丢最老的`() {
        // 每条 100 字，预算只够 2 条
        val items = (1..5).map { n -> item("m$n".repeat(50), updatedAt = n * 100L) }
        val block = AgentMemory.buildPromptBlock(items, maxChars = 240)
        // 新的（updatedAt 大）必须留下，最老的必须被丢
        assertTrue(block.contains("· " + "m5".repeat(50)))
        assertFalse(block.contains("· " + "m1".repeat(50)))
    }

    @Test
    fun `第一条永远不被预算挤掉`() {
        val items = listOf(item("记".repeat(300), 1L))
        val block = AgentMemory.buildPromptBlock(items, maxChars = 10)
        assertTrue(block.contains("【长期记忆】"))
        assertTrue(block.contains("记"))
    }

    @Test
    fun `输出不含内部换行的条目结构完整`() {
        val block = AgentMemory.buildPromptBlock(listOf(item("偏好一\n偏好二", 1L)))
        // 换行被压平：「· 」只出现在每条开头
        assertEquals(1, Regex("· ").findAll(block).count())
    }
}
