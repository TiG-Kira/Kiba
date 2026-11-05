package com.kira.kiba.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Id3Writer 结构单测：ID3v2.3 头部 syncsafe、帧布局、UTF-16 编码口径。
 */
class Id3WriterTest {

    @Test
    fun `输出以 ID3 头开始且大小为 syncsafe`() {
        val mp3 = ByteArray(100) { it.toByte() }
        val out = Id3Writer.withTags(mp3, "曲名", "曲师", "专辑", null)
        assertEquals('I'.code.toByte(), out[0])
        assertEquals('D'.code.toByte(), out[1])
        assertEquals('3'.code.toByte(), out[2])
        assertEquals(3, out[3].toInt()) // v2.3
        assertEquals(0, out[4].toInt()) // revision
        // 头部 size 是 syncsafe：每字节低 7 位
        val size = (out[6].toInt() and 0x7F) shl 21 or
            ((out[7].toInt() and 0x7F) shl 14) or
            ((out[8].toInt() and 0x7F) shl 7) or
            (out[9].toInt() and 0x7F)
        val frameBytes = out.size - 10 - mp3.size
        assertEquals(frameBytes, size)
    }

    @Test
    fun `mp3 数据原样保留在尾部`() {
        val mp3 = ByteArray(64) { (it * 3).toByte() }
        val out = Id3Writer.withTags(mp3, "T", "A", "AL", null)
        val tail = out.copyOfRange(out.size - mp3.size, out.size)
        assertTrue(mp3.contentEquals(tail))
    }

    @Test
    fun `封面帧携带 PNG 图片本体`() {
        val mp3 = ByteArray(32)
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3) // PNG magic + 杂数据
        val out = Id3Writer.withTags(mp3, "T", "A", "AL", cover)
        val outStr = String(out, Charsets.ISO_8859_1)
        assertTrue(outStr.contains("APIC"))
        assertTrue(outStr.contains("image/png"))
        // 封面最后 4 字节必须原样出现在输出尾部前的 mp3 段之前
        val tagged = out.copyOfRange(0, out.size - mp3.size)
        val tailBytes = tagged.copyOfRange(tagged.size - cover.size, tagged.size)
        assertTrue(cover.contentEquals(tailBytes))
    }

    @Test
    fun `UTF-16 文本帧带 BOM`() {
        val mp3 = ByteArray(16)
        val out = Id3Writer.withTags(mp3, "ハチミツ", "artist", "album", null)
        val outStr = String(out, Charsets.ISO_8859_1)
        assertTrue(outStr.contains("TIT2"))
        // encoding=0x01（UTF-16）后跟 FF FE BOM
        val idx = outStr.indexOf("TIT2")
        assertEquals(1, out[idx + 10].toInt()) // frame size 前的 flags 后第一字节是 encoding
        assertEquals(0xFF, out[idx + 11].toInt() and 0xFF)
    }
}
