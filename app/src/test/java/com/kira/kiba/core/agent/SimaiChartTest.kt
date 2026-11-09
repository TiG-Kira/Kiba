package com.kira.kiba.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimaiChart] 的纯算法单测：难度映射、单难度切分、落雪 BPM 标记还原。
 * 这些逻辑一旦错，Agent 会把别的难度谱面喂给模型，或把标记行当成音符。
 */
class SimaiChartTest {

    private val raw = """
        &title=测试曲
        &artist=Kira
        &first=1.234
        &bpm=174
        &lv_2=5
        &inote_2=(174){16},1,2,3,4,E
        &inote_3=(174){16},5h[4:1],6,7,E
        &inote_5=(174){16},1-4[8:3],2/3,E
    """.trimIndent()

    // ---------- URL 与难度编号 ----------

    @Test
    fun `普通谱面 URL 带难度索引`() {
        assertEquals(
            "https://maimai.lxns.net/chart?chart_id=1920&difficulty=3",
            SimaiChart.previewUrl(1920, 3),
        )
    }

    @Test
    fun `宴谱 URL 省略难度`() {
        assertEquals(
            "https://maimai.lxns.net/chart?chart_id=100123",
            SimaiChart.previewUrl(100123, 3),
        )
        assertEquals(
            "https://assets2.lxns.net/maimai/chart/100123.txt",
            SimaiChart.cdnUrl(100123),
        )
    }

    @Test
    fun `URL 难度加二才是落雪 inote 编号`() {
        assertEquals(2, SimaiChart.inoteIndex(0))   // BASIC
        assertEquals(3, SimaiChart.inoteIndex(1))   // ADVANCED
        assertEquals(4, SimaiChart.inoteIndex(2))   // EXPERT
        assertEquals(5, SimaiChart.inoteIndex(3))   // MASTER
        assertEquals(6, SimaiChart.inoteIndex(4))   // Re:MASTER
    }

    @Test
    fun `扫出文件里全部难度声明`() {
        assertEquals(listOf(2, 3, 5), SimaiChart.availableInotes(raw))
    }

    @Test
    fun `宴谱自动选最大难度编号`() {
        assertEquals(5, SimaiChart.resolveInote(raw, null))
        assertEquals(4, SimaiChart.resolveInote(raw, 2))
    }

    // ---------- 单难度切分 ----------

    @Test
    fun `抽出 MASTER 段并保留元数据头`() {
        val out = SimaiChart.extract(raw, 5)
        checkNotNull(out)
        assertTrue(out.contains("&title=测试曲"))
        assertTrue(out.contains("&bpm=174"))
        assertTrue(out.contains("&inote_5=(174){16},1-4[8:3],2/3,E"))
        assertFalse("不能把其它难度谱面带进来", out.contains("5h[4:1]"))
        assertFalse(out.contains("&inote_2="))
    }

    @Test
    fun `抽出 BASIC 段时后面的难度整段丢掉`() {
        val out = SimaiChart.extract(raw, 2)
        checkNotNull(out)
        assertTrue(out.contains("&inote_2=(174){16},1,2,3,4,E"))
        assertFalse(out.contains("&inote_3="))
        assertFalse(out.contains("&inote_5="))
    }

    @Test
    fun `目标难度不存在返回 null`() {
        assertNull(SimaiChart.extract(raw, 6))
        assertNull(SimaiChart.extract("", 2))
    }

    @Test
    fun `多行谱面正文按原顺序保留`() {
        val multi = """
            &title=Multi
            &inote_2=(120){16},1,
            2,3,
            4,E
            &inote_3=(120){16},8,E
        """.trimIndent()
        val out = SimaiChart.extract(multi, 2)
        checkNotNull(out)
        assertTrue(out.contains("(120){16},1,\n2,3,\n4,E"))
        assertFalse(out.contains("8,E"))
    }

    // ---------- 落雪显示写法还原 ----------

    @Test
    fun `BPM 与拍数标记行还原成标准写法`() {
        val displayed = """
            BPM 174 | 1/16
            1,2,3,
            1/8
            1-4[8:3],
            BPM 150.5
            5h[4:1],E
        """.trimIndent()
        val out = SimaiChart.normalizeDisplayed(displayed)
        assertTrue(out.startsWith("(174){16}"))
        assertTrue(out.contains("\n1,2,3,\n{8}\n"))
        assertTrue(out.contains("\n1-4[8:3],\n(150.5)\n"))
        assertTrue(out.endsWith("5h[4:1],E"))
    }

    @Test
    fun `普通音符行与时长记号原样保留`() {
        val line = "1/8h[4:1],1-4[160#8:3]b,C,E"
        assertEquals(line, SimaiChart.normalizeDisplayed(line))
    }

    @Test
    fun `单斜杠数字行不会被误判成拍数标记`() {
        val line = "1/16/2,3,"
        assertEquals(line, SimaiChart.normalizeDisplayed(line))
    }

    // ---------- 注入脚本 / 摘要 ----------

    @Test
    fun `注入脚本带上 CDN 地址且启动状态为 pending`() {
        val js = SimaiChart.fetchJs(SimaiChart.cdnUrl(1920))
        assertTrue(js.contains("https://assets2.lxns.net/maimai/chart/1920.txt"))
        assertTrue(js.contains("__kibaSimaiState='pending'"))
        assertTrue(js.contains("__kibaSimaiState='done'"))
    }

    @Test
    fun `摘要列出 BPM 与拍数声明`() {
        val summary = SimaiChart.summarize("(174){16},1,2,(150){8},3,E")
        assertTrue(summary.contains("174"))
        assertTrue(summary.contains("150"))
        assertTrue(summary.contains("16"))
        assertTrue(summary.contains("8"))
    }

    @Test
    fun `难度编号越界被夹到合法区间`() {
        assertEquals(2, SimaiChart.inoteIndex(-3))
        assertEquals(6, SimaiChart.inoteIndex(99))
    }
}
