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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 机台成绩更新「二维码导入」链路（复刻 EasyMai 的取数逻辑）。
 *
 * 与 VPN 抓包方案互补：无需 VPN / 机台 WiFi / 微信，只需用户的官方舞萌登录二维码
 * （在「舞萌 DX」公众号长按二维码复制出的 SGWCMAID 串）：
 *
 * 1. 上传凭证（分级，见 [resolveUploadCredential]）：
 *    - 用户手填的导入令牌（legacy import-token 路径）；
 *    - 或本机已 OAuth 绑定水鱼 → 直接用授权令牌上传（client_id 已获写权限，免手填）；
 *    - 或账号密码登录过 → 自动 PUT import_token 换令牌（[refreshImportToken]）；
 * 2. [resolveUserId]：GET EasyMai 接口 /apiqr?qr_code=<SGWCMAID> 解出官方加密 userId；
 * 3. [fetchUserMusic]：GET /getUserMusic?userID=<uid> 拉官方全量游玩数据；
 * 4. [convertOfficialRecords]：本地曲库补 title/type，转成水鱼 update_records 格式；
 * 5. 上传：按上述凭证 POST 水鱼 /player/update_records（JSON 数组）。
 *
 * 隐私铁律：二维码字符串只作为**函数参数**在内存中流转，任何环节都不写 SP / 日志明文 /
 * 数据库；更新完成后调用方清空输入框状态，二维码数据即随 GC 丢弃（「用完即毁」）。
 * 注意：解析 SGWCMAID 必须经 EasyMai 接口（与本机经微信访问官方接口同源），二维码
 * 字符串会出现在该请求 URL 中——这与 EasyMai 官方客户端行为一致。
 *
 * 水鱼登录态非必须（与 EasyMai 原版一致）；若本机已有账号密码登录的 jwt，会顺带传给
 * EasyMai 接口（OAuth 短 token 不传）。OAuth 绑定后写成绩走 Bearer 授权，无需导入令牌。
 */
object QrScoreImport {

    private const val TAG = "QrScoreImport"

    /** EasyMai 后端（负责 SGWCMAID → userId 解密与官方数据代理拉取）。 */
    private const val EASYMAI_BASE = "https://mai.lista233.cn"

    private const val DF_LEGACY_API = "https://www.diving-fish.com/api/maimaidxprober"

    /** 水鱼 OAuth API（Bearer 鉴权，带 write scope 即可写成绩）。 */
    private const val DF_OAUTH_API = "https://maimai.diving-fish.com/api/maimaidxprober"

    /** EasyMai 与水鱼后端约定的 jwt 加密密钥（与 mai.lista233.cn 服务端对齐）。 */
    private const val EASYMAI_SECRET = "MaiLista233QwQ"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── DTO（kotlinx.serialization 直出生成器，无 Gson 反射，不惧 R8） ──

    /** GET /apiqr 响应：userID == -1 表示二维码不合法或已过期。 */
    @Serializable
    data class ApiQrResponse(val userID: Long = -1L)

    /** 官方单条游玩记录（achievement 为 ×10000 的整数，如 1005000 = 100.5000%）。 */
    @Serializable
    data class OfficialMusicDetail(
        val musicId: Int = 0,
        val level: Int = 0,
        val achievement: Double = 0.0,
        val deluxscoreMax: Long = 0L,
        val comboStatus: Int = 0,
        val syncStatus: Int = 0,
        val playCount: Int = 0,
    )

    @Serializable
    data class OfficialUserMusic(val userMusicDetailList: List<OfficialMusicDetail> = emptyList())

    /** GET /getUserMusic 响应：官方全量玩家数据。 */
    @Serializable
    data class OfficialUserData(
        val userId: Long = -1L,
        val nickname: String? = null,
        val rating: Int? = null,
        @SerialName("additional_rating") val additionalRating: Int? = null,
        val plate: String? = null,
        val userMusicList: List<OfficialUserMusic> = emptyList(),
    )

    /** 水鱼 update_records 单条记录格式（与官方代理工具 / EasyMai 一致）。 */
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

    /** 本地曲库元信息（补 title / type 用）。 */
    data class SongMeta(val title: String, val type: String)

    /** 导入结果摘要（不含任何二维码数据）。 */
    data class QrImportSummary(
        val nickname: String,
        val officialRating: Int?,
        val recordCount: Int,
        val serverMessage: String,
    )

