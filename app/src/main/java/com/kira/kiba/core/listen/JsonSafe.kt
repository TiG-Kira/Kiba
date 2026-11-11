package com.kira.kiba.core.listen

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 宽松读 JSON 的小工具（`core.listen` 内部用）。
 *
 * ## 为什么不用 `gson.fromJson(json, X::class.java)`
 * Gson 反射建实例走的是 **Unsafe 分配 + 逐字段写入**，**完全不执行 Kotlin 构造器**。
 * 所以 `data class X(val summary: String = "")` 里那个 `= ""` 是死的：
 * JSON 缺这个键，字段就是 `null`，而声明上的 non-null 只是编译期承诺，运行时没有任何检查。
 *
 * v3.0.0 的「畅听」闪退就是这么来的 —— v2.5 存的旧歌单没有 `summary` / `reasons`，
 * 读出来是 null，迁移路径上 `legacy.summary.ifBlank {}` 直接 NPE，而且当时
 * 「先置迁移标记再迁移」，崩一次就永久跳过，用户升级后每次进畅听都闪退。
 *
 * 结论：凡是「跨版本读旧 JSON」的 DTO，一律手写逐字段解析，取不到就给兜底值。
 */

/** 取字符串，缺失 / null / 类型不符都给空串。 */
internal fun JsonObject.stringOrEmpty(key: String): String =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull().orEmpty()

/** 取 long，缺失 / null / 类型不符都给 0。 */
internal fun JsonObject.longOrZero(key: String): Long =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: 0L

/** 取数组，缺失 / null / 不是数组都给空列表。 */
internal fun JsonObject.arrayOf(key: String): List<JsonElement> =
    runCatching { get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() }.getOrNull().orEmpty()

/** 取对象，缺失 / null / 不是对象都给 null。 */
internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    runCatching { get(key)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

internal fun JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()

internal fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()
