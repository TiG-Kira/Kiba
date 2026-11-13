package com.kira.kiba.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版歌单 JSON 的兼容解析。
 *
 * 这组用例是为 v3.0.0 的闪退钉的：v2.5 存下来的歌单只有 `ids` / `reasons` / `summary`
 * 三个键（`summary` 甚至是后加的），而 Gson 反射建实例**不执行 Kotlin 默认值**，
 * 缺键就是 null —— 迁移路径 `legacy.summary.ifBlank {}` 直接 NPE。
 * 现在改成手写解析，这里覆盖「缺字段 / 类型错 / 空对象 / 压根不是 JSON」四类脏数据。
 */
class AgentPlaylistParseTest {

    // ── 旧键：单个 Saved 对象 ──

    @Test
    fun `v2_5 旧歌单只有 ids 也能读成合法对象`() {
        val saved = AgentPlaylist.parseSaved("""{"ids":[111,222,333]}""")
        assertNotNull(saved)
        assertEquals(listOf(111, 222, 333), saved!!.ids)
        // 关键：缺 summary 必须是空串，不能是 null（旧实现这里给 null，后面 ifBlank 崩）
        assertEquals("", saved.summary)
        assertEquals("", saved.name)
        assertEquals("", saved.coverPath)
        assertEquals("", saved.mood)
        assertTrue(saved.reasons.isEmpty())
        assertTrue(saved.coverTags.isEmpty())
    }

    @Test
    fun `旧歌单字段是 null 时全部兜底成空值`() {
        val saved = AgentPlaylist.parseSaved(
            """{"ids":[1],"summary":null,"reasons":null,"name":null,"coverPath":null}""",
        )
        assertNotNull(saved)
        assertEquals(listOf(1), saved!!.ids)
        assertEquals("", saved.summary)
        assertTrue(saved.reasons.isEmpty())
    }

    @Test
    fun `字段类型不符时不抛异常`() {
        // ids 里混对象和不可转数字的字符串、summary 给对象、reasons 不是数组
        // （注意 Gson 对 "2" 这类数字字符串是能转的，所以真正的脏数据要选转不动的）
        val saved = AgentPlaylist.parseSaved(
            """{"ids":[1,{"x":1},"abc",3],"summary":{"n":1},"reasons":"oops"}""",
        )
        assertNotNull(saved)
        assertEquals(listOf(1, 3), saved!!.ids)
        assertEquals("", saved.summary)
        assertTrue(saved.reasons.isEmpty())
    }

    @Test
    fun `没有曲目 id 的歌单视为无数据`() {
        assertNull(AgentPlaylist.parseSaved("""{"ids":[]}"""))
        assertNull(AgentPlaylist.parseSaved("""{"summary":"只有推荐语"}"""))
    }

