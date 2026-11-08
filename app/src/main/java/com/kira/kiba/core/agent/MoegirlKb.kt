// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.agent

import android.content.Context
import org.json.JSONObject

/**
 * 萌娘百科内置知识库（舞萌DX / 舞萌系列 / CHUNITHM 三个条目）。
 *
 * 为什么内置而不是每次联网：Agent 被问「定数怎么算 Rating」「LEX 是什么档」这类
 * 机制类问题时，模型凭空编造的概率很高；而萌娘百科对国内访问并不稳定（Cloudflare），
 * 每次问了再去抓网页既慢又可能失败。所以把条目正文固化成 assets，本地检索后喂给模型。
 *
 * 更新方式：仓库内 `tools/update_moegirl_kb.py` 抓取/转换页面 → 重写 `assets/kb` 下的
 * 条目 md 与 `manifest.json`（含 sha256 与抓取时间），App 重启即生效，无需改代码。
 *
 * 检索是纯关键词打分（不做 embedding）：中文按 bigram 切，英文/数字按词切，
 * 标题命中权重高。对「三篇文档、几十个小节」这个量级足够，而且零依赖、零延迟。
 */
object MoegirlKb {

    /** 知识库里的一个条目（对应一个 md 文件）。 */
    data class Doc(
        /** 展示名，例：舞萌DX */
        val title: String,
        /** 原文链接（回复里会附上，方便用户自己去看完整条目） */
        val source: String,
        /** 文件内的小节 */
        val sections: List<Section>,
    )

    /** 一个可检索的小节（以 Markdown 的 `##` 标题切分）。 */
    data class Section(
        val docTitle: String,
        val source: String,
        val heading: String,
        val body: String,
    )

    /** manifest.json 的运行时视图（版本号 / 抓取日期展示用）。 */
    data class Manifest(
        val version: Int = 0,
        val generatedAt: String = "",
        val docCount: Int = 0,
    )

    private const val DIR = "kb"
    private const val MANIFEST = "$DIR/manifest.json"

    /**
     * 单次检索回灌给模型的最大字符数：太大挤占上下文，太小又答不全。
     * v2 知识库从 35 节扩到 60 节、单节正文也更长了，原 4500 常常只够装下 2 节就被截断，
     * 因此放宽到 7000；具体装几节仍由 search() 的 limit 与这里的字符上限共同决定。
     */
    private const val MAX_RETURN_CHARS = 7000

    @Volatile
    private var docs: List<Doc>? = null

    @Volatile
    private var manifest: Manifest = Manifest()

    /** 载入状态（失败也不会重复读盘）。 */
    @Volatile
    private var attempted = false

    private fun ensureLoaded(context: Context) {
        if (attempted) return
        synchronized(this) {
            if (attempted) return
            attempted = true
            val app = context.applicationContext
            manifest = runCatching { readManifest(app) }.getOrDefault(Manifest())
            docs = runCatching {
                // 条目清单以 manifest 为准，新增条目只要在 manifest 里登记即可
                val names = runCatching {
                    val root = JSONObject(app.assets.open(MANIFEST).bufferedReader().use { it.readText() })
                    val arr = root.optJSONArray("docs") ?: return@runCatching emptyList<String>()
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("file")?.takeIf { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
                val files = names.ifEmpty { listOf("maimai_dx.md", "maimai_series.md", "chunithm.md") }
                files.mapNotNull { file -> runCatching { readDoc(app, file) }.getOrNull() }
            }.getOrDefault(emptyList())
        }
    }

    private fun readManifest(context: Context): Manifest {
        val text = context.assets.open(MANIFEST).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        return Manifest(
            version = root.optInt("version", 0),
            generatedAt = root.optString("generatedAt"),
            docCount = root.optJSONArray("docs")?.length() ?: 0,
        )
    }

    private fun readDoc(context: Context, file: String): Doc {
        val text = context.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
        // 文件首部登记来源：`> 来源：萌娘百科《X》 https://...`
        val source = Regex("""https?://\S+""").find(text)?.value.orEmpty()
        val title = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(text)
            ?.groupValues?.get(1)?.trim()
            ?: file.substringBeforeLast('.')

        val sections = mutableListOf<Section>()
        var heading = "概述"
        val buffer = StringBuilder()
        fun flush() {
            val body = buffer.toString().trim()
            if (body.isNotBlank()) {
                sections += Section(docTitle = title, source = source, heading = heading, body = body)
            }
            buffer.setLength(0)
        }
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("## ") -> {
                    flush()
                    heading = line.removePrefix("## ").trim()
                }
                line.startsWith("# ") -> Unit // 一级标题只用来取条目名
                else -> buffer.appendLine(line)
            }
        }
        flush()
        return Doc(title = title, source = source, sections = sections)
    }

