package com.kira.kiba.core.utils

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

object SpUtil {
    private lateinit var application: Application

    private val userInfoPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREF_USER_INFO)
    }

    private val versionPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREF_VERSION)
    }

    private val songInfoPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREF_SONG_INFO)
    }

    private val searchHistoryPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREF_SEARCH_HISTORY)
    }

    // 常量定义
    private const val PREF_USER_INFO = "userInfo"
    private const val PREF_VERSION = "version"
    private const val PREF_SONG_INFO = "songInfo"
    private const val PREF_SEARCH_HISTORY = "searchHistory"

    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_ACCOUNT_HISTORY = "account_history"
    private const val KEY_LAST_QUERY_LEVEL = "last_query_level"
    private const val KEY_LAST_QUERY_VERSION = "last_query_version"
    private const val KEY_DIVING_FISH_NICKNAME = "diving_fish_nickname"
    private const val KEY_DF_BOUND = "diving_fish_bound"
    private const val KEY_DF_INSTALL_ID = "diving_fish_install_id"
    private const val KEY_DF_NICKNAME_OAUTH = "diving_fish_nickname_oauth"
    private const val KEY_DF_JWT = "diving_fish_jwt"

    private const val KEY_VERSION = "db_version"
    private const val KEY_LAST_UPDATE_TIME = "chart_stats_db_last_update_time"

    private const val KEY_SEARCH_HISTORY = "search_history"

    fun init(appContext: Application) {
        application = appContext
    }

    private fun checkInitialization() {
        if (!::application.isInitialized) {
            throw IllegalStateException("SpUtil is not initialized, please call init() method first")
        }
    }

    private fun getSharedPreferences(name: String): SharedPreferences {
        checkInitialization()
        return application.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    // ================= USER INFO =================

    fun getUserName(): String = userInfoPrefs.getString(KEY_USERNAME, "") ?: ""

    fun getPassword(): String = userInfoPrefs.getString(KEY_PASSWORD, "") ?: ""

    fun getCookie(): String = userInfoPrefs.getString(KEY_COOKIE, "") ?: ""

    fun putLoginInfo(username: String, password: String, cookie: String) {
        userInfoPrefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putString(KEY_COOKIE, cookie)
        }
        saveAccountToHistory(username, password)
    }

    fun saveAccountToHistory(username: String, password: String) {
        val history = getAccountHistory().toMutableList()
        if (history.none { it.first == username }) {
            history.add(Pair(username, password))
            userInfoPrefs.edit { putString(KEY_ACCOUNT_HISTORY, Gson().toJson(history)) }
        }
    }

    fun getAccountHistory(): List<Pair<String, String>> {
        val json = userInfoPrefs.getString(KEY_ACCOUNT_HISTORY, "[]") ?: "[]"
        val type = object : TypeToken<List<Pair<String, String>>>() {}.type
        return Gson().fromJson(json, type)
    }

    fun removeAccount(username: String) {
        val history = getAccountHistory().toMutableList()
        history.removeAll { it.first == username }
        userInfoPrefs.edit { putString(KEY_ACCOUNT_HISTORY, Gson().toJson(history)) }
    }

    fun saveLastQueryLevel(level: Float) {
        userInfoPrefs.edit { putFloat(KEY_LAST_QUERY_LEVEL, level) }
    }

    fun getLastQueryLevel(): Float = userInfoPrefs.getFloat(KEY_LAST_QUERY_LEVEL, 18f)

    fun saveLastQueryVersion(version: Int) {
        userInfoPrefs.edit { putInt(KEY_LAST_QUERY_VERSION, version) }
    }

    fun getLastQueryVersion(): Int = userInfoPrefs.getInt(KEY_LAST_QUERY_VERSION, 0)

    fun saveDivingFishNickname(nickname: String) {
        userInfoPrefs.edit { putString(KEY_DIVING_FISH_NICKNAME, nickname) }
    }

    fun getDivingFishNickname(): String {
        return userInfoPrefs.getString(KEY_DIVING_FISH_NICKNAME, "") ?: ""
    }

    // ================= DIVINGFISH OAUTH 绑定 =================

    /** 是否已完成 OAuth 绑定（水鱼服务端持有授权关系）。 */
    fun isDivingFishBound(): Boolean = userInfoPrefs.getBoolean(KEY_DF_BOUND, false)

    fun setDivingFishBound(bound: Boolean) {
        userInfoPrefs.edit { putBoolean(KEY_DF_BOUND, bound) }
    }

    /** 安装级随机 ID：OAuth subject_ref 的盐值来源，首次生成后固定。 */
    fun getDivingFishInstallId(): String {
        var id = userInfoPrefs.getString(KEY_DF_INSTALL_ID, "") ?: ""
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            userInfoPrefs.edit { putString(KEY_DF_INSTALL_ID, id) }
        }
        return id
    }

    fun saveDivingFishOAuthNickname(nickname: String) {
        userInfoPrefs.edit { putString(KEY_DF_NICKNAME_OAUTH, nickname) }
    }

    fun getDivingFishOAuthNickname(): String =
        userInfoPrefs.getString(KEY_DF_NICKNAME_OAUTH, "") ?: ""

    // ================= DIVINGFISH 原版账号密码登录 =================

    /**
     * 原版（MaimaiData）登录方式拿到的 jwt_token：等价于用户口令的长效凭证，
     * 随请求以 `Cookie: jwt_token=xxx` 发送。仅存本机私有目录，不随备份外泄。
     */
    fun saveDivingFishJwt(jwt: String) {
        userInfoPrefs.edit { putString(KEY_DF_JWT, jwt) }
    }

    fun getDivingFishJwt(): String = userInfoPrefs.getString(KEY_DF_JWT, "") ?: ""

    /** 任一通道有效即视为已登录：OAuth 绑定（KEY_DF_BOUND）或原版 jwt。 */
    fun isDivingFishLoggedIn(): Boolean = isDivingFishBound() || getDivingFishJwt().isNotBlank()

    // ================= SONG INFO =================

    fun isFavorite(id: String): Boolean = songInfoPrefs.getBoolean(id, false)

    fun setFavorite(id: String, isFav: Boolean) {
        songInfoPrefs.edit { putBoolean(id, isFav) }
    }

    fun getFavIds(): List<String> {
        return songInfoPrefs.all
            .filter { it.value == true }
            .keys.toList()
    }

    // ================= VERSION INFO =================

    fun getDataVersion(): String = versionPrefs.getString(KEY_VERSION, "0") ?: "0"

    fun setDataVersion(version: String) {
        versionPrefs.edit { putString(KEY_VERSION, version) }
    }

    fun saveLastUpdateChartStats(time: Long) {
        versionPrefs.edit { putLong(KEY_LAST_UPDATE_TIME, time) }
    }

    fun getLastUpdateChartStats(): Long = versionPrefs.getLong(KEY_LAST_UPDATE_TIME, 0)

    // ================= SEARCH HISTORY =================

    fun saveSearchHistory(query: String) {
        val history = getSearchHistory().toMutableList()
        if (history.contains(query)) {
            history.remove(query) // 如果已存在，先移除
        }
        history.add(0, query) // 新记录添加到最前面
        if (history.size > 30) history.removeAt(history.lastIndex) // 限制最多存储 30 条记录
        searchHistoryPrefs.edit { putString(KEY_SEARCH_HISTORY, Gson().toJson(history)) }
    }

    fun getSearchHistory(): List<String> {
        val json = searchHistoryPrefs.getString(KEY_SEARCH_HISTORY, "[]") ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(json, type)
    }

    fun clearSearchHistory() {
        searchHistoryPrefs.edit { clear() }
    }

    // ================= LLM（Agent 模型配置）=================

    private const val KEY_LLM_ENABLED = "llm_enabled"
    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_LLM_TEMPERATURE = "llm_temperature"
    private const val KEY_LLM_SYSTEM_PROMPT = "llm_system_prompt"
    private const val KEY_LLM_MAX_TOKENS = "llm_max_tokens"

    const val LLM_DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val LLM_DEFAULT_MODEL = "gpt-4o-mini"
    const val LLM_DEFAULT_TEMPERATURE = 0.7f
    const val LLM_DEFAULT_MAX_TOKENS = 1024
    const val LLM_DEFAULT_SYSTEM_PROMPT =
        "你是 Kiba，舞萌（maimai DX）玩家的 AI 助手。回答简洁、准确，优先使用中文。" +
            "涉及玩家本人的成绩、Best 50、Rating 时，使用工具返回的本地数据，不要编造。" +
            "涉及舞萌机制（Rating 计算、定数、DX 分数）时按官方口径说明。"

    fun isLlmEnabled(): Boolean = userInfoPrefs.getBoolean(KEY_LLM_ENABLED, false)

    fun setLlmEnabled(enabled: Boolean) {
        userInfoPrefs.edit { putBoolean(KEY_LLM_ENABLED, enabled) }
    }

    fun getLlmBaseUrl(): String =
        userInfoPrefs.getString(KEY_LLM_BASE_URL, LLM_DEFAULT_BASE_URL) ?: LLM_DEFAULT_BASE_URL

    fun setLlmBaseUrl(url: String) {
        userInfoPrefs.edit { putString(KEY_LLM_BASE_URL, url.trim()) }
    }

    fun getLlmApiKey(): String = userInfoPrefs.getString(KEY_LLM_API_KEY, "") ?: ""

    fun setLlmApiKey(key: String) {
        userInfoPrefs.edit { putString(KEY_LLM_API_KEY, key.trim()) }
    }

    fun getLlmModel(): String =
        userInfoPrefs.getString(KEY_LLM_MODEL, LLM_DEFAULT_MODEL) ?: LLM_DEFAULT_MODEL

    fun setLlmModel(model: String) {
        userInfoPrefs.edit { putString(KEY_LLM_MODEL, model.trim()) }
    }

    fun getLlmTemperature(): Float = userInfoPrefs.getFloat(KEY_LLM_TEMPERATURE, LLM_DEFAULT_TEMPERATURE)

    fun setLlmTemperature(temperature: Float) {
        userInfoPrefs.edit { putFloat(KEY_LLM_TEMPERATURE, temperature.coerceIn(0f, 2f)) }
    }

    fun getLlmSystemPrompt(): String =
        userInfoPrefs.getString(KEY_LLM_SYSTEM_PROMPT, LLM_DEFAULT_SYSTEM_PROMPT)
            ?: LLM_DEFAULT_SYSTEM_PROMPT

    fun setLlmSystemPrompt(prompt: String) {
        userInfoPrefs.edit { putString(KEY_LLM_SYSTEM_PROMPT, prompt) }
    }

    fun getLlmMaxTokens(): Int = userInfoPrefs.getInt(KEY_LLM_MAX_TOKENS, LLM_DEFAULT_MAX_TOKENS)

    fun setLlmMaxTokens(tokens: Int) {
        userInfoPrefs.edit { putInt(KEY_LLM_MAX_TOKENS, tokens.coerceIn(64, 8192)) }
    }

    // ================= VPN 成绩更新（机台抓包）=================

    private const val KEY_VPN_DIFFICULTIES = "vpn_difficulties"

    /** 要通过 VPN 方案更新的难度索引集合（空 = 默认全部）。 */
    fun getVpnDifficulties(): Set<Int> {
        val raw = userInfoPrefs.getStringSet(KEY_VPN_DIFFICULTIES, emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setVpnDifficulties(difficulties: Set<Int>) {
        userInfoPrefs.edit { putStringSet(KEY_VPN_DIFFICULTIES, difficulties.map { it.toString() }.toSet()) }
    }

    // ================= 封面源 =================

    private const val KEY_COVER_SOURCE = "cover_source"

    /** 自定义封面图床前缀（留空 = 使用内置候选链）。例：https://xxx/maimai-mobile/img/Music/ */
    fun getCoverSource(): String = userInfoPrefs.getString(KEY_COVER_SOURCE, "") ?: ""

    fun setCoverSource(prefix: String) {
        userInfoPrefs.edit { putString(KEY_COVER_SOURCE, prefix.trim()) }
    }
}
