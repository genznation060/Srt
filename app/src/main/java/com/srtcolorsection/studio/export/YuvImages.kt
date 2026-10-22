package com.srtcolorsection.studio.export

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Limited-range BT.601 conversion from ARGB8888 into an encoder image.
 * Handles planar and semi-planar YUV (pixelStride 1 or 2).
 */
fun writeArgbToYuvImage(image: Image, argb: IntArray, width: Int, height: Int) {
    val planes = image.planes
    require(planes.size >= 3) { "Encoder image does not have 3 YUV planes" }
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val crop = image.cropRect
    val targetW = min(width, crop.width())
    val targetH = min(height, crop.height())
    for (row in 0 until targetH) {
        for (col in 0 until targetW) {
            val pixel = argb[row * width + col]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
            yBuffer.putIndex(row * yPlane.rowStride + col * yPlane.pixelStride, y)
            if (row % 2 == 0 && col % 2 == 0) {
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val uvRow = (row / 2) * uPlane.rowStride
                val uvCol = (col / 2) * uPlane.pixelStride
                uBuffer.putIndex(uvRow + uvCol, u)
                val vRow = (row / 2) * vPlane.rowStride
                val vCol = (col / 2) * vPlane.pixelStride
                vBuffer.putIndex(vRow + vCol, v)
            }
        }
    }
}

private fun ByteBuffer.putIndex(index: Int, value: Int) {
    if (index in 0 until capacity()) {
        put(index, value.toByte())
    }
}
