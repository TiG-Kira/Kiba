package com.kira.kiba.core.maimai

import android.util.Log
import com.kira.kiba.core.account.DivingFishAccount
import com.kira.kiba.core.utils.SpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 机台成绩更新「二维码导入」与「从账号同步」链路。
 *
 * 与 VPN 抓包方案互补：无需 VPN / 机台 WiFi / 微信，只需用户的官方舞萌登录二维码
 * （在「舞萌 DX」公众号长按二维码复制出的 SGWCMAID 串）。整体分两步：
 *
 * 1. 同步进账号：[MaiQrGateway.fetchByQrCode] 把二维码交给解析服务（互操作实现，见
 *    [MaiQrGateway] KDoc），服务端完成「解密二维码 → 拉官方数据 → 转查分器格式」并存入
 *    用户账号云端。前置条件：用户已在「我」页接入账号；
 * 2. 从账号拉取并上传水鱼：[syncGatewayToDivingFish] 调 [MaiQrGateway.pullScores] 拉取
 *    账号云端的同步成绩（查分器通用格式，[parseGatewayRecord] 宽容解析），本地曲库补
 *    title/type 后，按分级凭证（见 [resolveUploadCredential]）POST 水鱼
 *    /player/update_records。
 *
 * 二维码导入 = 1 + 2 一条龙；账号页的「手动同步」= 单独执行 2（不扫二维码也能把账号
 * 云端已有数据拉到水鱼）。每次成功拉取都会刷新「最近同步时间」（[lastSyncAt]）。
 *
 * 隐私铁律：二维码字符串只作为**函数参数**在内存中流转，任何环节都不写 SP / 日志明文 /
 * 数据库；更新完成后调用方清空输入框状态，二维码数据即随 GC 丢弃（「用完即毁」）。
 * 水鱼登录态非必须：有账号密码 jwt 时会作为 dfCode 传给解析服务（服务端可能用于附加
 * 校验），OAuth 短 token 不传。
 */
object QrScoreImport {

    private const val TAG = "QrScoreImport"

    private const val DF_LEGACY_API = "https://www.diving-fish.com/api/maimaidxprober"

    /** 水鱼 OAuth API（Bearer 鉴权，带 write scope 即可写成绩）。 */
    private const val DF_OAUTH_API = "https://maimai.diving-fish.com/api/maimaidxprober"

    /**
     * 浏览器 UA。水鱼部分接口对请求来源有环境校验，OkHttp 默认 UA（okhttp/4.x）可能被
     * 直接断连。统一伪装成 Android Chrome。
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build(),
            )
        }
        .build()

    // ── DTO ──

    /** 水鱼 update_records 单条记录格式。 */
    @Serializable
    data class DivingFishRecord(
        val achievements: Double,
        val dxScore: Long,
        val fc: String,
        val fs: String,
        @SerialName("level_index") val levelIndex: Int,
        val musicId: Int,
        val playCount: Int,
        val type: String,
        val title: String,
    )

    @Serializable
    data class UpdateResponse(val message: String? = null)

    @Serializable
    private data class ImportTokenEnvelope(val token: String? = null)

    /** 本地曲库元信息（补 title / type 用）。 */
    data class SongMeta(val title: String, val type: String)

    /** 导入结果摘要（不含任何二维码数据）。 */
    data class QrImportSummary(
        val nickname: String,
        val officialUserId: Long,
        val recordCount: Int,
        val serverMessage: String,
    )

    // ── 纯逻辑：宽容解析账号同步数据（供单测） ──

    /** comboStatus → 水鱼 fc 字符串。 */
    fun fcLabel(comboStatus: Int): String = when (comboStatus) {
        1 -> "fc"
        2 -> "fcp"
        3 -> "ap"
        4 -> "app"
        else -> ""
    }

    /** syncStatus → 水鱼 fs 字符串。 */
    fun fsLabel(syncStatus: Int): String = when (syncStatus) {
        1 -> "fs"
        2 -> "fsp"
        3 -> "fdx"
        4 -> "fdxp"
        5 -> "sync"
        else -> ""
    }

    /**
     * 从用户粘贴的文本中提取 SGWCMAID 串：
     * 容忍前后杂文/换行，从「SGWCMAID」出现处截取到首个空白符。
     */
    fun extractQrCode(raw: String): String? {
        val idx = raw.indexOf("SGWCMAID")
        if (idx < 0) return null
        return raw.substring(idx).trim().split(Regex("\\s"))[0].takeIf { it.length > 8 }
    }

