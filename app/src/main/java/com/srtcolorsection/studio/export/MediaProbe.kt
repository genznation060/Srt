package com.srtcolorsection.studio.export

import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.srtcolorsection.studio.domain.ProbedMedia
import com.srtcolorsection.studio.domain.ProbedStream
import java.io.File
import kotlin.math.abs

fun probeMediaFile(file: File): ProbedMedia {
    if (!file.exists()) {
        return ProbedMedia(false, 0L, false, emptyList(), null, "filesystem", "missing")
    }
    val extractorProbe = probeWithExtractor(file)
    val ffmpegProbe = runCatching { probeWithFfmpeg(file) }.getOrNull()
    if (ffmpegProbe != null && ffmpegProbe.containerParsed) {
        val merged = mergeProbe(extractorProbe, ffmpegProbe)
        return merged.copy(validator = "ffprobe (FFmpeg ${ffmpegVersionSafe()})")
    }
    return extractorProbe
}

private fun mergeProbe(extractor: ProbedMedia, ffmpeg: ProbedMedia): ProbedMedia {
    if (!extractor.containerParsed) return ffmpeg
    val streams = ffmpeg.streams.map { stream ->
        val match = extractor.streams.firstOrNull { sameKind(it, stream) }
        if (match == null) stream
        else stream.copy(
            width = stream.width ?: match.width,
            height = stream.height ?: match.height,
            frameRate = stream.frameRate ?: match.frameRate,
            durationUs = stream.durationUs ?: match.durationUs,
            sampleRate = stream.sampleRate ?: match.sampleRate,
            channelCount = stream.channelCount ?: match.channelCount,
            aacProfile = stream.aacProfile ?: match.aacProfile,
            bitrate = stream.bitrate ?: match.bitrate,
            mime = stream.mime.ifBlank { match.mime },
        )
    }
    return ffmpeg.copy(
        exists = true,
        sizeBytes = extractor.sizeBytes,
        streams = streams.ifEmpty { extractor.streams },
        durationUs = ffmpeg.durationUs ?: extractor.durationUs,
        detail = ffmpeg.detail,
    )
}

private fun sameKind(a: ProbedStream, b: ProbedStream): Boolean {
    val av = a.mime.startsWith("video") || a.codec.contains("264")
    val bv = b.mime.startsWith("video") || b.codec.contains("264") || b.codec.contains("h264")
    val aa = a.mime.startsWith("audio") || a.codec.contains("aac")
    val ba = b.mime.startsWith("audio") || b.codec.contains("aac")
    return (av && bv) || (aa && ba)
}

fun probeWithExtractor(file: File): ProbedMedia {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
    } catch (error: Exception) {
        return ProbedMedia(
            exists = true,
            sizeBytes = file.length(),
            containerParsed = false,
            streams = emptyList(),
            durationUs = null,
            validator = "MediaExtractor",
            detail = error.message ?: "setDataSource failed",
        )
    }
    val streams = mutableListOf<ProbedStream>()
    var duration = 0L
    for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val streamDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
        if (streamDuration != null) duration = maxOf(duration, streamDuration)
        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else null
        if (mime.startsWith("video/")) {
            val width = format.getIntegerOrNull(MediaFormat.KEY_WIDTH)
            val height = format.getIntegerOrNull(MediaFormat.KEY_HEIGHT)
            val reportedFps = format.getIntegerOrNull(MediaFormat.KEY_FRAME_RATE)?.toDouble()
            val measured = measureFrameRate(file, index)
            streams += ProbedStream(
                mime = mime,
                codec = mime,
                width = width,
                height = height,
                frameRate = measured ?: reportedFps,
                durationUs = streamDuration,
                bitrate = bitrate,
            )
        } else if (mime.startsWith("audio/")) {
            streams += ProbedStream(
                mime = mime,
                codec = mime,
                sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                aacProfile = format.getIntegerOrNull(MediaFormat.KEY_AAC_PROFILE),
                durationUs = streamDuration,
                bitrate = bitrate,
            )
        }
    }
    val parsed = streams.isNotEmpty()
    extractor.release()
    return ProbedMedia(
        exists = true,
        sizeBytes = file.length(),
        containerParsed = parsed,
        streams = streams,
        durationUs = duration.takeIf { it > 0L },
        validator = "MediaExtractor",
    )
}

private fun measureFrameRate(file: File, track: Int): Double? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(track)
        val times = ArrayList<Long>(24)
        while (times.size < 24) {
            val sample = extractor.sampleTime
            if (sample < 0) break
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0 || times.isEmpty() || true) {
                times += sample
            }
            if (!extractor.advance()) break
        }
        val deltas = times.zipWithNext { a, b -> b - a }.filter { it in 1_000..200_000 }
        if (deltas.size < 3) null else 1_000_000.0 / deltas.average()
    } catch (_: Exception) {
        null
    } finally {
        extractor.release()
    }
}

private fun probeWithFfmpeg(file: File): ProbedMedia? {
    val session = FFprobeKit.getMediaInformation(file.absolutePath)
    if (!ReturnCode.isSuccess(session.returnCode)) return null
    val info = session.mediaInformation ?: return null
    val streams = info.streams.orEmpty().map { stream ->
        val type = stream.type ?: ""
        val codec = stream.codec ?: ""
        val props = stream.allProperties
        fun str(key: String): String? = stream.getStringProperty(key)
        fun lng(key: String): Long? = str(key)?.toLongOrNull()
        val fps = parseRatio(stream.averageFrameRate) ?: parseRatio(stream.realFrameRate)
        ProbedStream(
            mime = if (type == "video") "video/$codec" else if (type == "audio") "audio/$codec" else type,
            codec = codec,
            width = stream.width?.toInt(),
            height = stream.height?.toInt(),
            frameRate = fps,
            durationUs = (stream.getStringProperty("duration")?.toDoubleOrNull()?.times(1_000_000.0))?.toLong(),
            sampleRate = stream.sampleRate?.toIntOrNull(),
            channelCount = stream.getStringProperty("channels")?.toIntOrNull()
                ?: stream.channelLayout?.let { if (it.contains("stereo")) 2 else if (it.contains("mono")) 1 else null },
            aacProfile = when (str("profile")?.lowercase()) {
                "lc", "aac_low", "aac lc" -> 2
                else -> null
            },
            bitrate = lng("bit_rate")?.toInt(),
        ).also { props }
    }
    val durationUs = info.duration?.toDoubleOrNull()?.times(1_000_000.0)?.toLong()
    return ProbedMedia(
        exists = true,
        sizeBytes = file.length(),
        containerParsed = true,
        streams = streams,
        durationUs = durationUs,
        validator = "ffprobe",
        detail = info.format ?: "",
    )
}

private fun parseRatio(value: String?): Double? {
    if (value.isNullOrBlank() || value == "0/0") return null
    val parts = value.split("/")
    return if (parts.size == 2) {
        val n = parts[0].toDoubleOrNull() ?: return null
        val d = parts[1].toDoubleOrNull() ?: return null
        if (abs(d) < 0.0001) null else n / d
    } else {
        value.toDoubleOrNull()
    }
}

private fun MediaFormat.getIntegerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

fun ffmpegVersionSafe(): String = runCatching {
    com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion()
}.getOrElse { "unavailable (${it.javaClass.simpleName})" }

fun ffmpegLibrariesSafe(): List<String> = runCatching {
    com.arthenica.ffmpegkit.Packages.getExternalLibraries().orEmpty()
}.getOrDefault(emptyList())
