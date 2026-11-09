// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.agent

/**
 * 舞萌 DX 谱面（Simai 文本）的纯算法层：URL 构造、难度段切分、落雪显示写法还原、摘要。
 *
 * ## 落雪谱面预览页的数据流（读其前端 bundle 实证）
 * 页面 `https://maimai.lxns.net/chart?chart_id={id}&difficulty={n}` 加载后
 * `fetch("https://assets2.lxns.net/maimai/chart/{id}.txt")` 取回**整曲一份、含全部难度**
 * 的原始 Simai 文本（store 里的 `rawSimaiText`）。「Simai 语句」折叠卡的难度选项就是
 * 用 `/^&inote_(\d+)=/gim` 扫出来的，选中难度后把那一段渲染成语句行。
 *
 * - URL 的 `difficulty` 0..4 = BASIC..Re:MASTER，页面内部 **+2** 才是 `&inote_N=` 编号
 *   （`N = parseInt(difficulty) + 2`），即 2=BASIC、3=ADVANCED、4=EXPERT、
 *   5=MASTER、6=Re:MASTER；宴谱（id ≥ 100000）URL 省略 difficulty，页面取最大编号。
 * - 渲染时把标准 Simai 的 `(174){16}` 拆成 `BPM 174 | 1/16`、把 `{16}` 拆成 `1/16`
 *   的**标记行**，音符本体写法完全不动 —— 这正是用户说的「BPM/拍数写法不太一样」。
 *
 * 因此读谱面的正确姿势是：拿原始 txt → 按 `&inote_N=` 切出目标难度 → 拼回单难度
 * 标准 Simai（元数据保留）；只有退化到扒 DOM 显示文本时，才需要 [normalizeDisplayed]
 * 把标记行还原成 `(bpm){divisor}`。
 */
object SimaiChart {

    private const val PREVIEW_BASE = "https://maimai.lxns.net/chart?chart_id="
    private const val CDN_BASE = "https://assets2.lxns.net/maimai/chart/"
    private const val UTAGE_ID_MIN = 100_000

    /** 谱面预览页 URL；宴谱省略 difficulty，由页面自行选谱。 */
    fun previewUrl(songId: Int, difficulty: Int?): String = buildString {
        append(PREVIEW_BASE).append(songId)
        if (difficulty != null && difficulty in 0..4 && songId < UTAGE_ID_MIN) {
            append("&difficulty=").append(difficulty)
        }
    }

    /** 落雪 CDN 上整曲 Simai 文本的地址（页面自己也 fetch 这一份）。 */
    fun cdnUrl(songId: Int): String = "$CDN_BASE$songId.txt"

    /** URL 难度索引 → 页面内部的 `&inote_N=` 编号。 */
    fun inoteIndex(difficulty: Int): Int = difficulty.coerceIn(0, 4) + 2

    /** URL 难度索引的中文名（宴谱 / 未指定时由页面选最高难度）。 */
    fun difficultyName(difficulty: Int?): String = when (difficulty) {
        0 -> "BASIC"
        1 -> "ADVANCED"
        2 -> "EXPERT"
        3 -> "MASTER"
        4 -> "Re:MASTER"
        else -> "宴谱 / 页面默认最高难度"
    }

    /** 原始文本里出现过的 `&inote_` 编号（升序）。 */
    fun availableInotes(raw: String): List<Int> =
        Regex("""(?mi)^\s*&inote_(\d+)\s*=""").findAll(raw)
            .map { it.groupValues[1].toInt() }
            .distinct()
            .sorted()
            .toList()

    /**
     * 决定最终读哪个 `&inote_`：显式难度走 [inoteIndex]，宴谱 / 未指定取文件里最大编号。
     * 找不到任何难度声明时返回 -1。
     */
    fun resolveInote(raw: String, difficulty: Int?): Int {
        val available = availableInotes(raw)
        if (difficulty != null) return inoteIndex(difficulty)
        return available.lastOrNull() ?: -1
    }

    /**
     * 从整曲 Simai 里切出**单个难度**的标准 Simai 文本。
     *
     * 保留所有非 `&inote_` 元数据行（`&title` / `&artist` / `&bpm` / `&first` /
     * `&lv_N` / `&des_N` 等），因为标准 Simai 文件本来就带这些头；再拼上目标难度段，
     * 其余难度整段丢掉。这样交给模型的是一份可以直接喂给 simai 解析器/播放器的谱面。
     *
     * @return 目标难度不存在时返回 null（调用方据此列出可用难度）
     */
    fun extract(raw: String, inote: Int): String? {
        if (raw.isBlank() || inote < 1) return null
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val metadata = mutableListOf<String>()
        val target = mutableListOf<String>()
        var inTarget = false
        var found = false
        val inoteRe = Regex("""^\s*&inote_(\d+)\s*=""")

        for (line in lines) {
            val m = inoteRe.find(line)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                inTarget = n == inote
                if (inTarget) {
                    found = true
                    // 保留原声明行（含它后面可能直接跟着的第一段谱面）
                    target += line.trimEnd()
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.startsWith("&")) {
                // 新的元数据行结束当前难度段；其它元数据行收进头部
                inTarget = false
                metadata += trimmed
                continue
            }
            if (inTarget) {
                // 谱面正文里的换行/缩进是合法的（simai 允许排版空白），原样保留
                target += line.trimEnd()
            }
        }
        if (!found) return null
        val body = target.joinToString("\n").trimEnd()
        if (metadata.isEmpty()) return body
        return metadata.distinct().joinToString("\n") + "\n" + body
    }

