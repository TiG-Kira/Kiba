package com.kira.kiba.core.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * NET 页面解析器：把机台抓到的 HTML 变成结构化成绩。
 *
 * ## 为什么必须自己解析
 * 落雪的 **HTML 直传端点属于开发者 API**（`POST /api/v0/{game}/player/{friend_code}/html`，
 * 需要 `allow_third_party_write_data` 权限 + 好友码，得走 OAuth 应用审核）。个人 API 密钥
 * 只有两条读写通道：`GET/POST /api/v0/user/{game}/player/scores`（JSON）。
 *
 * 此前 1.8.3～1.8.5 一直在往 `POST /api/v0/user/{game}/player/html` 直传页面源码，
 * 实测被服务端拒成 **HTTP 550**（该路径不是个人 API 的公开能力），所以改成
 * 「本地解析 → JSON 上传」。解析口径与水鱼服务端完全一致，依据两份生产实现：
 * - 舞萌：`Diving-Fish/maimaidx-prober` 的 `database/tools/page_parser.py`
 *   与 `TrueRou/maimai.py` 的 `maimai_py/utils/page_parser.py`；
 * - 中二：同一文件的 `chunithm_genre2json` / `parse_chuni_music_box`。
 *
 * 解析器是**纯函数**（不碰网络、不碰数据库），可以直接单测，见 [NetHtmlParserTest]。
 *
 * ## 页面结构要点（改版时先看这里）
 * - 舞萌成绩行：`div.w_450.m_15.p_r.f_0` → `form` → 首图是难度图标 `diff_xxx.png`，
 *   `music_name_block` 曲名、`music_score_block` 两个（达成率 / DX 分），
 *   `img[src*=music_icon]` 依次是 **FS、FC、评级**。
 * - 中二成绩行：`div.musiclist_box`，**类名最后一项** 是难度（`bg_basic`…`bg_worldsend`），
 *   `music_title` 曲名、`play_musicdata_highscore > span` 分数、`play_musicdata_icon > img`
 *   是 FC/评级图标（最后一个含 `rank` 说明没 FC）。
 */
object NetHtmlParser {

    /** 舞萌 Link 的 DX 满分随难度不同：SD 与 DX 是两首同名曲，靠满分区分。 */
    private val LINK_DX_SCORE = intArrayOf(372, 522, 942, 924, 1425)

    /** 中二难度类名 → level_index（与落雪 LevelIndex 一致，5 = WORLD'S END）。 */
    private val CHUNI_LEVELS = listOf("basic", "advanced", "expert", "master", "ultima", "worldsend")

    /** 舞萌难度图标关键字 → level_index。注意 remaster 要排在 master 前面判断。 */
    private val MAIMAI_LEVELS = listOf(
        "remaster" to 4,
        "master" to 3,
        "expert" to 2,
        "advanced" to 1,
        "basic" to 0,
    )

    /** 抓到的页面属于哪一类 —— 用来区分「登录页 / 错误页」与「真成绩页」。 */
    enum class PageKind {
        /** 舞萌最佳成绩页。 */
        MAIMAI_SCORE,

        /** 中二最佳成绩页（record/musicGenre*）。 */
        CHUNI_SCORE,

        /** 中二 WORLD'S END 列表页，结构是表格而不是 musiclist_box，本解析器不支持。 */
        CHUNI_WORLD_END,

        /** 登录页 / 授权失败页 / 空壳页。 */
        LOGIN_OR_ERROR,

        /** 无法识别。 */
        UNKNOWN,
    }

    /**
     * 一条从 NET 页面解析出来的成绩（与任何查分器无关的中间模型）。
     *
     * 舞萌与中二共用同一个结构：两个游戏的差异用 [songType]、[score] 的口径区分，
     * 上传时各自映射成落雪对应的字段。
     */
    data class NetScore(
        /** 曲名（舞萌的 `Link` 会被改写为 `Link(CoF)` 以区分两首同名曲）。 */
        val title: String,
        /** 难度索引：舞萌 0..4，中二 0..5。 */
        val levelIndex: Int,
        /** 中二分数（如 1010000）；舞萌无此概念，填 0。 */
        val score: Int,
        /** 舞萌达成率（如 101.0000）；中二无此概念，填 0。 */
        val achievements: Double,
        /** 舞萌 DX 分；中二填 0。 */
        val dxScore: Int,
        /** 舞萌 FCType（app/ap/fcp/fc）；中二 FullComboType（alljusticecritical/alljustice/fullcombo）。 */
        val fc: String?,
        /** 舞萌 FSType（fsdp/fsd/fsp/fs/sync）；中二无此概念，恒 null。 */
        val fs: String?,
        /** 评级（舞萌 sssp/sss/…；中二同）。仅用于日志。 */
        val rate: String?,
        /** 舞萌谱面类型 standard / dx；中二恒为 null。 */
        val songType: String?,
    )