    /**
     * 宽容解析单条账号同步记录 → 水鱼 update_records 格式。
     *
     * 同步数据为查分器通用格式，但字段名/数值形态存在变体，这里全部兜住：
     * - 歌曲标识：`song_id` / `musicId` / `music_id` / `id`；
     * - 难度：`level_index` / `levelIndex`（0..5，越界视为脏数据丢弃）；
     * - 达成率：`achievements` / `achievement`，字符串或数值。数值形态启发式：
     *   大于 101.0 视为 ×10000 整数（如 1005000 = 100.5000%）并除回；≤101.0 恒为
     *   百分数原样保留（100.5% 这类合法值在 (100,101] 区间，不能误除）；
     * - fc/fs：字符串原样（fc/fcp/ap/app、fs/fsp/fdx/fdxp/sync），数字则按官方
     *   comboStatus/syncStatus 枚举映射，未知/空置空；
     * - dxScore：`dxScore` / `deluxscore` / `deluxscoreMax` / `dx_score`；
     * - playCount：`playCount` / `play_count`，缺省 0；
     * - title/type：记录自带则用；缺失时从本地曲库补，曲库也没有则返回 null（跳过，
     *   宁缺毋滥，避免脏 title 上传）。
     */
    internal fun parseGatewayRecord(
        element: JsonElement,
        songMetaById: Map<Int, SongMeta>,
    ): DivingFishRecord? {
        if (element !is JsonObject) return null

        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (element[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
        fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
            (element[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
        }

        val songId = num("song_id", "musicId", "music_id", "id")?.toInt() ?: return null
        val levelIndex = num("level_index", "levelIndex")?.toInt() ?: return null
        if (levelIndex !in 0..5) return null

        val achievementRaw = num("achievements", "achievement") ?: return null
        val achievements = if (achievementRaw > 101.0) achievementRaw / 10000.0 else achievementRaw

        fun statusOrLabel(vararg keys: String, enumLabel: (Int) -> String): String {
            val raw = str(*keys) ?: return ""
            val asInt = raw.trim().toDoubleOrNull()?.toInt()
            return if (asInt != null) enumLabel(asInt) else raw
        }
        val fc = statusOrLabel("fc", "comboStatus", "combo_type", enumLabel = ::fcLabel)
        val fs = statusOrLabel("fs", "syncStatus", "sync_type", enumLabel = ::fsLabel)

        val dxScore = num("dxScore", "deluxscore", "deluxscoreMax", "dx_score")?.toLong() ?: 0L
        val playCount = num("playCount", "play_count")?.toInt() ?: 0

        var title = str("title") ?: ""
        var type = str("type") ?: ""
        val meta = songMetaById[songId]
        if (title.isBlank()) title = meta?.title ?: return null
        if (type.isBlank()) type = meta?.type ?: return null

        return DivingFishRecord(
            achievements = achievements,
            dxScore = dxScore,
            fc = fc,
            fs = fs,
            levelIndex = levelIndex,
            musicId = songId,
            playCount = playCount,
            type = type,
            title = title,
        )
    }

    /** 原始记录列表 → 水鱼格式（返回 records 与跳过数）。 */
    fun convertGatewayRecords(
        rawRecords: List<JsonElement>,
        songMetaById: Map<Int, SongMeta>,
    ): Pair<List<DivingFishRecord>, Int> {
        val records = ArrayList<DivingFishRecord>(rawRecords.size)
        var skipped = 0
        for (raw in rawRecords) {
            val parsed = runCatching { parseGatewayRecord(raw, songMetaById) }.getOrNull()
            if (parsed == null) skipped++ else records.add(parsed)
        }
        return records to skipped
    }

    // ── 网络步骤 ──

    /**
     * 刷新（生成）水鱼成绩导入令牌。jwt 为账号密码登录返回的原始 token 值
     * （非完整 Set-Cookie 串）。
     */
    suspend fun refreshImportToken(rawJwt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$DF_LEGACY_API/player/import_token")
                .header("jwt-token", rawJwt)
                .put(RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "生成导入令牌失败（HTTP ${response.code}）：$text",
                    )
                }
                parseImportToken(text) ?: throw IllegalStateException(
                    "导入令牌响应无法解析：${text.take(80)}",
                )
            }
        }
    }