    /**
     * 把落雪「Simai 语句」栏的显示文本还原成标准 Simai。
     *
     * 页面把 `(bpm){divisor}` 渲染成独占一行的 `BPM 174 | 1/16`、把 `{divisor}`
     * 渲染成 `1/16`、把 `(bpm)` 渲染成 `BPM 174`；音符行完全不变。
     * 虚拟列表只能拿到可见行时走这条兜底路径，把标记行翻译回去。
     */
    private val bpmMarker = Regex(
        """^\s*BPM\s+(\d+(?:\.\d+)?)\s*(?:\|\s*1\s*/\s*(\d+(?:\.\d+)?)\s*)?\s*$""",
    )
    private val divisorMarker = Regex("""^\s*1\s*/\s*(\d+(?:\.\d+)?)\s*$""")

    fun normalizeDisplayed(text: String): String = text.lineSequence().joinToString("\n") { line ->
        val bpm = bpmMarker.matchEntire(line)
        when {
            bpm != null -> {
                val bpmValue = bpm.groupValues[1]
                val divisor = bpm.groupValues.getOrNull(2).orEmpty()
                if (divisor.isEmpty()) "($bpmValue)" else "($bpmValue){$divisor}"
            }
            divisorMarker.matches(line) -> "{${divisorMarker.matchEntire(line)!!.groupValues[1]}}"
            else -> line
        }
    }

    /**
     * 给模型看的谱面摘要。只做**字符串级的可靠统计**，不去猜音符语义
     * （精确物量要跑完整 simai 解析器，这里不冒充）。BPM / 拍号变化点会列全，
     * 模型回答「变速在哪」时用得上。
     */
    fun summarize(text: String): String {
        val bpms = Regex("""\((\d+(?:\.\d+)?)\)""").findAll(text)
            .map { it.groupValues[1] }.distinct().toList()
        val divisors = Regex("""\{(\d+(?:\.\d+)?)\}""").findAll(text)
            .map { it.groupValues[1] }.distinct().toList()
        val commas = text.count { it == ',' }
        val each = text.count { it == '/' }
        val slides = Regex("""[-<>^vpqswzV]\[""").findAll(text).count()
        val breaks = Regex("""\d[bhx@\$?!/,E]""").findAll(text).count()
        return buildString {
            append("· 谱面文本长度：${text.length} 字符；逗号（时间步进）约 $commas 个\n")
            append("· BPM 声明：${if (bpms.isEmpty()) "仅元数据 / 未内联" else bpms.joinToString("、")}\n")
            append("· 拍数（1/N）声明：${if (divisors.isEmpty()) "默认 4" else divisors.joinToString("、")}\n")
            append("· 粗统计（按记号出现次数，非精确物量）：EACH 分隔符 $each 个、" +
                "SLIDE 时长记号约 $slides 处、含 b 的 BREAK 类记号约 $breaks 处\n")
            append("· 提示：精确 TAP/HOLD/SLIDE/TOUCH 物量需逐音符解析，回答数量问题时" +
                "请基于谱面文本谨慎推断，不要编造确定数字。")
        }
    }

    /**
     * 注入到预览页里、在**页面上下文**取回原始 Simai 文本的 JS。
     *
     * 页面自己就是从 assets2.lxns.net fetch 这份 txt，所以同源策略/跨域头一定通；
     * 由 WebView 发起而非 App 直连，也保留了页面可能带的鉴权 / referer 语义。
     * 结果写到 `window.__kibaSimai`，Kotlin 侧轮询 [stateJs] 判断完成后再取整文。
     */
    fun fetchJs(cdn: String): String =
        "window.__kibaSimaiState='pending'; window.__kibaSimai='';" +
            "fetch(${jsQuote(cdn)})" +
            ".then(function(r){ if(!r.ok) throw new Error('HTTP '+r.status); return r.text(); })" +
            ".then(function(t){ window.__kibaSimai=t; window.__kibaSimaiState='done'; })" +
            ".catch(function(e){ window.__kibaSimaiState='error:'+e; }); 'started';"

    /** 纯 Kotlin 的 JS 字符串字面量转义（不依赖 android.jar 的 org.json，便于本地单测）。 */
    private fun jsQuote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('"')
    }

    /** 轮询用的状态 JS：返回 `state:length`。 */
    const val STATE_JS = "window.__kibaSimaiState + ':' + (window.__kibaSimai||'').length"

    /** 取整文用的 JS。 */
    const val RAW_JS = "window.__kibaSimai || ''"
}
