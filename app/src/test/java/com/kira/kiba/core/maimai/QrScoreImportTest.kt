package com.kira.kiba.core.maimai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QrScoreImport 纯逻辑单测：二维码提取、fc/fs 映射、官方数据 → 水鱼格式转换。
 * 网络部分不在此覆盖（依赖外部服务）。
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

    // ── convertOfficialRecords ──

    private fun meta(vararg pairs: Pair<Int, String>) =
        pairs.associate { (id, type) -> id to QrScoreImport.SongMeta("曲目$id", type) }

    @Test
    fun `achievement按一万换算`() {
        val data = QrScoreImport.OfficialUserData(
            userId = 42L,
            userMusicList = listOf(
                QrScoreImport.OfficialUserMusic(
                    userMusicDetailList = listOf(
                        QrScoreImport.OfficialMusicDetail(
                            musicId = 100,
                            level = 3,
                            achievement = 1005000.0, // 100.5000%
                            deluxscoreMax = 2000L,
                            comboStatus = 3,
                            syncStatus = 0,
                            playCount = 7,
                        ),
                    ),
                ),
            ),
        )
        val (records, skipped) = QrScoreImport.convertOfficialRecords(data, meta(100 to "DX"))
        assertEquals(0, skipped)
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals(100.5, r.achievements, 1e-9)
        assertEquals(2000L, r.dxScore)
        assertEquals("ap", r.fc)
        assertEquals("", r.fs)
        assertEquals(3, r.levelIndex)
        assertEquals(100, r.musicId)
        assertEquals(7, r.playCount)
        assertEquals("DX", r.type)
        assertEquals("曲目100", r.title)
    }

    @Test
    fun `本地曲库未收录的跳过并计数`() {
        val data = QrScoreImport.OfficialUserData(
            userMusicList = listOf(
                QrScoreImport.OfficialUserMusic(
                    userMusicDetailList = listOf(
                        QrScoreImport.OfficialMusicDetail(musicId = 1, achievement = 900000.0),
                        QrScoreImport.OfficialMusicDetail(musicId = 2, achievement = 950000.0),
                        QrScoreImport.OfficialMusicDetail(musicId = 999, achievement = 990000.0),
                    ),
                ),
            ),
        )
        val (records, skipped) = QrScoreImport.convertOfficialRecords(data, meta(1 to "SD", 2 to "SD"))
        assertEquals(1, skipped)
        assertEquals(2, records.size)
        assertTrue(records.none { it.musicId == 999 })
    }

    @Test
    fun `空曲库返回空列表不崩溃`() {
        val data = QrScoreImport.OfficialUserData(
            userMusicList = listOf(
                QrScoreImport.OfficialUserMusic(
                    userMusicDetailList = listOf(
                        QrScoreImport.OfficialMusicDetail(musicId = 1, achievement = 900000.0),
                    ),
                ),
            ),
        )
        val (records, skipped) = QrScoreImport.convertOfficialRecords(data, emptyMap())
        assertEquals(0, records.size)
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

    // ── encryptString（复刻 EasyMai，与 mai.lista233.cn 服务端对齐）──

    @Test
    fun `encryptString与EasyMai原版逐字节一致`() {
        // 以下基准值由 EasyMai 源码 utils/apiconfig.js 的 encryptString 经 Node 实跑得出
        assertEquals("", QrScoreImport.encryptString(""))
        assertEquals("016b00e90082", QrScoreImport.encryptString("abc"))
        assertEquals(
            "016200c7017300350084012f00b3014d00a800ca0119000d00f8009200b5",
            QrScoreImport.encryptString("jwt_token_12345"),
        )
        assertEquals(
            "016700c20084011900de008d011500ab012b00b100ff0144001d00b50160003f00c50159003600eb00b2015f0084011d0095014f00e1015f00cb00d5014400ea017600eb016500eb0131004f",
            QrScoreImport.encryptString("eyJhbGciOiJIUzI1NiJ9.xxxxxxxx.yyyyyyyy"),
        )
    }
}
