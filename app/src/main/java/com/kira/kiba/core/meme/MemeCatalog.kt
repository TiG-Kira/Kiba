// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.meme

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 表情包模板目录（数据来自 [MemeCrafters/meme-generator](https://github.com/MemeCrafters/meme-generator)）。
 *
 * `tools/meme_export.py` 把上游模板编译成 `assets/meme/templates.json`：
 * - 静态模板：背景图 + 文字/图片图层（[MemeRenderer]）；
 * - 动图模板：`tools/meme_anim.py` 在导出时真实执行上游函数、录下逐帧操作，
 *   生成 [MemeAnimScript]（[MemeAnimRenderer] 重放，[GifEncoder] 出 GIF）。
 *
 * 本地引擎**确实做不了**的模板（透视变换 / 逐像素运算 / 无静态帧资源）在导出阶段
 * 就被剔除，目录里只保留真能生成的，不再列一堆灰显项。
 *
 * 解析用 org.json 手读，不走 Gson 反射 —— release 包开 R8 时 DTO 不会被误删。
 */
object MemeCatalog {

    private const val FILE = "meme/templates.json"

    @Volatile
    private var cached: List<MemeTemplate>? = null

    /** 全部可生成模板（不支持的已在导出阶段剔除）。 */
    fun all(context: Context): List<MemeTemplate> = load(context)

    /** 当前能离线生成的模板。 */
    fun renderable(context: Context): List<MemeTemplate> = load(context).filter { it.renderable }

    fun find(context: Context, key: String): MemeTemplate? =
        load(context).firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }

    /**
     * 模板检索：key 精确命中优先，其次别名 / 名称包含，最后按关键词打分。
     * 用户手输「低情商」「gocho」「喜报」这类中文词也要能选中模板。
     */
    fun search(context: Context, query: String, onlyRenderable: Boolean = true): List<MemeTemplate> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return if (onlyRenderable) renderable(context) else load(context)
        val source = if (onlyRenderable) renderable(context) else load(context)
        return source.mapNotNull { t ->
            val score = when {
                t.key.equals(q, ignoreCase = true) -> 1000
                t.name.equals(q, ignoreCase = true) -> 900
                t.aliases.any { it.equals(q, ignoreCase = true) } -> 800
                t.name.contains(q, ignoreCase = true) -> 600
                t.aliases.any { it.contains(q, ignoreCase = true) } -> 400
                t.name.length >= 2 && q.contains(t.name.lowercase()) -> 350
                t.aliases.any { it.length >= 2 && q.contains(it.lowercase()) } -> 300
                t.key.contains(q, ignoreCase = true) -> 200
                else -> 0
            }
            if (score > 0) score to t else null
        }.sortedByDescending { it.first }.map { it.second }
    }

    /** 给 LLM 的紧凑模板清单：只列可生成的，按「名称 / 别名 / 需要几句」压缩。 */
    fun usageHint(context: Context, limit: Int = 60): String = buildString {
        val list = renderable(context)
        append("可生成模板共 ${list.size} 个（静态图 + 多帧动图，都是本地离线合成）。")
        append("常用：")
        append(list.take(limit).joinToString("、") { "${it.name}(${it.key})" })
        append("。传模板 key 或中文名/别名均可；不确定时可把 template 传 list 获取完整清单。")
    }

    /**
     * 生成给模型看的完整清单（按每行一个，含文字槽数与是否需要用户图片）。
     * 只在模型主动请求 list 时才拼，平时不进 system prompt，避免挤占上下文。
     */
    fun listText(templates: List<MemeTemplate>): String = buildString {
        append("▾ 可生成的模板（${templates.size} 个）\n")
        templates.forEach { t ->
            append("· ${t.name} [${t.key}]")
            if (t.textSlots > 0) append(" · ${t.textSlots} 句文字")
            if (t.requiredImages > 0) append(" · 需要 ${t.requiredImages} 张图片（Agent 无法自动提供，需用户手动触发）")
            if (t.aliases.size > 1) append(" · 别名：${t.aliases.take(3).joinToString("/")}")
            append("\n")
        }
        append("\n用法：功能菜单 → 娱乐功能 → 表情包生成器，选模板后填文字即可。")
    }

    private fun load(context: Context): List<MemeTemplate> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val text = context.applicationContext.assets.open(FILE)
                    .bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("templates")
                (0 until arr.length())
                    .map { i -> arr.getJSONObject(i).toTemplate() }
                    .filter { it.renderable }
            }.getOrDefault(emptyList()).also { cached = it }
        }
    }

    private fun JSONObject.toTemplate(): MemeTemplate {
        fun strings(name: String): List<String> {
            val a = optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optString(i, "").takeIf { it.isNotBlank() } }
        }
        val layers = (optJSONArray("layers") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).toTextLayer() }
        }
        val imageLayers = (optJSONArray("imageLayers") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).toImageLayer() }
        }
        return MemeTemplate(
            key = optString("key"),
            name = optString("name").ifBlank { optString("key") },
            aliases = strings("aliases"),
            minTexts = optInt("minTexts"),
            maxTexts = optInt("maxTexts"),
            defaultTexts = strings("defaultTexts"),
            needsImage = optBoolean("needsImage"),
            animated = optBoolean("animated"),
            renderable = optBoolean("renderable"),
            renderer = optString("renderer").ifBlank { "generic" },
            background = optString("background"),
            width = optInt("width"),
            height = optInt("height"),
            layers = layers,
            imageLayers = imageLayers,
            anim = optJSONObject("anim")?.let { MemeAnimRenderer.parseScript(it) },
        )
    }

    private fun JSONObject.toTextLayer(): MemeTextLayer {
        val rect = (optJSONArray("rect") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i -> a.optInt(i) }
        }
        return MemeTextLayer(
            text = optString("text"),
            rect = rect,
            maxFont = optDouble("maxFont", 48.0).toFloat(),
            minFont = optDouble("minFont", 16.0).toFloat(),
            wrap = optBoolean("wrap"),
            fill = optString("fill").ifBlank { "#000000" },
            strokeFill = optString("strokeFill").ifBlank { "#000000" },
            strokeRatio = optDouble("strokeRatio", 0.0).toFloat(),
            align = optString("align").ifBlank { "center" },
            valign = optString("valign").ifBlank { "center" },
            bold = optBoolean("bold"),
            fontSize = optDouble("fontSize", 0.0).toFloat(),
            rotate = optDouble("rotate", 0.0).toFloat(),
        )
    }

    private fun JSONObject.toImageLayer(): MemeImageLayer = MemeImageLayer(
        index = optInt("index"),
        w = optInt("w"),
        h = optInt("h"),
        rotate = optDouble("rotate", 0.0).toFloat(),
        circle = optBoolean("circle"),
        square = optBoolean("square"),
        x = optInt("x"),
        y = optInt("y"),
    )
}

