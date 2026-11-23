package com.touchbridge.rpe.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** 纯 JVM 生成渐变占位封面 PNG，对应 app.py 的 _make_illustration()。 */
object PlaceholderCover {

    fun writePng(out: OutputStream, size: Int = 512) {
        val raw = ByteArrayOutputStream(size * (size * 3 + 1))
        for (y in 0 until size) {
            raw.write(0) // filter: None
            for (x in 0 until size) {
                val r = (30 + 60.0 * (x.toDouble() / size)).toInt()
                val g = (30 + 20.0 * (y.toDouble() / size)).toInt()
                val b = (70 + 80.0 * ((x + y).toDouble() / (size + size))).toInt()
                raw.write(r and 0xFF)
                raw.write(g and 0xFF)
                raw.write(b and 0xFF)
            }
        }
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(png, "IHDR", ihdr(size, size))
        writeChunk(png, "IDAT", deflate(raw.toByteArray(), 6))
        writeChunk(png, "IEND", ByteArray(0))
        png.writeTo(out)
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteArray(13).also { b ->
        b[0] = (width ushr 24).toByte()
        b[1] = (width ushr 16).toByte()
        b[2] = (width ushr 8).toByte()
        b[3] = width.toByte()
        b[4] = (height ushr 24).toByte()
        b[5] = (height ushr 16).toByte()
        b[6] = (height ushr 8).toByte()
        b[7] = height.toByte()
        b[8] = 8 // bit depth
        b[9] = 2 // color type: truecolor
        b[10] = 0
        b[11] = 0
        b[12] = 0
    }

    private fun writeChunk(out: ByteArrayOutputStream, tag: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(),
            (len ushr 8).toByte(), len.toByte(),
        ))
        val tagBytes = tag.toByteArray(Charsets.US_ASCII)
        out.write(tagBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(tagBytes)
        crc.update(data)
        val v = crc.value
        out.write(byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(),
            (v ushr 8).toByte(), v.toByte(),
        ))
    }

    private fun deflate(data: ByteArray, level: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val deflater = Deflater(level)
        DeflaterOutputStream(bos, deflater).use { it.write(data) }
        deflater.end()
        return bos.toByteArray()
    }
}
