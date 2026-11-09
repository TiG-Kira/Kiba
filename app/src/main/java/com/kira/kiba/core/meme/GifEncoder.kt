// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.kira.kiba.core.meme

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 纯 Kotlin 的 GIF89a 编码器（自实现中位切分量化 + LZW），不引第三方依赖。
 *
 * 为什么自己写：上游动图模板产出的是多帧序列，Android 只有 `Bitmap.compress`
 * 能出 PNG/JPEG/WebP，**没有动图编码器**；而 `coil-gif` 需要额外依赖、离线构建
 * 拿不到构件。GIF 是唯一「能编（自己写）+ 能解（系统 ImageDecoder，API 28+）」
 * 的组合，所以走 GIF。
 *
 * 每帧自带局部调色板（最多 256 色）：动图模板的帧差异大，全局调色板会把颜色
 * 压糊。含透明像素的帧额外保留 1 个透明索引（1 bit 透明，GIF 格式限制），
 * disposal=2（回填背景），这样圆形头像的圆角不会拖出残影。
 */
object GifEncoder {

    /** 单帧最长边超过这个值时先缩放，避免调色板量化 + LZW 把内存吃满。 */
    private const val MAX_SIDE = 640

    /**
     * 把 [frames] 编码成循环播放的 GIF。
     *
     * @param delayMs 每帧时长（毫秒），GIF 以 1/100 秒为单位，内部四舍五入。
     */
    fun encode(frames: List<Bitmap>, delayMs: Int, out: OutputStream) {
        require(frames.isNotEmpty()) { "没有可编码的帧" }
        val prepared = frames.map { scaleDown(it) }
        val w = prepared.first().width
        val h = prepared.first().height
        val delay = ((delayMs.coerceIn(20, 2000)) / 10.0).toInt().coerceAtLeast(2)

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLogicalScreen(out, w, h)
        writeLoopExtension(out)

        prepared.forEach { bmp ->
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val pal = PaletteBuilder.build(pixels, 256)
            writeGraphicControl(out, delay, pal.transparentIndex)
            writeImageDescriptor(out, w, h, pal)
            val indices = Palettize.map(pixels, pal)
            writeImageData(out, indices, pal.minCodeSize)
        }

        out.write(0x3B) // trailer
        out.flush()
    }

    fun encode(frames: List<Bitmap>, delayMs: Int): ByteArray {
        val bo = ByteArrayOutputStream(1 shl 18)
        encode(frames, delayMs, bo)
        return bo.toByteArray()
    }

    private fun scaleDown(bmp: Bitmap): Bitmap {
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= MAX_SIDE) return bmp
        val ratio = MAX_SIDE.toFloat() / maxSide
        val nw = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val nh = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    // ── GIF 结构 ────────────────────────────────────────────────────────────
    private fun writeLogicalScreen(out: OutputStream, w: Int, h: Int) {
        le16(out, w)
        le16(out, h)
        out.write(0x70) // no global color table; 8-bit color resolution
        out.write(0)    // background color index
        out.write(0)    // pixel aspect ratio
    }

    private fun writeLoopExtension(out: OutputStream) {
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        le16(out, 0) // 0 = 无限循环
        out.write(0)
    }

    private fun writeGraphicControl(out: OutputStream, delay: Int, transparentIndex: Int) {
        out.write(0x21); out.write(0xF9); out.write(4)
        // disposal=2（回填背景）；只有真存在透明像素时才挂透明标记，
        // 否则 0 号色会被当成透明色挖空
        out.write(0x08 or (if (transparentIndex >= 0) 0x01 else 0x00))
        le16(out, delay)
        out.write(if (transparentIndex >= 0) transparentIndex and 0xFF else 0)
        out.write(0)
    }