/** 一个表情包模板。 */
data class MemeTemplate(
    val key: String,
    val name: String,
    val aliases: List<String>,
    val minTexts: Int,
    val maxTexts: Int,
    val defaultTexts: List<String>,
    val needsImage: Boolean,
    val animated: Boolean,
    val renderable: Boolean,
    val renderer: String,
    val background: String,
    val width: Int,
    val height: Int,
    val layers: List<MemeTextLayer>,
    val imageLayers: List<MemeImageLayer>,
    /** 动图脚本；静态模板为 null。 */
    val anim: MemeAnimScript? = null,
) {
    /** 需要几张用户图片（静态图层 / 动图脚本里最大的 index + 1）。 */
    val requiredImages: Int
        get() {
            val fromLayers = imageLayers.maxOfOrNull { it.index + 1 } ?: 0
            val fromAnim = anim?.frames
                ?.flatMap { f -> f.ops.mapNotNull { op -> (op.src as? AnimSrc.User)?.index } }
                ?.maxOrNull()?.plus(1) ?: 0
            return maxOf(fromLayers, fromAnim)
        }

    /** 需要几段文字：图层 / 动图脚本里出现过的占位符与元数据取最大。 */
    val textSlots: Int by lazy {
        val fromLayers = layers.flatMap { l ->
            Regex("\\{(\\d+)\\}").findAll(l.text).map { it.groupValues[1].toInt() + 1 }.toList()
        }.maxOrNull() ?: 0
        val fromAnim = anim?.frames
            ?.flatMap { f -> f.ops.mapNotNull { op -> (op.src as? AnimSrc.Text)?.index } }
            ?.maxOrNull()?.plus(1) ?: 0
        maxOf(minTexts, maxTexts.coerceAtMost(8), fromLayers, fromAnim)
    }

    /** 需要用户提供图片、而 Agent 无法自动准备时给用户的一句说明。 */
    val unavailableReason: String
        get() = "这个模板需要你先选图片" 
}

/** 一行文字的定义：矩形区域内自动换行、自动缩字号。 */
data class MemeTextLayer(
    val text: String,
    /** 4 个数 = 文本框 (l,t,r,b)；2 个数 = 直接在该点绘制（固定字号）。 */
    val rect: List<Int>,
    val maxFont: Float,
    val minFont: Float,
    val wrap: Boolean,
    val fill: String,
    val strokeFill: String,
    val strokeRatio: Float,
    val align: String,
    val valign: String,
    val bold: Boolean,
    val fontSize: Float,
    val rotate: Float,
)

/** 把用户选的图片贴到背景上的一层。 */
data class MemeImageLayer(
    val index: Int,
    val w: Int,
    val h: Int,
    val rotate: Float,
    val circle: Boolean,
    val square: Boolean,
    val x: Int,
    val y: Int,
)
