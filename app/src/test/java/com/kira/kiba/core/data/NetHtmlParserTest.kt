package com.kira.kiba.core.data

import com.kira.kiba.core.data.NetHtmlParser.NetScore
import com.kira.kiba.core.data.NetHtmlParser.PageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetHtmlParser] 与 [LxnsScoreMapper] 的单元测试。
 *
 * ## 为什么这些用例重要
 * 落雪的个人 API 只收 JSON 成绩（`POST /api/v0/user/{game}/player/scores`），
 * 页面解析搬到了本地 —— 一旦选择器写错，表现是「上传成功 0 条」这种**沉默失败**，
 * 玩家看不出问题。所以这里按真实页面结构固定住解析口径。
 *
 * 样本结构来自两份生产实现：
 * - 舞萌：`Diving-Fish/maimaidx-prober` 的 `page_parser.py` 与 `TrueRou/maimai.py`
 * - 中二：同一份 `page_parser.py` 的 `parse_chuni_music_box`
 */
class NetHtmlParserTest {

    // ==================== 舞萌样本构造 ====================

    /**
     * 拼一行舞萌成绩。结构与 NET 实际页面一致：
     * form 里首图是难度图标，随后是等级块、曲名块、两个分数块，最后是 FS / FC / 评级三张图标。
     */
    private fun maimaiRow(
        title: String = "テストソング",
        diff: String = "master",
        level: String = "13+",
        achievement: String = "100.5000%",
        dxScore: String = "1,234/1,500",
        fsIcon: String = "fs",
        fcIcon: String = "ap",
        rateIcon: String = "sss",
        typeIcon: String = "music_dx",
        containerId: String = "",
    ): String {
        val idAttr = if (containerId.isEmpty()) "" else " id=\"$containerId\""
        return """
            <div class="w_450 m_15 p_r f_0"$idAttr>
              <form>
                <img src="https://maimai.wahlap.com/maimai-mobile/img/diff_$diff.png">
                <div class="music_lv_block f_r t_c f_15">$level</div>
                <div class="music_name_block t_l f_13 break">$title</div>
                <div class="music_score_block w_112 t_r f_l f_16">$achievement</div>
                <div class="music_score_block w_96 t_r f_l f_12">$dxScore</div>
                <img src="https://maimai.wahlap.com/maimai-mobile/img/music_icon_$fsIcon.png">
                <img src="https://maimai.wahlap.com/maimai-mobile/img/music_icon_$fcIcon.png">
                <img src="https://maimai.wahlap.com/maimai-mobile/img/music_icon_$rateIcon.png">
                <img src="https://maimai.wahlap.com/maimai-mobile/img/$typeIcon.png">
              </form>
            </div>
        """.trimIndent()
    }

    private fun maimaiPage(vararg rows: String): String =
        "<html><body>" + rows.joinToString("\n") + "</body></html>"

    // ==================== 舞萌解析 ====================

    @Test
    fun `MASTER 难度与 DX 谱面被正确识别`() {
        val html = maimaiPage(maimaiRow(title = "テストソング"))
        val scores = NetHtmlParser.parseMaimai(html)

        assertEquals(1, scores.size)
        with(scores[0]) {
            assertEquals("テストソング", title)
            assertEquals(3, levelIndex) // MASTER
            assertEquals(100.5, achievements, 0.0001)
            assertEquals(1234, dxScore)
            assertEquals("dx", songType)
            assertEquals("fs", fs)
            assertEquals("ap", fc)
            assertEquals("sss", rate)
        }
    }

    @Test
    fun `REMASTER 优先于 MASTER 判断`() {
        // diff_remaster.png 里同时含 "master"，顺序写反就会把 Re:MASTER 判成 MASTER
        val html = maimaiPage(maimaiRow(title = "重譜面", diff = "remaster"))
        val scores = NetHtmlParser.parseMaimai(html)

        assertEquals(1, scores.size)
        assertEquals(4, scores[0].levelIndex)
    }

