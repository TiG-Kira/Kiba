package com.kira.kiba.core.maimai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QrScoreImport / MaiQrGateway 纯逻辑单测：二维码提取、fc/fs 映射、账号同步记录宽容解析、
 * 网关对称加密往返与 JWT 过期判断。网络部分不在此覆盖（依赖外部服务）。
 */
class QrScoreImportTest {

    // ── extractQrCode ──

    @Test
    fun `提取纯二维码串`() {
        val qr = "SGWCMAID01ABCDEF1234567890"
        assertEquals(qr, QrScoreImport.extractQrCode(qr))
    }

    @Test
    fun `容忍前后杂文与换行`() {
        val text = "二维码内容：\nSGWCMAID01ABCDEF1234567890\n请勿泄露"
        assertEquals("SGWCMAID01ABCDEF1234567890", QrScoreImport.extractQrCode(text))
    }

    @Test
    fun `无SGWCMAID返回null`() {
        assertNull(QrScoreImport.extractQrCode("https://example.com/foo"))
        assertNull(QrScoreImport.extractQrCode(""))
    }

    // ── fc / fs 映射 ──

    @Test
    fun `fc映射与官方comboStatus一致`() {
        assertEquals("", QrScoreImport.fcLabel(0))
        assertEquals("fc", QrScoreImport.fcLabel(1))
        assertEquals("fcp", QrScoreImport.fcLabel(2))
        assertEquals("ap", QrScoreImport.fcLabel(3))
        assertEquals("app", QrScoreImport.fcLabel(4))
        assertEquals("", QrScoreImport.fcLabel(9)) // 越界回退空串而非抛异常
    }

    @Test
    fun `fs映射与官方syncStatus一致`() {
        assertEquals("", QrScoreImport.fsLabel(0))
        assertEquals("fs", QrScoreImport.fsLabel(1))
        assertEquals("fsp", QrScoreImport.fsLabel(2))
        assertEquals("fdx", QrScoreImport.fsLabel(3))
        assertEquals("fdxp", QrScoreImport.fsLabel(4))
        assertEquals("sync", QrScoreImport.fsLabel(5))
        assertEquals("", QrScoreImport.fsLabel(9))
    }

    // ── parseGatewayRecord（宽容解析账号同步记录） ──

    private fun meta(vararg pairs: Pair<Int, String>) =
        pairs.associate { (id, type) -> id to QrScoreImport.SongMeta("曲目$id", type) }

    @Test
    fun `查分器通用格式完整解析`() {
        val element = buildJsonObject {
            put("song_id", 100)
            put("level_index", 3)
            put("achievements", "100.5000")
            put("fc", "ap")
            put("fs", "")
            put("dxScore", 2000)
            put("playCount", 7)
            put("title", "Test Song")
            put("type", "DX")
        }
        val r = QrScoreImport.parseGatewayRecord(element, emptyMap())
        assertNotNull(r)
        assertEquals(100.5, r!!.achievements, 1e-9)
        assertEquals(2000L, r.dxScore)
        assertEquals("ap", r.fc)
        assertEquals("", r.fs)
        assertEquals(3, r.levelIndex)
        assertEquals(100, r.musicId)
        assertEquals(7, r.playCount)
        assertEquals("DX", r.type)
        assertEquals("Test Song", r.title)
    }

    @Test
    fun `达成率按一万换算仅当大于一百零一`() {
        // ×10000 整数形态：1005000 → 100.5
        val big = buildJsonObject {
            put("musicId", 1)
            put("levelIndex", 2)
            put("achievement", 1005000.0)
        }
        assertEquals(100.5, QrScoreImport.parseGatewayRecord(big, meta(1 to "SD"))!!.achievements, 1e-9)
        // 百分数形态：100.5 保持原样（(100,101] 区间不能误除）
        val pct = buildJsonObject {
            put("musicId", 1)
            put("levelIndex", 2)
            put("achievement", 100.5)
        }
        assertEquals(100.5, QrScoreImport.parseGatewayRecord(pct, meta(1 to "SD"))!!.achievements, 1e-9)
    }

    @Test
    fun `数字fc与fs按官方枚举映射`() {
        val element = buildJsonObject {
            put("id", 1)
            put("level_index", 0)
            put("achievements", 99.5)
            put("fc", 3)
            put("fs", 5)
        }
        val r = QrScoreImport.parseGatewayRecord(element, meta(1 to "SD"))
        assertEquals("ap", r!!.fc)
        assertEquals("sync", r.fs)
    }

