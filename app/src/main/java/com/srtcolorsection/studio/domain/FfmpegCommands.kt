package com.srtcolorsection.studio.domain

fun ffmpegAbiSupported(abis: List<String>): Boolean =
    abis.any { it == "arm64-v8a" || it == "x86_64" }

/**
 * libx264 software encode from a raw RGBA pipe, optional silent AAC, optional faststart.
 * The command never hardcodes a muxer version string.
 */
fun buildLibx264Arguments(
    pipePath: String,
    outputPath: String,
    width: Int,
    height: Int,
    fps: Int,
    videoBitrate: Int,
    durationMs: Long,
    audioEnabled: Boolean,
    audioBitrate: Int,
    sampleRate: Int,
    channels: Int,
    fastStart: Boolean,
    x264Preset: String,
    threads: Int,
): List<String> {
    require(width % 2 == 0 && height % 2 == 0) { "libx264 needs even dimensions" }
    require(fps in 1..60) { "Unsupported frame rate $fps" }
    val durationSec = "%.3f".format(durationMs.coerceAtLeast(1L) / 1000.0)
    val preset = x264Preset.ifBlank { "veryfast" }
    val threadCount = threads.coerceIn(1, 4)
    val channelLayout = if (channels >= 2) "stereo" else "mono"
    val args = mutableListOf(
        "-y",
        "-f", "rawvideo",
        "-pix_fmt", "rgba",
        "-s", "${width}x${height}",
        "-r", fps.toString(),
        "-i", pipePath,
    )
    if (audioEnabled) {
        args += listOf(
            "-f", "lavfi",
            "-t", durationSec,
            "-i", "anullsrc=channel_layout=$channelLayout:sample_rate=$sampleRate",
        )
    }
    args += listOf(
        "-map", "0:v:0",
    )
    if (audioEnabled) args += listOf("-map", "1:a:0")
    args += listOf(
        "-c:v", "libx264",
        "-preset", preset,
        "-pix_fmt", "yuv420p",
        "-b:v", videoBitrate.toString(),
        "-r", fps.toString(),
        "-threads", threadCount.toString(),
        "-g", (fps * 2).toString(),
    )
    if (audioEnabled) {
        args += listOf(
            "-c:a", "aac",
            "-b:a", audioBitrate.toString(),
            "-ar", sampleRate.toString(),
            "-ac", channels.coerceIn(1, 2).toString(),
            "-shortest",
        )
    } else {
        args += "-an"
    }
    if (fastStart) args += listOf("-movflags", "+faststart")
    args += outputPath
    return args
}

/** Remux a MediaCodec MP4. Copies video. Adds silent AAC when [audioEnabled]. */
fun buildRemuxArguments(
    inputPath: String,
    outputPath: String,
    durationMs: Long,
    audioEnabled: Boolean,
    audioBitrate: Int,
    sampleRate: Int,
    channels: Int,
    fastStart: Boolean,
): List<String> {
    val durationSec = "%.3f".format(durationMs.coerceAtLeast(1L) / 1000.0)
    val channelLayout = if (channels >= 2) "stereo" else "mono"
    val args = mutableListOf("-y", "-i", inputPath)
    if (audioEnabled) {
        args += listOf(
            "-f", "lavfi",
            "-t", durationSec,
            "-i", "anullsrc=channel_layout=$channelLayout:sample_rate=$sampleRate",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", audioBitrate.toString(),
            "-ar", sampleRate.toString(),
            "-ac", channels.coerceIn(1, 2).toString(),
            "-shortest",
        )
    } else {
        args += listOf("-map", "0:v:0", "-c", "copy", "-an")
    }
    if (fastStart) args += listOf("-movflags", "+faststart")
    args += outputPath
    return args
}

fun buildProbeArguments(path: String): List<String> = listOf(
    "-v", "error",
    "-print_format", "json",
    "-show_format",
    "-show_streams",
    path,
)