    /** 知识库概要（给「知识库更新到哪一版」这类问题用）。 */
    fun status(context: Context): String {
        ensureLoaded(context)
        val list = docs.orEmpty()
        if (list.isEmpty()) return "本地知识库为空（assets/kb 未打包或读取失败）。"
        val detail = list.joinToString("\n") { doc ->
            "· ${doc.title}：${doc.sections.size} 节" + if (doc.source.isNotBlank()) "（${doc.source}）" else ""
        }
        return buildString {
            append("本地知识库 v${manifest.version}")
            if (manifest.generatedAt.isNotBlank()) append("，抓取于 ${manifest.generatedAt}")
            append("，共 ${list.size} 个条目：\n")
            append(detail)
            append("\n这些内容随 APK 固化；要跟进最新页面需要重新跑 archive 工具并重新打包。")
        }
    }

    /**
     * 检索并拼成给模型看的上下文。
     *
     * 返回空串 = 没命中（调用方据此告诉模型「知识库里没有，建议联网或让用户提供」），
     * 而不是硬塞一段不相关的正文诱导模型瞎编。
     *
     * v2 起默认取 6 节（原 4 节）：机制类问题常同时涉及「公式 + 评级区间 + 口径冲突」，
     * 只给 4 节容易漏掉互相矛盾的那条注解；最终长度仍由 MAX_RETURN_CHARS 兜底。
     */
    fun search(context: Context, query: String, limit: Int = 6): String {
        ensureLoaded(context)
        val all = docs.orEmpty().flatMap { it.sections }
        if (all.isEmpty() || query.isBlank()) return ""

        val terms = tokenize(query)
        if (terms.isEmpty()) return ""

        val scored = all.map { section ->
            val headingLower = section.heading.lowercase()
            val bodyLower = section.body.lowercase()
            var score = 0.0
            terms.forEach { term ->
                if (headingLower.contains(term)) score += 6.0
                // 正文里出现次数有上限，避免长小节靠重复词刷分
                val hits = countOccurrences(bodyLower, term).coerceAtMost(6)
                score += hits * 1.0
            }
            section to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        if (scored.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("【本地知识库检索结果】（萌娘百科条目，回答时可直接引用，不必再联网）\n")
        var used = 0
        scored.take(limit).forEach { (section, _) ->
            val block = buildString {
                append("\n### ${section.docTitle} · ${section.heading}\n")
                append(section.body.trim())
                append('\n')
                if (section.source.isNotBlank()) append("（来源：${section.source}）\n")
            }
            if (used + block.length > MAX_RETURN_CHARS) {
                val room = MAX_RETURN_CHARS - used
                if (room > 200) sb.append(block.take(room)).append("\n…（以下截断）\n")
                return@forEach
            }
            sb.append(block)
            used += block.length
        }
        return sb.toString()
    }

    // ── 检索工具 ──

    /**
     * 切词：
     * - 拉丁字母/数字按词（长度 ≥2 才有意义，`ss`/`op` 这类两字母缩写保留）；
     * - 中文按 bigram（「定数怎么算」→ 定数 / 数怎 / 怎么 / 么算），
     *   并额外加入整段（长度 ≤6 时），让短查询也能整词命中。
     */
    private fun tokenize(query: String): List<String> {
        val cleaned = query.lowercase().replace(Regex("""[，。！？、；：「」（）()\[\]{}"',.!?;:]+"""), " ")
        val out = LinkedHashSet<String>()
        cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { chunk ->
            val latin = Regex("""[a-z0-9+#.]{2,}""").findAll(chunk).map { it.value }
            latin.forEach { out += it }
            val cjk = chunk.filter { it in '\u4e00'..'\u9fff' }
            if (cjk.length >= 2) {
                if (cjk.length <= 6) out += cjk
                for (i in 0 until cjk.length - 1) out += cjk.substring(i, i + 2)
            } else if (cjk.length == 1) {
                out += cjk
            }
        }
        return out.toList()
    }

    private fun countOccurrences(text: String, term: String): Int {
        if (term.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(term)
        while (index >= 0) {
            count++
            index = text.indexOf(term, index + term.length)
        }
        return count
    }
}
