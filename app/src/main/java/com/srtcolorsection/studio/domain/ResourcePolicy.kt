package com.srtcolorsection.studio.domain

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExportCancelled(message: String = "Export cancelled") : Exception(message)

class CancelToken {
    private val flag = AtomicBoolean(false)
    fun cancel() { flag.set(true) }
    fun isCancelled(): Boolean = flag.get()
    fun check() {
        if (flag.get()) throw ExportCancelled()
    }
}

class TempFileTracker {
    private val files = LinkedHashSet<File>()

    fun track(file: File): File {
        files += file
        return file
    }

    fun forget(file: File) {
        files -= file
    }

    fun deleteAll(): Int {
        var removed = 0
        files.toList().forEach { file ->
            if (file.exists() && file.delete()) removed += 1
            if (!file.exists()) files -= file
        }
        return removed
    }
}

enum class ThermalBand { NONE, WARM, HOT, CRITICAL }

/** Android PowerManager status values: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
fun thermalBand(status: Int): ThermalBand = when {
    status >= 4 -> ThermalBand.CRITICAL
    status >= 3 -> ThermalBand.HOT
    status >= 2 -> ThermalBand.WARM
    else -> ThermalBand.NONE
}

data class MemoryAdvice(val allowed: Boolean, val warning: String?)

fun assessBitmapMemory(heapMaxBytes: Long, width: Int, height: Int): MemoryAdvice {
    if (width <= 0 || height <= 0) return MemoryAdvice(false, "Output dimensions must be positive")
    if (width % 2 != 0 || height % 2 != 0) {
        return MemoryAdvice(false, "Width and height must be even so the encoder can use 4:2:0")
    }
    val pixels = width.toLong() * height.toLong()
    if (pixels > 2560L * 1440L) {
        return MemoryAdvice(false, "Resolution ${width}×${height} is above the safe cap of 2560×1440")
    }
    val bitmapBytes = pixels * 4L
    // One bitmap, one pixel copy, and one YUV buffer.
    val estimate = bitmapBytes * 3L
    if (heapMaxBytes > 0 && estimate > (heapMaxBytes * 0.45).toLong()) {
        return MemoryAdvice(
            false,
            "A ${width}×${height} frame needs about ${estimate / (1024 * 1024)} MB of buffers, " +
                "which is too large for this device's ${heapMaxBytes / (1024 * 1024)} MB heap. " +
                "Choose 1280×720.",
        )
    }
    val warning = if (pixels > 1920L * 1080L) {
        "This resolution is above 1080p. Export will use more memory and battery."
    } else {
        null
    }
    return MemoryAdvice(true, warning)
}

data class StoragePlan(val ok: Boolean, val message: String)

fun planStorage(
    availableBytes: Long,
    pngWidth: Int,
    pngHeight: Int,
    imageCount: Int,
    includeVideo: Boolean,
    videoBitrate: Int,
    durationMs: Long,
): StoragePlan {
    val pngEach = (pngWidth.toLong() * pngHeight.toLong() / 2L).coerceAtLeast(150_000L)
    val pngNeed = pngEach * imageCount.coerceAtLeast(0)
    val videoNeed = if (includeVideo) {
        (videoBitrate.toLong() / 8L) * (durationMs.coerceAtLeast(1000L) / 1000L) + 1_000_000L
    } else {
        0L
    }
    val need = pngNeed + videoNeed + 32L * 1024L * 1024L
    if (availableBytes in 1 until need) {
        return StoragePlan(
            false,
            "About ${need / (1024 * 1024)} MB of free space is recommended, " +
                "but only ${availableBytes / (1024 * 1024)} MB is available.",
        )
    }
    return StoragePlan(true, "Storage check passed")
}

data class LengthAdvice(val frames: Int, val warning: String?)

fun assessLength(durationMs: Long, fps: Int): LengthAdvice {
    val frames = frameCountFor(durationMs, fps)
    val warning = when {
        frames > 54_000 ->
            "This timeline is about $frames frames. A midrange phone may overheat or take a very long time. " +
                "Export a shorter range or use the Mobile Safe preset."
        frames > 18_000 ->
            "This export is about $frames frames. Expect a long encode and higher battery use."
        else -> null
    }
    return LengthAdvice(frames, warning)
}

fun evenDimension(value: Int): Int {
    val clamped = value.coerceIn(160, 2560)
    return if (clamped % 2 == 0) clamped else clamped - 1
}