    // ── 纯逻辑：官方数据 → 水鱼格式（供单测） ──

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
     * 官方全量数据 → 水鱼 update_records 记录列表。
     * 本地曲库查不到的歌（新曲未同步等）跳过并计数——宁缺毋滥，避免脏 title。
     */
    fun convertOfficialRecords(
        data: OfficialUserData,
        songMetaById: Map<Int, SongMeta>,
    ): Pair<List<DivingFishRecord>, Int> {
        val records = ArrayList<DivingFishRecord>()
        var skipped = 0
        for (music in data.userMusicList) {
            for (detail in music.userMusicDetailList) {
                val meta = songMetaById[detail.musicId]
                if (meta == null) {
                    skipped++
                    continue
                }
                records.add(
                    DivingFishRecord(
                        achievements = detail.achievement / 10000.0,
                        dxScore = detail.deluxscoreMax,
                        fc = fcLabel(detail.comboStatus),
                        fs = fsLabel(detail.syncStatus),
                        levelIndex = detail.level,
                        musicId = detail.musicId,
                        playCount = detail.playCount,
                        type = meta.type,
                        title = meta.title,
                    ),
                )
            }
        }
        return records to skipped
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
     * 复刻 EasyMai 的 jwt 加密（与 mai.lista233.cn 服务端对齐）：XOR + 偏移代换密码，
     * 密钥固定为 "MaiLista233QwQ"。二维码两步请求都把 DivingFish 的 legacy jwt 加密后
     * 放进 `jwt-token` 头——服务端大概率要求此头，缺失/裸传会直接断连（早年"connection closed"主因）。
     */
    internal fun encryptString(text: String, key: String = EASYMAI_SECRET): String {
        var offset = key.sumOf { it.code } % 256
        val sb = StringBuilder(text.length * 4)
        for (i in text.indices) {
            val charCode = text[i].code
            val keyChar = key[i % key.length].code
            val encrypted = ((charCode + offset) % 65536) xor keyChar
            sb.append(encrypted.toString(16).padStart(4, '0'))
            offset = (offset + charCode) % 256
        }
        return sb.toString()
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

    @Serializable
    private data class ImportTokenEnvelope(val token: String? = null)

    /** SGWCMAID → 官方加密 userId（-1 = 不合法或已过期）。 */
    suspend fun resolveUserId(qrCode: String, rawJwt: String?): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(qrCode, "UTF-8")
                val builder = Request.Builder()
                    .url("$EASYMAI_BASE/apiqr?qr_code=$encoded")
                    .get()
                if (!rawJwt.isNullOrBlank()) builder.header("jwt-token", encryptString(rawJwt))
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("解析二维码失败（HTTP ${response.code}）：${text.take(80)}")
                    }
                    json.decodeFromString(ApiQrResponse.serializer(), text).userID
                }
            }
        }

    /** userId → 官方全量游玩数据。 */
    suspend fun fetchUserMusic(userId: Long, rawJwt: String?): Result<OfficialUserData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url("$EASYMAI_BASE/getUserMusic?userID=$userId")
                    .get()
                if (!rawJwt.isNullOrBlank()) builder.header("jwt-token", encryptString(rawJwt))
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("获取官方数据失败（HTTP ${response.code}）：${text.take(80)}")
                    }
                    json.decodeFromString(OfficialUserData.serializer(), text)
                }
            }
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

    // ── 一条龙编排 ──

    /**
     * 二维码导入全流程：校验 → 解码 → 拉数据 → 转换 → 上传。
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

        // 1. 水鱼 jwt：非必须（EasyMai 原版即无水鱼登录态）；有账号密码 jwt 就顺带传
        val rawJwt = rawDivingFishJwt()
        if (rawJwt.isNullOrBlank()) {
            onLog("未检测到水鱼账号密码登录态（不影响导入，仅凭 OAuth 授权或导入令牌上传）")
        }

        // 2. 二维码合法性
        val qrCode = extractQrCode(qrCodeRaw)
            ?: return Result.failure(IllegalStateException("未识别到 SGWCMAID 二维码内容，请确认粘贴的是公众号二维码解析文本"))
        onLog("已提取二维码内容（SGWCMAID…${qrCode.takeLast(4)}，共 ${qrCode.length} 字符）")

        // 3. QR → userId
        onLog("正在解析二维码…")
        val userId = resolveUserId(qrCode, rawJwt).getOrElse {
            Log.w(TAG, "resolveUserId failed", it)
            return Result.failure(IllegalStateException("解析二维码失败：${it.message}"))
        }
        if (userId <= 0L) {
            return Result.failure(IllegalStateException("二维码不合法或已过期（请在公众号重新生成）"))
        }
        onLog("解析成功，官方 userId 已获取")

        // 4. 官方数据
        onLog("正在拉取官方游玩数据（全难度）…")
        val official = fetchUserMusic(userId, rawJwt).getOrElse {
            Log.w(TAG, "fetchUserMusic failed", it)
            return Result.failure(IllegalStateException("获取官方数据失败：${it.message}"))
        }
        if (official.userMusicList.isEmpty()) {
            return Result.failure(IllegalStateException("官方数据为空（可能二维码已过期），请在公众号重新生成后再试"))
        }
        onLog("官方数据已获取：${official.nickname.orEmpty().ifBlank { "未知昵称" }}，Rating ${official.rating ?: "?"}")

        // 5. 转换（本地曲库补 title / type）
        val songMetaById = withContext(Dispatchers.IO) { loadSongMeta() }
        val (records, skipped) = convertOfficialRecords(official, songMetaById)
        if (records.isEmpty()) {
            return Result.failure(IllegalStateException("没有可上传的记录（本地曲库可能未同步，请先更新曲库）"))
        }
        onLog("转换完成：${records.size} 条记录${if (skipped > 0) "（跳过本地曲库未收录 $skipped 条）" else ""}")

        // 6. 解析上传凭证（分级：手动令牌 > OAuth Bearer > legacy 自动换令牌）
        val cred = resolveUploadCredential(importToken) ?: return Result.failure(
            IllegalStateException(
                "没有可用的水鱼写入凭证：请先在本应用「我」页通过 OAuth 授权登录水鱼，" +
                    "或在下方填入水鱼网页版「个人资料」的导入令牌",
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

        // 二维码数据到此已无任何引用（qrCode 为局部变量，函数返回即随 GC 丢弃）
        return Result.success(
            QrImportSummary(
                nickname = official.nickname.orEmpty(),
                officialRating = official.rating,
                recordCount = records.size,
                serverMessage = message,
            ),
        )
    }

    /**
     * 取水鱼原始 jwt token 值。SP 里存的是账号密码登录响应的完整 Set-Cookie 串
     * （`jwt_token=xxx; Path=/; ...`），import_token / EasyMai 接口要的是裸值。
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