    /** 兼容多种导入令牌响应形态：裸串 / JSON 字符串 / {"token": "..."}。 */
    internal fun parseImportToken(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return runCatching {
                json.decodeFromString(ImportTokenEnvelope.serializer(), trimmed).token
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (trimmed.startsWith("\"")) {
            return runCatching {
                json.decodeFromString(String.serializer(), trimmed)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return trimmed
    }

    /** 上传到水鱼（legacy 路径）：POST update_records（import-token 头，JSON 数组 body）。 */
    suspend fun uploadRecords(importToken: String, records: List<DivingFishRecord>): Result<String> =
        postUpdateRecords(DF_LEGACY_API, "import-token", importToken, records)

    /** 上传到水鱼（OAuth 写路径）：POST update_records（Authorization Bearer 头）。 */
    suspend fun uploadRecordsOAuth(bearer: String, records: List<DivingFishRecord>): Result<String> =
        postUpdateRecords(DF_OAUTH_API, "Authorization", bearer, records)

    /** 真正的上传实现：按 baseUrl + 指定鉴权头 POST update_records。 */
    private suspend fun postUpdateRecords(
        baseUrl: String,
        authHeaderName: String,
        authHeaderValue: String,
        records: List<DivingFishRecord>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(ListSerializer(DivingFishRecord.serializer()), records)
            val request = Request.Builder()
                .url("$baseUrl/player/update_records")
                .header(authHeaderName, authHeaderValue)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("上传失败（HTTP ${response.code}）：${text.take(120)}")
                }
                json.decodeFromString(UpdateResponse.serializer(), text).message.orEmpty()
            }
        }
    }

    /** 上传凭证三元组：基础 URL + 鉴权头名/值 + 给人看的路径描述。 */
    private data class UploadCred(
        val baseUrl: String,
        val headerName: String,
        val headerValue: String,
        val desc: String,
    )

    /**
     * 解析上传凭证（分级，优先用最省事的）：
     * 1. 手动填入的导入令牌（非空）→ legacy import-token 路径；
     * 2. 已 OAuth 绑定 → Bearer access_token（client_id 已获写权限，免手填）；
     * 3. 仅 legacy jwt 登录 → 自动 PUT import_token 换令牌再走 legacy 路径；
     * 全都没有 → 返回 null（调用方报错，引导用户去绑定 OAuth 或填令牌）。
     */
    private suspend fun resolveUploadCredential(manualToken: String?): UploadCred? {
        val manual = manualToken?.trim().orEmpty()
        if (manual.isNotBlank()) {
            return UploadCred(DF_LEGACY_API, "import-token", manual, "导入令牌")
        }
        val bearer = DivingFishAccount.oauthWriteBearer()
        if (bearer != null) {
            return UploadCred(DF_OAUTH_API, "Authorization", bearer, "OAuth 授权令牌")
        }
        val rawJwt = rawDivingFishJwt()
        if (rawJwt != null) {
            val tok = refreshImportToken(rawJwt).getOrElse { return null } ?: return null
            return UploadCred(DF_LEGACY_API, "import-token", tok, "账号密码登录导入令牌")
        }
        return null
    }

    // ── 同步：账号云端 → 水鱼（二维码导入的后半程，也是「手动同步」本体） ──

    /**
     * 从账号云端拉取同步成绩并上传水鱼。
     * 成功后刷新「最近同步时间」（[lastSyncAt]）。
     *
     * @return 上传记录数
     */
    suspend fun syncGatewayToDivingFish(
        importToken: String? = null,
        onLog: (String) -> Unit = {},
    ): Result<Int> {
        onLog("正在从账号拉取同步成绩…")
        val rawRecords = MaiQrGateway.pullScores().getOrElse {
            Log.w(TAG, "pullScores failed", it)
            return Result.failure(IllegalStateException(dnsMsg("拉取同步数据失败", it)))
        }
        if (rawRecords.isEmpty()) {
            return Result.failure(IllegalStateException("账号云端暂无同步数据，请先用二维码导入一次"))
        }
        onLog("已拉取 ${rawRecords.size} 条原始记录，正在转换…")

        val songMetaById = withContext(Dispatchers.IO) { loadSongMeta() }
        val (records, skipped) = convertGatewayRecords(rawRecords, songMetaById)
        if (records.isEmpty()) {
            return Result.failure(IllegalStateException("没有可上传的记录（本地曲库可能未同步，请先更新曲库）"))
        }
        onLog("转换完成：${records.size} 条记录${if (skipped > 0) "（跳过无法解析 $skipped 条）" else ""}")

        val cred = resolveUploadCredential(importToken) ?: return Result.failure(
            IllegalStateException(
                "没有可用的水鱼写入凭证：请先在本应用「我」页通过 OAuth 授权登录水鱼，" +
                    "或填入水鱼网页版「个人资料」的导入令牌",
            ),
        )
        onLog("正在上传至水鱼查分器（凭证：${cred.desc}）…")
        val message = postUpdateRecords(cred.baseUrl, cred.headerName, cred.headerValue, records)
            .getOrElse {
                Log.w(TAG, "uploadRecords failed", it)
                return Result.failure(it)
            }
        if (message.contains("token", ignoreCase = true) && message.contains("误")) {
            return Result.failure(IllegalStateException("导入令牌有误，请到水鱼网页版「个人资料」核对后重试"))
        }
        onLog("水鱼响应：$message")

        SpUtil.setQrGatewayLastSyncAt(System.currentTimeMillis())
        return Result.success(records.size)
    }