    @Test
    fun `空串 坏 JSON 数组都返回 null 而不是崩`() {
        assertNull(AgentPlaylist.parseSaved(""))
        assertNull(AgentPlaylist.parseSaved("   "))
        assertNull(AgentPlaylist.parseSaved(""""not-json""""))
        assertNull(AgentPlaylist.parseSaved("""[1,2,3]"""))
    }

    @Test
    fun `新格式的歌单字段完整保留`() {
        val saved = AgentPlaylist.parseSaved(
            """{"ids":[7,8],"reasons":["理由A","理由B"],"summary":"深夜循环","name":"夜行歌单",
               "coverPath":"/data/x.png","mood":"夜行","coverTags":["专注","低音"]}""",
        )
        assertNotNull(saved)
        assertEquals("深夜循环", saved!!.summary)
        assertEquals("夜行歌单", saved.name)
        assertEquals("/data/x.png", saved.coverPath)
        assertEquals("夜行", saved.mood)
        assertEquals(listOf("专注", "低音"), saved.coverTags)
        assertEquals(2, saved.reasons.size)
    }

    // ── 历史存档：Item 数组 ──

    @Test
    fun `历史存档缺 id 的条目被跳过 其余照常`() {
        val items = AgentPlaylistStore.parseItems(
            """[{"id":"a","name":"第一张"},{"name":"没有 id"},{"id":"b"}]""",
        )
        assertEquals(2, items.size)
        assertEquals("a", items[0].id)
        assertEquals("b", items[1].id)
        // 缺 name 给默认名，缺字段全部兜底
        assertEquals("Agent 专属歌单", items[1].name)
        assertTrue(items[1].ids.isEmpty())
        assertEquals(PlaylistCover.TEMPLATE, items[1].template)
        assertEquals(0L, items[1].createdAt)
    }

    @Test
    fun `历史存档不是数组或为空时返回空列表`() {
        assertTrue(AgentPlaylistStore.parseItems("").isEmpty())
        assertTrue(AgentPlaylistStore.parseItems("""{"id":"a"}""").isEmpty())
        assertTrue(AgentPlaylistStore.parseItems("[]").isEmpty())
        assertTrue(AgentPlaylistStore.parseItems("garbage").isEmpty())
    }

    @Test
    fun `历史存档里混入非对象元素时只丢该元素`() {
        val items = AgentPlaylistStore.parseItems("""[1,"x",null,{"id":"ok"}]""")
        assertEquals(1, items.size)
        assertEquals("ok", items[0].id)
    }

    // ── LLM 输出解析（parse）：失败不抛异常，带回失败原因与主题供原地重试 ──

    private fun profiles(n: Int) = (1..n).map {
        AgentPlaylist.Profile(it, "曲$it", "曲师", "流行", 13.0, false)
    }

    @Test
    fun `正常输出解析出选曲与封面字段`() {
        val content = """
            {"summary":"深夜循环","cover":{"title":"夜行歌单","subtitle":"陪你到天亮",
             "mood":"夜行","tags":["专注","低音"]},"reasons":["理由A","理由B"],"picks":[1,3]}
        """.trimIndent()
        val parsed = AgentPlaylist.parse(content, profiles(5))
        assertNotNull(parsed.saved)
        assertEquals(listOf(1, 3), parsed.saved!!.ids)
        assertEquals("夜行", parsed.saved.mood)
        assertEquals("夜行歌单", parsed.saved.name)
        assertEquals(listOf("理由A", "理由B"), parsed.saved.reasons)
        assertEquals("夜行", parsed.theme)
        assertEquals("", parsed.failReason)
    }

    @Test
    fun `散文包裹的 JSON 也能抠出来`() {
        // 模型最常见失败形态：JSON 前后带解释文字
        val content = """
            好的，这是为你挑选的歌单：
            {"summary":"午后轻快","cover":{"title":"清晨散步","mood":"清晨"},"reasons":[],"picks":[2]}
            希望你喜欢！
        """.trimIndent()
        val parsed = AgentPlaylist.parse(content, profiles(5))
        assertNotNull(parsed.saved)
        assertEquals(listOf(2), parsed.saved!!.ids)
        assertEquals("清晨", parsed.saved.mood)
    }

    @Test
    fun `围栏代码块照常处理`() {
        val content = "```json\n{\"summary\":\"s\",\"picks\":[1]}\n```"
        val parsed = AgentPlaylist.parse(content, profiles(5))
        assertNotNull(parsed.saved)
        assertEquals(listOf(1), parsed.saved!!.ids)
    }

    @Test
    fun `不是合法 JSON 时返回失败 带原因与兜底主题`() {
        val parsed = AgentPlaylist.parse("这是一段纯文本输出，没有任何 JSON。", profiles(5))
        assertNull(parsed.saved)
        assertEquals("输出不是合法 JSON", parsed.failReason)
        // 兜底主题：输出里提到「夜行」就带回它，重试指令可以点名保持
        val themed = AgentPlaylist.parse("我想做一张夜行气质的歌单，但格式忘了。", profiles(5))
        assertNull(themed.saved)
        assertEquals("夜行", themed.theme)
    }

    @Test
    fun `缺少 picks 或 picks 全越界都视为失败`() {
        val missing = AgentPlaylist.parse("""{"summary":"s","reasons":[]}""", profiles(5))
        assertNull(missing.saved)
        assertEquals("缺少 picks 数组", missing.failReason)

        val outOfRange = AgentPlaylist.parse("""{"summary":"s","picks":[90,99]}""", profiles(5))
        assertNull(outOfRange.saved)
        assertEquals("picks 全部越界", outOfRange.failReason)
    }

    @Test
    fun `重复序号去重保序`() {
        val parsed = AgentPlaylist.parse("""{"summary":"s","picks":[1,1,2]}""", profiles(5))
        assertEquals(listOf(1, 2), parsed.saved!!.ids)
    }
}