    @Test
    fun `标准谱面按 music_standard 图标判定`() {
        val html = maimaiPage(
            maimaiRow(title = "標準譜面", diff = "expert", typeIcon = "music_standard")
        )
        val scores = NetHtmlParser.parseMaimai(html)

        assertEquals(1, scores.size)
        assertEquals("standard", scores[0].songType)
        assertEquals(2, scores[0].levelIndex) // EXPERT
    }

    @Test
    fun `没有类型图标时退回祖先 id 前缀`() {
        // 真实页面里类型图标有时不在 form 内，只能看祖先容器的 id（sta… / dx…）
        val html = maimaiPage(
            maimaiRow(title = "祖先判定", typeIcon = "not_a_type_icon", containerId = "sta_123")
        )
        // 该 img 不存在也无效，jsoup 不会报错，只是解析出的 src 不含 standard/dx
        val scores = NetHtmlParser.parseMaimai(html)

        assertEquals(1, scores.size)
        assertEquals("standard", scores[0].songType)
    }

    @Test
    fun `FS 图标的 fdx 映射为落雪的 fsd`() {
        // NET 用 fdx / fdxp，落雪 API 的枚举是 fsd / fsdp，不转换会 400
        assertEquals("fsd", NetHtmlParser.parseMaimai(maimaiPage(maimaiRow(fsIcon = "fdx")))[0].fs)
        assertEquals("fsdp", NetHtmlParser.parseMaimai(maimaiPage(maimaiRow(fsIcon = "fdxp")))[0].fs)
        assertEquals("sync", NetHtmlParser.parseMaimai(maimaiPage(maimaiRow(fsIcon = "sync")))[0].fs)
    }

    @Test
    fun `占位图标 back 解析为空而不是字符串 back`() {
        val scores = NetHtmlParser.parseMaimai(
            maimaiPage(maimaiRow(fsIcon = "back", fcIcon = "back"))
        )
        assertEquals(1, scores.size)
        assertNull(scores[0].fs)
        assertNull(scores[0].fc)
    }

    @Test
    fun `Link 靠 DX 满分区分标准与 DX 两首同名曲`() {
        // LINK_DX_SCORE 按 BASIC/ADVANCED/EXPERT/MASTER/ULTIMA 排，MASTER 是 924；
        // 不等于 924 说明这一行是另一首同名曲，必须改名才能匹配到正确的曲目 ID
        val cof = NetHtmlParser.parseMaimai(
            maimaiPage(maimaiRow(title = "Link", dxScore = "500/900"))
        )
        assertEquals("Link(CoF)", cof[0].title)

        val normal = NetHtmlParser.parseMaimai(
            maimaiPage(maimaiRow(title = "Link", dxScore = "900/924"))
        )
        assertEquals("Link", normal[0].title)
    }

    @Test
    fun `非成绩页解析结果为空而不是抛异常`() {
        assertEquals(0, NetHtmlParser.parseMaimai("<html><body>login</body></html>").size)
        assertEquals(0, NetHtmlParser.parseMaimai("").size)
    }

    // ==================== 中二样本构造 ====================

    private fun chuniRow(
        title: String,
        levelKey: String = "master",
        score: String = "1,010,000",
        icons: List<String> = listOf("icon_alljustice", "icon_rank_sss"),
    ): String {
        val iconHtml = icons.joinToString("") {
            """<img src="https://chunithm.wahlap.com/mobile/img/$it.png">"""
        }
        return """
            <div class="musiclist_box bg_$levelKey">
              <div class="music_title">$title</div>
              <div class="play_musicdata_highscore"><span>$score</span></div>
              <div class="play_musicdata_icon">$iconHtml</div>
            </div>
        """.trimIndent()
    }

    private fun chuniPage(vararg rows: String): String =
        "<html><body>" + rows.joinToString("\n") + "</body></html>"

    // ==================== 中二解析 ====================