    @Test
    fun `缺title从本地曲库补齐`() {
        val element = buildJsonObject {
            put("song_id", 42)
            put("level_index", 1)
            put("achievements", "98.0000")
        }
        val r = QrScoreImport.parseGatewayRecord(element, meta(42 to "SD"))
        assertEquals("曲目42", r!!.title)
        assertEquals("SD", r.type)
    }

    @Test
    fun `曲库也没有的跳过`() {
        val element = buildJsonObject {
            put("song_id", 999)
            put("level_index", 1)
            put("achievements", "98.0000")
        }
        assertNull(QrScoreImport.parseGatewayRecord(element, meta(1 to "SD")))
    }

    @Test
    fun `难度越界与缺关键字段的记录返回null`() {
        val badLevel = buildJsonObject {
            put("song_id", 1)
            put("level_index", 9)
            put("achievements", "98.0000")
            put("title", "t")
        }
        assertNull(QrScoreImport.parseGatewayRecord(badLevel, emptyMap()))

        val noAchv = buildJsonObject {
            put("song_id", 1)
            put("level_index", 1)
        }
        assertNull(QrScoreImport.parseGatewayRecord(noAchv, meta(1 to "SD")))

        assertNull(QrScoreImport.parseGatewayRecord(JsonPrimitive("not an object"), emptyMap()))
    }

    @Test
    fun `convertGatewayRecords统计跳过数`() {
        val ok = buildJsonObject {
            put("song_id", 1)
            put("level_index", 2)
            put("achievements", "97.0000")
        }
        val bad = buildJsonObject {
            put("foo", "bar")
        }
        val (records, skipped) = QrScoreImport.convertGatewayRecords(listOf(ok, bad, ok), meta(1 to "SD"))
        assertEquals(2, records.size)
        assertEquals(1, skipped)
    }

    // ── parseImportToken ──

    @Test
    fun `导入令牌兼容多种响应形态`() {
        val hex = "802f48105e7dbdd3c107cae68fd5f0fa"
        assertEquals(hex, QrScoreImport.parseImportToken(hex))
        assertEquals(hex, QrScoreImport.parseImportToken("  $hex \n"))
        assertEquals(hex, QrScoreImport.parseImportToken("\"$hex\""))
        assertEquals(hex, QrScoreImport.parseImportToken("""{"token":"$hex","expires_in":null}"""))
        assertNull(QrScoreImport.parseImportToken(""))
        assertNull(QrScoreImport.parseImportToken("{}"))
    }

    // ── MaiQrGateway：对称加密往返 + JWT 过期判断 ──

    @Test
    fun `seal与unseal往返一致`() {
        val samples = listOf("", "abc", "用户名中文也行", "eyJhbGciOiJIUzI1NiJ9.payload.sig", "x".repeat(100))
        for (text in samples) {
            val sealed = MaiQrGateway.seal(text)
            assertEquals(text, MaiQrGateway.unseal(sealed))
        }
    }

    @Test
    fun `seal带随机IV同明文密文不同`() {
        val a = MaiQrGateway.seal("same plaintext")
        val b = MaiQrGateway.seal("same plaintext")
        assertTrue(a != b)
        assertEquals("same plaintext", MaiQrGateway.unseal(a))
        assertEquals("same plaintext", MaiQrGateway.unseal(b))
    }

    @Test
    fun `unseal容错非法输入`() {
        assertNull(MaiQrGateway.unseal("not base64!!"))
        assertNull(MaiQrGateway.unseal("AAAA"))
    }

    @Test
    fun `JWT过期判断`() {
        val nowSec = System.currentTimeMillis() / 1000
        fun jwt(payload: String) = "header.${java.util.Base64.getEncoder()
            .encodeToString(payload.toByteArray())}.sig"

        assertTrue(MaiQrGateway.isTokenExpired("not-a-jwt"))
        assertTrue(MaiQrGateway.isTokenExpired("a.b")) // 段数不足
        assertTrue(MaiQrGateway.isTokenExpired(jwt("""{"foo":1}"""))) // 无 exp
        assertTrue(MaiQrGateway.isTokenExpired(jwt("""{"exp":${nowSec - 10}}"""))) // 已过期
        assertFalse(MaiQrGateway.isTokenExpired(jwt("""{"exp":${nowSec + 3600}}"""))) // 未过期
        // URL-safe base64 payload 也能解
        assertFalse(MaiQrGateway.isTokenExpired(jwt("""{"exp":${nowSec + 3600},"iss":"a_b"}""")))
    }
}
