package com.srtcolorsection.studio.domain

private val CLOCK = Regex("""^(\d{1,3}):([0-5]\d):([0-5]\d)[,.](\d{1,3})$""")

fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

fun formatRange(startMs: Long, endMs: Long): String =
    "${formatClock(startMs)} – ${formatClock(endMs)}"

/** Filename-safe stamp: HH-MM-SS-mmm */
fun formatFileStamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return "%02d-%02d-%02d-%03d".format(hours, minutes, seconds, millis)
}

fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    val millis = safe % 1000L
    return if (hours > 0) {
        "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    } else {
        "%d:%02d.%03d".format(minutes, seconds, millis)
    }
}

/**
 * Accepts HH:MM:SS.mmm, HH:MM:SS,mmm, and HH:MM:SS.
 * Millisecond fields shorter than 3 digits are padded on the right
 * (SubRip writers sometimes emit hundredths).
 */
fun parseClock(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withMillis = if (CLOCK.matches(text)) text else {
        val short = Regex("""^(\d{1,3}):([0-5]\d):([0-5]\d)$""").matchEntire(text) ?: return null
        "${short.groupValues[1]}:${short.groupValues[2]}:${short.groupValues[3]}.000"
    }
    val match = CLOCK.matchEntire(withMillis) ?: return null
    val hours = match.groupValues[1].toLong()
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    val fraction = match.groupValues[4]
    val millis = fraction.padEnd(3, '0').take(3).toLong()
    if (minutes > 59 || seconds > 59) return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

fun frameCountFor(durationMs: Long, fps: Int): Int {
    if (durationMs <= 0L || fps <= 0) return 0
    val frames = (durationMs * fps + 999L) / 1000L
    return frames.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

fun frameTimeMs(frameIndex: Int, fps: Int): Long {
    if (fps <= 0) return 0L
    return frameIndex * 1000L / fps
}