    @Test
    fun `中二难度按类名最后一项识别`() {
        val html = chuniPage(
            chuniRow("BASIC曲", levelKey = "basic", score = "900,000", icons = listOf("icon_rank_s")),
            chuniRow("MASTER曲", levelKey = "master", score = "1,010,000"),
            chuniRow("ULTIMA曲", levelKey = "ultima", score = "1,005,000"),
        )
        val scores = NetHtmlParser.parseChuni(html)

        assertEquals(3, scores.size)
        assertEquals(0, scores[0].levelIndex)
        assertEquals(3, scores[1].levelIndex)
        assertEquals(4, scores[2].levelIndex)
    }

    @Test
    fun `中二分数去掉千分位逗号`() {
        val scores = NetHtmlParser.parseChuni(chuniPage(chuniRow("曲名", score = "1,007,500")))
        assertEquals(1007500, scores[0].score)
    }

    @Test
    fun `中二 AJC 比 AJ 优先匹配`() {
        // alljusticecritical 的图标名里也含 alljustice，先判短串会把它降级成 AJ
        val ajc = NetHtmlParser.parseChuni(
            chuniPage(chuniRow("曲名", icons = listOf("icon_alljusticecritical", "icon_rank_sss")))
        )
        assertEquals("alljusticecritical", ajc[0].fc)

        val aj = NetHtmlParser.parseChuni(
            chuniPage(chuniRow("曲名", icons = listOf("icon_alljustice", "icon_rank_sss")))
        )
        assertEquals("alljustice", aj[0].fc)
    }

    @Test
    fun `中二只有评级图标时视为没有 FC`() {
        val scores = NetHtmlParser.parseChuni(
            chuniPage(chuniRow("曲名", icons = listOf("icon_rank_aaa")))
        )
        assertNull(scores[0].fc)
    }

    @Test
    fun `中二 worldsend 难度解析为 levelIndex 5`() {
        val scores = NetHtmlParser.parseChuni(
            chuniPage(chuniRow("WE曲", levelKey = "worldsend", score = "1,000,000"))
        )
        assertEquals(5, scores[0].levelIndex)
    }

    // ==================== 页面嗅探 ====================

    @Test
    fun `嗅探能区分登录页与成绩页`() {
        assertEquals(PageKind.MAIMAI_SCORE, NetHtmlParser.sniff("maimai", "<html>" + "x".repeat(300) + "<div class=\"music_name_block\">a</div></html>"))
        assertEquals(PageKind.CHUNI_SCORE, NetHtmlParser.sniff("chunithm", "<html>" + "x".repeat(300) + "<div class=\"musiclist_box bg_master\"></div></html>"))
        assertEquals(PageKind.LOGIN_OR_ERROR, NetHtmlParser.sniff("chunithm", ""))
        assertEquals(PageKind.LOGIN_OR_ERROR, NetHtmlParser.sniff("chunithm", "<html>short</html>"))
        assertEquals(
            PageKind.LOGIN_OR_ERROR,
            NetHtmlParser.sniff("maimai", "<html>" + "x".repeat(300) + "<input type=\"password\"></html>")
        )
    }

    // ==================== 载荷构造 ====================

    private val emptyIndex = LxnsScoreMapper.SongIndex(emptyMap(), emptyMap())

    @Test
    fun `舞萌载荷字段符合落雪 Score 结构`() {
        val index = LxnsScoreMapper.SongIndex(
            maimai = mapOf(LxnsScoreMapper.maimaiKey("テストソング", "dx") to 834),
            chuni = emptyMap(),
        )
        val scores = listOf(
            NetScore(
                title = "テストソング", levelIndex = 3, score = 0, achievements = 100.5,
                dxScore = 1234, fc = "ap", fs = "fsd", rate = "sss", songType = "dx",
            )
        )
        val built = LxnsScoreMapper.buildMaimaiPayload(scores, index)

        assertEquals(1, built.mapped)
        val first = built.payload.getAsJsonArray("scores")[0].asJsonObject
        assertEquals(834, first.get("id").asInt)
        assertEquals("dx", first.get("type").asString)
        assertEquals(3, first.get("level_index").asInt)
        assertEquals(100.5, first.get("achievements").asDouble, 0.0001)
        assertEquals("ap", first.get("fc").asString)
        assertEquals("fsd", first.get("fs").asString)
        assertEquals(1234, first.get("dx_score").asInt)
    }