    // ==================== 页面嗅探 ====================

    /**
     * 判断页面类型。**先嗅探再解析**，可以把「抓到登录页」这种最常见故障
     * 说成「授权没生效」，而不是让用户看到「上传失败：0 条」。
     */
    fun sniff(game: String, html: String): PageKind {
        if (html.isBlank() || html.length < 200) return PageKind.LOGIN_OR_ERROR
        val lower = html.lowercase()
        return when {
            // 中二：WORLD'S END 页面是独立表格，没有 musiclist_box
            lower.contains("worldsend") && !lower.contains("musiclist_box") -> PageKind.CHUNI_WORLD_END
            lower.contains("musiclist_box") -> PageKind.CHUNI_SCORE
            lower.contains("music_name_block") -> PageKind.MAIMAI_SCORE
            // 登录/授权失败的典型特征：有密码框或明确要求登录
            lower.contains("type=\"password\"") || lower.contains("login form") -> PageKind.LOGIN_OR_ERROR
            game == CHUNI_GAME -> PageKind.CHUNI_SCORE
            else -> PageKind.UNKNOWN
        }
    }

    // ==================== 舞萌 ====================

    /**
     * 解析舞萌「最佳成绩」页（`record/musicSort/search` 或 `record/musicGenre/search`）。
     *
     * 两条路径的页面**同构**，差别只在请求参数，所以解析器不关心来源 URL。
     */
    fun parseMaimai(html: String): List<NetScore> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        // 这四个 class 同时出现才是成绩行（w_450 / m_15 / p_r / f_0 缺一不可）
        val rows = doc.select("div.w_450.m_15.p_r.f_0")
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<NetScore>(rows.size)
        for (row in rows) {
            parseMaimaiRow(row)?.let { out.add(it) }
        }
        return out
    }

    private fun parseMaimaiRow(row: Element): NetScore? {
        val form = row.selectFirst("form") ?: return null
        val firstImg = form.selectFirst("img") ?: return null
        val diffSrc = firstImg.attr("src")

        val levelIndex = MAIMAI_LEVELS.firstOrNull { diffSrc.contains(it.first) }?.second ?: return null

        val title = form.selectFirst(".music_name_block")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val scoreBlocks = form.select(".music_score_block")
        // 达成率形如 "100.5000%"，DX 分形如 "1,234/1,500"。
        // NET 这两个块的文本里常夹换行/全角空格，不清干净会 toInt 失败 ——
        // 而 DX 满分是 Link 同名曲的唯一区分依据，不能容忍这种静默归零。
        fun digitsOf(raw: String?): String =
            raw.orEmpty().filter { it.isDigit() }

        val achievements = scoreBlocks.getOrNull(0)?.text()
            ?.let { raw -> raw.filter { it.isDigit() || it == '.' } }
            ?.let { runCatching { it.toDouble() }.getOrNull() } ?: 0.0

        val dxFull = digitsOf(scoreBlocks.getOrNull(1)?.text()?.substringAfter("/", ""))
            .let { runCatching { it.toInt() }.getOrNull() } ?: 0
        val dxScore = digitsOf(scoreBlocks.getOrNull(1)?.text()?.substringBefore("/", ""))
            .let { runCatching { it.toInt() }.getOrNull() } ?: 0

        // FS / FC / 评级三张图标按文档顺序出现
        val icons = form.select("img[src*=music_icon]")
        val fs = icons.getOrNull(0)?.let { musicIconName(it.attr("src")) }
        val fc = icons.getOrNull(1)?.let { musicIconName(it.attr("src")) }
        val rate = icons.getOrNull(2)?.let { musicIconName(it.attr("src")) }

        val songType = resolveMaimaiSongType(row, form)

        // Link 有 SD / DX 两首同名曲，只能靠 DX 满分区分（与查分器口径一致）
        val fixedTitle = if (title == "Link" && dxFull != LINK_DX_SCORE.getOrElse(levelIndex) { -1 }) {
            "Link(CoF)"
        } else {
            title
        }

        return NetScore(
            title = fixedTitle,
            levelIndex = levelIndex,
            score = 0,
            achievements = achievements,
            dxScore = dxScore,
            fc = fc,
            fs = fs,
            rate = rate,
            songType = songType,
        )
    }

    /**
     * 判断是标准谱还是 DX 谱。
     *
     * 三个依据按可靠性排序：`music_standard.png` / `music_dx.png` 类型图标 →
     * 祖先元素 id 前缀（`sta…` / `dx…`）→ 默认 DX。
     */
    private fun resolveMaimaiSongType(row: Element, form: Element): String {
        val allImgs = form.select("img")
        val typeIcon = allImgs.firstOrNull { img ->
            val src = img.attr("src")
            src.contains("music_standard") || src.contains("music_dx")
        }
        if (typeIcon != null) {
            return if (typeIcon.attr("src").contains("music_standard")) TYPE_STANDARD else TYPE_DX
        }
        // 兜底一：祖先 id 前缀（查分器就是这么判的）。注意要连自身一起看 ——
        // 有些版本把 sta_xxx / dx_xxx 直接挂在成绩行容器上。
        for (parent in sequenceOf(row) + row.parents()) {
            val id = parent.id()
            if (id.length >= 3) {
                if (id.startsWith("sta")) return TYPE_STANDARD
                if (id.startsWith("dx")) return TYPE_DX
            }
        }
        // 兜底二：父节点的下一个兄弟是类型图标
        val siblingSrc = row.nextElementSibling()?.selectFirst("img")?.attr("src")
        if (siblingSrc != null && siblingSrc.isNotEmpty()) {
            return if (siblingSrc.contains("standard")) TYPE_STANDARD else TYPE_DX
        }
        return TYPE_DX
    }

    /** `music_icon_app.png` → `app`；占位图 `music_icon_back.png` 视为无。 */
    private fun musicIconName(src: String): String? {
        val matched = Regex("music_icon_(.+?)\\.png").find(src) ?: return null
        val name = matched.groupValues[1]
        if (name.isEmpty() || name == "back") return null
        // 落雪的 FSType 里 FDX 记作 fsd、FDX+ 记作 fsdp，NET 图标却是 fdx / fdxp
        return when (name) {
            "fdx" -> "fsd"
            "fdxp" -> "fsdp"
            else -> name
        }
    }

    // ==================== 中二节奏 ====================

    /**
     * 解析中二「最佳成绩」页（`record/musicGenre/{basic..ultima}`）。
     *
     * 曲名是页面里唯一的身份信息（NET 不暴露曲目 ID），上传前要用曲库把
     * 曲名映射成落雪的曲目 ID，见 [LxnsScoreMapper]。
     */
    fun parseChuni(html: String): List<NetScore> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val boxes = doc.select("div.musiclist_box")
        if (boxes.isEmpty()) return emptyList()
        val out = ArrayList<NetScore>(boxes.size)
        for (box in boxes) {
            parseChuniRow(box)?.let { out.add(it) }
        }
        return out
    }

    private fun parseChuniRow(box: Element): NetScore? {
        // 难度是类名的最后一项：musiclist_box bg_master
        val classes = box.classNames()
        val levelKey = classes.lastOrNull { it.startsWith("bg_") }?.removePrefix("bg_") ?: return null
        val levelIndex = CHUNI_LEVELS.indexOf(levelKey)
        if (levelIndex < 0) return null

        val title = box.selectFirst(".music_title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        // 分数形如 "1,010,000"
        val scoreText = box.selectFirst(".play_musicdata_highscore")
            ?.selectFirst("span")
            ?.text()
            ?.replace(",", "")
            ?.trim()
            .orEmpty()
        val score = runCatching { scoreText.toInt() }.getOrNull() ?: return null

        return NetScore(
            title = title,
            levelIndex = levelIndex,
            score = score,
            achievements = 0.0,
            dxScore = 0,
            fc = parseChuniFullCombo(box),
            fs = null,
            rate = null,
            songType = null,
        )
    }

    /**
     * 从 `play_musicdata_icon` 里读 FULL COMBO 类型。
     *
     * 判定规则与水鱼 `parse_chuni_music_box` 等价，但**不靠「最后一个图标是否 rank」短路**：
     * 那个写法在「AJC 图标 + 评级图标」同框时会直接放弃，把 AJC 丢掉。
     * 这里改成正向匹配 —— 先找 AJC（图标名里也含 alljustice，必须先判长的），
     * 再找 AJ、FULL COMBO，都没有就是没有 FC（评级图标永远不含这几个词）。
     */
    private fun parseChuniFullCombo(box: Element): String? {
        val iconBox = box.selectFirst(".play_musicdata_icon") ?: return null
        val imgs = iconBox.select("img")
        if (imgs.isEmpty()) return null
        val srcs = imgs.map { it.attr("src") }
        if (srcs.any { it.contains("alljusticecritical") }) return "alljusticecritical"
        if (srcs.any { it.contains("alljustice") }) return "alljustice"
        if (srcs.any { it.contains("fullcombo") }) return "fullcombo"
        return null
    }

    // ==================== 常量 ====================

    const val MAIMAI_GAME = "maimai"
    const val CHUNI_GAME = "chunithm"

    private const val TYPE_STANDARD = "standard"
    private const val TYPE_DX = "dx"

    /** 调试用：把页面里最像「曲名」的几段文字抽出来，方便定位改版。 */
    fun debugTitleSample(html: String, limit: Int = 5): String {
        return runCatching {
            val doc: Document = Jsoup.parse(html)
            val titles = doc.select(".music_title, .music_name_block")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .take(limit)
            if (titles.isEmpty()) "(未找到曲名节点)" else titles.joinToString(" / ")
        }.getOrDefault("(解析失败)")
    }
}