    private fun writeImageDescriptor(out: OutputStream, w: Int, h: Int, pal: Palette) {
        out.write(0x2C)
        le16(out, 0); le16(out, 0)
        le16(out, w); le16(out, h)
        // 局部调色板 + 表大小
        out.write(0x80 or (pal.tableBits - 1))
        pal.colors.forEach { c ->
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun writeImageData(out: OutputStream, indices: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val compressed = Lzw.encode(indices, minCodeSize)
        var pos = 0
        while (pos < compressed.size) {
            val n = minOf(255, compressed.size - pos)
            out.write(n)
            out.write(compressed, pos, n)
            pos += n
        }
        out.write(0)
    }

    private fun le16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** 一帧的调色板。 */
    internal class Palette(
        val colors: IntArray,
        val tableBits: Int,
        val transparentIndex: Int,
        val minCodeSize: Int,
    )

    // ── 中位切分量化 ─────────────────────────────────────────────────────────
    internal object PaletteBuilder {

        fun build(pixels: IntArray, maxColors: Int): Palette {
            var hasTransparent = false
            val hist = HashMap<Int, Int>(1 shl 14)
            for (p in pixels) {
                if ((p ushr 24) < 128) hasTransparent = true
                val rgb = p and 0xFFFFFF
                hist[rgb] = (hist[rgb] ?: 0) + 1
            }
            // 透明索引固定占用 0 号位，其余颜色从 1 开始排
            val reserved = if (hasTransparent) 1 else 0
            val budget = (maxColors - reserved).coerceAtLeast(2)
            val boxes = medianCut(hist, budget)

            // GIF 要求 LZW 最小码长 >= 2，对应调色板至少 4 项
            val tableSize = nextPow2((boxes.size + reserved).coerceAtLeast(4))
            val colors = IntArray(tableSize)
            var idx = 0
            if (hasTransparent) colors[idx++] = 0x000000
            boxes.forEach { c -> if (idx < tableSize) colors[idx++] = c }
            while (idx < tableSize) colors[idx] = colors[idx++ - 1]

            val bits = Integer.numberOfTrailingZeros(tableSize).coerceIn(2, 8)
            val minCodeSize = bits
            return Palette(colors, bits, if (hasTransparent) 0 else -1, minCodeSize)
        }

        /** 返回每类的平均色（0xRRGGBB）。 */
        private fun medianCut(hist: HashMap<Int, Int>, budget: Int): List<Int> {
            if (hist.isEmpty()) return listOf(0x000000)
            data class Box(val colors: IntArray, val counts: IntArray) {
                val weight: Long get() = counts.sumOf { it.toLong() }
                fun rangeChannel(): Int {
                    var rMin = 255; var rMax = 0
                    var gMin = 255; var gMax = 0
                    var bMin = 255; var bMax = 0
                    colors.forEach { c ->
                        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                        if (r < rMin) rMin = r; if (r > rMax) rMax = r
                        if (g < gMin) gMin = g; if (g > gMax) gMax = g
                        if (b < bMin) bMin = b; if (b > bMax) bMax = b
                    }
                    val dr = rMax - rMin; val dg = gMax - gMin; val db = bMax - bMin
                    return when {
                        dr >= dg && dr >= db -> 16
                        dg >= db -> 8
                        else -> 0
                    }
                }
                fun split(): Pair<Box, Box>? {
                    if (colors.size < 2) return null
                    val shift = rangeChannel()
                    val order = colors.indices.sortedBy { (colors[it] shr shift) and 0xFF }
                    if (order.size < 2) return null
                    var acc = 0L
                    val half = weight / 2
                    var cut = 1
                    for (i in order.indices) {
                        acc += counts[order[i]]
                        if (acc >= half && i + 1 < order.size) { cut = i + 1; break }
                    }
                    val a = order.take(cut)
                    val b = order.drop(cut)
                    if (a.isEmpty() || b.isEmpty()) return null
                    fun make(sel: List<Int>) = Box(
                        sel.map { colors[it] }.toIntArray(),
                        sel.map { counts[it] }.toIntArray(),
                    )
                    return make(a) to make(b)
                }
                fun average(): Int {
                    var r = 0L; var g = 0L; var b = 0L; var n = 0L
                    colors.forEachIndexed { i, c ->
                        val w = counts[i].toLong()
                        r += ((c shr 16) and 0xFF) * w
                        g += ((c shr 8) and 0xFF) * w
                        b += (c and 0xFF) * w
                        n += w
                    }
                    if (n == 0L) return 0x000000
                    return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                }
            }

            val keys = hist.keys.toIntArray()
            val counts = IntArray(keys.size) { hist[keys[it]] ?: 1 }
            var boxes = mutableListOf(Box(keys, counts))
            while (boxes.size < budget) {
                val pick = boxes.indices
                    .filter { boxes[it].colors.size > 1 }
                    .maxByOrNull { boxes[it].weight * boxes[it].rangeChannel() }
                    ?: break
                val (a, b) = boxes[pick].split() ?: break
                boxes[pick] = a
                boxes.add(b)
            }
            return boxes.map { it.average() }
        }

        private fun nextPow2(v: Int): Int {
            var n = 2
            while (n < v) n = n shl 1
            return n.coerceAtMost(256)
        }
    }

    // ── 像素映射 ────────────────────────────────────────────────────────────
    internal object Palettize {
        fun map(pixels: IntArray, pal: Palette): ByteArray {
            val out = ByteArray(pixels.size)
            val cache = HashMap<Int, Int>(1 shl 12)
            val t = pal.transparentIndex
            for (i in pixels.indices) {
                val p = pixels[i]
                if ((p ushr 24) < 128 && t >= 0) {
                    out[i] = t.toByte()
                    continue
                }
                val rgb = p and 0xFFFFFF
                val idx = cache.getOrPut(rgb) {
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (c in pal.colors.indices) {
                        if (c == t) continue
                        val d = dist(rgb, pal.colors[c])
                        if (d < bestDist) { bestDist = d; best = c }
                    }
                    best
                }
                out[i] = idx.toByte()
            }
            return out
        }

        private fun dist(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return dr * dr + dg * dg + db * db
        }
    }

    // ── LZW ────────────────────────────────────────────────────────────────
    internal object Lzw {
        fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = endCode + 1
            val dict = HashMap<Int, Int>()
            val out = ByteArrayOutputStream(indices.size / 2 + 16)
            var bitBuf = 0
            var bitCount = 0
            var cur = 0

            fun emit(code: Int) {
                bitBuf = bitBuf or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    out.write(bitBuf and 0xFF)
                    bitBuf = bitBuf ushr 8
                    bitCount -= 8
                }
            }

            emit(clearCode)
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val found = dict[key]
                if (found != null) {
                    prefix = found
                } else {
                    emit(prefix)
                    if (nextCode < 4096) {
                        dict[key] = nextCode++
                        // 加宽时机必须与解码器一致：nextCode 越过 1<<codeSize 再加宽。
                        // （用 == 会早一位，PIL/系统解码器直接报 broken data stream）
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        emit(clearCode)
                        dict.clear()
                        codeSize = minCodeSize + 1
                        nextCode = endCode + 1
                    }
                    prefix = k
                }
            }
            emit(prefix)
            emit(endCode)
            if (bitCount > 0) out.write(bitBuf and 0xFF)
            return out.toByteArray()
        }
    }
}
