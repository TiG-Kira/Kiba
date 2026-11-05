package com.kira.kiba.core.listen

import java.io.ByteArrayOutputStream

/**
 * 极简 ID3v2.3 写入器：把标题 / 艺术家 / 专辑 / 封面拼成 ID3v2 头，
 * 垫在 mp3 数据前面——系统播放器与其他 App 读「文件自带」的元数据，
 * 不依赖 MediaStore 后扫（MediaStore 的 TITLE/ARTIST 列只救自家的库）。
 *
 * ## 编码口径
 * - 文本帧（TIT2/TPE1/TALB）用 UTF-16LE w/ BOM（encoding=0x01）：
 *   v2.3 不支持 UTF-8 全帧，ISO-8859-1 存不下日文曲名（maimai 曲名大量假名/汉字）；
 * - APIC 封面用 ISO-8859-1 的 mime + 二进制图片本体，图片类型 0x03（front cover）。
 *
 * ## size 字段
 * ID3v2.3 的帧 size 是普通大端 32 位（不是 v2.4 的 syncsafe）；
 * 头部 size 是 syncsafe（每字节只用低 7 位）——两处口径不同，混了会读坏。
 */
object Id3Writer {

    private const val UTF16 = 0x01
    private const val ISO = 0x00

    /** 拼接 ID3v2.3 头 + 原始 mp3 数据。封面 bytes 可空（拿不到封面时只写文本）。 */
    fun withTags(
        mp3: ByteArray,
        title: String,
        artist: String,
        album: String,
        coverPng: ByteArray?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val frames = ByteArrayOutputStream()

        frames.write(textFrame("TIT2", title))
        frames.write(textFrame("TPE1", artist))
        frames.write(textFrame("TALB", album))
        coverPng?.let { frames.write(apicFrame(it)) }

        val body = frames.toByteArray()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(syncsafe(body.size))
        out.write(body)
        out.write(mp3)
        return out.toByteArray()
    }

    /** 文本帧：encoding + UTF-16LE(BOM) + 终止符。 */
    private fun textFrame(id: String, value: String): ByteArray {
        val text = value.ifBlank { "-" }.toByteArray(Charsets.UTF_16LE)
        val data = ByteArrayOutputStream().apply {
            write(UTF16)
            // UTF-16LE 没有 BOM 时部分播放器按大端读会乱码，手动补小端 BOM
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(text)
            write(byteArrayOf(0, 0)) // UTF-16 终止符
        }.toByteArray()
        return frame(id, data)
    }

    /** APIC 封面帧：encoding + mime + picType + desc + 图片本体。 */
    private fun apicFrame(coverPng: ByteArray): ByteArray {
        val data = ByteArrayOutputStream().apply {
            write(ISO)
            write("image/png".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(0x03) // front cover
            write(0)    // 空 description（ISO 终止符）
            write(coverPng)
        }.toByteArray()
        return frame("APIC", data)
    }

    private fun frame(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + be32(data.size) + byteArrayOf(0, 0) + data

    private fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    /** syncsafe 整数：每字节只用低 7 位（ID3v2 头部口径）。 */
    private fun syncsafe(v: Int): ByteArray = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )
}
