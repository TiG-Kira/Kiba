package com.kira.kiba.core.security

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 落雪凭据保险箱：构建期密封、运行时还原。
 *
 * ## 为什么不再用 BuildConfig
 * 1.8.7 之前开发者密钥 / OAuth 凭据是 `buildConfigField` 生成的字符串常量，
 * 名字直白（`LXNS_DEV_KEY`）、值连续 —— `strings app-release.apk | grep LXNS` 一条命令就捞走，
 * 连反编译都不用。这是本项目里唯一一把「全账号级别」的密钥，太便宜了。
 *
 * ## 现在的存储与还原链路（与 [LxnsSealData] / build.gradle.kts 的 sealLxnsCredentials 严格对应）
 * 构建期（`sealLxnsCredentials`）：
 * 1. 明文 `dev=…\nid=…\nsecret=…\n` 用 **AES-256-GCM** 加密，密钥 = SHA-256(master ‖ salt)，
 *    master / salt / nonce 全部**每次构建随机生成**（同一个版本重新构建，载荷也不一样）；
 * 2. 明文摘要（SHA-256）一起打包，用于运行时校验 —— 被篡改只会解出垃圾，不会解出半个密钥；
 * 3. 整个 blob 逐字节「位置加扰」（第 i 字节 + i·37 + 11），再按 4 字节打包成 int，
 *    最后用**随机置换**打乱 int 顺序，写成 int 数组源码。
 *
 * 运行时（本对象）：
 * 1. 按 PERM 还原 int 顺序 → 拆字节 → 反向位置加扰 → 得到 blob；
 * 2. 取 master / salt / nonce，派生密钥解密，校验明文摘要；
 * 3. 解析成三件凭据，缓存在内存里；**进程内只解一次**。
 *
 * ## 边界（必须说清楚）
 * 客户端能解的东西，逆向也一定能解。这套方案做到的是：
 * - 直接搜字符串拿不到（载荷是加扰后的 int 数组，没有任何可读片段）；
 * - 「解包 → 抄」失效，必须逆一遍本文件 + R8 混淆后的 seal 数据布局；
 * - 重打包 / 改动载荷会解出垃圾（摘要校验），不会静默用错密钥去打接口。
 * 真要「拿不到」，只能把密钥留在服务端做中转 —— 那是另一个量级的工程。
 *
 * ## 失败行为
 * 任何一步失败（载荷为空、解密失败、摘要不符）都退化成「凭据为空」：
 * - 开发者 API（HTML 直传 / 查朋友）自动关闭，上分回落本地解析；
 * - OAuth 登录入口自动隐藏，用户仍可用「个人 API 密钥」。
 * 也就是说**解密失败不会让查分 / 上分崩掉**，只是少一条附加通道。
 */
object LxnsVault {

    private const val TAG = "LxnsVault"

    /** 载荷布局常量（与 build.gradle.kts 的 sealLxnsCredentials 一一对应）。 */
    private const val MASTER_LEN = 32
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val DIGEST_LEN = 32
    private const val HEADER_LEN = MASTER_LEN + SALT_LEN + NONCE_LEN + 2

    private val lock = Any()

    @Volatile
    private var loaded = false

    private var dev = ""
    private var clientId = ""
    private var clientSecret = ""

    init {
        // 主线程提前解一次（几百字节的 AES + 一次 SHA-256，耗时在微秒级），
        // 这样 UI 侧第一次判断「有没有开发者通道」不会卡在一个懒加载上。
        ensureLoaded()
    }

    /** 开发者 API 密钥（HTML 直传 / 按好友码查玩家）。空 = 通道关闭。 */
    fun devKey(): String {
        ensureLoaded()
        return dev
    }

    /** OAuth 应用 ID。空 = OAuth 登录入口关闭。 */
    fun oauthClientId(): String {
        ensureLoaded()
        return clientId
    }

    /** OAuth 应用密钥。 */
    fun oauthClientSecret(): String {
        ensureLoaded()
        return clientSecret
    }

    /** 开发者 API 是否可用（密钥非空）。 */
    fun hasDevKey(): Boolean = devKey().isNotBlank()

    /** OAuth 应用凭据是否齐全。 */
    fun hasOAuth(): Boolean = oauthClientId().isNotBlank() && oauthClientSecret().isNotBlank()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                restore()
            } catch (t: Throwable) {
                // 不抛给上层：宁可少一条通道，也不能让整个 App 因为凭据解不开而崩
                Log.w(TAG, "seal restore failed: ${t.javaClass.simpleName}: ${t.message}")
                dev = ""
                clientId = ""
                clientSecret = ""
            }
            loaded = true
        }
    }

    private fun restore() {
        if (LxnsSealData.EMPTY) return
        val blob = unmask()
        if (blob.size < HEADER_LEN + DIGEST_LEN) {
            Log.w(TAG, "seal payload too short: ${blob.size}")
            return
        }

        var p = 0
        val master = blob.copyOfRange(p, p + MASTER_LEN).also { p += MASTER_LEN }
        val salt = blob.copyOfRange(p, p + SALT_LEN).also { p += SALT_LEN }
        val nonce = blob.copyOfRange(p, p + NONCE_LEN).also { p += NONCE_LEN }
        val ctLen = ((blob[p].toInt() and 0xFF) shl 8) or (blob[p + 1].toInt() and 0xFF)
        p += 2
        if (ctLen <= 0 || p + ctLen + DIGEST_LEN > blob.size) {
            Log.w(TAG, "seal payload truncated (ctLen=$ctLen, size=${blob.size})")
            return
        }
        val ct = blob.copyOfRange(p, p + ctLen).also { p += ctLen }
        val digest = blob.copyOfRange(p, p + DIGEST_LEN)

        val key = MessageDigest.getInstance("SHA-256").apply {
            update(master)
            update(salt)
        }.digest()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        val plain = cipher.doFinal(ct)

        val actual = MessageDigest.getInstance("SHA-256").digest(plain)
        if (!actual.contentEquals(digest)) {
            Log.w(TAG, "seal digest mismatch, refusing to use credentials")
            plain.fill(0)
            return
        }

        val text = String(plain, Charsets.UTF_8)
        plain.fill(0)
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val value = line.substring(idx + 1)
            when (line.substring(0, idx)) {
                "dev" -> dev = value
                "id" -> clientId = value
                "secret" -> clientSecret = value
            }
        }
    }

    /**
     * 还原原始 blob：
     * 1. 按 PERM 把 int 数组放回原下标；
     * 2. 拆回字节；
     * 3. 反向位置加扰（`b[i] = obf[i] − i·37 − 11`）。
     */
    private fun unmask(): ByteArray {
        val data = LxnsSealData.DATA
        val perm = LxnsSealData.PERM
        require(data.size == perm.size) { "seal data/perm size mismatch" }

        val words = IntArray(data.size)
        for (i in data.indices) {
            val origin = perm[i]
            if (origin in words.indices) words[origin] = data[i]
        }

        val meshed = ByteArray(words.size * 4)
        for (k in words.indices) {
            val w = words[k]
            val base = k * 4
            meshed[base] = ((w ushr 24) and 0xFF).toByte()
            meshed[base + 1] = ((w ushr 16) and 0xFF).toByte()
            meshed[base + 2] = ((w ushr 8) and 0xFF).toByte()
            meshed[base + 3] = (w and 0xFF).toByte()
        }

        return ByteArray(meshed.size) { i ->
            ((meshed[i].toInt() and 0xFF) - i * 37 - 11).and(0xFF).toByte()
        }
    }
}