    @Test
    fun `曲名匹配不上时计入 skipped 而不是静默丢弃`() {
        val scores = listOf(
            NetScore("不存在的曲", 3, 0, 99.0, 0, null, null, null, "dx"),
            NetScore("存在的曲", 3, 0, 100.0, 0, null, null, null, "dx"),
        )
        val index = LxnsScoreMapper.SongIndex(
            maimai = mapOf(LxnsScoreMapper.maimaiKey("存在的曲", "dx") to 1),
            chuni = emptyMap(),
        )
        val built = LxnsScoreMapper.buildMaimaiPayload(scores, index)

        assertEquals(1, built.mapped)
        assertEquals(listOf("不存在的曲"), built.skippedTitles)
    }

    @Test
    fun `中二载荷按分数推断 CLEAR 类型`() {
        val index = LxnsScoreMapper.SongIndex(
            maimai = emptyMap(),
            chuni = mapOf("CLEAR曲" to 3, "FAILED曲" to 4),
        )
        val scores = listOf(
            NetScore("CLEAR曲", 3, 1_010_000, 0.0, 0, "alljusticecritical", null, null, null),
            NetScore("FAILED曲", 2, 300_000, 0.0, 0, null, null, null, null),
        )
        val built = LxnsScoreMapper.buildChuniPayload(scores, index)

        assertEquals(2, built.mapped)
        val arr = built.payload.getAsJsonArray("scores")
        assertEquals("clear", arr[0].asJsonObject.get("clear").asString)
        assertEquals("alljusticecritical", arr[0].asJsonObject.get("full_combo").asString)
        assertEquals("failed", arr[1].asJsonObject.get("clear").asString)
        // 没有 FC 时不能写 full_combo，否则会把玩家已有的 AJ 覆盖成空
        assertNull(arr[1].asJsonObject.get("full_combo"))
    }

    @Test
    fun `中二 WORLD'S END 与零分成绩被跳过并计数`() {
        val scores = listOf(
            NetScore("WE曲", 5, 1_000_000, 0.0, 0, null, null, null, null),
            NetScore("零分曲", 3, 0, 0.0, 0, null, null, null, null),
        )
        val built = LxnsScoreMapper.buildChuniPayload(scores, emptyIndex)

        assertEquals(0, built.mapped)
        assertEquals(2, built.unsupported)
        assertTrue(built.skippedTitles.isEmpty())
    }

    @Test
    fun `中二载荷的曲目 ID 与难度原样透传`() {
        // 索引里的 id 已经过 %10000 归一（见 LxnsScoreMapper.index），这里验证不被二次改动
        val index = LxnsScoreMapper.SongIndex(
            maimai = emptyMap(),
            chuni = mapOf("中二曲" to 1234),
        )
        val built = LxnsScoreMapper.buildChuniPayload(
            listOf(NetScore("中二曲", 3, 1_000_000, 0.0, 0, null, null, null, null)),
            index,
        )
        val first = built.payload.getAsJsonArray("scores")[0].asJsonObject
        assertEquals(1234, first.get("id").asInt)
        assertEquals(3, first.get("level_index").asInt)
    }

    @Test
    fun `调试样本能把曲名摘出来`() {
        val html = chuniPage(chuniRow("デバッグ曲"))
        assertNotNull(NetHtmlParser.debugTitleSample(html))
        assertTrue(NetHtmlParser.debugTitleSample(html).contains("デバッグ曲"))
    }
}
