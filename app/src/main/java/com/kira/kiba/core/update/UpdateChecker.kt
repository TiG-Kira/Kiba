package com.kira.kiba.core.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 版本号数据类（Kiba：tag 带 v 前缀，如 v0.3.0；BuildConfig.VERSION_NAME 为 0.3.0）。
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<AppVersion> {

    companion object {
        // 同时接受 "v0.3.0"（GitHub tag）与 "0.3.0"（VERSION_NAME）
        private val PLAIN_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        /** 解析版本号字符串，格式不匹配返回 null。 */
        fun parse(versionName: String): AppVersion? {
            val m = PLAIN_REGEX.find(versionName.trim()) ?: return null
            return AppVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt()
            )
        }
    }

    override fun compareTo(other: AppVersion): Int {
        return compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }

    fun toVersionName(): String = "$major.$minor.$patch"
}

/**
 * 更新检查结果。
 */
sealed class UpdateResult {
    data class UpdateAvailable(
        val latestVersion: AppVersion,
        val latestVersionName: String,
        val currentVersion: AppVersion,
        val currentVersionName: String,
        val releaseUrl: String,
        val releaseNotes: String,
        val isBeta: Boolean
    ) : UpdateResult()

    data class UpToDate(
        val currentVersion: AppVersion,
        val currentVersionName: String,
        /** 当前版本自身的 Release Notes；无则为空串。已是最新版时用于展示更新日志。 */
        val releaseNotes: String = ""
    ) : UpdateResult()

    data object CheckFailed : UpdateResult()
}

/**
 * GitHub Release 更新检查（移植自 Termux Ultra）。
 * Kiba 的 tag 形如 v0.3.0，Release 资产为 app-release.apk。
 */
object UpdateChecker {
    private const val GITHUB_REPO = "TiG-Kira/Kiba"
    private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=50"
    private const val GITHUB_RELEASE_PAGE_URL = "https://github.com/$GITHUB_REPO/releases"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    enum class ReleaseStatus {
        NORMAL,
        PRERELEASE,
        NOT_FOUND
    }

    /**
     * 检查更新。
     * @param currentVersionName 当前版本号（如 "0.3.3"）
     * @param betaEnabled 是否包含 Beta/Pre-release 版本
     */
    suspend fun checkForUpdates(
        currentVersionName: String,
        betaEnabled: Boolean = false
    ): UpdateResult {
        val currentVersion = AppVersion.parse(currentVersionName)
            ?: return UpdateResult.CheckFailed

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LIST_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UpdateResult.CheckFailed
                    }

                    val body = response.body?.string() ?: ""
                    val releases = JSONArray(body)

                    var currentTagMatched = false
                    var currentReleaseNotes = ""
                    var bestRelease: org.json.JSONObject? = null
                    var bestVersion: AppVersion? = null
                    var bestVersionName = ""
                    var bestIsPreRelease = false

                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        val isDraft = release.optBoolean("draft", false)
                        if (isDraft) continue

                        val tagName = release.optString("tag_name", "")
                        val tagVersion = AppVersion.parse(tagName) ?: continue

                        if (tagVersion.major != currentVersion.major) continue

                        if (tagVersion == currentVersion) {
                            currentTagMatched = true
                            // 记录当前版本自身的 Release Notes，供「已是最新版」时展示
                            val notes = release.optString("body", "")
                            if (tagName.equals(currentVersionName, ignoreCase = true) ||
                                tagName.equals("v$currentVersionName", ignoreCase = true)
                            ) {
                                currentReleaseNotes = notes
                            } else if (currentReleaseNotes.isEmpty()) {
                                currentReleaseNotes = notes
                            }
                        }

                        val isPreRelease = release.optBoolean("prerelease", false)
                        if (!betaEnabled && isPreRelease) continue

                        if (bestVersion == null || tagVersion > bestVersion) {
                            bestVersion = tagVersion
                            bestVersionName = tagName
                            bestRelease = release
                            bestIsPreRelease = isPreRelease
                        }
                    }

                    // 当前 tag 在 GitHub 上找不到时仍然允许返回更新（例如本机构建比最新
                    // 公开版还新时则判为最新）。完全没有任何候选时才提前退出。
                    if (bestVersion == null || bestVersion <= currentVersion) {
                        return@withContext UpdateResult.UpToDate(
                            currentVersion = currentVersion,
                            currentVersionName = currentVersionName,
                            releaseNotes = currentReleaseNotes
                        )
                    }

                    val release = bestRelease!!
                    val releaseUrl = release.optString("html_url", "")
                        .ifEmpty { GITHUB_RELEASE_PAGE_URL }
                    val releaseNotes = release.optString("body", "")

                    UpdateResult.UpdateAvailable(
                        latestVersion = bestVersion,
                        latestVersionName = bestVersionName,
                        currentVersion = currentVersion,
                        currentVersionName = currentVersionName,
                        releaseUrl = releaseUrl,
                        releaseNotes = releaseNotes,
                        isBeta = bestIsPreRelease
                    )
                }
            }
        } catch (e: Exception) {
            UpdateResult.CheckFailed
        }
    }

    /**
     * 获取当前版本的 Release 状态（NORMAL / PRERELEASE / NOT_FOUND）。
     * NOT_FOUND 常见于 CI 直装的内部构建（tag 尚未发布或已被替换）。
     */
    suspend fun getReleaseStatus(currentVersionName: String): ReleaseStatus? {
        val currentVersion = AppVersion.parse(currentVersionName)
            ?: return null

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LIST_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }

                    val body = response.body?.string() ?: ""
                    val releases = JSONArray(body)

                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        val tagName = release.optString("tag_name", "")
                        val tagVersion = AppVersion.parse(tagName) ?: continue

                        if (tagVersion == currentVersion) {
                            val isPrerelease = release.optBoolean("prerelease", false)
                            val isDraft = release.optBoolean("draft", false)
                            return@withContext when {
                                isDraft -> ReleaseStatus.NOT_FOUND
                                isPrerelease -> ReleaseStatus.PRERELEASE
                                else -> ReleaseStatus.NORMAL
                            }
                        }
                    }

                    ReleaseStatus.NOT_FOUND
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建 APK 下载 URL。Kiba 的 Release 资产固定为 app-release.apk，
     * tag 带 v 前缀（versionName 传 "0.3.4" 或 "v0.3.4" 均可）。
     */
    fun constructDownloadUrl(versionName: String): String {
        val tag = if (versionName.startsWith("v")) versionName else "v$versionName"
        return "$GITHUB_RELEASE_PAGE_URL/download/$tag/app-release.apk"
    }
}