    /** 本机记录的最近一次「账号 → 水鱼」同步时间（毫秒；从未同步过为 0）。 */
    fun lastSyncAt(): Long = SpUtil.getQrGatewayLastSyncAt()

    // ── 二维码导入一条龙：校验 → 同步进账号 → 拉取 → 上传水鱼 ──

    /**
     * 二维码导入全流程。
     *
     * @param qrCodeRaw 用户输入 / 扫码得到的原始文本（内部自动提取 SGWCMAID）
     * @param importToken 用户在水鱼网页版「个人资料」查看的**成绩导入令牌**（可选）：
     *   不填时，若本机已 OAuth 绑定水鱼则自动用授权令牌上传（client_id 已获写权限），
     *   否则若账号密码登录过则自动换令牌，都无则报错引导。
     * @param onLog 进度日志回调（**不包含二维码明文**）
     */
    suspend fun importFromQr(
        qrCodeRaw: String,
        importToken: String? = null,
        onLog: (String) -> Unit = {},
    ): Result<QrImportSummary> {
        onLog("开始二维码导入流程")

        // 1. 二维码合法性
        val qrCode = extractQrCode(qrCodeRaw)
            ?: return Result.failure(IllegalStateException("未识别到 SGWCMAID 二维码内容，请确认粘贴的是公众号二维码解析文本"))
        onLog("已提取二维码内容（SGWCMAID…${qrCode.takeLast(4)}，共 ${qrCode.length} 字符）")

        // 2. 同步进账号（解析服务一步完成解密+拉官方+转换入库；需已接入账号）
        onLog("正在把二维码交给解析服务同步（全难度）…")
        val dfCode = rawDivingFishJwt()
        val fetch = MaiQrGateway.fetchByQrCode(qrCode, divingFishCode = dfCode, onLog = onLog)
            .getOrElse {
                Log.w(TAG, "fetchByQrCode failed", it)
                return Result.failure(IllegalStateException(dnsMsg("同步二维码数据失败", it)))
            }
        val preview = fetch.preview
        onLog("同步成功：${preview.userName.orEmpty().ifBlank { "未知昵称" }}，正在拉取全量数据…")

        // 3. 从账号拉取并上传水鱼
        val count = syncGatewayToDivingFish(importToken, onLog).getOrElse {
            return Result.failure(it)
        }

        // 二维码数据到此已无任何引用（qrCode 为局部变量，函数返回即随 GC 丢弃）
        return Result.success(
            QrImportSummary(
                nickname = preview.userName.orEmpty(),
                officialUserId = preview.userId,
                recordCount = count,
                serverMessage = "同步完成",
            ),
        )
    }

    /**
     * 把异常转成友好文案：DNS 解析失败（No address / Unable to resolve host）提示切换网络
     * 或开 VPN；其余原样透出 message。
     */
    private fun dnsMsg(prefix: String, e: Throwable): String {
        val msg = e.message.orEmpty()
        val isDns = e is UnknownHostException ||
            msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("No address associated with hostname", ignoreCase = true)
        return if (isDns) {
            "$prefix：无法解析服务域名（当前网络可能限制了 DNS），请切换网络或开启 VPN 后重试"
        } else {
            "$prefix：$msg"
        }
    }

    /**
     * 取水鱼原始 jwt token 值。SP 里存的是账号密码登录响应的完整 Set-Cookie 串
     * （`jwt_token=xxx; Path=/; ...`），import_token / 解析服务要的是裸值。
     */
    internal fun rawDivingFishJwt(): String? {
        val stored = SpUtil.getDivingFishJwt()
        if (stored.isBlank()) return null
        val idx = stored.indexOf("jwt_token=")
        return if (idx >= 0) {
            stored.substring(idx + "jwt_token=".length).substringBefore(';').trim()
        } else {
            stored.trim()
        }.takeIf { it.isNotBlank() }
    }

    /** 本地曲库 musicId → (title, type)。曲库未同步时返回空表（导入会失败并提示）。 */
    private suspend fun loadSongMeta(): Map<Int, SongMeta> = runCatching {
        com.kira.kiba.core.db.AppDataBase.getInstance()
            .songDao()
            .getAllSongsList()
            .associate { it.id to SongMeta(it.title, it.type) }
    }.getOrElse {
        Log.w(TAG, "loadSongMeta failed", it)
        emptyMap()
    }
}
