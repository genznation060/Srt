package com.srtcolorsection.studio.domain

data class ProbedStream(
    val mime: String,
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val durationUs: Long? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val aacProfile: Int? = null,
    val bitrate: Int? = null,
)

data class ProbedMedia(
    val exists: Boolean,
    val sizeBytes: Long,
    val containerParsed: Boolean,
    val streams: List<ProbedStream>,
    val durationUs: Long?,
    val validator: String,
    val detail: String = "",
)

data class ExpectedMedia(
    val width: Int,
    val height: Int,
    val fps: Int,
    val wantAudio: Boolean,
    val durationUs: Long,
    val videoBitrate: Int,
)

data class ValidationIssue(val message: String, val blocking: Boolean = true)

fun validateMedia(probe: ProbedMedia, expected: ExpectedMedia): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    if (!probe.exists) {
        issues += ValidationIssue("Output file does not exist")
        return issues
    }
    if (probe.sizeBytes <= 0L) {
        issues += ValidationIssue("Output file is empty")
        return issues
    }
    if (!probe.containerParsed) {
        issues += ValidationIssue("Container could not be parsed. ${probe.detail}".trim())
        return issues
    }
    val video = probe.streams.firstOrNull { it.mime.startsWith("video/") || it.codec.contains("264", true) }
    if (video == null) {
        issues += ValidationIssue("No video stream found")
    } else {
        val codec = video.codec.lowercase()
        val mime = video.mime.lowercase()
        val h264 = mime.contains("avc") || mime.contains("h264") || codec.contains("h264") ||
            codec.contains("avc") || codec == "libx264"
        if (!h264) issues += ValidationIssue("Video codec is ${video.codec.ifBlank { video.mime }}, not H.264")
        if (video.width != null && video.width != expected.width) {
            issues += ValidationIssue("Width is ${video.width}, expected ${expected.width}")
        }
        if (video.height != null && video.height != expected.height) {
            issues += ValidationIssue("Height is ${video.height}, expected ${expected.height}")
        }
        if (video.frameRate != null) {
            val delta = kotlin.math.abs(video.frameRate - expected.fps)
            if (delta > 0.6) {
                issues += ValidationIssue(
                    "Frame rate is ${"%.3f".format(video.frameRate)} fps, expected ${expected.fps} fps",
                )
            }
        }
    }
    val duration = probe.durationUs ?: video?.durationUs
    if (duration != null && expected.durationUs > 0L) {
        val tolerance = maxOf(150_000L, (2_000_000L / expected.fps.coerceAtLeast(1)))
        if (kotlin.math.abs(duration - expected.durationUs) > tolerance) {
            issues += ValidationIssue(
                "Duration is ${duration / 1000} ms, expected about ${expected.durationUs / 1000} ms",
            )
        }
    }
    val audio = probe.streams.firstOrNull { it.mime.startsWith("audio/") || it.codec.contains("aac", true) }
    if (expected.wantAudio) {
        if (audio == null) {
            issues += ValidationIssue("AAC audio track was requested but no audio stream is present")
        } else {
            val mime = audio.mime.lowercase()
            val codec = audio.codec.lowercase()
            val aac = mime.contains("mp4a") || mime.contains("aac") || codec.contains("aac")
            if (!aac) issues += ValidationIssue("Audio codec is ${audio.codec.ifBlank { audio.mime }}, not AAC")
            if (audio.aacProfile != null && audio.aacProfile != 2) {
                issues += ValidationIssue("AAC profile is ${audio.aacProfile}, expected AAC-LC (2)")
            }
            if (audio.sampleRate != null && audio.sampleRate != 44_100) {
                issues += ValidationIssue("Audio sample rate is ${audio.sampleRate} Hz, expected 44100 Hz")
            }
            if (audio.channelCount != null && audio.channelCount != 2) {
                issues += ValidationIssue("Audio channels are ${audio.channelCount}, expected 2")
            }
        }
    }
    if (video?.bitrate != null && expected.videoBitrate > 0) {
        // Reported, not blocking. Short clips and VBR will not match the target exactly.
    }
    return issues
}

fun summarizeProbe(probe: ProbedMedia): String {
    val video = probe.streams.firstOrNull { it.mime.startsWith("video/") }
    val audio = probe.streams.firstOrNull { it.mime.startsWith("audio/") }
    return buildString {
        append("Validator: ${probe.validator}\n")
        append("Size: ${probe.sizeBytes} bytes\n")
        if (video != null) {
            append("Video: ${video.codec.ifBlank { video.mime }}")
            if (video.width != null) append(" ${video.width}x${video.height}")
            if (video.frameRate != null) append(" @ ${"%.3f".format(video.frameRate)} fps")
            if (video.bitrate != null) append(" ~${video.bitrate / 1000} kbps")
            append('\n')
        }
        if (audio != null) {
            append("Audio: ${audio.codec.ifBlank { audio.mime }}")
            if (audio.sampleRate != null) append(" ${audio.sampleRate} Hz")
            if (audio.channelCount != null) append(" ch=${audio.channelCount}")
            if (audio.aacProfile != null) append(" profile=${audio.aacProfile}")
            if (audio.bitrate != null) append(" ~${audio.bitrate / 1000} kbps")
            append('\n')
        } else {
            append("Audio: none\n")
        }
        if (probe.durationUs != null) append("Duration: ${probe.durationUs / 1000} ms\n")
        if (probe.detail.isNotBlank()) append(probe.detail)
    }.trim()
}
