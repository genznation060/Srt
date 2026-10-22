package com.srtcolorsection.studio.domain

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal RGBA PNG writer. Filter 0, one IDAT, no palette.
 * Alpha is stored as a real channel (color type 6). This is not SVG and not a renamed bitmap.
 */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        val out = ByteArrayOutputStream(width * height + 64)
        encodeTo(out, width, height, argb)
        return out.toByteArray()
    }

    fun encodeTo(out: OutputStream, width: Int, height: Int, argb: IntArray) {
        require(width > 0 && height > 0) { "PNG dimensions must be positive" }
        require(argb.size >= width * height) { "Pixel buffer is smaller than width*height" }
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream(13)
        ihdr.writeInt(width)
        ihdr.writeInt(height)
        ihdr.write(8) // bit depth
        ihdr.write(6) // RGBA
        ihdr.write(0) // compression
        ihdr.write(0) // filter
        ihdr.write(0) // interlace
        writeChunk(out, "IHDR", ihdr.toByteArray())

        val raw = ByteArrayOutputStream(height * (width * 4 + 1))
        val deflater = Deflater(Deflater.BEST_SPEED)
        DeflaterOutputStream(raw, deflater).use { deflated ->
            val row = ByteArray(width * 4)
            for (y in 0 until height) {
                deflated.write(0) // filter None
                var offset = 0
                val base = y * width
                for (x in 0 until width) {
                    val pixel = argb[base + x]
                    row[offset++] = ((pixel ushr 16) and 0xFF).toByte()
                    row[offset++] = ((pixel ushr 8) and 0xFF).toByte()
                    row[offset++] = (pixel and 0xFF).toByte()
                    row[offset++] = ((pixel ushr 24) and 0xFF).toByte()
                }
                deflated.write(row)
            }
        }
        deflater.end()
        writeChunk(out, "IDAT", raw.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }
    }

    /** Color type from IHDR, or null if the buffer is not a PNG. 6 means RGBA. */
    fun colorType(bytes: ByteArray): Int? {
        if (!isPng(bytes) || bytes.size < 8 + 4 + 4 + 13) return null
        val length = readInt(bytes, 8)
        if (length != 13) return null
        val type = String(bytes, 12, 4, Charsets.US_ASCII)
        if (type != "IHDR") return null
        return bytes[8 + 4 + 4 + 8 + 1].toInt() and 0xFF
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt((crc.value and 0xFFFFFFFFL).toInt())
    }

    private fun OutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
